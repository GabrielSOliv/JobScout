package com.jobscout.feature.resume.data

import android.content.Context
import androidx.core.net.toUri
import com.jobscout.domain.*
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

class LocalPdfImporter @Inject constructor(@param:ApplicationContext private val context: Context) : ResumeImporter {
    override suspend fun importDocument(uri: String): ResumeDraft = withContext(Dispatchers.IO) {
        require(uri.toUri().scheme == "content") { "Escolha o PDF pelo seletor de documentos." }
        val bytes = context.contentResolver.openInputStream(uri.toUri())?.use { input ->
            val buffer = ByteArray(8192)
            val output = java.io.ByteArrayOutputStream()
            while (true) {
                coroutineContext.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= 10 * 1024 * 1024) { "PDF maior que 10 MB. Use um currículo menor." }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: error("Não foi possível abrir o arquivo.")
        require(bytes.take(5).toByteArray().toString(Charsets.US_ASCII) == "%PDF-") { "O arquivo escolhido não é um PDF." }
        try {
            PDDocument.load(bytes).use { document ->
                require(!document.isEncrypted) { "PDF protegido por senha não é suportado." }
                require(document.numberOfPages <= 40) { "Use um currículo de até 40 páginas." }
                coroutineContext.ensureActive()
                val text = PDFTextStripper().apply { sortByPosition = true }.getText(document)
                coroutineContext.ensureActive()
                ResumeParser.parse(text)
            }
        } catch (error: com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException) {
            throw IllegalArgumentException("PDF protegido por senha não é suportado.", error)
        }
        // Neither bytes, raw text nor URI are persisted. No upload or filesystem permissions.
    }
}
