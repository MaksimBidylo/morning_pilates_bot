package morningpilates.telegram

import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup
import com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton
import java.nio.ByteBuffer
import java.util.Base64
import java.util.UUID

internal fun TrainingReply.withQuestionToken(): TrainingReply {
    val keyboard = replyMarkup ?: return this
    val uuid = UUID.randomUUID()
    val token =
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            ByteBuffer
                .allocate(16)
                .putLong(uuid.mostSignificantBits)
                .putLong(uuid.leastSignificantBits)
                .array(),
        )
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
