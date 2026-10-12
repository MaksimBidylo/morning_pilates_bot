package morningpilates.flow.model

import morningpilates.user.TelegramUserId

sealed interface FlowEvent {
    val telegramUserId: TelegramUserId

    data class UserInput(
        override val telegramUserId: TelegramUserId,
        val text: String,
    ) : FlowEvent

    data class Continue(
        override val telegramUserId: TelegramUserId,
        val sessionId: SessionId,
    ) : FlowEvent
}
