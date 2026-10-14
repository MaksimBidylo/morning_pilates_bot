package morningpilates.telegram

import morningpilates.user.TelegramUserId
import java.nio.ByteBuffer
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val LAUNCH_TOKEN_LENGTH = 8
private const val QUESTION_TOKEN_LENGTH = 13
private const val FULL_TOKEN_LENGTH = LAUNCH_TOKEN_LENGTH + 1 + QUESTION_TOKEN_LENGTH

class TrainingMessageSessionStorage {
    private val launchToken = randomQuestionToken().take(LAUNCH_TOKEN_LENGTH)
    private val sessions = ConcurrentHashMap<TelegramUserId, StoredSession>()

    internal fun newQuestionToken(): String = "$launchToken.${randomQuestionToken().take(QUESTION_TOKEN_LENGTH)}"

    internal fun isFromPreviousLaunch(callbackData: String): Boolean {
        val token = callbackData.substringAfterLast(':')
        return token.length == FULL_TOKEN_LENGTH &&
            token[LAUNCH_TOKEN_LENGTH] == '.' &&
            token.substringBefore('.') != launchToken
    }

    fun get(userId: TelegramUserId): TrainingMessageSession? = sessions[userId]?.registration

    fun remove(
        userId: TelegramUserId,
        expected: TrainingMessageSession,
    ): Boolean {
        val stored = sessions[userId] ?: return false
        return stored.registration == expected && sessions.remove(userId, stored)
    }

    fun put(
        userId: TelegramUserId,
        session: TrainingMessageSession,
    ) {
        sessions.compute(userId) { _, previous ->
            if (previous?.registration == session) previous else StoredSession(session, null)
        }
    }

    internal fun questionToken(userId: TelegramUserId): String? =
        sessions[userId]
            ?.question
            ?.callbackData
            ?.firstOrNull()
            ?.substringAfterLast(':')

    /** Removes callback authorization while keeping the message available for redisplay. */
    internal fun invalidateQuestion(
        userId: TelegramUserId,
        expected: TrainingMessageSession,
    ) {
        val stored = sessions[userId] ?: return
        if (stored.registration == expected) {
            sessions.replace(userId, stored, stored.copy(question = null))
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

private fun randomQuestionToken(): String {
    val uuid = UUID.randomUUID()
    val bytes =
        ByteBuffer
            .allocate(16)
            .putLong(uuid.mostSignificantBits)
            .putLong(uuid.leastSignificantBits)
            .array()
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
