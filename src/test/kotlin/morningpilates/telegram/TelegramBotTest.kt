package morningpilates.telegram

import kotlinx.coroutines.runBlocking
import morningpilates.config.telegram.TelegramProperties
import morningpilates.flow.model.UserSession
import morningpilates.flow.session.FlowSessionManager
import morningpilates.flow.session.FlowStorage
import morningpilates.infrastructure.InMemoryFlowStorage
import morningpilates.training.FakeTrainingRepository
import morningpilates.training.FakeTrainingRepository.SaveCall
import morningpilates.training.TechniqueDraft
import morningpilates.training.TrainingDetails
import morningpilates.training.TrainingFlowService
import morningpilates.training.TrainingFlowState.WaitingAddAnotherTechnique
import morningpilates.training.TrainingFlowState.WaitingAddNote
import morningpilates.training.TrainingFlowState.WaitingConfirmation
import morningpilates.training.TrainingFlowState.WaitingDate
import morningpilates.training.TrainingFlowState.WaitingNote
import morningpilates.training.TrainingFlowState.WaitingStartTime
import morningpilates.training.TrainingFlowState.WaitingTechniqueDescription
import morningpilates.training.TrainingFlowState.WaitingTechniqueName
import morningpilates.training.populatedTrainingDetails
import morningpilates.training.toTrainingId
import morningpilates.user.TelegramUserId
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val START_COMMAND = "/start"
private const val TRAINING_ADD_COMMAND = "/training_add"
private const val SENDER_ID = 42L
private const val GROUP_CHAT_ID = -987654321L
private const val TEST_TOKEN = "test-token"
private const val EXPECTED_GREETING = "Morning Pilates Bot работает 🥋"
private const val EXPECTED_UNFINISHED_TRAINING_MESSAGE =
    "У вас уже есть незавершённая тренировка. Продолжите её, прежде чем создавать новую."
private const val START_TIME_INPUT = "6:30"
private const val TECHNIQUE_NAME_INPUT = "Armbar"
private const val TECHNIQUE_DESCRIPTION_INPUT = "Рычаг локтя из маунта"
private const val NOTE_INPUT = "Отрабатывали выход из сайд-контроля"
private const val YES_BUTTON_TEXT = "Да"
private const val NO_BUTTON_TEXT = "Нет"
private const val EXPECTED_TECHNIQUE_NAME_PROMPT = "Введите название техники."
private const val EXPECTED_TECHNIQUE_DESCRIPTION_PROMPT = "Введите описание техники."
private const val EXPECTED_ADD_ANOTHER_TECHNIQUE_PROMPT = "Добавить ещё одну технику?"
private const val EXPECTED_ADD_NOTE_PROMPT = "Добавить заметку?"
private const val EXPECTED_NOTE_PROMPT = "Введите заметку о тренировке."
private const val EXPECTED_NOTE_REQUIRED_REASON = "Введите заметку о тренировке"
private const val EXPECTED_CONFIRMATION_PROMPT = "Сохранить тренировку?"
private const val EXPECTED_SAVED_MESSAGE = "Тренировка сохранена."
private const val EXPECTED_CANCELLED_MESSAGE = "Создание тренировки отменено."
private const val EXPECTED_SAVE_FAILURE_MESSAGE = "Не удалось сохранить тренировку. Попробуйте ещё раз."
private val TRAINING_DATE = LocalDate.of(2026, 10, 12)
private val EXPECTED_DATE_PROMPT =
    """
    Введите дату тренировки.

    Например: 11.10.2026 или 2026-10-11.

    Можно использовать разделители: точку, дефис или косую черту.
    """.trimIndent()

class TelegramBotTest {
    @Test
    fun `failed saving keeps the session and retry saves the same training`() =
        runBlocking {
            val storage: FlowStorage = InMemoryFlowStorage()
            val repository = FakeTrainingRepository()
            val telegramUserId = TelegramUserId(SENDER_ID)
            val details = populatedTrainingDetails().copy(note = null)
            val originalSession = UserSession(WaitingNote(details), createdAt = Instant.EPOCH)
            check(storage.start(telegramUserId, originalSession))
            createTestBot(storage, repository = repository).use { bot ->
                val confirmationQuestion = bot.sendTextAndCollectReplies(NOTE_INPUT, senderId = SENDER_ID).single()
                val expectedDetails = details.copy(note = NOTE_INPUT)
                val expectedSession = originalSession.copy(state = WaitingConfirmation(expectedDetails))
                val expectedSaveCall =
                    SaveCall(telegramUserId, originalSession.sessionId.toTrainingId(), expectedDetails)
                assertEquals(expectedSession, storage.get(telegramUserId))

                repository.saveResult = Result.failure(IllegalStateException("Ошибка записи"))
                val failedAttempt =
                    bot.pressInlineButtonAndCollectReplies(confirmationQuestion, YES_BUTTON_TEXT, senderId = SENDER_ID)

                assertEquals(listOf(expectedSaveCall), repository.saveCalls)
                assertEquals(expectedSession, storage.get(telegramUserId))
                val retryQuestion = failedAttempt.replies.single()
                assertEquals(EXPECTED_SAVE_FAILURE_MESSAGE, retryQuestion.text)
                assertEquals(setOf(YES_BUTTON_TEXT, NO_BUTTON_TEXT), retryQuestion.inlineButtonTexts.toSet())
                assertTrue(failedAttempt.acknowledged)

                repository.saveResult = Result.success(Unit)
                val successfulRetry =
                    bot.pressInlineButtonAndCollectReplies(retryQuestion, YES_BUTTON_TEXT, senderId = SENDER_ID)

                assertEquals(listOf(expectedSaveCall, expectedSaveCall), repository.saveCalls)
                assertNull(storage.get(telegramUserId))
                assertEquals(listOf(SentTelegramMessage(SENDER_ID, EXPECTED_SAVED_MESSAGE)), successfulRetry.replies)
                assertTrue(successfulRetry.acknowledged)
            }
        }

    @Test
    fun `declining confirmation cancels training without saving`() =
        runBlocking {
            val storage: FlowStorage = InMemoryFlowStorage()
            val repository = FakeTrainingRepository()
            val telegramUserId = TelegramUserId(SENDER_ID)
            val details = populatedTrainingDetails().copy(note = null)
            val originalSession = UserSession(WaitingNote(details), createdAt = Instant.EPOCH)
            check(storage.start(telegramUserId, originalSession))
            createTestBot(storage, repository = repository).use { bot ->
                val confirmationQuestion = bot.sendTextAndCollectReplies(NOTE_INPUT, senderId = SENDER_ID).single()
                assertEquals(
                    originalSession.copy(state = WaitingConfirmation(details.copy(note = NOTE_INPUT))),
                    storage.get(telegramUserId),
                )

                val interaction =
                    bot.pressInlineButtonAndCollectReplies(confirmationQuestion, NO_BUTTON_TEXT, senderId = SENDER_ID)

                assertNull(storage.get(telegramUserId))
                assertTrue(repository.saveCalls.isEmpty())
                assertEquals(listOf(SentTelegramMessage(SENDER_ID, EXPECTED_CANCELLED_MESSAGE)), interaction.replies)
                assertTrue(interaction.acknowledged)
            }
        }

    @Test
    fun `confirming training saves it once and closes the session`() =
        runBlocking {
            val storage: FlowStorage = InMemoryFlowStorage()
            val repository = FakeTrainingRepository()
            val telegramUserId = TelegramUserId(SENDER_ID)
            val details = populatedTrainingDetails().copy(note = null)
            val originalSession = UserSession(WaitingNote(details), createdAt = Instant.EPOCH)
            check(storage.start(telegramUserId, originalSession))
            createTestBot(storage, repository = repository).use { bot ->
                val confirmationQuestion = bot.sendTextAndCollectReplies(NOTE_INPUT, senderId = SENDER_ID).single()
                val expectedDetails = details.copy(note = NOTE_INPUT)
                assertEquals(
                    originalSession.copy(state = WaitingConfirmation(expectedDetails)),
                    storage.get(telegramUserId),
                )

                val interaction =
                    bot.pressInlineButtonAndCollectReplies(confirmationQuestion, YES_BUTTON_TEXT, senderId = SENDER_ID)

                val expectedSaveCall =
                    SaveCall(telegramUserId, originalSession.sessionId.toTrainingId(), expectedDetails)
                assertEquals(listOf(expectedSaveCall), repository.saveCalls)
                assertNull(storage.get(telegramUserId))
                assertEquals(listOf(SentTelegramMessage(SENDER_ID, EXPECTED_SAVED_MESSAGE)), interaction.replies)
                assertTrue(interaction.acknowledged)

                val repeated =
                    bot.pressInlineButtonAndCollectReplies(confirmationQuestion, YES_BUTTON_TEXT, senderId = SENDER_ID)
                assertTrue(repeated.replies.isEmpty())
                assertEquals(listOf(expectedSaveCall), repository.saveCalls)
            }
        }

    @Test
    fun `blank note is rejected and valid retry reaches confirmation`() =
        runBlocking {
            val storage: FlowStorage = InMemoryFlowStorage()
            val repository = FakeTrainingRepository()
            val telegramUserId = TelegramUserId(SENDER_ID)
            val details =
                TrainingDetails(
                    date = TRAINING_DATE,
                    time = LocalTime.of(6, 30),
                    techniques = listOf(TechniqueDraft("Kimura", "Из гарда")),
                )
            val originalSession = UserSession(WaitingNote(details), createdAt = Instant.EPOCH)
            check(storage.start(telegramUserId, originalSession))
            createTestBot(storage, repository = repository).use { bot ->
                val invalidReplies = bot.sendTextAndCollectReplies("   ", senderId = SENDER_ID)

                assertEquals(listOf(SentTelegramMessage(SENDER_ID, EXPECTED_NOTE_REQUIRED_REASON)), invalidReplies)
                assertEquals(originalSession, storage.get(telegramUserId))

                val validReplies = bot.sendTextAndCollectReplies(NOTE_INPUT, senderId = SENDER_ID)

                assertEquals(
                    originalSession.copy(state = WaitingConfirmation(details.copy(note = NOTE_INPUT))),
                    storage.get(telegramUserId),
                )
                val confirmation = validReplies.single()
                assertEquals(EXPECTED_CONFIRMATION_PROMPT, confirmation.text)
                assertEquals(setOf(YES_BUTTON_TEXT, NO_BUTTON_TEXT), confirmation.inlineButtonTexts.toSet())
                assertTrue(repository.saveCalls.isEmpty())
            }
        }

    @Test
    fun `note text moves session to confirmation and asks to save training`() =
        runBlocking {
            val storage: FlowStorage = InMemoryFlowStorage()
            val repository = FakeTrainingRepository()
            val telegramUserId = TelegramUserId(SENDER_ID)
            val details =
                TrainingDetails(
                    date = TRAINING_DATE,
                    time = LocalTime.of(6, 30),
                    techniques = listOf(TechniqueDraft("Kimura", "Из гарда"), TechniqueDraft("Triangle")),
                )
            val originalSession = UserSession(WaitingNote(details), createdAt = Instant.EPOCH)
            check(storage.start(telegramUserId, originalSession))
            createTestBot(storage, repository = repository).use { bot ->
                val replies = bot.sendTextAndCollectReplies(NOTE_INPUT, senderId = SENDER_ID)

                assertEquals(
                    originalSession.copy(state = WaitingConfirmation(details.copy(note = NOTE_INPUT))),
                    storage.get(telegramUserId),
                )
                val confirmation = replies.single()
                assertEquals(SENDER_ID, confirmation.chatId)
                assertEquals(EXPECTED_CONFIRMATION_PROMPT, confirmation.text)
                assertEquals(setOf(YES_BUTTON_TEXT, NO_BUTTON_TEXT), confirmation.inlineButtonTexts.toSet())
                assertTrue(repository.saveCalls.isEmpty())
            }
        }

    @Test
    fun `add note no callback asks for confirmation without changing training details`() =
        runBlocking {
            val storage: FlowStorage = InMemoryFlowStorage()
            val repository = FakeTrainingRepository()
            val telegramUserId = TelegramUserId(SENDER_ID)
            val details =
                TrainingDetails(
                    date = TRAINING_DATE,
                    time = LocalTime.of(6, 30),
                    techniques = listOf(TechniqueDraft("Kimura", "Из гарда"), TechniqueDraft("Triangle")),
                )
            val originalSession =
                UserSession(WaitingTechniqueDescription(details, TECHNIQUE_NAME_INPUT), createdAt = Instant.EPOCH)
            check(storage.start(telegramUserId, originalSession))
            createTestBot(storage, repository = repository).use { bot ->
                val anotherQuestion =
                    bot.sendTextAndCollectReplies(TECHNIQUE_DESCRIPTION_INPUT, senderId = SENDER_ID).single()
                val noteInteraction =
                    bot.pressInlineButtonAndCollectReplies(anotherQuestion, NO_BUTTON_TEXT, senderId = SENDER_ID)
                val noteQuestion = noteInteraction.replies.single()

                val interaction =
                    bot.pressInlineButtonAndCollectReplies(noteQuestion, NO_BUTTON_TEXT, senderId = SENDER_ID)

                val addedTechnique = TechniqueDraft(TECHNIQUE_NAME_INPUT, TECHNIQUE_DESCRIPTION_INPUT)
                val expectedDetails = details.copy(techniques = details.techniques + addedTechnique)
                assertEquals(
                    originalSession.copy(state = WaitingConfirmation(expectedDetails)),
                    storage.get(telegramUserId),
                )
                val confirmation = interaction.replies.single()
                assertEquals(SENDER_ID, confirmation.chatId)
                assertEquals(EXPECTED_CONFIRMATION_PROMPT, confirmation.text)
                assertEquals(setOf(YES_BUTTON_TEXT, NO_BUTTON_TEXT), confirmation.inlineButtonTexts.toSet())
                assertTrue(interaction.acknowledged)
                assertTrue(repository.saveCalls.isEmpty())
            }
        }

    @Test
    fun `add note yes callback requests note without changing training details`() =
        runBlocking {
            val storage: FlowStorage = InMemoryFlowStorage()
            val repository = FakeTrainingRepository()
            val telegramUserId = TelegramUserId(SENDER_ID)
            val details =
                TrainingDetails(
                    date = TRAINING_DATE,
                    time = LocalTime.of(6, 30),
                    techniques = listOf(TechniqueDraft("Kimura", "Из гарда")),
                )
            val originalSession =
                UserSession(WaitingTechniqueDescription(details, TECHNIQUE_NAME_INPUT), createdAt = Instant.EPOCH)
            check(storage.start(telegramUserId, originalSession))
            createTestBot(storage, repository = repository).use { bot ->
                val anotherQuestion =
                    bot.sendTextAndCollectReplies(TECHNIQUE_DESCRIPTION_INPUT, senderId = SENDER_ID).single()
                val noteInteraction =
                    bot.pressInlineButtonAndCollectReplies(anotherQuestion, NO_BUTTON_TEXT, senderId = SENDER_ID)
                val noteQuestion = noteInteraction.replies.single()

                val interaction =
                    bot.pressInlineButtonAndCollectReplies(noteQuestion, YES_BUTTON_TEXT, senderId = SENDER_ID)

                val addedTechnique = TechniqueDraft(TECHNIQUE_NAME_INPUT, TECHNIQUE_DESCRIPTION_INPUT)
                val expectedDetails = details.copy(techniques = details.techniques + addedTechnique)
                assertEquals(
                    originalSession.copy(state = WaitingNote(expectedDetails)),
                    storage.get(telegramUserId),
                )
                assertEquals(listOf(SentTelegramMessage(SENDER_ID, EXPECTED_NOTE_PROMPT)), interaction.replies)
                assertTrue(interaction.acknowledged)
                assertTrue(repository.saveCalls.isEmpty())
            }
        }

    @Test
    fun `another technique no callback asks about note without changing training details`() =
        runBlocking {
            val storage: FlowStorage = InMemoryFlowStorage()
            val repository = FakeTrainingRepository()
            val telegramUserId = TelegramUserId(SENDER_ID)
            val details =
                TrainingDetails(
                    date = TRAINING_DATE,
                    time = LocalTime.of(6, 30),
                    techniques = listOf(TechniqueDraft("Kimura", "Из гарда")),
                    note = "Личная тренировка",
                )
            val originalSession =
                UserSession(WaitingTechniqueDescription(details, TECHNIQUE_NAME_INPUT), createdAt = Instant.EPOCH)
            check(storage.start(telegramUserId, originalSession))
            createTestBot(storage, repository = repository).use { bot ->
                val question = bot.sendTextAndCollectReplies(TECHNIQUE_DESCRIPTION_INPUT, senderId = SENDER_ID).single()

                val interaction =
                    bot.pressInlineButtonAndCollectReplies(question, NO_BUTTON_TEXT, senderId = SENDER_ID)

                val addedTechnique = TechniqueDraft(TECHNIQUE_NAME_INPUT, TECHNIQUE_DESCRIPTION_INPUT)
                val expectedDetails = details.copy(techniques = details.techniques + addedTechnique)
                assertEquals(
                    originalSession.copy(state = WaitingAddNote(expectedDetails)),
                    storage.get(telegramUserId),
                )
                assertEquals(
                    listOf(
                        SentTelegramMessage(
                            SENDER_ID,
                            EXPECTED_ADD_NOTE_PROMPT,
                            inlineButtonTexts = listOf(YES_BUTTON_TEXT, NO_BUTTON_TEXT),
                        ),
                    ),
                    interaction.replies,
                )
                assertTrue(interaction.acknowledged)
                assertTrue(repository.saveCalls.isEmpty())
            }
        }

    @Test
    fun `another technique yes callback requests a name without changing existing techniques`() =
        runBlocking {
            val storage: FlowStorage = InMemoryFlowStorage()
            val repository = FakeTrainingRepository()
            val telegramUserId = TelegramUserId(SENDER_ID)
            val details =
                TrainingDetails(
                    date = TRAINING_DATE,
                    time = LocalTime.of(6, 30),
                    techniques = listOf(TechniqueDraft("Kimura", "Из гарда"), TechniqueDraft("Triangle")),
                    note = "Личная тренировка",
                )
            val originalSession =
                UserSession(WaitingTechniqueDescription(details, TECHNIQUE_NAME_INPUT), createdAt = Instant.EPOCH)
            check(storage.start(telegramUserId, originalSession))
            createTestBot(storage, repository = repository).use { bot ->
                val question = bot.sendTextAndCollectReplies(TECHNIQUE_DESCRIPTION_INPUT, senderId = SENDER_ID).single()

                val interaction =
                    bot.pressInlineButtonAndCollectReplies(question, YES_BUTTON_TEXT, senderId = SENDER_ID)

                val addedTechnique = TechniqueDraft(TECHNIQUE_NAME_INPUT, TECHNIQUE_DESCRIPTION_INPUT)
                val expectedDetails = details.copy(techniques = details.techniques + addedTechnique)
                assertEquals(
                    originalSession.copy(state = WaitingTechniqueName(expectedDetails)),
                    storage.get(telegramUserId),
                )
                assertEquals(
                    listOf(SentTelegramMessage(SENDER_ID, EXPECTED_TECHNIQUE_NAME_PROMPT)),
                    interaction.replies,
                )
                assertTrue(interaction.acknowledged)
                assertTrue(repository.saveCalls.isEmpty())
            }
        }

    @Test
    fun `description no callback adds technique without description and asks about another technique`() =
        runBlocking {
            val storage: FlowStorage = InMemoryFlowStorage()
            val repository = FakeTrainingRepository()
            val telegramUserId = TelegramUserId(SENDER_ID)
            val details =
                TrainingDetails(
                    date = TRAINING_DATE,
                    time = LocalTime.of(6, 30),
                    techniques = listOf(TechniqueDraft("Kimura", "Из гарда")),
                    note = "Личная тренировка",
                )
            val originalSession = UserSession(WaitingTechniqueName(details), createdAt = Instant.EPOCH)
            check(storage.start(telegramUserId, originalSession))
            createTestBot(storage, repository = repository).use { bot ->
                val question = bot.sendTextAndCollectReplies(TECHNIQUE_NAME_INPUT, senderId = SENDER_ID).single()

                val interaction =
                    bot.pressInlineButtonAndCollectReplies(question, NO_BUTTON_TEXT, senderId = SENDER_ID)

                val expectedDetails =
                    details.copy(techniques = details.techniques + TechniqueDraft(TECHNIQUE_NAME_INPUT, null))
                assertEquals(
                    originalSession.copy(state = WaitingAddAnotherTechnique(expectedDetails)),
                    storage.get(telegramUserId),
                )
                assertEquals(
                    listOf(
                        SentTelegramMessage(
                            SENDER_ID,
                            EXPECTED_ADD_ANOTHER_TECHNIQUE_PROMPT,
                            inlineButtonTexts = listOf(YES_BUTTON_TEXT, NO_BUTTON_TEXT),
                        ),
                    ),
                    interaction.replies,
                )
                assertTrue(interaction.acknowledged)
                assertTrue(repository.saveCalls.isEmpty())
            }
        }

    @Test
    fun `description yes callback advances to technique description and acknowledges button press`() =
        runBlocking {
            val storage: FlowStorage = InMemoryFlowStorage()
            val repository = FakeTrainingRepository()
            val telegramUserId = TelegramUserId(SENDER_ID)
            val details = TrainingDetails(date = TRAINING_DATE, time = LocalTime.of(6, 30))
            val originalSession = UserSession(WaitingTechniqueName(details), createdAt = Instant.EPOCH)
            check(storage.start(telegramUserId, originalSession))
            createTestBot(storage, repository = repository).use { bot ->
                val question = bot.sendTextAndCollectReplies(TECHNIQUE_NAME_INPUT, senderId = SENDER_ID).single()

                val interaction =
                    bot.pressInlineButtonAndCollectReplies(question, YES_BUTTON_TEXT, senderId = SENDER_ID)

                assertEquals(
                    originalSession.copy(state = WaitingTechniqueDescription(details, TECHNIQUE_NAME_INPUT)),
                    storage.get(telegramUserId),
                )
                assertEquals(
                    listOf(SentTelegramMessage(SENDER_ID, EXPECTED_TECHNIQUE_DESCRIPTION_PROMPT)),
                    interaction.replies,
                )
                assertTrue(interaction.acknowledged)
                assertTrue(repository.saveCalls.isEmpty())
            }
        }

    @Test
    fun `callback without active session does not start training or send a reply`() =
        runBlocking {
            val storage: FlowStorage = InMemoryFlowStorage()
            val telegramUserId = TelegramUserId(SENDER_ID)
            val startingSession = UserSession(WaitingStartTime(TRAINING_DATE), createdAt = Instant.EPOCH)
            check(storage.start(telegramUserId, startingSession))
            createTestBot(storage).use { bot ->
                val question = bot.sendTextAndCollectReplies(START_TIME_INPUT, senderId = SENDER_ID).single()
                check(storage.remove(telegramUserId, requireNotNull(storage.get(telegramUserId))))

                val interaction =
                    bot.pressInlineButtonAndCollectReplies(question, YES_BUTTON_TEXT, senderId = SENDER_ID)

                assertNull(storage.get(telegramUserId))
                assertEquals(emptyList(), interaction.replies)
            }
        }

    @Test
    fun `callback with altered data does not change session or send a reply`() =
        runBlocking {
            val storage: FlowStorage = InMemoryFlowStorage()
            val telegramUserId = TelegramUserId(SENDER_ID)
            val startingSession = UserSession(WaitingStartTime(TRAINING_DATE), createdAt = Instant.EPOCH)
            check(storage.start(telegramUserId, startingSession))
            createTestBot(storage).use { bot ->
                val question = bot.sendTextAndCollectReplies(START_TIME_INPUT, senderId = SENDER_ID).single()
                val expectedSession = requireNotNull(storage.get(telegramUserId))

                val interaction =
                    bot.pressInlineButtonAndCollectReplies(
                        question,
                        YES_BUTTON_TEXT,
                        senderId = SENDER_ID,
                        callbackDataSuffix = ":unexpected",
                    )

                assertEquals(expectedSession, storage.get(telegramUserId))
                assertEquals(emptyList(), interaction.replies)
            }
        }

    @Test
    fun `stale technique button does not change progressed session`() =
        runBlocking {
            val storage: FlowStorage = InMemoryFlowStorage()
            val telegramUserId = TelegramUserId(SENDER_ID)
            val startingSession = UserSession(WaitingStartTime(TRAINING_DATE), createdAt = Instant.EPOCH)
            check(storage.start(telegramUserId, startingSession))
            createTestBot(storage).use { bot ->
                val question = bot.sendTextAndCollectReplies(START_TIME_INPUT, senderId = SENDER_ID).single()
                bot.pressInlineButtonAndCollectReplies(question, NO_BUTTON_TEXT, senderId = SENDER_ID)
                val expectedSession = requireNotNull(storage.get(telegramUserId))
                assertEquals(WaitingAddNote(TrainingDetails(TRAINING_DATE, LocalTime.of(6, 30))), expectedSession.state)

                val interaction =
                    bot.pressInlineButtonAndCollectReplies(question, YES_BUTTON_TEXT, senderId = SENDER_ID)

                assertEquals(expectedSession, storage.get(telegramUserId))
                assertEquals(emptyList(), interaction.replies)
            }
        }

    @ParameterizedTest(name = "registered callback in {0} is ignored")
    @CsvSource(
        "group, $GROUP_CHAT_ID",
        "supergroup, -100987654321",
    )
    fun `registered callback ignores group chats without changing personal session`(
        chatType: String,
        groupChatId: Long,
    ) = runBlocking {
        val storage: FlowStorage = InMemoryFlowStorage()
        val telegramUserId = TelegramUserId(SENDER_ID)
        check(storage.start(telegramUserId, UserSession(WaitingStartTime(TRAINING_DATE), createdAt = Instant.EPOCH)))
        createTestBot(storage).use { bot ->
            val question = bot.sendTextAndCollectReplies(START_TIME_INPUT, senderId = SENDER_ID).single()
            val sessionBeforeGroupCallback = storage.get(telegramUserId)

            val interaction =
                bot.pressInlineButtonAndCollectReplies(
                    question,
                    YES_BUTTON_TEXT,
                    senderId = SENDER_ID,
                    callbackChatId = groupChatId,
                    callbackChatType = chatType,
                )

            assertEquals(sessionBeforeGroupCallback, storage.get(telegramUserId))
            assertEquals(emptyList(), interaction.replies)
        }
    }

    @Test
    fun `registered no callback advances to add note and acknowledges button press`() =
        runBlocking {
            val storage: FlowStorage = InMemoryFlowStorage()
            val repository = FakeTrainingRepository()
            val telegramUserId = TelegramUserId(SENDER_ID)
            val originalSession = UserSession(WaitingStartTime(TRAINING_DATE), createdAt = Instant.EPOCH)
            check(storage.start(telegramUserId, originalSession))
            val details = TrainingDetails(date = TRAINING_DATE, time = LocalTime.of(6, 30))
            createTestBot(storage, repository = repository).use { bot ->
                val question = bot.sendTextAndCollectReplies(START_TIME_INPUT, senderId = SENDER_ID).single()

                val interaction =
                    bot.pressInlineButtonAndCollectReplies(question, NO_BUTTON_TEXT, senderId = SENDER_ID)

                assertEquals(
                    originalSession.copy(state = WaitingAddNote(details)),
                    storage.get(telegramUserId),
                )
                assertEquals(
                    listOf(
                        SentTelegramMessage(
                            SENDER_ID,
                            EXPECTED_ADD_NOTE_PROMPT,
                            inlineButtonTexts = listOf(YES_BUTTON_TEXT, NO_BUTTON_TEXT),
                        ),
                    ),
                    interaction.replies,
                )
                assertTrue(interaction.acknowledged)
                assertTrue(repository.saveCalls.isEmpty())
            }
        }

    @Test
    fun `registered yes callback advances to technique name and acknowledges button press`() =
        runBlocking {
            val storage: FlowStorage = InMemoryFlowStorage()
            val repository = FakeTrainingRepository()
            val telegramUserId = TelegramUserId(SENDER_ID)
            val originalSession = UserSession(WaitingStartTime(TRAINING_DATE), createdAt = Instant.EPOCH)
            check(storage.start(telegramUserId, originalSession))
            val details = TrainingDetails(date = TRAINING_DATE, time = LocalTime.of(6, 30))
            createTestBot(storage, repository = repository).use { bot ->
                val question = bot.sendTextAndCollectReplies(START_TIME_INPUT, senderId = SENDER_ID).single()

                val interaction =
                    bot.pressInlineButtonAndCollectReplies(question, YES_BUTTON_TEXT, senderId = SENDER_ID)

                assertEquals(
                    originalSession.copy(state = WaitingTechniqueName(details)),
                    storage.get(telegramUserId),
                )
                assertEquals(
                    listOf(SentTelegramMessage(SENDER_ID, EXPECTED_TECHNIQUE_NAME_PROMPT)),
                    interaction.replies,
                )
                assertTrue(interaction.acknowledged)
                assertTrue(repository.saveCalls.isEmpty())
            }
        }

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
    fun `registered training add uses the supplied message session storage`() =
        runBlocking {
            val storage = InMemoryFlowStorage()
            val messageSessions = TrainingMessageSessionStorage()
            val telegramUserId = TelegramUserId(SENDER_ID)
            createTestBot(storage, messageSessionStorage = messageSessions).use { bot ->
                val reply = bot.sendCommand(TRAINING_ADD_COMMAND, senderId = SENDER_ID, messageId = 713L)

                val session = assertNotNull(storage.get(telegramUserId))
                assertEquals(
                    TrainingMessageSession(
                        sessionId = session.sessionId,
                        chatId = TelegramChatId(SENDER_ID),
                        messageId = TelegramMessageId(reply.messageId),
                    ),
                    messageSessions.get(telegramUserId),
                    "Registered handlers must write to the storage supplied to TelegramBot",
                )
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
        val messageSessions = TrainingMessageSessionStorage()
        createTestBot(
            storage,
            chatId = chatId,
            chatType = chatType,
            messageSessionStorage = messageSessions,
        ).use { bot ->
            val replies = bot.sendCommandAndCollectReplies(TRAINING_ADD_COMMAND, senderId = SENDER_ID)

            assertNull(storage.get(TelegramUserId(SENDER_ID)))
            assertNull(messageSessions.get(TelegramUserId(SENDER_ID)))
            assertEquals(emptyList(), replies)
            assertEquals(emptyList(), bot.deletedMessages)
        }
    }

    private fun createTestBot(
        storage: FlowStorage,
        chatId: Long = SENDER_ID,
        chatType: String = "private",
        repository: FakeTrainingRepository = FakeTrainingRepository(),
        messageSessionStorage: TrainingMessageSessionStorage = TrainingMessageSessionStorage(),
    ): TestTelegramBot {
        val sessionManager = FlowSessionManager(storage, listOf(TrainingFlowService(repository)))
        return TestTelegramBot(
            telegramBot =
                TelegramBot(
                    properties = TelegramProperties(TEST_TOKEN),
                    flowStorage = storage,
                    sessionManager = sessionManager,
                    messageSessionStorage = messageSessionStorage,
                ),
            chatId = chatId,
            chatType = chatType,
        )
    }
}
