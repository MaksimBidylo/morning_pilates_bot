package morningpilates.telegram.training.creation

import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup
import com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton
import morningpilates.telegram.training.reply.TrainingReply

internal const val TIME_PICKER_PREFIX = "training-time:"
private const val HOURS_PER_ROW = 6
private val MINUTES = listOf(0, 15, 30, 45)

internal data class TrainingTimePicker(
    val hour: Int? = null,
) {
    fun reply(base: TrainingReply): TrainingReply {
        val token = base.callbackData().first().substringAfterLast(':')
        val rows =
            if (hour == null) {
                (0..23).map { padded(it) to "hour-$it" }.chunked(HOURS_PER_ROW)
            } else {
                listOf(MINUTES.map { padded(it) to "minute-$it" }, listOf("Назад" to "back"))
            }
        val buttons =
            rows.map { row ->
                row.map { (text, action) ->
                    InlineKeyboardButton.CallbackData(text, "$TIME_PICKER_PREFIX$action:$token")
                }
            }
        val instruction =
            if (hour == null) {
                "🕒 Выберите час (00–23) или отправьте время сообщением. Формат: Ч:ММ."
            } else {
                "🕒 Час: ${padded(hour)}. Выберите минуты или отправьте время сообщением. Формат: Ч:ММ."
            }
        // Keep any rejection or unfinished-training warning below the instruction, above the keyboard.
        val warning =
            base.text
                .removeSuffix(TIME_PROMPT)
                .trim()
                .takeIf { it.isNotEmpty() }
        return base.copy(
            text = listOfNotNull(instruction, warning).joinToString("\n\n"),
            replyMarkup = InlineKeyboardMarkup.create(buttons + base.replyMarkup?.inlineKeyboard.orEmpty()),
        )
    }
}

private fun padded(value: Int): String = value.toString().padStart(2, '0')
