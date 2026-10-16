package morningpilates.telegram.profile

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.network.bimap
import morningpilates.telegram.api.TelegramMessageEditStatus
import morningpilates.telegram.api.messageEditStatus
import java.time.LocalDate

internal data class ProfileScreen(
    val chatId: Long,
    val messageId: Long,
    val token: String,
    val content: ProfileContent,
) {
    fun authorizes(
        chatId: Long,
        messageId: Long,
        data: String,
    ): Boolean =
        this.chatId == chatId && this.messageId == messageId &&
            content.actions().any { (_, action) -> data == "profile:$action:$token" }
}

internal fun Bot.deliverProfileScreen(
    chatId: Long,
    previous: ProfileScreen?,
    content: ProfileContent,
    token: String,
    today: LocalDate,
): ProfileScreen? =
    if (previous == null) {
        sendProfileScreen(chatId, content, token, today)
    } else {
        when (updateProfileScreen(previous, content, token, today)) {
            TelegramMessageEditStatus.Updated -> previous.copy(token = token, content = content)
            TelegramMessageEditStatus.Missing -> sendProfileScreen(chatId, content, token, today)
            TelegramMessageEditStatus.Failed -> null
        }
    }

private fun Bot.sendProfileScreen(
    chatId: Long,
    content: ProfileContent,
    token: String,
    today: LocalDate,
): ProfileScreen? =
    sendMessage(
        ChatId.fromId(chatId),
        text = content.text(today),
        replyMarkup = content.keyboard(token),
    ).getOrNull()?.let { ProfileScreen(chatId, it.messageId, token, content) }

private fun Bot.updateProfileScreen(
    screen: ProfileScreen,
    content: ProfileContent,
    token: String,
    today: LocalDate,
): TelegramMessageEditStatus {
    val result =
        editMessageText(
            ChatId.fromId(screen.chatId),
            screen.messageId,
            text = content.text(today),
            replyMarkup = content.keyboard(token),
        )
    return result.bimap(
        mapResponse = {
            if (it?.ok == true) TelegramMessageEditStatus.Updated else TelegramMessageEditStatus.Failed
        },
        mapError = { error -> error.messageEditStatus(result.first?.code()) },
    )
}
