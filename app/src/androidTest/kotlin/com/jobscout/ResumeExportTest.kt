package com.jobscout

import android.content.ContentValues
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import com.jobscout.domain.*
import com.jobscout.feature.resume.data.ResumePdfRenderer
import com.jobscout.feature.resume.data.LocalResumeExporter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Fictional content only, exported for visual inspection. Never saved as the user's résumé. */
class ResumeExportTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun sample() = ResumeDocument(fullName = "PESSOA DE TESTE", phone = "(00) 00000-0000", city = "São Paulo, SP",
        email = "pessoa@example.com", links = "https://example.com/portfolio", summary = "Profissional de tecnologia com atuação em desenvolvimento de aplicações e análise de dados. Experiência em automação e integração de sistemas, com foco em soluções claras e manutenção de software.",
        skills = "Linguagens: Kotlin, Java, SQL\nTecnologias: Android, Git, SQLite\nArquitetura: MVVM, Clean Architecture", languages = "Português nativo, Inglês avançado",
        education = listOf(ResumeEducation("Engenharia de Software", "Instituição de Exemplo", period = "Dez 2025")),
        experience = listOf(ResumeExperience("Analista de sistemas", "Empresa de Exemplo", "Brasil", "Jan 2024 - Atual",
            "Desenvolvimento de aplicações para organização de informações.\nIntegração de dados com validação e testes automatizados.\nColaboração com usuários para entender requisitos e melhorar a experiência.")),
        projects = listOf(ResumeProject("Aplicativo de organização", "Kotlin, Android, SQLite", "Criação de telas acessíveis e armazenamento local.\nEstruturação de módulos com responsabilidades separadas.")))

    @Test fun pdfHasSelectableAccentedTextAndNativeExportWritesChosenUri() = runBlocking {
        val dir = File(context.filesDir, "validation").apply { mkdirs() }
        val file = File(dir, "resume-single.pdf")
        file.outputStream().use { ResumePdfRenderer().write(sample(), it) }
        PDDocument.load(file).use { pdf ->
            assertEquals(1, pdf.numberOfPages)
            val text = PDFTextStripper().getText(pdf)
            assertTrue(text.contains("PESSOA DE TESTE")); assertTrue(text.contains("São Paulo")); assertTrue(text.contains("Integração"))
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "jobscout-v09-export-test.pdf"); put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
        })!!
        try {
            LocalResumeExporter(context).export(uri.toString(), sample())
            context.contentResolver.openInputStream(uri)!!.use { input -> PDDocument.load(input).use { assertEquals(1, it.numberOfPages) } }
        } finally { context.contentResolver.delete(uri, null, null) }
    }
    @Test fun longTextAndLongLinksPaginateWithoutLosingLastEntry() {
        val dir = File(context.filesDir, "validation").apply { mkdirs() }
        val file = File(dir, "resume-multiple.pdf")
        val long = sample().copy(links = "https://example.com/" + "portfolio".repeat(45), experience = (1..8).map { index ->
            ResumeExperience("Experiência $index", "Empresa de Exemplo", period = "2020 - 2026", details = (1..12).joinToString("\n") { "Atividade $it: análise, integração e documentação de processos com precisão e responsabilidade." })
        }, activities = "MARCADOR FINAL: todos os dados preservados.")
        file.outputStream().use { ResumePdfRenderer().write(long, it) }
        PDDocument.load(file).use { pdf ->
            assertTrue(pdf.numberOfPages > 1); assertTrue(pdf.numberOfPages <= 20)
            val text = PDFTextStripper().getText(pdf)
            assertTrue(text.contains("Experiência 8")); assertTrue(text.contains("MARCADOR FINAL"))
        }
    }
}
