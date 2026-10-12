package morningpilates.telegram

import com.github.kotlintelegrambot.dispatcher.Dispatcher
import com.github.kotlintelegrambot.dispatcher.text
import com.github.kotlintelegrambot.entities.ChatId
import morningpilates.flow.model.FlowEvent.UserInput
import morningpilates.flow.model.FlowResult
import morningpilates.flow.model.FlowState
import morningpilates.flow.session.FlowSessionManager
import morningpilates.flow.session.FlowStorage
import morningpilates.training.TrainingFlowState.WaitingDate
import morningpilates.training.TrainingFlowState.WaitingStartTime
import morningpilates.user.TelegramUserId

private val TIME_PROMPT =
    """
    Введите время начала тренировки.

    Например: 8:00, 19:30 или 8-00.
    """.trimIndent()

fun Dispatcher.registerTrainingTextHandler(
    storage: FlowStorage,
    sessionManager: FlowSessionManager,
) {
    text {
        if (message.chat.type != "private") return@text
        if (text.startsWith("/")) return@text

        val sender = message.from ?: return@text
        val telegramUserId = TelegramUserId(sender.id)
        if (storage.get(telegramUserId)?.state != WaitingDate) return@text

        val result = sessionManager.handle(UserInput(telegramUserId, text))
        val replyText =
            when (result) {
                is FlowResult.Rejected -> result.reason
                is FlowResult.Transition -> promptFor(result.nextState)
                FlowResult.Completed, FlowResult.Cancelled -> null
            } ?: return@text

        bot.sendMessage(
            chatId = ChatId.fromId(message.chat.id),
            text = replyText,
        )
    }
}

private fun promptFor(state: FlowState): String? =
    when (state) {
        is WaitingStartTime -> TIME_PROMPT
        else -> null
    }
