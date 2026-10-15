package morningpilates.training

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

private const val DATE_INPUT_LENGTH = 10
private val DATE_FORMATTERS =
    listOf(
        "dd.MM.uuuu",
        "dd/MM/uuuu",
        "dd-MM-uuuu",
        "uuuu.MM.dd",
        "uuuu/MM/dd",
        "uuuu-MM-dd",
    ).map {
        DateTimeFormatter.ofPattern(it).withResolverStyle(ResolverStyle.STRICT)
    }

internal fun parseTrainingDate(text: String): LocalDate? {
    val input = text.trim()
    if (input.length == DATE_INPUT_LENGTH) {
        for (formatter in DATE_FORMATTERS) {
            try {
                return LocalDate.parse(input, formatter)
            } catch (_: DateTimeParseException) {
                continue
            }
        }
    }
    return null
}
