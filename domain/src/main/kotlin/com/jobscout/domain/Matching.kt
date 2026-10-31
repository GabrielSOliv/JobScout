package com.jobscout.domain

import java.text.Normalizer
import kotlin.math.roundToInt
import java.util.concurrent.ConcurrentHashMap

private val combiningMarks = Regex("\\p{M}+")
private val whitespace = Regex("\\s+")
private val normalizedTerms = ConcurrentHashMap<String, String>()
fun normalized(value: String): String {
    if (value.length > 160) return normalizeUncached(value)
    if (normalizedTerms.size > 1_024) normalizedTerms.clear()
    return normalizedTerms.getOrPut(value) { normalizeUncached(value) }
}
private fun normalizeUncached(value: String): String = Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
    .replace(combiningMarks, "").trim().replace(whitespace, " ")

/** Normalizes a potentially large description once, independently of the number of search terms. */
class TermMatcher(text: String) {
    private val text = normalized(text)
    fun contains(term: String): Boolean {
        val word = normalized(term)
        if (word.isBlank()) return false
        fun wordChar(char: Char) = char in 'a'..'z' || char in '0'..'9'
        var index = text.indexOf(word)
        while (index >= 0) {
            val end = index + word.length
            if ((index == 0 || !wordChar(text[index - 1])) && (end == text.length || !wordChar(text[end]))) return true
            index = text.indexOf(word, index + 1)
        }
        return false
    }
}
fun containsTerm(text: String, term: String): Boolean = TermMatcher(text).contains(term)

object Synonyms {
    val defaults = linkedMapOf(
        "desenvolvedor" to listOf("developer", "software engineer", "engenheiro de software"),
        "junior" to listOf("júnior", "jr", "entry level"),
        "estagio" to listOf("estágio", "intern", "internship"),
        "automacao" to listOf("automação", "automation"),
        "dados" to listOf("data"),
        "analista" to listOf("analyst"),
        "integracoes" to listOf("integrações", "integrations", "integration"),
        "remoto" to listOf("remote"),
        "brasil" to listOf("brazil"),
        "sao paulo" to listOf("são paulo"),
    )
    fun expand(term: String, dictionary: Map<String, List<String>> = defaults): List<String> {
        val exact = dictionary.entries.firstOrNull { (key, values) ->
            normalized(key) == normalized(term) || values.any { normalized(it) == normalized(term) }
        }
        if (exact != null) return (listOf(term, exact.key) + exact.value).distinct()
        val variants = mutableListOf(term)
        dictionary.forEach { (key, values) ->
            if (containsTerm(term, key)) values.forEach { replacement ->
                variants += normalized(term).replace(normalized(key), normalized(replacement))
            }
        }
        return variants.distinct()
    }
}

object Geography {
    private val countries = setOf("brasil", "brazil", "br", "portugal", "pt", "canada", "ca", "united states", "estados unidos", "us", "usa", "united kingdom", "uk", "germany", "alemanha", "de", "spain", "espanha", "france", "franca", "argentina", "mexico", "colombia")
    fun targets(locations: List<String>): List<String> = locations.filter { normalized(it) !in countries }.ifEmpty { locations }
    fun matches(location: String, locations: List<String>, dictionary: Map<String, List<String>> = Synonyms.defaults): Boolean {
        val matcher = TermMatcher(location)
        return targets(locations).any { term ->
            if (BrazilMarket.isBrazilTerm(term)) return@any BrazilMarket.matches(location)
            val codes = when (normalized(term)) { "brasil", "brazil" -> listOf("BR"); "portugal" -> listOf("PT"); else -> emptyList() }
            (Synonyms.expand(term, dictionary) + codes).any(matcher::contains)
        }
    }
}

object JobFilter {
    fun matches(job: Job, query: SearchQuery, dictionary: Map<String, List<String>> = Synonyms.defaults,
                now: Long = System.currentTimeMillis()): Boolean {
        if (JobLinks.canonical(job.url) == null) return false
        val locationUnconfirmed = !BrazilMarket.matches(job.location) && !BrazilMarket.hasForeignEvidence(job.location)
        if (!query.worldwide && !BrazilMarket.matches(job.location) && !(query.includeUnknownLocations && locationUnconfirmed)) return false
        val verification = job.verification
        if (verification.state in setOf(VerificationState.UNAVAILABLE, VerificationState.NOT_A_JOB, VerificationState.EXPIRED) ||
            verification.validThrough?.let { it < now } == true) return false
        if (query.onlyVerifiedPages && (verification.state != VerificationState.PAGE_READ || verification.checkedAt == null)) return false
        if (query.methods.isNotEmpty() && JobProvenance.origins(job).none { it.method in query.methods }) return false
        if (query.excludeTalentPools && listOf("banco de talentos", "banco talentos", "talent pool", "talent community", "cadastro reserva")
                .any { containsTerm(job.title, it) }) return false
        val text = "${job.title} ${job.description} ${job.skills.joinToString(" ")}"
        val titleMatcher = TermMatcher(job.title)
        fun matchesTerm(term: String, target: TermMatcher) = Synonyms.expand(term, dictionary).any(target::contains)
        if (query.titles.isNotEmpty() && query.titles.none { matchesTerm(it, titleMatcher) || DorkTitles.matches(job.title, it, dictionary) }) return false
        // Only build the large full-text matcher when a full-text filter actually needs it.
        val textMatcher by lazy { TermMatcher(text) }
        if (query.keywords.isNotEmpty() && RequiredKeywords.missing(textMatcher, query.keywords, dictionary).isNotEmpty()) return false
        if (query.excludedKeywords.any { textMatcher.contains(it) }) return false
        val companyMatcher = TermMatcher(job.company)
        if (query.excludedCompanies.any(companyMatcher::contains)) return false
        if (query.company.isNotBlank() && !companyMatcher.contains(query.company)) return false
        if (query.employment.isNotBlank() && !EmploymentFilter.matches(job.employment.orEmpty(), query.employment)) return false
        if (query.workplaces.isNotEmpty() && job.workplace !in query.workplaces) return false
        if (query.seniorities.isNotEmpty() && job.seniority !in query.seniorities) return false
        val genericUnknown = job.location.isBlank() || normalized(job.location) in setOf("remote", "remoto", "worldwide", "anywhere", "anywhere in the world", "latam")
        val allowUnconfirmedPlace = query.includeUnknownLocations && locationUnconfirmed &&
            (genericUnknown || Geography.targets(query.locations).all(BrazilMarket::isBrazilTerm))
        if (query.locations.isNotEmpty() && !(query.remoteAnywhere && job.workplace == Workplace.REMOTE) &&
            !Geography.matches(job.location, query.locations, dictionary) && !allowUnconfirmedPlace) return false
        query.postedWithinDays?.let { days ->
            if (days !in 1..3650) return false
            val date = (if (query.dateBasis == DateBasis.DISCOVERY) job.firstSeen else job.postedAt)?.takeIf { it > 0L }
            if (date == null) { if (!query.includeUnknownDates) return false }
            else if (date < now - days * 86_400_000L || date > now) return false
        }
        query.salaryMin?.let { min ->
            if (job.currency != query.salaryCurrency || job.salaryPeriod != query.salaryPeriod) return false
            if ((job.salaryMax ?: job.salaryMin ?: return false) < min) return false
        }
        return true
    }
}

/** Contract words are published evidence; working hours alone never establish CLT or PJ. */
object EmploymentFilter {
    fun aliases(requested: String): List<String> = when (normalized(requested)) {
            "clt" -> listOf("clt")
            "pj" -> listOf("pj", "pessoa juridica")
            "estagio" -> listOf("estagio", "internship", "intern")
            "aprendiz" -> listOf("aprendiz", "apprenticeship", "apprentice")
            "temporario" -> listOf("temporario", "temporary", "fixed term", "fixed-term")
            else -> listOf(requested)
        }
    fun matches(published: String, requested: String): Boolean = aliases(requested).any { containsTerm(published.replace('_', ' '), it) }
}

object RankingEngine {
    fun score(job: Job, profile: CandidateProfile, query: SearchQuery, weights: ScoreWeights = ScoreWeights(),
              dictionary: Map<String, List<String>> = Synonyms.defaults): JobScore {
        val text = "${job.title} ${job.description} ${job.skills.joinToString(" ")}"
        val textMatcher by lazy { TermMatcher(text) }
        val titleMatcher = TermMatcher(job.title)
        val matched = profile.skills.filter { textMatcher.contains(it) }
        val missing = profile.skills - matched.toSet()
        val workWords = normalized(profile.experience).split(Regex("[^a-z0-9+#.]+"))
            .filter { it.length > 3 && it !in setOf("para", "with", "from", "como", "empresa", "experiencia", "trabalho", "that", "this") }.distinct()
        val experienceMatches = workWords.count { textMatcher.contains(it) }
        val targets = profile.targets.ifEmpty { query.titles }
        val targetMatches = targets.count { target -> Synonyms.expand(target, dictionary).any(titleMatcher::contains) }
        val geoMatch = Geography.matches(job.location, query.locations, dictionary) ||
            (query.remoteAnywhere && job.workplace == Workplace.REMOTE)
        val raw = listOf(weights.skills, weights.experience, weights.targets, weights.geography).map { it.coerceAtLeast(0).toDouble() }
        val sum = raw.sum().takeIf { it > 0 } ?: 100.0
        val w = if (raw.sum() == 0.0) listOf(40.0, 25.0, 20.0, 15.0) else raw
        fun part(name: String, fraction: Double, index: Int, explanation: String): ScorePart {
            val weight = w[index] / sum * 100
            return ScorePart(name, fraction.coerceIn(0.0, 1.0) * weight, weight, explanation)
        }
        val parts = listOf(
            part("Habilidades", if (profile.skills.isEmpty()) 0.0 else matched.size.toDouble() / profile.skills.size, 0,
                if (profile.skills.isEmpty()) "Confirme habilidades no perfil." else "${matched.size}/${profile.skills.size} habilidades do perfil encontradas no texto; não confirma requisitos da empresa."),
            part("Experiência", if (workWords.isEmpty()) 0.0 else experienceMatches.toDouble() / workWords.size, 1,
                "Sobreposição textual: $experienceMatches/${workWords.size} termos do histórico revisado. Não avalia anos ou qualidade da experiência."),
            part("Objetivos", if (targets.isEmpty()) 0.0 else targetMatches.toDouble() / targets.size, 2,
                "$targetMatches/${targets.size} objetivos do perfil (ou cargos buscados, se não houver objetivos) no título da vaga."),
            part("Local e modalidade", if (geoMatch) 1.0 else if (query.locations.isEmpty() && query.workplaces.isNotEmpty() && job.workplace in query.workplaces) 1.0 else 0.0, 3,
                if (geoMatch) "Local compatível com a busca." else "Sem evidência de local compatível ou sem preferência informada."),
        )
        return JobScore(parts.sumOf { it.earned }.roundToInt().coerceIn(0, 100), parts, matched, missing)
    }
}

fun inferSeniority(title: String): Seniority = when {
    listOf("intern", "internship", "estagio", "estagiario", "estagiaria").any { containsTerm(title, it) } -> Seniority.INTERN
    listOf("senior", "sr", "staff", "principal", "lead").any { containsTerm(title, it) } -> Seniority.SENIOR
    listOf("junior", "jr", "entry level").any { containsTerm(title, it) } -> Seniority.JUNIOR
    listOf("pleno", "mid", "mid-level").any { containsTerm(title, it) } -> Seniority.MID
    else -> Seniority.UNKNOWN
}
