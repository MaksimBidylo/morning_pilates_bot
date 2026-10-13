package morningpilates.telegram

import morningpilates.flow.model.SessionId

data class TrainingMessageSession(
    val sessionId: SessionId,
    val chatId: TelegramChatId,
    val messageId: TelegramMessageId,
)
