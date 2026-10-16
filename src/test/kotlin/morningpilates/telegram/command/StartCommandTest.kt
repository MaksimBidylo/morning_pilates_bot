package morningpilates.telegram.command

import kotlinx.coroutines.runBlocking
import morningpilates.config.telegram.TelegramProperties
import morningpilates.flow.session.FlowSessionManager
import morningpilates.infrastructure.InMemoryFlowStorage
import morningpilates.telegram.TelegramBot
import morningpilates.telegram.api.TelegramMessageId
import morningpilates.telegram.support.TestTelegramBot
import morningpilates.telegram.training.creation.TrainingCreationMessageSessionStorage
import morningpilates.training.FakeTrainingRepository
import morningpilates.training.TrainingDetails
import morningpilates.training.TrainingFlowService
import morningpilates.training.TrainingFlowState.WaitingTechniqueName
import morningpilates.training.TrainingQueryService
import morningpilates.user.TelegramUserId
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SENDER_ID = 42L

class StartCommandTest {
    @Test
    fun `start in private chat sends greeting to original chat`() {
        assertStartReply(chatId = SENDER_ID, chatType = "private")
    }

    @Test
    fun `start in group chat sends greeting to original negative chat ID`() {
        assertStartReply(chatId = -987654321L, chatType = "group")
    }

    @Test
    fun `start preserves active question and data and its existing buttons remain usable`() =
        runBlocking {
            val storage = InMemoryFlowStorage()
            val repository = FakeTrainingRepository()
            val messages = TrainingCreationMessageSessionStorage()
            val userId = TelegramUserId(SENDER_ID)
            createBot(storage, messages, repository).use { bot ->
                bot.sendCommand("/training_add")
                bot.sendTextAndCollectReplies("12.10.2026")
                bot.sendTextAndCollectReplies("6:30")
                val registered = assertNotNull(messages.get(userId))
                val question = bot.currentMessage(registered.messageId)
                val token = messages.questionToken(userId)
                val session = assertNotNull(storage.get(userId))
                val callback = bot.captureCallback(question, "Да", userId)
                val callCount = bot.apiCalls.size

                repeat(2) {
                    val greeting = bot.sendCommand("/start")

                    assertEquals(SENDER_ID, greeting.chatId)
                    assertTrue(greeting.text.startsWith("Morning Pilates Bot 🥋"))
                    assertTrue(greeting.inlineButtonTexts.isEmpty())
                    assertNotEquals(registered.messageId, TelegramMessageId(greeting.messageId))
                    assertEquals(session, storage.get(userId))
                    assertEquals(registered, messages.get(userId))
                    assertEquals(token, messages.questionToken(userId))
                    assertEquals(question, bot.currentMessage(registered.messageId))
                }
                assertEquals(listOf("sendMessage", "sendMessage"), bot.apiCalls.drop(callCount))
                assertTrue(repository.saveCalls.isEmpty())

                val interaction = bot.deliverCallbackAndCollectReplies(callback)

                assertTrue(interaction.acknowledged)
                assertTrue(interaction.replies.isEmpty())
                val continued = assertNotNull(storage.get(userId))
                assertEquals(session.sessionId, continued.sessionId)
                assertEquals(
                    WaitingTechniqueName(TrainingDetails(LocalDate.of(2026, 10, 12), LocalTime.of(6, 30))),
                    continued.state,
                )
                assertEquals(registered.messageId, assertNotNull(messages.get(userId)).messageId)
                assertEquals("🏷️ Введите название техники.", bot.currentMessage(registered.messageId).text)
                assertTrue(repository.saveCalls.isEmpty())
            }
        }

    @Test
    fun `repeated start sends separate greetings without starting training`() =
        runBlocking {
            val storage = InMemoryFlowStorage()
            val messages = TrainingCreationMessageSessionStorage()
            val userId = TelegramUserId(SENDER_ID)
            createBot(storage, messages).use { bot ->
                val first = bot.sendCommand("/start")
                val second = bot.sendCommand("/start")

                assertEquals(first, second)
                assertTrue(first.inlineButtonTexts.isEmpty())
                assertNotEquals(first.messageId, second.messageId)
                assertEquals(listOf("sendMessage", "sendMessage"), bot.apiCalls)
                assertNull(storage.get(userId))
                assertNull(messages.get(userId))
            }
        }

    private fun createBot(
        storage: InMemoryFlowStorage,
        messages: TrainingCreationMessageSessionStorage,
        repository: FakeTrainingRepository = FakeTrainingRepository(),
    ): TestTelegramBot =
        TestTelegramBot(
            telegramBot =
                TelegramBot(
                    profileRepository = morningpilates.profile.FakeProfileRepository(),
                    TelegramProperties("test-token"),
                    storage,
                    FlowSessionManager(storage, listOf(TrainingFlowService(repository))),
                    messages,
                    TrainingQueryService(repository),
                    deleteTraining = repository::delete,
                ),
            chatId = SENDER_ID,
        )

    private fun assertStartReply(
        chatId: Long,
        chatType: String,
    ) = runBlocking {
        TestTelegramBot(
            chatId = chatId,
            chatType = chatType,
            registerCommands = { registerStartCommand() },
        ).use { bot ->
            val reply = bot.sendCommand("/start", senderId = SENDER_ID)

            assertEquals(chatId, reply.chatId)
            assertEquals(
                """
                Morning Pilates Bot 🥋

                Твой личный дневник тренировок по BJJ.

                Записывай тренировки, сохраняй изученные техники и отслеживай свой прогресс.

                Доступные команды:

                /profile — личный профиль спортсмена
                /training_add — записать тренировку
                /training_get_last — посмотреть последнюю тренировку
                /training_get_date — найти тренировки за дату
                /help — помощь
                """.trimIndent(),
                reply.text,
            )
            assertTrue(reply.inlineButtonTexts.isEmpty())
            assertEquals(listOf("sendMessage"), bot.apiCalls)
        }
    }
}
