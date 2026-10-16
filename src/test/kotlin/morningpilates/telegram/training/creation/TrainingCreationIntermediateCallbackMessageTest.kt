package morningpilates.telegram.training.creation

import com.github.kotlintelegrambot.dispatcher.command
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup
import com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton
import kotlinx.coroutines.runBlocking
import morningpilates.config.telegram.TelegramProperties
import morningpilates.flow.model.UserSession
import morningpilates.flow.session.FlowSessionManager
import morningpilates.infrastructure.InMemoryFlowStorage
import morningpilates.telegram.TelegramBot
import morningpilates.telegram.api.TelegramChatId
import morningpilates.telegram.api.TelegramMessageId
import morningpilates.telegram.support.DeletedTelegramMessage
import morningpilates.telegram.support.EXPECTED_CANCEL_ROW
import morningpilates.telegram.support.EditedTelegramInlineButton
import morningpilates.telegram.support.EditedTelegramMessage
import morningpilates.telegram.support.TestTelegramBot
import morningpilates.telegram.support.withoutQuestionToken
import morningpilates.telegram.training.reply.TrainingReply
import morningpilates.training.FakeTrainingRepository
import morningpilates.training.TechniqueDraft
import morningpilates.training.TrainingDetails
import morningpilates.training.TrainingFlowService
import morningpilates.training.TrainingFlowState
import morningpilates.training.TrainingFlowState.WaitingAddAnotherTechnique
import morningpilates.training.TrainingFlowState.WaitingAddNote
import morningpilates.training.TrainingFlowState.WaitingAddTechnique
import morningpilates.training.TrainingFlowState.WaitingAddTechniqueDescription
import morningpilates.training.TrainingFlowState.WaitingConfirmation
import morningpilates.training.TrainingFlowState.WaitingNote
import morningpilates.training.TrainingFlowState.WaitingTechniqueDescription
import morningpilates.training.TrainingFlowState.WaitingTechniqueName
import morningpilates.training.TrainingQueryService
import morningpilates.user.TelegramUserId
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private val DATE = LocalDate.of(2026, 10, 12)
private val TIME = LocalTime.of(6, 30)
private val DETAILS = TrainingDetails(DATE, TIME, listOf(TechniqueDraft("Kimura", "Из гарда")))

internal class TrainingCreationIntermediateCallbackMessageTest {
    private val storage = InMemoryFlowStorage()
    private val messageSessions = TrainingCreationMessageSessionStorage()
    private val userId = TelegramUserId(42L)
    private val chatId = TelegramChatId(4_200L)
    private val repository = FakeTrainingRepository()
    private val manager = FlowSessionManager(storage, listOf(TrainingFlowService(repository)))

    @ParameterizedTest(name = "{0}")
    @MethodSource("transitions")
    fun `intermediate callback edits the registered question`(scenario: CallbackTransition) =
        runBlocking {
            val session = UserSession(scenario.initialState, Instant.EPOCH)
            check(storage.start(userId, session))
            fixtureBot(scenario).use { bot ->
                val sentQuestion = bot.sendCommand("/question", userId.value, 713L)
                val registered = assertNotNull(messageSessions.get(userId))
                assertEquals(TelegramMessageId(sentQuestion.messageId), registered.messageId)
                val editsBefore = bot.editedMessages.size

                press(
                    bot,
                    registered,
                    scenario.answer,
                    scenario.question,
                    scenario.nextState,
                    scenario.nextQuestion,
                )

                val expectedQuestion = scenario.nextQuestion.at(registered)
                assertAll(
                    {
                        assertEquals(
                            listOf(expectedQuestion),
                            bot.editedMessages.drop(editsBefore).map { it.withoutQuestionToken() },
                        )
                    },
                    { assertEquals(1, bot.apiCalls.count { it == "sendMessage" }) },
                    { assertEquals(emptyList(), bot.deletedMessages) },
                    { assertTrue(repository.saveCalls.isEmpty()) },
                )
            }
        }

    @Test
    fun `two techniques and a note use one message with current keyboards`() =
        runBlocking {
            realBot().use { bot ->
                bot.sendCommand("/training_add", userId.value, 713L)
                val original = assertNotNull(storage.get(userId))
                val question = assertNotNull(messageSessions.get(userId))
                val details = TrainingDetails(DATE, TIME)
                bot.sendTextAndCollectReplies("12.10.2026", userId.value, 829L)
                bot.sendTextAndCollectReplies("6:30", userId.value, 947L)
                assertEquals(original.copy(state = WaitingAddTechnique(details)), storage.get(userId))

                val firstTechnique = addFirstTechnique(bot, question, details)
                val bothTechniques = addSecondTechnique(bot, question, firstTechnique)
                press(
                    bot,
                    question,
                    "Нет",
                    ANOTHER_QUESTION,
                    WaitingAddNote(bothTechniques),
                    NOTE_QUESTION,
                )
                press(
                    bot,
                    question,
                    "Да",
                    NOTE_QUESTION,
                    WaitingNote(bothTechniques),
                    CallbackQuestion("💬 Введите заметку о тренировке."),
                )
                val note = "Отрабатывали выход из сайд-контроля"
                val finalDetails = bothTechniques.copy(note = note)
                enter(
                    bot,
                    question,
                    note,
                    TelegramMessageId(1_469L),
                    WaitingConfirmation(finalDetails),
                    CONFIRMATION_QUESTION,
                )

                assertEquals(original.copy(state = WaitingConfirmation(finalDetails)), storage.get(userId))
                assertEquals(question, messageSessions.get(userId))
                assertEquals(1, bot.apiCalls.count { it == "sendMessage" })
                assertTrue(repository.saveCalls.isEmpty())
            }
        }

    private suspend fun addFirstTechnique(
        bot: TestTelegramBot,
        question: TrainingCreationMessageSession,
        details: TrainingDetails,
    ): TrainingDetails {
        press(
            bot,
            question,
            "Да",
            CallbackQuestion("🥋 Добавить технику?", "add_technique"),
            WaitingTechniqueName(details),
            CallbackQuestion("🏷️ Введите название техники."),
        )
        enter(
            bot,
            question,
            "Armbar",
            TelegramMessageId(1_123L),
            WaitingAddTechniqueDescription(details, "Armbar"),
            DESCRIPTION_QUESTION,
        )
        press(
            bot,
            question,
            "Да",
            DESCRIPTION_QUESTION,
            WaitingTechniqueDescription(details, "Armbar"),
            CallbackQuestion("✍️ Введите описание техники."),
        )
        val firstTechnique = details.copy(techniques = listOf(TechniqueDraft("Armbar", "Из маунта")))
        enter(
            bot,
            question,
            "Из маунта",
            TelegramMessageId(1_237L),
            WaitingAddAnotherTechnique(firstTechnique),
            ANOTHER_QUESTION,
        )
        return firstTechnique
    }

    private suspend fun addSecondTechnique(
        bot: TestTelegramBot,
        question: TrainingCreationMessageSession,
        firstTechnique: TrainingDetails,
    ): TrainingDetails {
        press(
            bot,
            question,
            "Да",
            ANOTHER_QUESTION,
            WaitingTechniqueName(firstTechnique),
            CallbackQuestion("🏷️ Введите название техники."),
        )
        enter(
            bot,
            question,
            "Triangle",
            TelegramMessageId(1_351L),
            WaitingAddTechniqueDescription(firstTechnique, "Triangle"),
            DESCRIPTION_QUESTION,
        )
        val bothTechniques =
            firstTechnique.copy(techniques = firstTechnique.techniques + TechniqueDraft("Triangle"))
        press(
            bot,
            question,
            "Нет",
            DESCRIPTION_QUESTION,
            WaitingAddAnotherTechnique(bothTechniques),
            ANOTHER_QUESTION,
        )
        return bothTechniques
    }

    private suspend fun press(
        bot: TestTelegramBot,
        question: TrainingCreationMessageSession,
        answer: String,
        current: CallbackQuestion,
        nextState: TrainingFlowState,
        next: CallbackQuestion,
    ) {
        val before = assertNotNull(storage.get(userId))
        val callsBefore = bot.apiCalls.size
        val currentMessage = bot.currentMessage(question.messageId)
        assertEquals(current.at(question), currentMessage.withoutQuestionToken())
        val interaction = bot.pressInlineButtonAndCollectReplies(currentMessage, answer, userId.value)
        assertAll(
            { assertEquals(before.copy(state = nextState), storage.get(userId)) },
            { assertEquals(question, messageSessions.get(userId)) },
            { assertTrue(interaction.acknowledged) },
            { assertEquals(emptyList(), interaction.replies) },
            { assertEquals(next.at(question), bot.currentMessage(question.messageId).withoutQuestionToken()) },
            { assertEquals(listOf("editMessageText", "answerCallbackQuery"), bot.apiCalls.drop(callsBefore)) },
        )
    }

    private suspend fun enter(
        bot: TestTelegramBot,
        question: TrainingCreationMessageSession,
        text: String,
        messageId: TelegramMessageId,
        nextState: TrainingFlowState,
        next: CallbackQuestion,
    ) {
        val before = assertNotNull(storage.get(userId))
        val callsBefore = bot.apiCalls.size
        val deletionCount = bot.deletedMessages.size
        assertEquals(emptyList(), bot.sendTextAndCollectReplies(text, userId.value, messageId.value))
        assertEquals(before.copy(state = nextState), storage.get(userId))
        assertEquals(question, messageSessions.get(userId))
        assertEquals(next.at(question), bot.currentMessage(question.messageId).withoutQuestionToken())
        assertEquals(listOf("editMessageText", "deleteMessage"), bot.apiCalls.drop(callsBefore))
        assertEquals(
            listOf(DeletedTelegramMessage(chatId.value, messageId.value)),
            bot.deletedMessages.drop(deletionCount),
        )
    }

    private fun realBot() =
        TestTelegramBot(
            telegramBot =
                TelegramBot(
                    profileRepository = morningpilates.profile.FakeProfileRepository(),
                    TelegramProperties("test-token"),
                    storage,
                    manager,
                    messageSessions,
                    queryService = TrainingQueryService(repository),
                    deleteTraining = repository::delete,
                ),
            chatId = chatId.value,
            firstBotMessageId = 6_341L,
        )

    private fun fixtureBot(scenario: CallbackTransition) =
        TestTelegramBot(chatId = chatId.value, firstBotMessageId = 6_341L) {
            command("question") {
                val reply =
                    TrainingReply(scenario.question.text, scenario.question.markup())
                        .withCreationQuestionToken(messageSessions.newQuestionToken())
                val sent =
                    checkNotNull(
                        bot
                            .sendMessage(
                                ChatId.fromId(chatId.value),
                                reply.text,
                                replyMarkup = reply.replyMarkup,
                            ).getOrNull(),
                    )
                messageSessions.put(
                    userId,
                    TrainingCreationMessageSession(
                        assertNotNull(storage.get(userId)).sessionId,
                        TelegramChatId(sent.chat.id),
                        TelegramMessageId(sent.messageId),
                    ),
                )
                messageSessions.registerQuestion(
                    userId,
                    checkNotNull(messageSessions.get(userId)),
                    reply.callbackData(),
                )
            }
            registerTrainingCreationCallbacks(storage, manager, messageSessions)
        }

    companion object {
        @JvmStatic
        fun transitions(): List<CallbackTransition> =
            listOf(
                CallbackTransition(
                    WaitingAddTechnique(DETAILS),
                    CallbackQuestion("🥋 Добавить технику?", "add_technique"),
                    "Нет",
                    WaitingAddNote(DETAILS),
                    NOTE_QUESTION,
                ),
                CallbackTransition(
                    WaitingAddTechniqueDescription(DETAILS, "Armbar"),
                    DESCRIPTION_QUESTION,
                    "Да",
                    WaitingTechniqueDescription(DETAILS, "Armbar"),
                    CallbackQuestion("✍️ Введите описание техники."),
                ),
                CallbackTransition(
                    WaitingAddTechniqueDescription(DETAILS, "Armbar"),
                    DESCRIPTION_QUESTION,
                    "Нет",
                    WaitingAddAnotherTechnique(
                        DETAILS.copy(techniques = DETAILS.techniques + TechniqueDraft("Armbar")),
                    ),
                    ANOTHER_QUESTION,
                ),
                CallbackTransition(
                    WaitingAddAnotherTechnique(DETAILS),
                    ANOTHER_QUESTION,
                    "Да",
                    WaitingTechniqueName(DETAILS),
                    CallbackQuestion("🏷️ Введите название техники."),
                ),
                CallbackTransition(
                    WaitingAddAnotherTechnique(DETAILS),
                    ANOTHER_QUESTION,
                    "Нет",
                    WaitingAddNote(DETAILS),
                    NOTE_QUESTION,
                ),
                CallbackTransition(
                    WaitingAddNote(DETAILS),
                    NOTE_QUESTION,
                    "Да",
                    WaitingNote(DETAILS),
                    CallbackQuestion("💬 Введите заметку о тренировке."),
                ),
                CallbackTransition(
                    WaitingAddNote(DETAILS),
                    NOTE_QUESTION,
                    "Нет",
                    WaitingConfirmation(DETAILS),
                    CONFIRMATION_QUESTION,
                ),
            )
    }
}

private val DESCRIPTION_QUESTION = CallbackQuestion("📝 Добавить описание техники?", "add_technique_description")
private val ANOTHER_QUESTION = CallbackQuestion("➕ Добавить ещё одну технику?", "add_another_technique")
private val NOTE_QUESTION = CallbackQuestion("💬 Добавить заметку?", "add_note")
private val CONFIRMATION_QUESTION = CallbackQuestion("💾 Сохранить тренировку?", "confirmation")

internal data class CallbackTransition(
    val initialState: TrainingFlowState,
    val question: CallbackQuestion,
    val answer: String,
    val nextState: TrainingFlowState,
    val nextQuestion: CallbackQuestion,
) {
    override fun toString(): String = "${question.text} $answer → ${nextQuestion.text}"
}

internal data class CallbackQuestion(
    val text: String,
    val callbackAction: String? = null,
) {
    private fun keyboard(): List<List<EditedTelegramInlineButton>> =
        callbackAction
            ?.let {
                listOf(
                    listOf(
                        EditedTelegramInlineButton("Да", "training:$it:yes"),
                        EditedTelegramInlineButton("Нет", "training:$it:no"),
                    ),
                )
            }.orEmpty() + listOf(EXPECTED_CANCEL_ROW)

    internal fun at(question: TrainingCreationMessageSession) =
        EditedTelegramMessage(
            chatId = question.chatId,
            messageId = question.messageId,
            text = text,
            inlineKeyboard = keyboard(),
        )

    internal fun markup() =
        InlineKeyboardMarkup.create(
            keyboard().map { row -> row.map { InlineKeyboardButton.CallbackData(it.text, it.callbackData) } },
        )
}
