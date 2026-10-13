package morningpilates.telegram

import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup
import com.github.kotlintelegrambot.entities.ParseMode
import morningpilates.training.TrainingDetails
import java.time.format.DateTimeFormatter

private val DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.uuuu")
private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")

internal fun TrainingDetails.toSavedTrainingReply(): TrainingReply {
    val summary =
        buildString {
            append("Дата: ${date.format(DATE_FORMAT)}")
            append("\nВремя: ${time.format(TIME_FORMAT)}")
            if (techniques.isNotEmpty()) {
                append("\n\nТехники:")
                techniques.forEachIndexed { index, technique ->
                    append("\n${index + 1}. ${technique.name.escapeHtml()}")
                    technique.description?.let { append("\n   Описание: ${it.escapeHtml()}") }
                }
            }
            note?.let { append("\n\nЗаметка: ${it.escapeHtml()}") }
        }
    return TrainingReply(
        text = "Тренировка сохранена.\n\n<blockquote expandable>$summary</blockquote>",
        replyMarkup = InlineKeyboardMarkup.create(),
        parseMode = ParseMode.HTML,
    )
}

private fun String.escapeHtml(): String = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
