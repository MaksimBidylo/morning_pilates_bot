package morningpilates.telegram.training.creation

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup
import com.github.kotlintelegrambot.entities.Message
import com.github.kotlintelegrambot.network.bimap
import morningpilates.flow.model.UserSession
import morningpilates.flow.session.FlowStorage
import morningpilates.telegram.api.TelegramChatId
import morningpilates.telegram.api.TelegramMessageEditStatus
import morningpilates.telegram.api.TelegramMessageId
import morningpilates.telegram.api.messageEditStatus
import morningpilates.telegram.training.calendar.TrainingDateCalendar
import morningpilates.telegram.training.reply.TrainingReply
import morningpilates.training.TrainingFlowState
import morningpilates.user.TelegramUserId
import java.time.YearMonth

internal fun Bot.updateTrainingCreationQuestionAndDeleteInput(
    session: UserSession,
    reply: TrainingReply,
    userMessage: Message,
    messageSessionStorage: TrainingCreationMessageSessionStorage,
    telegramUserId: TelegramUserId,
    flowStorage: FlowStorage,
) {
    if (updateTrainingCreationQuestion(session, reply, messageSessionStorage, telegramUserId, flowStorage)) {
        deleteMessage(ChatId.fromId(userMessage.chat.id), messageId = userMessage.messageId)
    }
}

internal fun Bot.updateTrainingCreationQuestion(
    session: UserSession,
    reply: TrainingReply,
    messageSessionStorage: TrainingCreationMessageSessionStorage,
    telegramUserId: TelegramUserId,
    flowStorage: FlowStorage,
    calendar: TrainingDateCalendar? = null,
    timePicker: TrainingTimePicker? = null,
): Boolean {
    val pending =
        PendingTrainingCreationQuestion(
            session,
            telegramUserId,
            flowStorage,
            messageSessionStorage,
            calendar,
            timePicker,
        )
    val displayed = pending.reply(reply)
    val question =
        pending.expected
            ?.registration
            ?.takeIf { it.sessionId == session.sessionId && session.state is TrainingFlowState }
            ?: return false
    val result =
        editMessageText(
            chatId = ChatId.fromId(question.chatId.value),
            messageId = question.messageId.value,
            text = displayed.text,
            replyMarkup = displayed.replyMarkup,
            parseMode = displayed.parseMode,
        )
    val editStatus =
        result.bimap(
            mapResponse = { response ->
                if (response?.ok == true) TelegramMessageEditStatus.Updated else TelegramMessageEditStatus.Failed
            },
            mapError = { error -> error.messageEditStatus(result.first?.code()) },
        )
    return when (editStatus) {
        TelegramMessageEditStatus.Updated -> pending.register(question, displayed)
        TelegramMessageEditStatus.Missing -> sendTrainingCreationQuestion(pending, question.chatId, displayed)
        TelegramMessageEditStatus.Failed -> false
    }
}

internal fun Bot.sendTrainingCreationQuestion(
    session: UserSession,
    chatId: TelegramChatId,
    reply: TrainingReply,
    messageSessionStorage: TrainingCreationMessageSessionStorage,
    telegramUserId: TelegramUserId,
    flowStorage: FlowStorage,
): Boolean {
    if (session.state !is TrainingFlowState) return false
    val pending = PendingTrainingCreationQuestion(session, telegramUserId, flowStorage, messageSessionStorage)
    return sendTrainingCreationQuestion(pending, chatId, pending.reply(reply))
}

private fun Bot.sendTrainingCreationQuestion(
    pending: PendingTrainingCreationQuestion,
    chatId: TelegramChatId,
    reply: TrainingReply,
): Boolean {
    val sent =
        sendMessage(
            chatId = ChatId.fromId(chatId.value),
            text = reply.text,
            replyMarkup = reply.replyMarkup,
            parseMode = reply.parseMode,
        ).getOrNull() ?: return false
    val registration =
        TrainingCreationMessageSession(
            sessionId = pending.session.sessionId,
            chatId = TelegramChatId(sent.chat.id),
            messageId = TelegramMessageId(sent.messageId),
        )
    val registered = pending.register(registration, reply)
    if (!registered) retireTrainingCreationQuestion(registration)
    return registered
}

private class PendingTrainingCreationQuestion(
    val session: UserSession,
    private val userId: TelegramUserId,
    private val flowStorage: FlowStorage,
    private val messages: TrainingCreationMessageSessionStorage,
    selectedCalendar: TrainingDateCalendar? = null,
    selectedTimePicker: TrainingTimePicker? = null,
) {
    val expected = messages.snapshot(userId)
    private val calendar =
        if (messages.calendarEnabled && session.state == TrainingFlowState.WaitingDate) {
            selectedCalendar ?: expected?.calendar?.takeIf { expected.registration?.sessionId == session.sessionId }
                ?: TrainingDateCalendar(YearMonth.now(messages.clock))
        } else {
            null
        }

    private val timePicker =
        if (messages.timePickerEnabled && session.state is TrainingFlowState.WaitingStartTime) {
            selectedTimePicker ?: expected?.timePicker?.takeIf { expected.registration?.sessionId == session.sessionId }
                ?: TrainingTimePicker()
        } else {
            null
        }

    fun reply(base: TrainingReply): TrainingReply = timePicker?.reply(base) ?: calendar?.reply(base) ?: base

    fun register(
        question: TrainingCreationMessageSession,
        reply: TrainingReply,
    ): Boolean =
        flowStorage.withCurrentSession(userId, session) {
            messages.putQuestionIfCurrent(userId, expected, question, reply.callbackData(), calendar, timePicker)
        }
}

private fun Bot.retireTrainingCreationQuestion(question: TrainingCreationMessageSession) {
    val chatId = ChatId.fromId(question.chatId.value)
    if (deleteMessage(chatId, question.messageId.value).getOrNull() != true) {
        editMessageReplyMarkup(
            chatId = chatId,
            messageId = question.messageId.value,
            replyMarkup = InlineKeyboardMarkup.create(),
        )
    }
}
