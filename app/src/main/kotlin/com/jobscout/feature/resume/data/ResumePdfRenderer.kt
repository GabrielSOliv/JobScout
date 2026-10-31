package com.jobscout.feature.resume.data

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.jobscout.domain.ResumeDocument
import java.io.OutputStream
import java.util.Locale

/** Letter, 0.3-inch margins and classic single-column sections, based on the supplied PDF/TeX. */
class ResumePdfRenderer {
    fun write(document: ResumeDocument, output: OutputStream) {
        require(document.validationError() == null) { document.validationError().orEmpty() }
        val pdf = PdfDocument()
        val layout = Layout(pdf)
        try { layout.render(document); layout.finish(); pdf.writeTo(output) }
        finally { layout.finish(); pdf.close() }
    }

    private class Layout(private val pdf: PdfDocument) {
        private val width = 612
        private val height = 792
        private val margin = 21.6f
        private val usable = width - margin * 2
        private val bottom = height - margin - 12
        private var page: PdfDocument.Page? = null
        private var number = 0
        private var y = margin
        private val normal = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 11f; typeface = Typeface.create("serif", Typeface.NORMAL) }
        private val bold = Paint(normal).apply { typeface = Typeface.create("serif", Typeface.BOLD) }
        private val title = Paint(bold).apply { textSize = 17.2f }
        private val heading = Paint(bold).apply { textSize = 11f }
        private val link = Paint(normal).apply { color = Color.rgb(22, 70, 150) }
        private val rule = Paint().apply { color = Color.GRAY; strokeWidth = .5f }
        private val footer = Paint(normal).apply { textSize = 8f; color = Color.GRAY }
        private fun lineHeight(paint: Paint) = paint.textSize * 1.25f

        private fun newPage() {
            finish()
            require(number < 20) { "O currículo excedeu 20 páginas. Reduza o conteúdo e tente novamente." }
            page = pdf.startPage(PdfDocument.PageInfo.Builder(width, height, ++number).create())
            y = margin
        }
        private fun room(amount: Float) { if (page == null || y + amount > bottom) newPage() }
        fun finish() { page?.let {
            if (number > 1) it.canvas.drawText(number.toString(), width / 2f, height - margin + 2, footer)
            pdf.finishPage(it); page = null
        } }
        private fun clean(text: String) = text.replace('\t', ' ').filter { it == '\n' || !it.isISOControl() }.replace('\u2011', '-').replace('\u2013', '-').replace('\u2014', '-')
        private fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
            val lines = mutableListOf<String>()
            clean(text).lines().forEach { paragraph ->
                var rest = paragraph.trim()
                if (rest.isEmpty()) { lines += ""; return@forEach }
                while (rest.isNotEmpty()) {
                    var count = paint.breakText(rest, true, maxWidth, null).coerceAtLeast(1)
                    if (count < rest.length) {
                        val space = rest.lastIndexOf(' ', count - 1)
                        if (space > 0) count = space
                        if (count > 0 && rest[count - 1].isHighSurrogate()) count--
                    }
                    if (count == 0) count = minOf(2, rest.length)
                    lines += rest.take(count).trimEnd(); rest = rest.drop(count).trimStart()
                }
            }
            return lines
        }
        private fun line(text: String, paint: Paint = normal, x: Float = margin, centered: Boolean = false) {
            val h = lineHeight(paint); room(h)
            page!!.canvas.drawText(text, if (centered) (width - paint.measureText(text)) / 2 else x, y - paint.fontMetrics.ascent, paint)
            y += h
        }
        private fun paragraph(text: String, paint: Paint = normal, indent: Float = 0f, centered: Boolean = false) {
            if (text.isBlank()) return
            wrap(text, paint, usable - indent).forEach { line(it, paint, margin + indent, centered) }
        }
        private fun section(label: String) {
            room(80f); y += 10f
            line(label.uppercase(Locale.forLanguageTag("pt-BR")), heading)
            page!!.canvas.drawLine(margin, y + 1, width - margin, y + 1, rule); y += 6
        }
        private fun dated(left: String, date: String) {
            if (left.isBlank() && date.isBlank()) return
            val dates = wrap(date, normal, usable * .30f)
            val leftLines = wrap(left, bold, if (date.isBlank()) usable else usable * .66f)
            val rows = maxOf(leftLines.size, if (date.isBlank()) 0 else dates.size)
            room(minOf(3, rows) * lineHeight(normal))
            repeat(rows) { i ->
                room(lineHeight(normal))
                leftLines.getOrNull(i)?.let { page!!.canvas.drawText(it, margin, y - bold.fontMetrics.ascent, bold) }
                if (date.isNotBlank()) dates.getOrNull(i)?.let { page!!.canvas.drawText(it, width - margin - normal.measureText(it), y - normal.fontMetrics.ascent, normal) }
                y += lineHeight(normal)
            }
        }
        private fun bullets(text: String) {
            clean(text).lines().map { it.trim().trimStart('•', '-', '*').trim() }.filter(String::isNotBlank).forEach { bullet ->
                val lines = wrap(bullet, normal, usable - 18)
                room(minOf(2, lines.size) * lineHeight(normal))
                lines.forEachIndexed { index, value ->
                    room(lineHeight(normal))
                    if (index == 0) page!!.canvas.drawText("•", margin + 4, y - normal.fontMetrics.ascent, normal)
                    line(value, x = margin + 18)
                }; y += 2
            }
        }
        private fun skillRow(label: String, value: String) {
            if (label.isBlank()) { paragraph(value); return }
            val labelWidth = minOf(125f, maxOf(80f, bold.measureText(label) + 12))
            val labels = wrap(label, bold, labelWidth - 8)
            val values = wrap(value, normal, usable - labelWidth)
            repeat(maxOf(labels.size, values.size)) { i ->
                room(lineHeight(normal))
                labels.getOrNull(i)?.let { page!!.canvas.drawText(it, margin, y - bold.fontMetrics.ascent, bold) }
                values.getOrNull(i)?.let { page!!.canvas.drawText(it, margin + labelWidth, y - normal.fontMetrics.ascent, normal) }
                y += lineHeight(normal)
            }
        }
        fun render(d: ResumeDocument) {
            newPage()
            paragraph(d.fullName.trim().uppercase(Locale.forLanguageTag("pt-BR")), title, centered = true); y += 4
            paragraph(listOf(d.city, d.phone).filter(String::isNotBlank).joinToString("  ·  "), centered = true)
            paragraph(listOf(d.email, d.links).filter(String::isNotBlank).joinToString("\n"), link, centered = true); y += 4
            if (d.summary.isNotBlank()) { section(d.summaryTitle); paragraph(d.summary) }
            val education = d.education.filter { listOf(it.degree, it.institution, it.location, it.period, it.details).any(String::isNotBlank) }
            if (education.isNotEmpty()) { section("Educação"); education.forEach {
                dated(listOf(it.degree, it.institution).filter(String::isNotBlank).joinToString(", "), it.period)
                paragraph(it.location); paragraph(it.details); y += 5
            } }
            if (d.skills.isNotBlank() || d.languages.isNotBlank()) {
                section("Habilidades")
                d.skills.lines().filter(String::isNotBlank).forEach { value ->
                    if (':' in value && value.substringBefore(':').length <= 40) skillRow(value.substringBefore(':').trim(), value.substringAfter(':').trim())
                    else paragraph(value)
                }
                if (d.languages.isNotBlank()) skillRow("Idiomas", d.languages)
            }
            val experience = d.experience.filter { listOf(it.role, it.company, it.location, it.period, it.details).any(String::isNotBlank) }
            if (experience.isNotEmpty()) { section("Experiência"); experience.forEach {
                room(45f); dated(it.role, it.period)
                paragraph(listOf(it.company, it.location).filter(String::isNotBlank).joinToString(" · "))
                bullets(it.details); y += 6
            } }
            val projects = d.projects.filter { listOf(it.name, it.link, it.details).any(String::isNotBlank) }
            if (projects.isNotEmpty()) { section("Projetos"); projects.forEach {
                room(40f); paragraph(it.name, bold); paragraph(it.link, link); bullets(it.details); y += 5
            } }
            if (d.activities.isNotBlank()) { section("Informações adicionais"); paragraph(d.activities) }
        }
    }
}
