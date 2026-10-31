package com.jobscout.feature.resume.data

import android.content.Context
import android.net.Uri
import com.jobscout.domain.ResumeDocument
import com.jobscout.domain.ResumeExporter
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream

class LocalResumeExporter @Inject constructor(@param:ApplicationContext private val context: Context) : ResumeExporter {
    override suspend fun export(uri: String, document: ResumeDocument) = withContext(Dispatchers.IO) {
        val target = Uri.parse(uri)
        require(target.scheme == "content") { "Escolha onde salvar pelo seletor de arquivos do Android." }
        val bytes = ByteArrayOutputStream().use { output -> ResumePdfRenderer().write(document, output); output.toByteArray() }
        ensureActive()
        context.contentResolver.openOutputStream(target, "wt")?.use { it.write(bytes); it.flush() }
            ?: error("Não foi possível abrir o destino do PDF. Escolha outra pasta.")
    }
}
