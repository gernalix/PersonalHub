package com.wordpulse.app.data

data class CsvTable(
    val header: List<String>,
    val rows: List<List<String>>,
)

object CsvTableCodec {
    fun encode(header: List<String>, rows: List<List<Any?>>): String =
        buildString {
            appendLine(header.joinToString(",") { it.toCsvCell() })
            rows.forEach { row ->
                appendLine(row.joinToString(",") { it?.toString().orEmpty().toCsvCell() })
            }
        }

    fun decode(csv: String): CsvTable? {
        val parsedRows = parseRows(csv).filter { row -> row.any(String::isNotBlank) }
        if (parsedRows.isEmpty()) return null
        return CsvTable(
            header = parsedRows.first().map(String::trim),
            rows = parsedRows.drop(1),
        )
    }

    private fun String.toCsvCell(): String {
        val mustQuote = any { it == '"' || it == ',' || it == '\n' || it == '\r' }
        val escaped = replace("\"", "\"\"")
        return if (mustQuote) "\"$escaped\"" else escaped
    }

    private fun parseRows(csv: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val cell = StringBuilder()
        var inQuotes = false
        var index = 0
        while (index < csv.length) {
            val char = csv[index]
            when {
                inQuotes && char == '"' && csv.getOrNull(index + 1) == '"' -> {
                    cell.append('"')
                    index += 1
                }
                char == '"' -> inQuotes = !inQuotes
                char == ',' && !inQuotes -> {
                    row += cell.toString()
                    cell.clear()
                }
                (char == '\n' || char == '\r') && !inQuotes -> {
                    if (char == '\r' && csv.getOrNull(index + 1) == '\n') index += 1
                    row += cell.toString()
                    rows += row.toList()
                    row.clear()
                    cell.clear()
                }
                else -> cell.append(char)
            }
            index += 1
        }
        row += cell.toString()
        if (row.any(String::isNotEmpty)) rows += row.toList()
        return rows
    }
}
