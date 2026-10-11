package morningpilates.telegram.command

import kotlinx.coroutines.runBlocking
import morningpilates.flow.FlowStorage
import morningpilates.infrastructure.InMemoryFlowStorage
import morningpilates.telegram.TestTelegramBot
import morningpilates.training.TrainingFlowState.WaitingDate
import morningpilates.user.TelegramUserId
import kotlin.test.Test
import kotlin.test.assertEquals

class TrainingAddCommandTest {
    @Test
    fun `training add in private chat starts sender session and requests date`() =
        runBlocking {
            // Given
            val storage: FlowStorage = InMemoryFlowStorage()
            val telegramUserId = TelegramUserId(42L)
            val chatId = 123456789L
            TestTelegramBot(
                chatId = chatId,
                registerCommands = { registerTrainingAddCommand(storage) },
            ).use { bot ->
                // When
                val reply = bot.sendCommand("/training_add", senderId = telegramUserId.value)

                // Then
                assertEquals(WaitingDate, storage.get(telegramUserId)?.state)
                assertEquals(chatId, reply.chatId)
                assertEquals(
                    """
                    Введите дату тренировки.

                    Например: 11.10.2026 или 2026-10-11.

                    Можно использовать разделители: точку, дефис или косую черту.
                    """.trimIndent(),
                    reply.text,
                )
            }
        }
}
