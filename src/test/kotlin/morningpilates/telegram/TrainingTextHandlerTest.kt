package morningpilates.telegram

import kotlinx.coroutines.runBlocking
import morningpilates.flow.model.UserSession
import morningpilates.flow.session.FlowSessionManager
import morningpilates.flow.session.FlowStorage
import morningpilates.infrastructure.InMemoryFlowStorage
import morningpilates.telegram.command.registerStartCommand
import morningpilates.telegram.command.registerTrainingAddCommand
import morningpilates.training.FakeTrainingRepository
import morningpilates.training.TechniqueDraft
import morningpilates.training.TrainingDetails
import morningpilates.training.TrainingFlowService
import morningpilates.training.TrainingFlowState.WaitingAddAnotherTechnique
import morningpilates.training.TrainingFlowState.WaitingAddTechnique
import morningpilates.training.TrainingFlowState.WaitingAddTechniqueDescription
import morningpilates.training.TrainingFlowState.WaitingDate
import morningpilates.training.TrainingFlowState.WaitingStartTime
import morningpilates.training.TrainingFlowState.WaitingTechniqueDescription
import morningpilates.training.TrainingFlowState.WaitingTechniqueName
import morningpilates.user.TelegramUserId
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SENDER_ID = 42L
private const val GROUP_CHAT_ID = -987654321L
private const val SUPERGROUP_CHAT_ID = -100987654321L
private const val START_COMMAND = "/start"
private const val TRAINING_ADD_COMMAND = "/training_add"
private const val EXPECTED_GREETING = "Morning Pilates Bot работает 🥋"
private val EXPECTED_DATE_PROMPT =
    """
    Введите дату тренировки.

    Например: 11.10.2026 или 2026-10-11.

    Можно использовать разделители: точку, дефис или косую черту.
    """.trimIndent()
private const val VALID_DATE_INPUT = "11/10/2026"
private const val VALID_START_TIME_INPUT = "6:30"
private const val VALID_TECHNIQUE_NAME_INPUT = "Armbar"
private const val VALID_TECHNIQUE_DESCRIPTION_INPUT = "Рычаг локтя из маунта"
private const val BLANK_TECHNIQUE_NAME_INPUT = "   "
private const val BLANK_TECHNIQUE_DESCRIPTION_INPUT = "   "
private const val INVALID_START_TIME_INPUT = "25:70"
private const val EXPECTED_INVALID_TIME_MESSAGE =
    "Некорректное время. Используйте формат Ч:ММ, ЧЧ:ММ или аналогичный формат с дефисом. " +
        "Например: 8:00 или 19-30."
private const val EXPECTED_ADD_TECHNIQUE_PROMPT = "Добавить технику?"
private const val EXPECTED_TECHNIQUE_DESCRIPTION_PROMPT = "Добавить описание техники?"
private const val EXPECTED_ADD_ANOTHER_TECHNIQUE_PROMPT = "Добавить ещё одну технику?"
private const val EXPECTED_TECHNIQUE_NAME_REQUIRED_MESSAGE = "Введите название техники"
private const val EXPECTED_TECHNIQUE_DESCRIPTION_REQUIRED_MESSAGE = "Введите описание техники"
private const val INVALID_DATE_INPUT = "31.02.2026"
private const val EXPECTED_INVALID_DATE_MESSAGE =
    "Некорректная дата. Используйте формат ДД.ММ.ГГГГ или ГГГГ-ММ-ДД. " +
        "Разделители: ., / или -. Проверьте правильность даты."
private val EXPECTED_TRAINING_DATE = LocalDate.of(2026, 10, 11)
private val TIME_INPUT_TRAINING_DATE = LocalDate.of(2026, 10, 12)
private val EXPECTED_START_TIME = LocalTime.of(6, 30)
private val EXPECTED_YES_NO_BUTTONS = listOf("Да", "Нет")
private val EXPECTED_TIME_PROMPT =
    """
    Введите время начала тренировки.

    Например: 8:00, 19:30 или 8-00.
    """.trimIndent()

class TrainingTextHandlerTest {
    private val storage: FlowStorage = InMemoryFlowStorage()
    private val telegramUserId = TelegramUserId(SENDER_ID)
    private val repository = FakeTrainingRepository()
    private val sessionManager = FlowSessionManager(storage, listOf(TrainingFlowService(repository)))
    private val messageSessionStorage = TrainingMessageSessionStorage()

    @Test
    fun `blank technique description preserves session and allows retry`() =
        runBlocking {
            val details = TrainingDetails(date = TIME_INPUT_TRAINING_DATE, time = EXPECTED_START_TIME)
            val originalSession =
                UserSession(
                    state = WaitingTechniqueDescription(details, VALID_TECHNIQUE_NAME_INPUT),
                    createdAt = Instant.EPOCH,
                )
            check(storage.start(telegramUserId, originalSession))
            createTestBot().use { bot ->
                val errorReplies =
                    bot.sendTextAndCollectReplies(BLANK_TECHNIQUE_DESCRIPTION_INPUT, senderId = SENDER_ID)

                assertEquals(originalSession, storage.get(telegramUserId))
                assertRecoveredError(
                    bot,
                    originalSession,
                    errorReplies,
                    "⚠️ $EXPECTED_TECHNIQUE_DESCRIPTION_REQUIRED_MESSAGE\n\nВведите описание техники.",
                )

                val retryReplies =
                    bot.sendTextAndCollectReplies(VALID_TECHNIQUE_DESCRIPTION_INPUT, senderId = SENDER_ID)

                val expectedTechnique = TechniqueDraft(VALID_TECHNIQUE_NAME_INPUT, VALID_TECHNIQUE_DESCRIPTION_INPUT)
                val expectedDetails = details.copy(techniques = listOf(expectedTechnique))
                assertEquals(
                    originalSession.copy(state = WaitingAddAnotherTechnique(expectedDetails)),
                    storage.get(telegramUserId),
                )
                assertTrue(retryReplies.isEmpty())
                assertRetryEdits(
                    bot,
                    errorReplies.single(),
                    CallbackQuestion(EXPECTED_ADD_ANOTHER_TECHNIQUE_PROMPT, "add_another_technique"),
                )
                assertTrue(repository.saveCalls.isEmpty())
            }
        }

    @Test
    fun `technique description text adds technique and asks about another one without saving`() =
        runBlocking {
            val details = TrainingDetails(date = TIME_INPUT_TRAINING_DATE, time = EXPECTED_START_TIME)
            val originalSession =
                UserSession(
                    state = WaitingTechniqueDescription(details, VALID_TECHNIQUE_NAME_INPUT),
                    createdAt = Instant.EPOCH,
                )
            check(storage.start(telegramUserId, originalSession))
            createTestBot().use { bot ->
                val replies = bot.sendTextAndCollectReplies(VALID_TECHNIQUE_DESCRIPTION_INPUT, senderId = SENDER_ID)

                val expectedTechnique = TechniqueDraft(VALID_TECHNIQUE_NAME_INPUT, VALID_TECHNIQUE_DESCRIPTION_INPUT)
                val updatedDetails = details.copy(techniques = listOf(expectedTechnique))
                assertEquals(
                    originalSession.copy(state = WaitingAddAnotherTechnique(updatedDetails)),
                    storage.get(telegramUserId),
                )
                assertEquals(
                    listOf(
                        SentTelegramMessage(
                            chatId = SENDER_ID,
                            text = EXPECTED_ADD_ANOTHER_TECHNIQUE_PROMPT,
                            inlineButtonTexts = EXPECTED_YES_NO_BUTTONS + "Отменить тренировку",
                        ),
                    ),
                    replies,
                )
                assertTrue(repository.saveCalls.isEmpty())
            }
        }

    @Test
    fun `valid technique name advances session and asks for description without saving`() =
        runBlocking {
            val details = TrainingDetails(date = TIME_INPUT_TRAINING_DATE, time = EXPECTED_START_TIME)
            val originalSession = UserSession(state = WaitingTechniqueName(details), createdAt = Instant.EPOCH)
            check(storage.start(telegramUserId, originalSession))
            createTestBot().use { bot ->
                val replies = bot.sendTextAndCollectReplies(VALID_TECHNIQUE_NAME_INPUT, senderId = SENDER_ID)

                assertEquals(
                    originalSession.copy(state = WaitingAddTechniqueDescription(details, VALID_TECHNIQUE_NAME_INPUT)),
                    storage.get(telegramUserId),
                )
                assertEquals(
                    listOf(
                        SentTelegramMessage(
                            chatId = SENDER_ID,
                            text = EXPECTED_TECHNIQUE_DESCRIPTION_PROMPT,
                            inlineButtonTexts = EXPECTED_YES_NO_BUTTONS + "Отменить тренировку",
                        ),
                    ),
                    replies,
                )
                assertTrue(repository.saveCalls.isEmpty())
            }
        }

    @Test
    fun `blank technique name preserves session and allows retry`() =
        runBlocking {
            val details = TrainingDetails(date = TIME_INPUT_TRAINING_DATE, time = EXPECTED_START_TIME)
            val originalSession = UserSession(state = WaitingTechniqueName(details), createdAt = Instant.EPOCH)
            check(storage.start(telegramUserId, originalSession))
            createTestBot().use { bot ->
                val errorReplies = bot.sendTextAndCollectReplies(BLANK_TECHNIQUE_NAME_INPUT, senderId = SENDER_ID)

                assertEquals(originalSession, storage.get(telegramUserId))
                assertRecoveredError(
                    bot,
                    originalSession,
                    errorReplies,
                    "⚠️ $EXPECTED_TECHNIQUE_NAME_REQUIRED_MESSAGE\n\nВведите название техники.",
                )

                val retryReplies = bot.sendTextAndCollectReplies(VALID_TECHNIQUE_NAME_INPUT, senderId = SENDER_ID)

                assertEquals(
                    originalSession.copy(state = WaitingAddTechniqueDescription(details, VALID_TECHNIQUE_NAME_INPUT)),
                    storage.get(telegramUserId),
                )
                assertTrue(retryReplies.isEmpty())
                assertRetryEdits(
                    bot,
                    errorReplies.single(),
                    CallbackQuestion(EXPECTED_TECHNIQUE_DESCRIPTION_PROMPT, "add_technique_description"),
                )
            }
        }

    @Test
    fun `valid training start time advances session and asks to add technique with inline buttons without saving`() =
        runBlocking {
            // Given
            val originalSession =
                UserSession(state = WaitingStartTime(TIME_INPUT_TRAINING_DATE), createdAt = Instant.EPOCH)
            check(storage.start(telegramUserId, originalSession))
            val expectedDetails =
                TrainingDetails(
                    date = TIME_INPUT_TRAINING_DATE,
                    time = EXPECTED_START_TIME,
                    techniques = emptyList(),
                    note = null,
                )
            createTestBot().use { bot ->
                // When
                val replies = bot.sendTextAndCollectReplies(VALID_START_TIME_INPUT, senderId = SENDER_ID)

                // Then
                assertEquals(
                    originalSession.copy(state = WaitingAddTechnique(expectedDetails)),
                    storage.get(telegramUserId),
                )
                assertEquals(
                    listOf(
                        SentTelegramMessage(
                            chatId = SENDER_ID,
                            text = EXPECTED_ADD_TECHNIQUE_PROMPT,
                            inlineButtonTexts = EXPECTED_YES_NO_BUTTONS + "Отменить тренировку",
                        ),
                    ),
                    replies,
                )
                assertTrue(repository.saveCalls.isEmpty())
            }
        }

    @Test
    fun `invalid training start time preserves session and allows retry after an error message`() =
        runBlocking {
            // Given
            val originalSession =
                UserSession(state = WaitingStartTime(TIME_INPUT_TRAINING_DATE), createdAt = Instant.EPOCH)
            check(storage.start(telegramUserId, originalSession))
            createTestBot().use { bot ->
                // When
                val errorReplies = bot.sendTextAndCollectReplies(INVALID_START_TIME_INPUT, senderId = SENDER_ID)

                // Then
                assertEquals(originalSession, storage.get(telegramUserId))
                assertRecoveredError(
                    bot,
                    originalSession,
                    errorReplies,
                    "⚠️ $EXPECTED_INVALID_TIME_MESSAGE\n\n$EXPECTED_TIME_PROMPT",
                )

                // When
                val retryReplies = bot.sendTextAndCollectReplies(VALID_START_TIME_INPUT, senderId = SENDER_ID)

                // Then
                val expectedDetails =
                    TrainingDetails(
                        date = TIME_INPUT_TRAINING_DATE,
                        time = EXPECTED_START_TIME,
                    )
                assertEquals(
                    originalSession.copy(state = WaitingAddTechnique(expectedDetails)),
                    storage.get(telegramUserId),
                )
                assertTrue(retryReplies.isEmpty())
                assertRetryEdits(
                    bot,
                    errorReplies.single(),
                    CallbackQuestion(EXPECTED_ADD_TECHNIQUE_PROMPT, "add_technique"),
                )
                assertTrue(repository.saveCalls.isEmpty())
            }
        }

    @Test
    fun `valid training date advances session and requests start time without saving`() =
        runBlocking {
            // Given
            val originalSession = startSessionAwaitingDate()
            createTestBot().use { bot ->
                // When
                val replies = bot.sendTextAndCollectReplies(VALID_DATE_INPUT, senderId = SENDER_ID)

                // Then
                assertEquals(
                    originalSession.copy(state = WaitingStartTime(EXPECTED_TRAINING_DATE)),
                    storage.get(telegramUserId),
                )
                assertEquals(listOf(sentTrainingQuestion(SENDER_ID, EXPECTED_TIME_PROMPT)), replies)
                assertTrue(repository.saveCalls.isEmpty())
            }
        }

    @Test
    fun `invalid training date preserves session and allows retry after an error message`() =
        runBlocking {
            // Given
            val originalSession = startSessionAwaitingDate()
            createTestBot().use { bot ->
                // When
                val errorReplies = bot.sendTextAndCollectReplies(INVALID_DATE_INPUT, senderId = SENDER_ID)

                // Then
                assertEquals(originalSession, storage.get(telegramUserId))
                assertRecoveredError(
                    bot,
                    originalSession,
                    errorReplies,
                    "⚠️ $EXPECTED_INVALID_DATE_MESSAGE\n\n$EXPECTED_DATE_PROMPT",
                )

                // When
                val retryReplies = bot.sendTextAndCollectReplies(VALID_DATE_INPUT, senderId = SENDER_ID)

                // Then
                assertEquals(
                    originalSession.copy(state = WaitingStartTime(EXPECTED_TRAINING_DATE)),
                    storage.get(telegramUserId),
                )
                assertTrue(retryReplies.isEmpty())
                assertRetryEdits(bot, errorReplies.single(), CallbackQuestion(EXPECTED_TIME_PROMPT))
            }
        }

    @ParameterizedTest(name = "training text in {0} is ignored")
    @CsvSource(
        "group, $GROUP_CHAT_ID",
        "supergroup, $SUPERGROUP_CHAT_ID",
    )
    fun `training text in group preserves private session and sends no reply`(
        chatType: String,
        chatId: Long,
    ) = runBlocking {
        // Given
        val originalSession = startSessionAwaitingDate()
        createTestBot(chatId = chatId, chatType = chatType).use { bot ->
            // When
            val replies = bot.sendTextAndCollectReplies(VALID_DATE_INPUT, senderId = SENDER_ID)

            // Then
            assertEquals(originalSession, storage.get(telegramUserId))
            assertEquals(emptyList(), replies)
        }
    }

    @Test
    fun `training text without sender preserves existing session and sends no reply`() =
        runBlocking {
            // Given
            val originalSession = startSessionAwaitingDate()
            createTestBot().use { bot ->
                // When
                val replies = bot.sendTextAndCollectReplies(VALID_DATE_INPUT, senderId = null)

                // Then
                assertEquals(originalSession, storage.get(telegramUserId))
                assertEquals(emptyList(), replies)
            }
        }

    @Test
    fun `training text without active session does not create session or send reply`() =
        runBlocking {
            // Given
            createTestBot().use { bot ->
                // When
                val replies = bot.sendTextAndCollectReplies(VALID_DATE_INPUT, senderId = SENDER_ID)

                // Then
                assertNull(storage.get(telegramUserId))
                assertEquals(emptyList(), replies)
            }
        }

    @ParameterizedTest(name = "{0} is handled only as a command")
    @MethodSource("commandReplies")
    fun `commands preserve active session and send only their own reply`(
        command: String,
        expectedReply: String,
        expectedButtons: List<String>,
    ) = runBlocking {
        // Given
        val originalSession = startSessionAwaitingDate()
        val commandMessageId = TelegramMessageId(829L)
        createTestBot().use { bot ->
            // When
            val replies =
                bot.sendCommandAndCollectReplies(command, senderId = SENDER_ID, messageId = commandMessageId.value)

            // Then
            assertEquals(originalSession, storage.get(telegramUserId))
            assertEquals(listOf(SentTelegramMessage(SENDER_ID, expectedReply, expectedButtons)), replies)
            if (command == TRAINING_ADD_COMMAND) {
                val registered = assertNotNull(messageSessionStorage.get(telegramUserId))
                assertEquals(
                    TrainingMessageSession(
                        originalSession.sessionId,
                        TelegramChatId(SENDER_ID),
                        TelegramMessageId(replies.single().messageId),
                    ),
                    registered,
                )
                assertNotNull(messageSessionStorage.questionToken(telegramUserId))
                assertEquals(
                    trainingQuestion(registered.chatId, registered.messageId, EXPECTED_DATE_PROMPT),
                    bot.currentMessage(registered.messageId).withoutQuestionToken(),
                )
                assertEquals(listOf("sendMessage", "deleteMessage"), bot.apiCalls)
                assertEquals(
                    listOf(DeletedTelegramMessage(SENDER_ID, commandMessageId.value)),
                    bot.deletedMessages,
                )
                assertTrue(repository.saveCalls.isEmpty())
            }
        }
    }

    companion object {
        @JvmStatic
        fun commandReplies(): List<Arguments> =
            listOf(
                Arguments.of(START_COMMAND, EXPECTED_GREETING, emptyList<String>()),
                Arguments.of(TRAINING_ADD_COMMAND, EXPECTED_DATE_PROMPT, listOf("Отменить тренировку")),
            )
    }

    private fun assertRecoveredError(
        bot: TestTelegramBot,
        session: UserSession,
        replies: List<SentTelegramMessage>,
        text: String,
    ) {
        assertEquals(listOf(sentTrainingQuestion(SENDER_ID, text)), replies)
        val registered = assertNotNull(messageSessionStorage.get(telegramUserId))
        assertEquals(
            TrainingMessageSession(
                sessionId = session.sessionId,
                chatId = TelegramChatId(SENDER_ID),
                messageId = TelegramMessageId(replies.single().messageId),
            ),
            registered,
        )
        assertEquals(
            expected = CallbackQuestion(text).at(registered),
            actual = bot.currentMessage(registered.messageId).withoutQuestionToken(),
        )
        assertNotNull(messageSessionStorage.questionToken(telegramUserId))
        assertEquals(listOf("sendMessage", "deleteMessage"), bot.apiCalls)
        assertTrue(repository.saveCalls.isEmpty())
    }

    private fun assertRetryEdits(
        bot: TestTelegramBot,
        original: SentTelegramMessage,
        expected: CallbackQuestion,
    ) {
        val registered = assertNotNull(messageSessionStorage.get(telegramUserId))
        assertEquals(TelegramMessageId(original.messageId), registered.messageId)
        assertEquals(TelegramChatId(original.chatId), registered.chatId)
        val current = bot.currentMessage(registered.messageId)
        assertEquals(expected.at(registered), current.withoutQuestionToken())
        assertEquals(current, bot.editedMessages.last())
        assertNotNull(messageSessionStorage.questionToken(telegramUserId))
        assertEquals(1, bot.apiCalls.count { it == "sendMessage" })
        assertEquals(listOf("sendMessage", "deleteMessage", "editMessageText", "deleteMessage"), bot.apiCalls)
    }

    private fun startSessionAwaitingDate(): UserSession {
        val session = UserSession(state = WaitingDate, createdAt = Instant.EPOCH)
        check(storage.start(telegramUserId, session))
        return session
    }

    private fun createTestBot(
        chatId: Long = SENDER_ID,
        chatType: String = "private",
    ) = TestTelegramBot(
        chatId = chatId,
        chatType = chatType,
        registerCommands = {
            registerStartCommand()
            registerTrainingAddCommand(storage, messageSessionStorage)
            registerTrainingTextHandler(storage, sessionManager, messageSessionStorage)
        },
    )
}
