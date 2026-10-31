package com.jobscout

import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jobscout.feature.resume.data.LocalPdfImporter
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class ResumeImportTest {
    @Test fun digitalPdfImportsLocallyAndDoesNotInferTargetsFromSkills() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bytes = ByteArrayOutputStream().use { output ->
            PDDocument().use { doc ->
                val page = PDPage(); doc.addPage(page)
                PDPageContentStream(doc, page).use { stream ->
                    stream.beginText(); stream.setFont(PDType1Font.HELVETICA, 12f); stream.setLeading(18f)
                    stream.newLineAtOffset(40f, 700f)
                    listOf("Test Candidate", "Professional experience", "Automation analyst with VBA and SQL at Example Company.",
                        "Projects", "Personal project in Java and Kotlin.", "Education", "Information Systems").forEach { line -> stream.showText(line); stream.newLine() }
                    stream.endText()
                }
                doc.save(output)
            }
            output.toByteArray()
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "jobscout-instrumentation-fixture.pdf")
            put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
        }) ?: error("Could not create PDF fixture")
        try {
            context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
            val draft = LocalPdfImporter(context).importDocument(uri.toString())
            assertTrue(draft.profile.skills.containsAll(listOf("VBA", "SQL", "Java", "Kotlin")))
            assertFalse(draft.profile.experience.contains("Kotlin"))
            assertTrue(draft.profile.targets.isEmpty())
            assertTrue(draft.warnings.isNotEmpty())
        } finally { context.contentResolver.delete(uri, null, null) }
    }
}
