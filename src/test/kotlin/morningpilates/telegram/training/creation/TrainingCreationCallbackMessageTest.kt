package morningpilates.telegram.training.creation

import kotlinx.coroutines.runBlocking
import morningpilates.config.telegram.TelegramProperties
import morningpilates.flow.session.FlowSessionManager
import morningpilates.infrastructure.InMemoryFlowStorage
import morningpilates.telegram.TelegramBot
import morningpilates.telegram.api.TelegramChatId
import morningpilates.telegram.api.TelegramMessageId
import morningpilates.telegram.support.DeletedTelegramMessage
import morningpilates.telegram.support.EditedTelegramInlineButton
import morningpilates.telegram.support.EditedTelegramMessage
import morningpilates.telegram.support.TestTelegramBot
import morningpilates.telegram.support.trainingQuestion
import morningpilates.telegram.support.withoutQuestionToken
import morningpilates.training.FakeTrainingRepository
import morningpilates.training.TrainingDetails
import morningpilates.training.TrainingFlowService
import morningpilates.training.TrainingFlowState.WaitingAddTechnique
import morningpilates.training.TrainingFlowState.WaitingAddTechniqueDescription
import morningpilates.training.TrainingFlowState.WaitingDate
import morningpilates.training.TrainingFlowState.WaitingStartTime
import morningpilates.training.TrainingFlowState.WaitingTechniqueName
import morningpilates.training.TrainingQueryService
import morningpilates.user.TelegramUserId
import org.junit.jupiter.api.Assertions.assertAll
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private val TRAINING_DATE = LocalDate.of(2026, 10, 12)
private val TRAINING_TIME = LocalTime.of(6, 30)
private val EXPECTED_TECHNIQUE_KEYBOARD =
    listOf(
        listOf(
            EditedTelegramInlineButton("Да", "training:add_technique:yes"),
            EditedTelegramInlineButton("Нет", "training:add_technique:no"),
        ),
    )
private val EXPECTED_DESCRIPTION_KEYBOARD =
    listOf(
        listOf(
            EditedTelegramInlineButton("Да", "training:add_technique_description:yes"),
            EditedTelegramInlineButton("Нет", "training:add_technique_description:no"),
        ),
    )

class TrainingCreationCallbackMessageTest {
    private val storage = InMemoryFlowStorage()
    private val messageSessions = TrainingCreationMessageSessionStorage()
    private val telegramUserId = TelegramUserId(42L)
    private val chatId = TelegramChatId(4_200L)
    private val commandMessageId = TelegramMessageId(713L)
    private val dateMessageId = TelegramMessageId(829L)
    private val timeMessageId = TelegramMessageId(947L)
    private val nameMessageId = TelegramMessageId(1_123L)

    @Test
    fun `add technique callback and name input continue in the registered message`() =
        runBlocking {
            createTestBot().use { bot ->
                val initialQuestion =
                    bot.sendCommand("/training_add", telegramUserId.value, commandMessageId.value)
                val initialSession = assertNotNull(storage.get(telegramUserId))
                assertEquals(WaitingDate, initialSession.state)
                val registeredQuestion = assertNotNull(messageSessions.get(telegramUserId))
                assertEquals(initialSession.sessionId, registeredQuestion.sessionId)
                assertEquals(chatId, registeredQuestion.chatId)
                assertEquals(TelegramMessageId(initialQuestion.messageId), registeredQuestion.messageId)

                val dateReplies = bot.sendTextAndCollectReplies("12.10.2026", telegramUserId.value, dateMessageId.value)
                assertEquals(initialSession.copy(state = WaitingStartTime(TRAINING_DATE)), storage.get(telegramUserId))
                assertEquals(registeredQuestion, messageSessions.get(telegramUserId))

                val timeReplies = bot.sendTextAndCollectReplies("6:30", telegramUserId.value, timeMessageId.value)
                val details = TrainingDetails(TRAINING_DATE, TRAINING_TIME)
                assertEquals(initialSession.copy(state = WaitingAddTechnique(details)), storage.get(telegramUserId))
                assertEquals(registeredQuestion, messageSessions.get(telegramUserId))
                val techniqueQuestion = bot.currentMessage(registeredQuestion.messageId)
                assertEquals(
                    trainingQuestion(
                        chatId,
                        registeredQuestion.messageId,
                        "🥋 Добавить технику?",
                        EXPECTED_TECHNIQUE_KEYBOARD,
                    ),
                    techniqueQuestion.withoutQuestionToken(),
                )
                val previousApiCallCount = bot.apiCalls.size
                val previousEditCount = bot.editedMessages.size

                val interaction = bot.pressInlineButtonAndCollectReplies(techniqueQuestion, "Да", telegramUserId.value)

                assertEquals(initialSession.copy(state = WaitingTechniqueName(details)), storage.get(telegramUserId))
                assertEquals(registeredQuestion, messageSessions.get(telegramUserId))
                assertTrue(interaction.acknowledged)
                val questionAfterCallback = bot.currentMessage(registeredQuestion.messageId)
                val nameReplies = bot.sendTextAndCollectReplies("Armbar", telegramUserId.value, nameMessageId.value)

                assertEquals(
                    initialSession.copy(state = WaitingAddTechniqueDescription(details, "Armbar")),
                    storage.get(telegramUserId),
                )
                assertEquals(registeredQuestion, messageSessions.get(telegramUserId))
                val expectedDeletions =
                    listOf(commandMessageId, dateMessageId, timeMessageId, nameMessageId)
                        .map { DeletedTelegramMessage(chatId.value, it.value) }
                assertAll(
                    {
                        assertCallbackAndNameMessages(
                            bot,
                            registeredQuestion,
                            questionAfterCallback,
                            previousApiCallCount,
                            previousEditCount,
                        )
                    },
                    { assertEquals(emptyList(), dateReplies + timeReplies + interaction.replies + nameReplies) },
                    { assertEquals(expectedDeletions, bot.deletedMessages) },
                )
            }
        }

    private fun assertCallbackAndNameMessages(
        bot: TestTelegramBot,
        question: TrainingCreationMessageSession,
        questionAfterCallback: EditedTelegramMessage,
        previousApiCallCount: Int,
        previousEditCount: Int,
    ) {
        val expectedNameQuestion =
            trainingQuestion(question.chatId, question.messageId, "🏷️ Введите название техники.")
        val expectedDescriptionQuestion =
            trainingQuestion(
                question.chatId,
                question.messageId,
                "📝 Добавить описание техники?",
                EXPECTED_DESCRIPTION_KEYBOARD,
            )
        assertAll(
            { assertEquals(expectedNameQuestion, questionAfterCallback.withoutQuestionToken()) },
            {
                assertEquals(
                    expectedDescriptionQuestion,
                    bot.currentMessage(question.messageId).withoutQuestionToken(),
                )
            },
            {
                assertEquals(
                    listOf(expectedNameQuestion, expectedDescriptionQuestion),
                    bot.editedMessages.drop(previousEditCount).map { it.withoutQuestionToken() },
                )
            },
            {
                assertEquals(
                    listOf("editMessageText", "answerCallbackQuery", "editMessageText", "deleteMessage"),
                    bot.apiCalls.drop(previousApiCallCount),
                )
            },
            { assertEquals(1, bot.apiCalls.count { it == "sendMessage" }) },
        )
    }

    private fun createTestBot(): TestTelegramBot {
        val repository = FakeTrainingRepository()
        val sessionManager = FlowSessionManager(storage, listOf(TrainingFlowService(repository)))
        return TestTelegramBot(
            telegramBot =
                TelegramBot(
                    profileRepository = morningpilates.profile.FakeProfileRepository(),
                    properties = TelegramProperties("test-token"),
                    flowStorage = storage,
                    sessionManager = sessionManager,
                    messageSessionStorage = messageSessions,
                    queryService = TrainingQueryService(repository),
                    deleteTraining = repository::delete,
                ),
            chatId = chatId.value,
            firstBotMessageId = 6_341L,
        )
    }
}
