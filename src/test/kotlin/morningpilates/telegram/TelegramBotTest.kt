package morningpilates.telegram

import kotlinx.coroutines.runBlocking
import morningpilates.config.TelegramProperties
import morningpilates.flow.FlowStorage
import morningpilates.infrastructure.InMemoryFlowStorage
import morningpilates.training.TrainingFlowState.WaitingDate
import morningpilates.user.TelegramUserId
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private const val START_COMMAND = "/start"
private const val TRAINING_ADD_COMMAND = "/training_add"
private const val SENDER_ID = 42L
private const val GROUP_CHAT_ID = -987654321L
private const val TEST_TOKEN = "test-token"
private const val EXPECTED_GREETING = "Morning Pilates Bot работает 🥋"
private const val EXPECTED_UNFINISHED_TRAINING_MESSAGE =
    "У вас уже есть незавершённая тренировка. Продолжите её, прежде чем создавать новую."
private val EXPECTED_DATE_PROMPT =
    """
    Введите дату тренировки.

    Например: 11.10.2026 или 2026-10-11.

    Можно использовать разделители: точку, дефис или косую черту.
    """.trimIndent()

class TelegramBotTest {
    @Test
    fun `registered start command sends greeting without starting training`() =
        runBlocking {
            val storage: FlowStorage = InMemoryFlowStorage()
            createTestBot(storage).use { bot ->
                val reply = bot.sendCommand(START_COMMAND, senderId = SENDER_ID)

                assertEquals(SentTelegramMessage(SENDER_ID, EXPECTED_GREETING), reply)
                assertNull(storage.get(TelegramUserId(SENDER_ID)))
            }
        }

    @Test
    fun `registered training add starts session in supplied storage and requests date`() =
        runBlocking {
            val storage: FlowStorage = InMemoryFlowStorage()
            createTestBot(storage).use { bot ->
                val replies = bot.sendCommandAndCollectReplies(TRAINING_ADD_COMMAND, senderId = SENDER_ID)

                assertEquals(WaitingDate, storage.get(TelegramUserId(SENDER_ID))?.state)
                assertEquals(listOf(SentTelegramMessage(SENDER_ID, EXPECTED_DATE_PROMPT)), replies)
            }
        }

    @Test
    fun `registered commands preserve active training and report repeated training add`() =
        runBlocking {
            val storage: FlowStorage = InMemoryFlowStorage()
            val telegramUserId = TelegramUserId(SENDER_ID)
            val expectedSession = startSessionAwaitingConfirmation(storage, telegramUserId)
            createTestBot(storage).use { bot ->
                val replies =
                    listOf(START_COMMAND, TRAINING_ADD_COMMAND, START_COMMAND).flatMap { command ->
                        bot.sendCommandAndCollectReplies(command, senderId = SENDER_ID)
                    }

                assertEquals(expectedSession, storage.get(telegramUserId))
                assertEquals(
                    listOf(EXPECTED_GREETING, EXPECTED_UNFINISHED_TRAINING_MESSAGE, EXPECTED_GREETING),
                    replies.map { it.text },
                )
            }
        }

    @ParameterizedTest(name = "registered training add in {0} is ignored")
    @CsvSource(
        "group, $GROUP_CHAT_ID",
        "supergroup, -100987654321",
    )
    fun `registered training add ignores group chats`(
        chatType: String,
        chatId: Long,
    ) = runBlocking {
        val storage: FlowStorage = InMemoryFlowStorage()
        createTestBot(storage, chatId = chatId, chatType = chatType).use { bot ->
            val replies = bot.sendCommandAndCollectReplies(TRAINING_ADD_COMMAND, senderId = SENDER_ID)

            assertNull(storage.get(TelegramUserId(SENDER_ID)))
            assertEquals(emptyList(), replies)
        }
    }

    private fun createTestBot(
        storage: FlowStorage,
        chatId: Long = SENDER_ID,
        chatType: String = "private",
    ) = TestTelegramBot(
        telegramBot = TelegramBot(TelegramProperties(TEST_TOKEN), storage),
        chatId = chatId,
        chatType = chatType,
    )
}
