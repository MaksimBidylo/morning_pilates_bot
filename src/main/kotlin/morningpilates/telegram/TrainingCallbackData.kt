package morningpilates.telegram

import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup
import com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton
import java.nio.ByteBuffer
import java.util.Base64
import java.util.UUID

internal fun TrainingReply.withQuestionToken(existingToken: String? = null): TrainingReply {
    val keyboard = replyMarkup ?: return this
    val token = existingToken ?: newQuestionToken()
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

private fun newQuestionToken(): String {
    val uuid = UUID.randomUUID()
    val bytes =
        ByteBuffer
            .allocate(16)
            .putLong(uuid.mostSignificantBits)
            .putLong(uuid.leastSignificantBits)
            .array()
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
