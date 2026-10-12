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
import morningpilates.training.TrainingFlowState.WaitingAddNote
import morningpilates.training.TrainingFlowState.WaitingAddTechnique
import morningpilates.training.TrainingFlowState.WaitingAddTechniqueDescription
import morningpilates.training.TrainingFlowState.WaitingDate
import morningpilates.training.TrainingFlowState.WaitingStartTime
import morningpilates.training.TrainingFlowState.WaitingTechniqueDescription
import morningpilates.training.TrainingFlowState.WaitingTechniqueName
import morningpilates.user.TelegramUserId

private const val ADD_TECHNIQUE_PROMPT = "Добавить технику?"
private const val ADD_TECHNIQUE_DESCRIPTION_PROMPT = "Добавить описание техники?"
private const val ADD_NOTE_PROMPT = "Добавить заметку?"
private const val TECHNIQUE_NAME_PROMPT = "Введите название техники."
private const val TECHNIQUE_DESCRIPTION_PROMPT = "Введите описание техники."
internal const val YES_BUTTON_TEXT = "Да"
internal const val NO_BUTTON_TEXT = "Нет"
internal const val ADD_TECHNIQUE_YES_CALLBACK = "training:add_technique:yes"
internal const val ADD_TECHNIQUE_NO_CALLBACK = "training:add_technique:no"
internal const val ADD_TECHNIQUE_DESCRIPTION_YES_CALLBACK = "training:add_technique_description:yes"
private const val ADD_TECHNIQUE_DESCRIPTION_NO_CALLBACK = "training:add_technique_description:no"
private const val ADD_NOTE_YES_CALLBACK = "training:add_note:yes"
private const val ADD_NOTE_NO_CALLBACK = "training:add_note:no"
private val ADD_TECHNIQUE_KEYBOARD = yesNoKeyboard(ADD_TECHNIQUE_YES_CALLBACK, ADD_TECHNIQUE_NO_CALLBACK)
private val ADD_TECHNIQUE_DESCRIPTION_KEYBOARD =
    yesNoKeyboard(ADD_TECHNIQUE_DESCRIPTION_YES_CALLBACK, ADD_TECHNIQUE_DESCRIPTION_NO_CALLBACK)
private val ADD_NOTE_KEYBOARD = yesNoKeyboard(ADD_NOTE_YES_CALLBACK, ADD_NOTE_NO_CALLBACK)
private val ADD_TECHNIQUE_DESCRIPTION_REPLY =
    TrainingReply(ADD_TECHNIQUE_DESCRIPTION_PROMPT, replyMarkup = ADD_TECHNIQUE_DESCRIPTION_KEYBOARD)
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
        if (state != WaitingDate && state !is WaitingStartTime && state !is WaitingTechniqueName) return@text

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

internal fun promptFor(state: FlowState): TrainingReply? =
    when (state) {
        is WaitingStartTime -> TrainingReply(TIME_PROMPT)
        is WaitingAddTechnique -> TrainingReply(ADD_TECHNIQUE_PROMPT, replyMarkup = ADD_TECHNIQUE_KEYBOARD)
        is WaitingAddTechniqueDescription -> ADD_TECHNIQUE_DESCRIPTION_REPLY
        is WaitingAddNote -> TrainingReply(ADD_NOTE_PROMPT, replyMarkup = ADD_NOTE_KEYBOARD)
        is WaitingTechniqueDescription -> TrainingReply(TECHNIQUE_DESCRIPTION_PROMPT)
        is WaitingTechniqueName -> TrainingReply(TECHNIQUE_NAME_PROMPT)
        else -> null
    }

private fun yesNoKeyboard(
    yesCallback: String,
    noCallback: String,
): InlineKeyboardMarkup =
    InlineKeyboardMarkup.createSingleRowKeyboard(
        InlineKeyboardButton.CallbackData(text = YES_BUTTON_TEXT, callbackData = yesCallback),
        InlineKeyboardButton.CallbackData(text = NO_BUTTON_TEXT, callbackData = noCallback),
    )

internal data class TrainingReply(
    val text: String,
    val replyMarkup: InlineKeyboardMarkup? = null,
)
