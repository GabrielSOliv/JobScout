package com.jobscout.domain

import org.junit.Assert.*
import org.junit.Test

class RequiredKeywordsTest {
    private fun job(description: String, complete: Boolean = true) = Job("1", "fixture", "1", "Java Developer", "Example",
        "https://example.com/jobs/1", "Brasil", description = description, descriptionComplete = complete)

    @Test fun `English in description satisfies required ingles with an old saved dictionary and vice versa`() {
        assertTrue(JobFilter.matches(job("Requirements: English proficiency."), SearchQuery(keywords = listOf("Inglês")), emptyMap()))
        assertTrue(JobFilter.matches(job("Requisitos: inglês."), SearchQuery(keywords = listOf("English")), emptyMap()))
        assertFalse(JobFilter.matches(job("Java and SQL only."), SearchQuery(keywords = listOf("Inglês"))))
        assertFalse(JobFilter.matches(job("Java and SQL only.").copy(company = "English Company"), SearchQuery(keywords = listOf("Inglês"))))
    }
    @Test fun `every required term including the fourth must occur in the available job content`() {
        val query = SearchQuery(keywords = listOf("inglês", "SQL", "Excel", "Python"))
        assertFalse(JobFilter.matches(job("English, SQL and Excel."), query))
        assertTrue(JobFilter.matches(job("English, SQL and Excel. Python is required."), query))
        assertEquals(listOf("Python"), RequiredKeywords.missing(job("English, SQL and Excel."), query.keywords))
    }
    @Test fun `incomplete summary cannot inherit English evidence from search intent`() {
        val preview = job("Java development in Brazil.", complete = false)
        val query = SearchQuery(keywords = listOf("inglês"))
        assertEquals(listOf("inglês"), RequiredKeywords.missing(preview, query.keywords))
        assertFalse(JobFilter.matches(preview, query))
        assertTrue(JobFilter.matches(preview.copy(description = "Java development. English required.", descriptionComplete = true), query))
    }
    @Test fun `custom synonyms and known multiword spellings work without weakening technology boundaries`() {
        assertTrue(JobFilter.matches(job("PowerBI dashboards and SQL."), SearchQuery(keywords = listOf("Power BI"))))
        assertTrue(JobFilter.matches(job("Uses NG."), SearchQuery(keywords = listOf("Angular")), mapOf("Angular" to listOf("NG"))))
        assertFalse(JobFilter.matches(job("JavaScript.").copy(title = "Developer"), SearchQuery(keywords = listOf("Java"))))
    }
}
