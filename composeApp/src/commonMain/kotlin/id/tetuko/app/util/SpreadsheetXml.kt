package id.tetuko.app.util

/**
 * Dependency-free SpreadsheetML 2003 writer (the format Excel, LibreOffice and
 * Google Sheets open as a real `.xls` document).
 *
 * String cells never become formulas — SpreadsheetML requires an explicit
 * `ss:Formula`, so server-provided text such as `=HYPERLINK(...)` stays inert.
 */
data class ExcelSheet(
    val name: String,
    val header: List<String>,
    val rows: List<List<Any?>>,
    val widths: List<Int>? = null,
)

object SpreadsheetXml {
    private val INVALID_XML_CHARS = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\uFFFE\\uFFFF]")
    private val INVALID_SHEET_NAME = Regex("[\\[\\]:*?/\\\\]")

    fun build(sheets: List<ExcelSheet>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<?mso-application progid=\"Excel.Sheet\"?>\n")
        append("<Workbook xmlns=\"urn:schemas-microsoft-com:office:spreadsheet\"")
        append(" xmlns:ss=\"urn:schemas-microsoft-com:office:spreadsheet\">")
        append(
            "<Styles><Style ss:ID=\"Default\" ss:Name=\"Normal\">" +
                "<Alignment ss:Vertical=\"Center\"/><Font ss:FontName=\"Calibri\" ss:Size=\"11\"/>" +
                "</Style><Style ss:ID=\"kepala\"><Font ss:Bold=\"1\" ss:Color=\"#1E293B\"/>" +
                "<Interior ss:Color=\"#E2E8F0\" ss:Pattern=\"Solid\"/>" +
                "<Alignment ss:Vertical=\"Center\" ss:WrapText=\"1\"/></Style></Styles>",
        )
        sheets.forEach { sheet ->
            append("<Worksheet ss:Name=\"").append(escape(sheetName(sheet.name))).append("\"><Table>")
            sheet.widths?.forEach { width ->
                append("<Column ss:AutoFitWidth=\"0\" ss:Width=\"").append(width.coerceIn(20, 400)).append("\"/>")
            }
            append("<Row ss:StyleID=\"kepala\">")
            sheet.header.forEach { value ->
                append("<Cell><Data ss:Type=\"String\">").append(escape(value)).append("</Data></Cell>")
            }
            append("</Row>")
            sheet.rows.forEach { row ->
                append("<Row>")
                row.forEach { value -> appendCell(value) }
                append("</Row>")
            }
            append("</Table><WorksheetOptions xmlns=\"urn:schemas-microsoft-com:office:excel\">")
            append("<FreezePanes/><SplitHorizontal>1</SplitHorizontal><TopRowBottomPane>1</TopRowBottomPane>")
            append("<ActivePane>2</ActivePane></WorksheetOptions></Worksheet>")
        }
        append("</Workbook>")
    }

    private fun StringBuilder.appendCell(value: Any?) {
        when (value) {
            null -> Unit
            is Number -> append("<Cell><Data ss:Type=\"Number\">")
                .append(value)
                .append("</Data></Cell>")
            is Boolean -> append("<Cell><Data ss:Type=\"String\">")
                .append(value.toString())
                .append("</Data></Cell>")
            else -> append("<Cell><Data ss:Type=\"String\">")
                .append(escape(value.toString()))
                .append("</Data></Cell>")
        }
    }

    private fun sheetName(raw: String): String {
        val clean = INVALID_SHEET_NAME.replace(raw, " ").trim()
        return clean.ifBlank { "Sheet" }.take(31)
    }

    private fun escape(value: String): String = value
        .replace(INVALID_XML_CHARS, "")
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
