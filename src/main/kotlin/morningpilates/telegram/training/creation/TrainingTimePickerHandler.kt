package morningpilates.telegram.training.creation

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.dispatcher.Dispatcher
import morningpilates.flow.model.FlowEvent.UserInput
import morningpilates.flow.model.FlowResult
import morningpilates.flow.model.UserSession
import morningpilates.flow.session.FlowSessionManager
import morningpilates.flow.session.FlowStorage
import morningpilates.telegram.api.TelegramChatId
import morningpilates.telegram.api.TelegramMessageId
import morningpilates.telegram.api.registerAcknowledgedCallback
import morningpilates.training.TrainingFlowState.WaitingStartTime
import morningpilates.user.TelegramUserId
import java.time.LocalTime

internal fun Dispatcher.registerTrainingTimePicker(
    storage: FlowStorage,
    manager: FlowSessionManager,
    messages: TrainingCreationMessageSessionStorage,
) {
    registerAcknowledgedCallback(TIME_PICKER_PREFIX, messages::isFromPreviousLaunch) handle@{
        val message = callbackQuery.message ?: return@handle
        if (message.chat.type != "private") return@handle
        val user = TelegramUserId(callbackQuery.from.id)
        val session = storage.get(user)?.takeIf { it.state is WaitingStartTime } ?: return@handle
        val before = messages.snapshot(user) ?: return@handle
        val picker = before.timePicker ?: return@handle
        val question =
            TrainingCreationMessageSession(
                session.sessionId,
                TelegramChatId(message.chat.id),
                TelegramMessageId(message.messageId),
            )
        if (!messages.consumeQuestion(user, question, callbackQuery.data)) return@handle
        val action = callbackQuery.data.removePrefix(TIME_PICKER_PREFIX).substringBeforeLast(':')
        if (action.startsWith("minute-")) {
            val time = LocalTime.of(checkNotNull(picker.hour), action.removePrefix("minute-").toInt())
            val result = manager.handle(UserInput(user, time.toString()))
            val current = storage.get(user)?.takeIf { it.sessionId == session.sessionId } ?: return@handle
            if (result !is FlowResult.Transition || current.state != result.nextState) return@handle
            val token = messages.newQuestionToken()
            val reply = promptForCreation(current.state)?.withCreationQuestionToken(token) ?: return@handle
            bot.updateTrainingCreationQuestion(current, reply, messages, user, storage)
        } else {
            val next =
                if (action == "back") {
                    TrainingTimePicker()
                } else {
                    TrainingTimePicker(action.removePrefix("hour-").toInt())
                }
            bot.navigateTimePicker(storage, messages, user, session, before, next)
        }
    }
}

private fun Bot.navigateTimePicker(
    storage: FlowStorage,
    messages: TrainingCreationMessageSessionStorage,
    user: TelegramUserId,
    session: UserSession,
    before: TrainingCreationMessageSessionStorage.StoredSession,
    picker: TrainingTimePicker,
) {
    val consumed = messages.snapshot(user)
    val reply = checkNotNull(promptForCreation(session.state)).withCreationQuestionToken(messages.newQuestionToken())
    if (!updateTrainingCreationQuestion(session, reply, messages, user, storage, timePicker = picker)) {
        storage.withCurrentSession(user, session) {
            val question = checkNotNull(before.question)
            messages.putQuestionIfCurrent(
                user,
                consumed,
                question.message,
                question.callbackData,
                timePicker = before.timePicker,
            )
        }
    }
}
