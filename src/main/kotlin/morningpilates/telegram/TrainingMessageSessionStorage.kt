package morningpilates.telegram

import morningpilates.user.TelegramUserId
import java.util.concurrent.ConcurrentHashMap

class TrainingMessageSessionStorage {
    private val sessions = ConcurrentHashMap<TelegramUserId, TrainingMessageSession>()

    private val questions = ConcurrentHashMap<TelegramUserId, ActiveQuestion>()

    fun get(userId: TelegramUserId): TrainingMessageSession? = sessions[userId]

    fun put(
        userId: TelegramUserId,
        session: TrainingMessageSession,
    ) {
        sessions[userId] = session
    }

    internal fun registerQuestion(
        userId: TelegramUserId,
        message: TrainingMessageSession,
        callbackData: Set<String>,
    ) {
        questions[userId] = ActiveQuestion(message, get(userId), callbackData)
    }

    internal fun consumeQuestion(
        userId: TelegramUserId,
        message: TrainingMessageSession,
        callbackData: String,
    ): Boolean {
        val question = questions[userId] ?: return false
        val matches = question.message == message && question.registration == get(userId)
        val registrationMatches = question.registration?.sessionId?.let { it == message.sessionId } ?: true
        val allowed = matches && registrationMatches && callbackData in question.callbackData
        return allowed && questions.remove(userId, question)
    }

    private data class ActiveQuestion(
        val message: TrainingMessageSession,
        val registration: TrainingMessageSession?,
        val callbackData: Set<String>,
    )
}
