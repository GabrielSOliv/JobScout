package com.jobscout.domain

data class ResumeEducation(val degree: String = "", val institution: String = "", val location: String = "", val period: String = "", val details: String = "")
data class ResumeExperience(val role: String = "", val company: String = "", val location: String = "", val period: String = "", val details: String = "")
data class ResumeProject(val name: String = "", val link: String = "", val details: String = "")

/** User-authored facts. Search targets and achievements are never inferred from the summary. */
data class ResumeDocument(
    val fullName: String = "", val phone: String = "", val email: String = "", val city: String = "", val links: String = "",
    val summary: String = "", val skills: String = "", val languages: String = "",
    val education: List<ResumeEducation> = emptyList(), val experience: List<ResumeExperience> = emptyList(),
    val projects: List<ResumeProject> = emptyList(), val activities: String = "",
    val summaryTitle: String = "Resumo profissional",
) {
    fun toProfile(targets: List<String>) = CandidateProfile(
        skills.lines().flatMap { it.substringAfter(':').split(',', ';') }.map(String::trim).filter(String::isNotEmpty).distinct(),
        experience.joinToString("\n\n") { listOf(it.role, it.company, it.location, it.period, it.details).filter(String::isNotBlank).joinToString("\n") },
        targets,
        education.joinToString("\n\n") { listOf(it.degree, it.institution, it.location, it.period, it.details).filter(String::isNotBlank).joinToString("\n") },
        languages.split(',', ';', '\n').map(String::trim).filter(String::isNotEmpty).distinct())

    fun validationError(): String? = when {
        fullName.isBlank() -> "Informe seu nome para gerar o currículo."
        education.size > 15 || experience.size > 15 || projects.size > 15 -> "Use até 15 itens em cada seção."
        toString().length > 100_000 -> "O currículo está muito extenso. Reduza os textos antes de exportar."
        else -> null
    }

    companion object {
        fun fromProfile(profile: CandidateProfile, displayName: String = "") = ResumeDocument(fullName = displayName,
            skills = profile.skills.joinToString(", "), languages = profile.languages.joinToString(", "),
            experience = if (profile.experience.isBlank()) emptyList() else listOf(ResumeExperience(details = profile.experience)),
            education = if (profile.education.isBlank()) emptyList() else listOf(ResumeEducation(details = profile.education)))
    }
}

interface ResumeExporter { suspend fun export(uri: String, document: ResumeDocument) }
