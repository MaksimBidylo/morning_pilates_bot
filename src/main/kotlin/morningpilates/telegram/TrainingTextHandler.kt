package morningpilates.telegram

import com.github.kotlintelegrambot.dispatcher.Dispatcher
import com.github.kotlintelegrambot.dispatcher.text
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup
import com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton
import morningpilates.flow.model.FlowEvent.UserInput
import morningpilates.flow.model.FlowResult
import morningpilates.flow.model.FlowState
import morningpilates.flow.session.FlowSessionManager
import morningpilates.flow.session.FlowStorage
import morningpilates.training.TrainingFlowState.WaitingAddTechnique
import morningpilates.training.TrainingFlowState.WaitingDate
import morningpilates.training.TrainingFlowState.WaitingStartTime
import morningpilates.user.TelegramUserId

private const val ADD_TECHNIQUE_PROMPT = "Добавить технику?"
private const val YES_BUTTON_TEXT = "Да"
private const val NO_BUTTON_TEXT = "Нет"
private const val ADD_TECHNIQUE_YES_CALLBACK = "training:add_technique:yes"
private const val ADD_TECHNIQUE_NO_CALLBACK = "training:add_technique:no"
private val ADD_TECHNIQUE_KEYBOARD =
    InlineKeyboardMarkup.createSingleRowKeyboard(
        InlineKeyboardButton.CallbackData(text = YES_BUTTON_TEXT, callbackData = ADD_TECHNIQUE_YES_CALLBACK),
        InlineKeyboardButton.CallbackData(text = NO_BUTTON_TEXT, callbackData = ADD_TECHNIQUE_NO_CALLBACK),
    )
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
        val state = storage.get(telegramUserId)?.state
        if (state != WaitingDate && state !is WaitingStartTime) return@text

        val reply =
            when (val result = sessionManager.handle(UserInput(telegramUserId, text))) {
                is FlowResult.Rejected -> TrainingReply(result.reason)
                is FlowResult.Transition -> promptFor(result.nextState)
                FlowResult.Completed, FlowResult.Cancelled -> null
            } ?: return@text

        bot.sendMessage(
            chatId = ChatId.fromId(message.chat.id),
            text = reply.text,
            replyMarkup = reply.replyMarkup,
        )
    }
}

private fun promptFor(state: FlowState): TrainingReply? =
    when (state) {
        is WaitingStartTime -> TrainingReply(TIME_PROMPT)
        is WaitingAddTechnique -> TrainingReply(ADD_TECHNIQUE_PROMPT, replyMarkup = ADD_TECHNIQUE_KEYBOARD)
        else -> null
    }

private data class TrainingReply(
    val text: String,
    val replyMarkup: InlineKeyboardMarkup? = null,
)
