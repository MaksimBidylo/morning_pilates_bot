package morningpilates.telegram

import morningpilates.user.TelegramUserId
import java.util.concurrent.ConcurrentHashMap

class TrainingMessageSessionStorage {
    private val sessions = ConcurrentHashMap<TelegramUserId, StoredSession>()

    fun get(userId: TelegramUserId): TrainingMessageSession? = sessions[userId]?.registration

    fun put(
        userId: TelegramUserId,
        session: TrainingMessageSession,
    ) {
        sessions.compute(userId) { _, previous ->
            if (previous?.registration == session) previous else StoredSession(session, null)
        }
    }

    internal fun registerQuestion(
        userId: TelegramUserId,
        message: TrainingMessageSession,
        callbackData: Set<String>,
    ) {
        sessions.compute(userId) { _, previous ->
            StoredSession(previous?.registration, ActiveQuestion(message, callbackData))
        }
    }

    internal fun consumeQuestion(
        userId: TelegramUserId,
        message: TrainingMessageSession,
        callbackData: String,
    ): Boolean {
        val stored = sessions[userId]
        val question = stored?.question ?: return false
        val matches = question.message == message
        val registrationMatches = stored.registration?.sessionId?.let { it == message.sessionId } ?: true
        val allowed = matches && registrationMatches && callbackData in question.callbackData
        return allowed && sessions.replace(userId, stored, stored.copy(question = null))
    }

    private data class StoredSession(
        val registration: TrainingMessageSession?,
        val question: ActiveQuestion?,
    )

    private data class ActiveQuestion(
        val message: TrainingMessageSession,
        val callbackData: Set<String>,
    )
}
