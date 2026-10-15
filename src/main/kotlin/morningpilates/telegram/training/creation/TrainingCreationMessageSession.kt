package morningpilates.telegram.training.creation

import morningpilates.flow.model.SessionId
import morningpilates.telegram.api.TelegramChatId
import morningpilates.telegram.api.TelegramMessageId

data class TrainingCreationMessageSession(
    val sessionId: SessionId,
    val chatId: TelegramChatId,
    val messageId: TelegramMessageId,
)
