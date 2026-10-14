package morningpilates.telegram

import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup
import com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton

internal fun TrainingReply.withQuestionToken(token: String): TrainingReply {
    val keyboard = replyMarkup ?: return this
    return copy(
        replyMarkup =
            InlineKeyboardMarkup.create(
                keyboard.inlineKeyboard.map { row ->
                    row.map { button ->
                        check(button is InlineKeyboardButton.CallbackData)
                        button.copy(callbackData = "${button.callbackData}:$token")
                    }
                },
            ),
    )
}

internal fun TrainingReply.callbackData(): Set<String> =
    replyMarkup
        ?.inlineKeyboard
        .orEmpty()
        .flatten()
        .map {
            check(it is InlineKeyboardButton.CallbackData)
            it.callbackData
        }.toSet()
