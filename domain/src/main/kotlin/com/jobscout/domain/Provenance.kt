package com.jobscout.domain

enum class DiscoveryMethod(val label: String) {
    HTML("HTML"), API("API"), RSS("RSS"), SHARED_LINK("Link importado"), DORKS("Dorks"), UNKNOWN("Origem não informada")
}

data class DiscoveryOrigin(val method: DiscoveryMethod, val sourceKey: String, val sourceName: String,
    val firstFound: Long, val lastFound: Long, val query: String? = null)

/** Classifies our own collectors, never words in the employer's description. */
object JobProvenance {
    fun sourceMethod(kind: String): DiscoveryMethod = when (kind.substringBefore(':')) {
        "dorks", "google" -> DiscoveryMethod.DORKS
        "web" -> DiscoveryMethod.HTML
        "greenhouse", "lever", "freehire" -> DiscoveryMethod.API
        "rss" -> DiscoveryMethod.RSS
        else -> DiscoveryMethod.UNKNOWN
    }
    fun origins(job: Job, sourceName: String? = null): List<DiscoveryOrigin> {
        val existing = job.discoveryOrigins.orEmpty().filter { it.method != DiscoveryMethod.DORKS || !it.query.isNullOrBlank() }
        if (existing.isNotEmpty()) return existing
        val kind = job.source.substringBefore(':')
        val board = job.source.substringAfter(':')
        val method = if (kind == "web" && !job.discoveryMethod.orEmpty().startsWith("Coleta HTML") && board !in setOf("vagas", "empregos", "python"))
            DiscoveryMethod.SHARED_LINK else sourceMethod(kind).takeUnless { it == DiscoveryMethod.DORKS } ?: DiscoveryMethod.UNKNOWN
        val label = sourceName ?: when (kind) {
            "freehire" -> "Freehire"
            "greenhouse" -> "Greenhouse · $board"
            "lever" -> "Lever · $board"
            "rss" -> when (board) { "wwr" -> "We Work Remotely"; "python" -> "Python.org"; else -> board }
            "web" -> when (board) { "vagas" -> "Vagas.com.br"; "empregos" -> "Empregos.com.br"; "python" -> "Python.org"; else -> board }
            else -> job.source
        }
        return listOf(DiscoveryOrigin(method, job.source, label.substringBefore(" · "), job.collectedAt, job.collectedAt))
    }
    fun merge(incoming: Job, previous: Job?, sourceName: String? = null): Job {
        val evidence = (previous?.let { origins(it) }.orEmpty() + origins(incoming, sourceName))
            .groupBy { it.method to it.sourceKey }.values.map { same ->
                val newest = same.maxBy { it.lastFound }
                newest.copy(firstFound = same.minOf { it.firstFound }, lastFound = same.maxOf { it.lastFound })
            }
        val keepDescription = !incoming.descriptionComplete && previous?.descriptionComplete == true && previous.description.isNotBlank()
        val hasSalary = incoming.salaryMin != null || incoming.salaryMax != null
        return incoming.copy(id = previous?.id ?: incoming.id, firstSeen = previous?.firstSeen ?: incoming.firstSeen,
            verification = if ((incoming.verification.checkedAt ?: Long.MIN_VALUE) >= (previous?.verification?.checkedAt ?: Long.MIN_VALUE))
                incoming.verification else previous!!.verification,
            company = incoming.company.takeUnless { it.isBlank() || it == "Empresa não informada" } ?: previous?.company ?: incoming.company,
            location = incoming.location.ifBlank { previous?.location.orEmpty() },
            seniority = incoming.seniority.takeUnless { it == Seniority.UNKNOWN } ?: previous?.seniority ?: Seniority.UNKNOWN,
            discoveryOrigins = evidence, description = if (keepDescription) previous!!.description else incoming.description,
            descriptionComplete = incoming.descriptionComplete || keepDescription,
            logoUrl = incoming.logoUrl ?: previous?.logoUrl, postedAt = incoming.postedAt ?: previous?.postedAt,
            workplace = incoming.workplace.takeUnless { it == Workplace.UNKNOWN } ?: previous?.workplace ?: Workplace.UNKNOWN,
            employment = incoming.employment ?: previous?.employment,
            salaryMin = if (hasSalary) incoming.salaryMin else previous?.salaryMin,
            salaryMax = if (hasSalary) incoming.salaryMax else previous?.salaryMax,
            currency = if (hasSalary) incoming.currency else previous?.currency,
            salaryPeriod = if (hasSalary) incoming.salaryPeriod else previous?.salaryPeriod)
    }
}
