package morningpilates.telegram.support

import morningpilates.config.telegram.TelegramProperties
import morningpilates.flow.FlowHandler
import morningpilates.flow.model.FlowEvent
import morningpilates.flow.model.FlowResult
import morningpilates.flow.model.FlowState
import morningpilates.flow.session.FlowSessionManager
import morningpilates.infrastructure.InMemoryFlowStorage
import morningpilates.telegram.TelegramBot
import morningpilates.telegram.api.TelegramChatId
import morningpilates.telegram.api.TelegramMessageId
import morningpilates.telegram.training.creation.TrainingCreationMessageSessionStorage
import morningpilates.telegram.training.reply.toSavedTrainingReply
import morningpilates.telegram.training.view.ActiveTrainingView
import morningpilates.telegram.training.view.TrainingViewStorage
import morningpilates.training.FakeTrainingRepository
import morningpilates.training.FakeTrainingRepository.FindByDateCall
import morningpilates.training.SavedTraining
import morningpilates.training.TechniqueDraft
import morningpilates.training.TrainingDetails
import morningpilates.training.TrainingFlowService
import morningpilates.training.TrainingId
import morningpilates.training.TrainingQueryService
import morningpilates.user.TelegramUserId
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal const val DATE_VIEW_QUESTION = "Введите дату тренировки. Формат: ДД.ММ.ГГГГ"
internal const val DATE_VIEW_FINISH = "Завершить просмотр"
internal const val DATE_VIEW_CHANGE = "Выбрать другую дату"
internal const val DATE_VIEW_NEXT = "Следующая"
internal const val DATE_VIEW_PREVIOUS = "Назад"
internal val SEARCH_DATE: LocalDate = LocalDate.of(2026, 10, 12)
internal val NEXT_SEARCH_DATE: LocalDate = SEARCH_DATE.plusDays(1)

internal class TrainingDateViewTestFixture {
    val user = TelegramUserId(42L)
    val chat = TelegramChatId(4_200L)
    val storage = InMemoryFlowStorage()
    val messages = TrainingCreationMessageSessionStorage()
    val repository = FakeTrainingRepository()
    val events = mutableListOf<FlowEvent>()

    // Preserve repository order even if a test double returns a non-chronological sequence.
    val trainings =
        listOf(
            SavedTraining(
                TrainingId(UUID(0L, 3L)),
                TrainingDetails(
                    SEARCH_DATE,
                    LocalTime.of(12, 0),
                    listOf(TechniqueDraft("Triangle <&>", "Описание <first>&"), TechniqueDraft("Armbar")),
                    "Заметка <first>&",
                ),
            ),
            SavedTraining(
                TrainingId(UUID(0L, 1L)),
                TrainingDetails(SEARCH_DATE, LocalTime.of(19, 0), note = "Вторая карточка"),
            ),
            SavedTraining(
                TrainingId(UUID(0L, 2L)),
                TrainingDetails(SEARCH_DATE, LocalTime.of(7, 0), listOf(TechniqueDraft("Третья техника"))),
            ),
        )
    private val trainingHandler = TrainingFlowService(repository)
    private val recordingHandler =
        object : FlowHandler {
            override fun supports(state: FlowState): Boolean = trainingHandler.supports(state)

            override fun handle(
                state: FlowState,
                event: FlowEvent,
            ): FlowResult {
                events += event
                return trainingHandler.handle(state, event)
            }
        }
    private val telegramBot =
        TelegramBot(
            profileRepository = morningpilates.profile.FakeProfileRepository(),
            TelegramProperties("test-token"),
            storage,
            FlowSessionManager(storage, listOf(recordingHandler)),
            messages,
            TrainingQueryService(repository),
            deleteTraining = repository::delete,
        )

    // Registration is inspected only for display/cleanup guarantees; interaction still uses the real Dispatcher.
    fun activeView(userId: TelegramUserId = user): ActiveTrainingView? {
        val field = TelegramBot::class.java.getDeclaredField("viewStorage").apply { isAccessible = true }
        return (field.get(telegramBot) as TrainingViewStorage).get(userId)
    }

    fun bot(
        chatId: Long = chat.value,
        chatType: String = "private",
        firstBotMessageId: Long = 1_000L,
        existingMessages: List<EditedTelegramMessage> = emptyList(),
    ): TestTelegramBot = TestTelegramBot(telegramBot, chatId, chatType, firstBotMessageId, existingMessages)

    fun configure(
        values: List<SavedTraining>,
        date: LocalDate = SEARCH_DATE,
    ) {
        repository.findByDateResults[FindByDateCall(user, date)] = Result.success(values)
    }

    suspend fun openQuestion(bot: TestTelegramBot): EditedTelegramMessage {
        val replies = bot.sendCommandAndCollectReplies("/training_get_date", user.value)
        assertEquals(1, replies.size, "/training_get_date must send a date question through the real Dispatcher")
        val question = bot.currentMessage(TelegramMessageId(replies.single().messageId))
        assertQuestion(question)
        return question
    }

    suspend fun enterDate(
        bot: TestTelegramBot,
        question: EditedTelegramMessage,
        text: String = "12.10.2026",
        messageId: Long = 9_000L,
    ): EditedTelegramMessage {
        val replies = bot.sendTextAndCollectReplies(text, user.value, messageId)
        assertTrue(replies.isEmpty(), "The date must update the view without an extra creation-handler response")
        return bot.currentMessage(question.messageId)
    }

    suspend fun press(
        bot: TestTelegramBot,
        message: EditedTelegramMessage,
        button: String,
    ): EditedTelegramMessage {
        val interaction = bot.pressInlineButtonAndCollectReplies(message, button, user.value)
        assertTrue(interaction.acknowledged)
        assertTrue(interaction.replies.isEmpty())
        return bot.currentMessage(message.messageId)
    }

    fun assertQuestion(message: EditedTelegramMessage) {
        assertEquals(DATE_VIEW_QUESTION, message.text)
        assertButtons(message, setOf(DATE_VIEW_FINISH))
    }

    fun assertButtons(
        message: EditedTelegramMessage,
        expected: Set<String>,
    ) {
        val actual = message.inlineKeyboard.flatten().map { it.text }
        assertEquals(expected, actual.toSet())
        assertEquals(expected.size, actual.size, "Buttons must not be duplicated")
    }

    fun assertCard(
        message: EditedTelegramMessage,
        values: List<SavedTraining>,
        position: Int,
        date: LocalDate = SEARCH_DATE,
    ) {
        val formattedDate = date.format(DateTimeFormatter.ofPattern("dd.MM.uuuu"))
        assertTrue(message.text.contains("Тренировки за $formattedDate"))
        assertTrue(message.text.contains("Тренировка ${position + 1} из ${values.size}"))
        assertEquals("HTML", message.parseMode)
        assertTrue(message.text.contains("<blockquote expandable>"))
        val expectedBody =
            values[position]
                .details
                .toSavedTrainingReply()
                .text
                .substringAfter("<blockquote expandable>")
        assertEquals(expectedBody, message.text.substringAfter("<blockquote expandable>"))
        val buttons = mutableSetOf(DELETE_TRAINING, DATE_VIEW_CHANGE, DATE_VIEW_FINISH)
        if (position > 0) buttons += DATE_VIEW_PREVIOUS
        if (position < values.lastIndex) buttons += DATE_VIEW_NEXT
        assertButtons(message, buttons)
    }

    fun assertNoCreation() {
        assertNull(storage.get(user))
        assertNull(messages.get(user))
        assertTrue(events.isEmpty())
        assertTrue(repository.saveCalls.isEmpty())
    }
}
