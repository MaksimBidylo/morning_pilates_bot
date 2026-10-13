package morningpilates.telegram

import com.github.kotlintelegrambot.dispatcher.Dispatcher
import com.github.kotlintelegrambot.dispatcher.text
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup
import com.github.kotlintelegrambot.entities.ParseMode
import com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton
import morningpilates.flow.model.FlowEvent.UserInput
import morningpilates.flow.model.FlowResult
import morningpilates.flow.model.FlowState
import morningpilates.flow.session.FlowSessionManager
import morningpilates.flow.session.FlowStorage
import morningpilates.training.TrainingFlowState.WaitingAddAnotherTechnique
import morningpilates.training.TrainingFlowState.WaitingAddNote
import morningpilates.training.TrainingFlowState.WaitingAddTechnique
import morningpilates.training.TrainingFlowState.WaitingAddTechniqueDescription
import morningpilates.training.TrainingFlowState.WaitingConfirmation
import morningpilates.training.TrainingFlowState.WaitingDate
import morningpilates.training.TrainingFlowState.WaitingNote
import morningpilates.training.TrainingFlowState.WaitingStartTime
import morningpilates.training.TrainingFlowState.WaitingTechniqueDescription
import morningpilates.training.TrainingFlowState.WaitingTechniqueName
import morningpilates.user.TelegramUserId

private const val ADD_TECHNIQUE_PROMPT = "Добавить технику?"
private const val ADD_TECHNIQUE_DESCRIPTION_PROMPT = "Добавить описание техники?"
private const val ADD_ANOTHER_TECHNIQUE_PROMPT = "Добавить ещё одну технику?"
private const val ADD_NOTE_PROMPT = "Добавить заметку?"
private const val CONFIRMATION_PROMPT = "Сохранить тренировку?"
private const val TECHNIQUE_NAME_PROMPT = "Введите название техники."
private const val TECHNIQUE_DESCRIPTION_PROMPT = "Введите описание техники."
private const val NOTE_PROMPT = "Введите заметку о тренировке."
internal const val CANCEL_TRAINING_CALLBACK = "training:cancel"
private const val CANCEL_TRAINING_TEXT = "Отменить тренировку"
internal const val YES_BUTTON_TEXT = "Да"
internal const val NO_BUTTON_TEXT = "Нет"
internal const val ADD_TECHNIQUE_YES_CALLBACK = "training:add_technique:yes"
internal const val ADD_TECHNIQUE_NO_CALLBACK = "training:add_technique:no"
internal const val ADD_TECHNIQUE_DESCRIPTION_YES_CALLBACK = "training:add_technique_description:yes"
internal const val ADD_TECHNIQUE_DESCRIPTION_NO_CALLBACK = "training:add_technique_description:no"
internal const val ADD_ANOTHER_TECHNIQUE_YES_CALLBACK = "training:add_another_technique:yes"
internal const val ADD_ANOTHER_TECHNIQUE_NO_CALLBACK = "training:add_another_technique:no"
internal const val ADD_NOTE_YES_CALLBACK = "training:add_note:yes"
internal const val ADD_NOTE_NO_CALLBACK = "training:add_note:no"
internal const val CONFIRMATION_YES_CALLBACK = "training:confirmation:yes"
internal const val CONFIRMATION_NO_CALLBACK = "training:confirmation:no"
private val ADD_TECHNIQUE_KEYBOARD = yesNoKeyboard(ADD_TECHNIQUE_YES_CALLBACK, ADD_TECHNIQUE_NO_CALLBACK)
private val ADD_TECHNIQUE_DESCRIPTION_KEYBOARD =
    yesNoKeyboard(ADD_TECHNIQUE_DESCRIPTION_YES_CALLBACK, ADD_TECHNIQUE_DESCRIPTION_NO_CALLBACK)
private val ADD_ANOTHER_TECHNIQUE_KEYBOARD =
    yesNoKeyboard(ADD_ANOTHER_TECHNIQUE_YES_CALLBACK, ADD_ANOTHER_TECHNIQUE_NO_CALLBACK)
private val ADD_NOTE_KEYBOARD = yesNoKeyboard(ADD_NOTE_YES_CALLBACK, ADD_NOTE_NO_CALLBACK)
private val CONFIRMATION_KEYBOARD = yesNoKeyboard(CONFIRMATION_YES_CALLBACK, CONFIRMATION_NO_CALLBACK)
private val ADD_TECHNIQUE_DESCRIPTION_REPLY =
    TrainingReply(ADD_TECHNIQUE_DESCRIPTION_PROMPT, replyMarkup = ADD_TECHNIQUE_DESCRIPTION_KEYBOARD)
private val ADD_ANOTHER_TECHNIQUE_REPLY =
    TrainingReply(ADD_ANOTHER_TECHNIQUE_PROMPT, replyMarkup = ADD_ANOTHER_TECHNIQUE_KEYBOARD)
private val ADD_NOTE_REPLY = TrainingReply(ADD_NOTE_PROMPT, replyMarkup = ADD_NOTE_KEYBOARD)
private val CONFIRMATION_REPLY = TrainingReply(CONFIRMATION_PROMPT, replyMarkup = CONFIRMATION_KEYBOARD)
private val DATE_PROMPT =
    """
    Введите дату тренировки.

    Например: 11.10.2026 или 2026-10-11.

    Можно использовать разделители: точку, дефис или косую черту.
    """.trimIndent()
private val TIME_PROMPT =
    """
    Введите время начала тренировки.

    Например: 8:00, 19:30 или 8-00.
    """.trimIndent()

fun Dispatcher.registerTrainingTextHandler(
    storage: FlowStorage,
    sessionManager: FlowSessionManager,
    messageSessionStorage: TrainingMessageSessionStorage,
) {
    text {
        if (message.chat.type != "private") return@text
        if (text.startsWith("/")) return@text

        val sender = message.from ?: return@text
        val telegramUserId = TelegramUserId(sender.id)
        val state = storage.get(telegramUserId)?.state
        when (state) {
            WaitingDate,
            is WaitingStartTime,
            is WaitingTechniqueName,
            is WaitingTechniqueDescription,
            is WaitingNote,
            -> Unit

            else -> return@text
        }

        val result = sessionManager.handle(UserInput(telegramUserId, text))
        val question = messageSessionStorage.get(telegramUserId)
        val reply =
            (result.toTrainingReply(state, hasRegisteredQuestion = question != null) ?: return@text)
                .withQuestionToken(
                    if (result is FlowResult.Rejected) messageSessionStorage.questionToken(telegramUserId) else null,
                )

        if (question != null) {
            if (result is FlowResult.Transition) messageSessionStorage.invalidateQuestion(telegramUserId, question)
            bot.updateTrainingQuestionAndDeleteInput(question, reply, message, messageSessionStorage, telegramUserId)
            return@text
        }

        val sent =
            bot
                .sendMessage(
                    chatId = ChatId.fromId(message.chat.id),
                    text = reply.text,
                    replyMarkup = reply.replyMarkup,
                ).getOrNull() ?: return@text
        val session = storage.get(telegramUserId) ?: return@text
        messageSessionStorage.registerQuestion(
            telegramUserId,
            TrainingMessageSession(session.sessionId, TelegramChatId(sent.chat.id), TelegramMessageId(sent.messageId)),
            reply.callbackData(),
        )
    }
}

private fun FlowResult.toTrainingReply(
    state: FlowState,
    hasRegisteredQuestion: Boolean,
): TrainingReply? =
    when (this) {
        is FlowResult.Rejected -> {
            if (hasRegisteredQuestion) {
                promptFor(state)?.let { prompt -> prompt.copy(text = "⚠️ $reason\n\n${prompt.text}") }
            } else {
                TrainingReply(reason)
            }
        }

        is FlowResult.Transition -> {
            promptFor(nextState)
        }

        FlowResult.Completed, FlowResult.Cancelled -> {
            null
        }
    }

internal fun promptFor(state: FlowState): TrainingReply? {
    val reply =
        when (state) {
            WaitingDate -> TrainingReply(DATE_PROMPT)
            is WaitingStartTime -> TrainingReply(TIME_PROMPT)
            is WaitingAddTechnique -> TrainingReply(ADD_TECHNIQUE_PROMPT, replyMarkup = ADD_TECHNIQUE_KEYBOARD)
            is WaitingAddTechniqueDescription -> ADD_TECHNIQUE_DESCRIPTION_REPLY
            is WaitingAddAnotherTechnique -> ADD_ANOTHER_TECHNIQUE_REPLY
            is WaitingAddNote -> ADD_NOTE_REPLY
            is WaitingConfirmation -> CONFIRMATION_REPLY
            is WaitingNote -> TrainingReply(NOTE_PROMPT)
            is WaitingTechniqueDescription -> TrainingReply(TECHNIQUE_DESCRIPTION_PROMPT)
            is WaitingTechniqueName -> TrainingReply(TECHNIQUE_NAME_PROMPT)
            else -> return null
        }
    return reply.withCancelButton()
}

private fun TrainingReply.withCancelButton(): TrainingReply {
    val rows = replyMarkup?.inlineKeyboard.orEmpty()
    val cancel = InlineKeyboardButton.CallbackData(CANCEL_TRAINING_TEXT, CANCEL_TRAINING_CALLBACK)
    return copy(replyMarkup = InlineKeyboardMarkup.create(rows + listOf(listOf(cancel))))
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
    val parseMode: ParseMode? = null,
)
