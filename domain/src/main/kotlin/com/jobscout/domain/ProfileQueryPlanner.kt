package com.jobscout.domain

object ProfileQueryPlanner {
    fun suggestedTitles(profile: CandidateProfile): List<String> = buildList {
        addAll(profile.targets)
        if (profile.skills.any { containsTerm(it, "VBA") }) {
            add("automation developer")
            add("analista de automação")
        }
        if (profile.skills.any { containsTerm(it, "SQL") } && profile.skills.any { containsTerm(it, "Power BI") }) {
            add("data analyst")
            add("analista de dados")
        }
    }.distinctBy(::normalized).take(12)
}
