package morningpilates.telegram

import morningpilates.flow.model.SessionId
import morningpilates.user.TelegramUserId
import kotlin.test.Test
import kotlin.test.assertEquals

class TrainingMessageSessionStorageTest {
    @Test
    fun `stores message sessions independently for different users`() {
        // Given
        val storage = TrainingMessageSessionStorage()
        val firstUserId = TelegramUserId(42L)
        val secondUserId = TelegramUserId(84L)
        val firstSession =
            TrainingMessageSession(
                sessionId = SessionId.new(),
                chatId = TelegramChatId(1_001L),
                messageId = TelegramMessageId(901L),
            )
        val secondSession =
            TrainingMessageSession(
                sessionId = SessionId.new(),
                chatId = TelegramChatId(2_002L),
                messageId = TelegramMessageId(902L),
            )

        // When
        storage.put(firstUserId, firstSession)
        storage.put(secondUserId, secondSession)

        // Then
        assertEquals(firstSession, storage.get(firstUserId))
        assertEquals(secondSession, storage.get(secondUserId))
    }

    @Test
    fun `replaces current user message session and preserves another user session`() {
        // Given
        val storage = TrainingMessageSessionStorage()
        val firstUserId = TelegramUserId(42L)
        val secondUserId = TelegramUserId(84L)
        val originalSession =
            TrainingMessageSession(
                sessionId = SessionId.new(),
                chatId = TelegramChatId(1_001L),
                messageId = TelegramMessageId(901L),
            )
        val otherSession =
            TrainingMessageSession(
                sessionId = SessionId.new(),
                chatId = TelegramChatId(2_002L),
                messageId = TelegramMessageId(902L),
            )
        val replacementSession =
            TrainingMessageSession(
                sessionId = SessionId.new(),
                chatId = TelegramChatId(3_003L),
                messageId = TelegramMessageId(903L),
            )
        storage.put(firstUserId, originalSession)
        storage.put(secondUserId, otherSession)

        // When
        storage.put(firstUserId, replacementSession)

        // Then
        assertEquals(replacementSession, storage.get(firstUserId))
        assertEquals(otherSession, storage.get(secondUserId))
    }
}
