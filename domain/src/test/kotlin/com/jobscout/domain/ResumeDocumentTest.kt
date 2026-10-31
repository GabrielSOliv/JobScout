package com.jobscout.domain

import org.junit.Assert.*
import org.junit.Test

class ResumeDocumentTest {
    @Test fun `only confirmed experience contributes to employment evidence`() {
        val doc = ResumeDocument(fullName = "Fixture", summary = "Aspiro trabalhar com IA", skills = "Linguagens: Kotlin, SQL\nFerramentas: Git",
            experience = listOf(ResumeExperience(role = "Analista", company = "Empresa", details = "Automação com SQL")),
            projects = listOf(ResumeProject(name = "Projeto pessoal IA", details = "Estudo Python")))
        val profile = doc.toProfile(listOf("Data Analyst"))
        assertEquals(listOf("Kotlin", "SQL", "Git"), profile.skills)
        assertTrue(profile.experience.contains("Automação com SQL"))
        assertFalse(profile.experience.contains("Python")); assertFalse(profile.experience.contains("Aspiro"))
        assertEquals(listOf("Data Analyst"), profile.targets)
    }
    @Test fun `legacy profile migrates to editable sections without inventing roles`() {
        val old = CandidateProfile(listOf("SQL"), "Texto antigo", listOf("Objetivo"), "Formação antiga", listOf("Português"))
        val doc = ResumeDocument.fromProfile(old, "Nome")
        assertEquals("", doc.experience.single().role)
        assertEquals(old, doc.toProfile(old.targets))
        assertNull(doc.validationError()); assertNotNull(ResumeDocument().validationError())
    }
    @Test fun `new remote default preserves Brazil requirement`() {
        val q = SearchQuery(locations = listOf("Campinas"))
        assertTrue(q.remoteAnywhere)
        val job = Job("1", "test", "1", "Analista", "Fixture", "https://example.com/jobs/1", "Recife, PE, Brasil", Workplace.REMOTE)
        assertTrue(JobFilter.matches(job, q))
        assertFalse(JobFilter.matches(job.copy(location = "Lisboa, Portugal"), q))
        assertFalse(JobFilter.matches(job, q.copy(remoteAnywhere = false)))
    }
}
