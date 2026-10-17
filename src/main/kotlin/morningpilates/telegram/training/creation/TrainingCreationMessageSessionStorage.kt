package morningpilates.telegram.training.creation

import morningpilates.telegram.training.calendar.TrainingDateCalendar
import morningpilates.user.TelegramUserId
import java.nio.ByteBuffer
import java.time.Clock
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val LAUNCH_TOKEN_LENGTH = 8
private const val QUESTION_TOKEN_LENGTH = 13
private const val FULL_TOKEN_LENGTH = LAUNCH_TOKEN_LENGTH + 1 + QUESTION_TOKEN_LENGTH

class TrainingCreationMessageSessionStorage(
    internal val calendarEnabled: Boolean = false,
    internal val clock: Clock = Clock.systemDefaultZone(),
) {
    private val launchToken = randomQuestionToken().take(LAUNCH_TOKEN_LENGTH)
    private val sessions = ConcurrentHashMap<TelegramUserId, StoredSession>()

    internal fun newQuestionToken(): String = "$launchToken.${randomQuestionToken().take(QUESTION_TOKEN_LENGTH)}"

    internal fun isFromPreviousLaunch(callbackData: String): Boolean {
        val token = callbackData.substringAfterLast(':')
        return token.length == FULL_TOKEN_LENGTH &&
            token[LAUNCH_TOKEN_LENGTH] == '.' &&
            token.substringBefore('.') != launchToken
    }

    fun get(userId: TelegramUserId): TrainingCreationMessageSession? = sessions[userId]?.registration

    internal fun snapshot(userId: TelegramUserId): StoredSession? = sessions[userId]

    internal fun putQuestionIfCurrent(
        userId: TelegramUserId,
        expected: StoredSession?,
        message: TrainingCreationMessageSession,
        callbackData: Set<String>,
        calendar: TrainingDateCalendar? = null,
    ): Boolean {
        var registered = false
        sessions.compute(userId) { _, current ->
            if (current === expected) {
                registered = true
                StoredSession(message, ActiveQuestion(message, callbackData), calendar)
            } else {
                current
            }
        }
        return registered
    }

    fun remove(
        userId: TelegramUserId,
        expected: TrainingCreationMessageSession?,
    ): Boolean {
        val stored = sessions[userId] ?: return false
        return stored.registration == expected && sessions.remove(userId, stored)
    }

    fun put(
        userId: TelegramUserId,
        session: TrainingCreationMessageSession,
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
        expected: TrainingCreationMessageSession,
    ) {
        val stored = sessions[userId] ?: return
        if (stored.registration == expected) {
            sessions.replace(userId, stored, stored.copy(question = null))
        }
    }

    internal fun registerQuestion(
        userId: TelegramUserId,
        message: TrainingCreationMessageSession,
        callbackData: Set<String>,
    ) {
        sessions.compute(userId) { _, previous ->
            StoredSession(previous?.registration, ActiveQuestion(message, callbackData))
        }
    }

    internal fun consumeQuestion(
        userId: TelegramUserId,
        message: TrainingCreationMessageSession,
        callbackData: String,
    ): Boolean {
        val stored = sessions[userId]
        val question = stored?.question ?: return false
        val matches = question.message == message
        val registrationMatches = stored.registration?.sessionId?.let { it == message.sessionId } ?: true
        val allowed = matches && registrationMatches && callbackData in question.callbackData
        return allowed && sessions.replace(userId, stored, stored.copy(question = null))
    }

    internal data class StoredSession(
        val registration: TrainingCreationMessageSession?,
        val question: ActiveQuestion?,
        val calendar: TrainingDateCalendar? = null,
    )

    internal data class ActiveQuestion(
        val message: TrainingCreationMessageSession,
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
