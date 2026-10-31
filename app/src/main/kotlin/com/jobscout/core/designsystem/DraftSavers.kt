package com.jobscout.core.designsystem

import androidx.compose.runtime.saveable.mapSaver
import com.jobscout.domain.*

private fun Map<String, Any?>.terms(key: String): List<String> = (this[key] as? String)?.split('\u0000')?.filter(String::isNotEmpty).orEmpty()
val SearchQuerySaver = mapSaver(
    save = { q: SearchQuery -> mapOf("titles" to q.titles.joinToString("\u0000"), "keywords" to q.keywords.joinToString("\u0000"),
        "locations" to q.locations.joinToString("\u0000"), "workplaces" to q.workplaces.joinToString("\u0000") { it.name },
        "seniorities" to q.seniorities.joinToString("\u0000") { it.name }, "excluded" to q.excludedKeywords.joinToString("\u0000"),
        "companies" to q.excludedCompanies.joinToString("\u0000"), "company" to q.company, "employment" to q.employment,
        "days" to q.postedWithinDays, "salary" to q.salaryMin, "currency" to q.salaryCurrency, "period" to q.salaryPeriod, "remote" to q.remoteAnywhere,
        "worldwide" to q.worldwide, "dateBasis" to q.dateBasis.name, "unknownDates" to q.includeUnknownDates,
        "verified" to q.onlyVerifiedPages, "talentPools" to q.excludeTalentPools, "unknownLocations" to q.includeUnknownLocations,
        "hideViewed" to q.hideViewed, "hideApplied" to q.hideApplied, "methods" to q.methods.joinToString("\u0000") { it.name }) },
    restore = { m -> SearchQuery(m.terms("titles"), m.terms("keywords"), m.terms("locations"), m.terms("workplaces").map(Workplace::valueOf).toSet(),
        m.terms("seniorities").map(Seniority::valueOf).toSet(), m.terms("excluded"), m.terms("companies"), m["company"] as String,
        m["employment"] as String, m["days"] as Int?, m["salary"] as Double?, m["currency"] as String, m["period"] as String, m["remote"] as Boolean,
        m["worldwide"] as? Boolean ?: false,
        (m["dateBasis"] as? String)?.let { runCatching { DateBasis.valueOf(it) }.getOrNull() } ?: DateBasis.PUBLICATION,
        m["unknownDates"] as? Boolean ?: true, m["verified"] as? Boolean ?: false, m["talentPools"] as? Boolean ?: true,
        m["unknownLocations"] as? Boolean ?: false, m["hideViewed"] as? Boolean ?: false, m["hideApplied"] as? Boolean ?: false,
        m.terms("methods").mapNotNull { runCatching { DiscoveryMethod.valueOf(it) }.getOrNull() }.toSet()) })
val CandidateProfileSaver = mapSaver(
    save = { p: CandidateProfile -> mapOf("skills" to p.skills.joinToString("\u0000"), "experience" to p.experience, "targets" to p.targets.joinToString("\u0000"),
        "education" to p.education, "languages" to p.languages.joinToString("\u0000")) },
    restore = { m -> CandidateProfile(m.terms("skills"), m["experience"] as String, m.terms("targets"), m["education"] as String, m.terms("languages")) })
