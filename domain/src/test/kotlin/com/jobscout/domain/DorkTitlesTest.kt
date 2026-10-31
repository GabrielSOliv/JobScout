package com.jobscout.domain

import org.junit.Assert.*
import org.junit.Test

class DorkTitlesTest {
    @Test fun `business intelligence abbreviation and expanded label are one concept rather than cumulative requirements`() {
        val role = "Analista de BI Business Intelligence"
        assertTrue(DorkTitles.matches("Analista de BI", role))
        assertTrue(DorkTitles.matches("Business Intelligence Analyst", role))
        assertFalse(DorkTitles.matches("Analista Administrativo", role))
    }
    @Test fun `administrative finance accounting and optimization roles have literal bilingual aliases`() {
        assertTrue(DorkTitles.matches("Administrative Assistant", "assistente administrativo"))
        assertTrue(DorkTitles.matches("Financial Analyst", "analista financeiro"))
        assertTrue(DorkTitles.matches("Contadora", "accountant"))
        assertTrue(DorkTitles.matches("Optimization Analyst", "Otimização"))
        assertFalse(DorkTitles.matches("Assistente Comercial", "assistente administrativo"))
    }
    @Test fun `all role concepts must occur in the title including the technology`() {
        assertTrue(DorkTitles.matches("Desenvolvedor(a) Backend Java Pleno", "Java Developer"))
        assertTrue(DorkTitles.matches("Software Engineer - Java", "Desenvolvedor Java"))
        assertTrue(DorkTitles.matches("Desenvolvedora Java", "Desenvolvedor/a Java"))
        assertFalse(DorkTitles.matches("JavaScript Developer", "Java Developer"))
        assertFalse(DorkTitles.matches("Auxiliar Administrativo", "Java"))
        assertFalse(DorkTitles.matches("Java Account Manager", "Java Developer"))
        assertFalse(DorkTitles.matches("Java Developer", "Java ".repeat(40) + "Python"))
    }
    @Test fun `Portuguese English word order and intervening seniority do not lose the same role`() {
        assertTrue(DorkTitles.matches("Data Analyst", "Analista de Dados"))
        assertTrue(DorkTitles.matches("Analista Sênior de Dados", "Data Analyst"))
        assertFalse(DorkTitles.matches("Analista Financeiro", "Data Analyst"))
        assertFalse(DorkTitles.matches("Junior Java Developer", "Java Developer Senior"))
    }
    @Test fun `technical punctuation is preserved`() {
        assertTrue(DorkTitles.matches("C++ Developer", "Desenvolvedor C++"))
        assertFalse(DorkTitles.matches("C# Developer", "Desenvolvedor C++"))
        assertTrue(DorkTitles.matches("ASP.NET Full-Stack Developer", ".NET fullstack developer"))
        assertTrue(DorkTitles.matches("Java Backend Developer", "Java - backend developer"))
    }
    @Test fun `custom dictionary participates and job filter uses the same title semantics`() {
        val aliases = mapOf("programador" to listOf("coder"))
        assertTrue(DorkTitles.matches("Java Coder", "Programador Java", aliases))
        val job = Job("1", "google:gupy", "1", "Data Analyst", "Fixture", "https://fixture.gupy.io/jobs/1", "São Paulo, Brasil")
        assertTrue(JobFilter.matches(job, SearchQuery(titles = listOf("Analista de Dados"))))
        assertFalse(JobFilter.matches(job.copy(location = "Portugal"), SearchQuery(titles = listOf("Analista de Dados"))))
    }
}
