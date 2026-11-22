package id.tetuko.app.util

/**
 * Minimal, dependency-free PDF table writer.
 *
 * Reports only need a paginated text grid; embedding a full PDF library would
 * add megabytes to the AppImage. The output is a valid PDF 1.4 document with
 * the built-in Helvetica font, so it opens in every viewer without licensing
 * or font-embedding concerns.
 *
 * Text uses WinAnsi-compatible encoding, matching the built-in Helvetica fonts
 * used by the Tauri report writer.
 */
object PdfTable {
    private const val PAGE_WIDTH = 842
    private const val PAGE_HEIGHT = 595
    private const val MARGIN = 36
    private const val ROWS_PER_PAGE = 34
    private const val FONT_REGULAR = "F1"
    private const val FONT_BOLD = "F2"

    fun build(
        title: String,
        subtitle: String,
        headers: List<String>,
        widths: List<Int>,
        rows: List<List<String>>,
    ): String {
        require(headers.isNotEmpty()) { "PDF table requires at least one column." }
        val pages = rows.chunked(ROWS_PER_PAGE).ifEmpty { listOf(emptyList()) }
        val objects = mutableListOf<String>()

        val regularFontId = 3
        val boldFontId = 4
        val firstPageId = 5
        val pageIds = pages.indices.map { firstPageId + it * 2 }

        objects += "<< /Type /Catalog /Pages 2 0 R >>"
        objects += "<< /Type /Pages /Kids [${pageIds.joinToString(" ") { "$it 0 R" }}] /Count ${pages.size} >>"
        objects += "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>"
        objects += "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>"

        val columnWeights = headers.indices.map { index -> widths.getOrNull(index)?.takeIf { it > 0 } ?: 1 }
        val totalWidth = columnWeights.sum().toFloat()
        val usableWidth = (PAGE_WIDTH - MARGIN * 2).toFloat()
        val scale = usableWidth / totalWidth
        val colWidths = columnWeights.map { it * scale }

        pages.forEachIndexed { index, pageRows ->
            val pageId = pageIds[index]
            val contentId = pageId + 1
            val content = buildString {
                appendText(MARGIN.toFloat(), (PAGE_HEIGHT - MARGIN).toFloat(), 14f, title, FONT_BOLD, usableWidth)
                appendText(MARGIN.toFloat(), (PAGE_HEIGHT - MARGIN - 20).toFloat(), 9f, subtitle, maxWidth = usableWidth)
                var y = (PAGE_HEIGHT - MARGIN - 44).toFloat()
                var x = MARGIN.toFloat()
                headers.forEachIndexed { i, header ->
                    appendText(x, y, 9f, header, FONT_BOLD, colWidths[i] - 8f)
                    x += colWidths[i]
                }
                y -= 4
                appendRaw("0.6 w $MARGIN ${fmt(y)} m ${fmt(PAGE_WIDTH - MARGIN.toFloat())} ${fmt(y)} l S\n")
                y -= 14
                pageRows.forEach { row ->
                    x = MARGIN.toFloat()
                    headers.indices.forEach { i ->
                        appendText(x, y, 8.5f, row.getOrNull(i).orEmpty(), maxWidth = colWidths[i] - 8f)
                        x += colWidths[i]
                    }
                    y -= 13
                }
                appendText(
                    MARGIN.toFloat(),
                    (MARGIN - 4).toFloat(),
                    8f,
                    "Halaman ${index + 1} dari ${pages.size}",
                )
            }
            objects += "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $PAGE_WIDTH $PAGE_HEIGHT] " +
                "/Resources << /Font << /$FONT_REGULAR $regularFontId 0 R /$FONT_BOLD $boldFontId 0 R >> >> /Contents $contentId 0 R >>"
            objects += "<< /Length ${content.toByteArray(Charsets.US_ASCII).size} >>\nstream\n$content\nendstream"
        }

        val out = StringBuilder("%PDF-1.4\n")
        val offsets = mutableListOf<Int>()
        objects.forEachIndexed { i, body ->
            offsets += out.length
            out.append("${i + 1} 0 obj\n").append(body).append("\nendobj\n")
        }
        val xref = out.length
        out.append("xref\n0 ${objects.size + 1}\n")
        out.append("0000000000 65535 f \n")
        offsets.forEach { out.append(it.toString().padStart(10, '0')).append(" 00000 n \n") }
        out.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return out.toString()
    }

    private fun StringBuilder.appendText(
        x: Float,
        y: Float,
        size: Float,
        text: String,
        font: String = FONT_REGULAR,
        maxWidth: Float = Float.MAX_VALUE,
    ) {
        appendRaw("BT /$font ${fmt(size)} Tf ${fmt(x)} ${fmt(y)} Td (${escape(fitText(text, maxWidth, size))}) Tj ET\n")
    }

    private fun StringBuilder.appendRaw(raw: String) {
        append(raw)
    }

    private fun fmt(value: Float): String = if (value == value.toInt().toFloat()) value.toInt().toString() else value.toString()

    private fun fitText(raw: String, maxWidth: Float, fontSize: Float): String {
        val clean = normalizeText(raw)
        if (maxWidth <= 0f) return ""
        val maxCharacters = (maxWidth / (fontSize * 0.52f)).toInt().coerceAtLeast(0)
        if (clean.length <= maxCharacters) return clean
        if (maxCharacters <= 3) return ".".repeat(maxCharacters)
        return clean.take(maxCharacters - 3) + "..."
    }

    private fun normalizeText(raw: String): String = buildString {
        raw.forEach { ch ->
            when (ch) {
                '\u2018', '\u2019' -> append('\'')
                '\u201C', '\u201D' -> append('"')
                '\u2013', '\u2014' -> append('-')
                '\u2026' -> append("...")
                '\u2022' -> append('-')
                '\u00A0' -> append(' ')
                else -> when {
                    ch.code < 32 || ch.code in 127..159 -> append(' ')
                    ch.code <= 255 -> append(ch)
                    else -> append('?')
                }
            }
        }
    }.replace(Regex("\\s+"), " ").trim()

    /** Escape PDF literal strings while keeping the resulting stream ASCII-only. */
    private fun escape(raw: String): String = buildString {
        raw.forEach { ch ->
            when {
                ch == '\\' -> append("\\\\")
                ch == '(' -> append("\\(")
                ch == ')' -> append("\\)")
                ch.code in 32..126 -> append(ch)
                ch.code in 160..255 -> append('\\').append(ch.code.toString(8).padStart(3, '0'))
                else -> append(' ')
            }
        }
    }
}
