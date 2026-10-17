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
import morningpilates.training.TrainingFlowState.WaitingDate
import morningpilates.user.TelegramUserId

internal fun Dispatcher.registerTrainingDateCalendar(
    storage: FlowStorage,
    manager: FlowSessionManager,
    messages: TrainingCreationMessageSessionStorage,
) {
    registerAcknowledgedCallback(CALENDAR_PREFIX, messages::isFromPreviousLaunch) handle@{
        val message = callbackQuery.message ?: return@handle
        if (message.chat.type != "private") return@handle
        val user = TelegramUserId(callbackQuery.from.id)
        val session = storage.get(user)?.takeIf { it.state == WaitingDate } ?: return@handle
        val before = messages.snapshot(user) ?: return@handle
        val calendar = before.calendar ?: return@handle
        val action = callbackQuery.data.removePrefix(CALENDAR_PREFIX).substringBeforeLast(':')
        if (action == "ignore") return@handle
        val question =
            TrainingCreationMessageSession(
                session.sessionId,
                TelegramChatId(message.chat.id),
                TelegramMessageId(message.messageId),
            )
        if (!messages.consumeQuestion(user, question, callbackQuery.data)) return@handle
        if (action.startsWith("day-")) {
            val date = calendar.month.atDay(action.removePrefix("day-").toInt())
            val result = manager.handle(UserInput(user, date.toString()))
            val current = storage.get(user)?.takeIf { it.sessionId == session.sessionId } ?: return@handle
            if (result !is FlowResult.Transition || current.state != result.nextState) return@handle
            val token = messages.newQuestionToken()
            val reply = promptForCreation(current.state)?.withCreationQuestionToken(token) ?: return@handle
            bot.updateTrainingCreationQuestion(current, reply, messages, user, storage)
        } else {
            bot.navigateCalendar(storage, messages, user, session, before, calendar.navigate(action))
        }
    }
}

private fun Bot.navigateCalendar(
    storage: FlowStorage,
    messages: TrainingCreationMessageSessionStorage,
    user: TelegramUserId,
    session: UserSession,
    before: TrainingCreationMessageSessionStorage.StoredSession,
    calendar: TrainingDateCalendar,
) {
    val consumed = messages.snapshot(user)
    val reply = checkNotNull(promptForCreation(session.state)).withCreationQuestionToken(messages.newQuestionToken())
    if (!updateTrainingCreationQuestion(session, reply, messages, user, storage, calendar)) {
        // A failed navigation must leave the previous question usable, without resurrecting a cancelled session.
        storage.withCurrentSession(user, session) {
            val question = checkNotNull(before.question)
            messages.putQuestionIfCurrent(user, consumed, question.message, question.callbackData, before.calendar)
        }
    }
}
