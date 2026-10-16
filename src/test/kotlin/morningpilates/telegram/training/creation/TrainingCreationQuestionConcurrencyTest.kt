package morningpilates.telegram.training.creation

import com.github.kotlintelegrambot.entities.CallbackQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import morningpilates.config.telegram.TelegramProperties
import morningpilates.flow.session.FlowSessionManager
import morningpilates.infrastructure.InMemoryFlowStorage
import morningpilates.telegram.TelegramBot
import morningpilates.telegram.api.TelegramChatId
import morningpilates.telegram.api.TelegramMessageId
import morningpilates.telegram.support.EditedTelegramMessage
import morningpilates.telegram.support.TestTelegramBot
import morningpilates.training.FakeTrainingRepository
import morningpilates.training.TechniqueDraft
import morningpilates.training.TrainingFlowService
import morningpilates.training.TrainingFlowState.WaitingAddAnotherTechnique
import morningpilates.training.TrainingFlowState.WaitingAddTechniqueDescription
import morningpilates.training.TrainingFlowState.WaitingConfirmation
import morningpilates.training.TrainingFlowState.WaitingTechniqueName
import morningpilates.training.TrainingQueryService
import morningpilates.user.TelegramUserId
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val QUESTION_USER = TelegramUserId(42L)
private val QUESTION_CHAT = TelegramChatId(4_200L)

internal class TrainingCreationQuestionConcurrencyTest {
    private val storage = InMemoryFlowStorage()
    private val messages = TrainingCreationMessageSessionStorage()
    private val repository = FakeTrainingRepository()
    private val manager = FlowSessionManager(storage, listOf(TrainingFlowService(repository)))
    private val telegramBot =
        TelegramBot(
            profileRepository = morningpilates.profile.FakeProfileRepository(),
            TelegramProperties("test-token"),
            storage,
            manager,
            messages,
            TrainingQueryService(repository),
            deleteTraining = repository::delete,
        )

    @ParameterizedTest(name = "stale message deletion fails = {0}")
    @ValueSource(booleans = [false, true])
    fun `successful send arriving after cancellation cannot restore the question registration`(deletionFails: Boolean) =
        runBlocking<Unit> {
            startSessionAwaitingConfirmation(storage, QUESTION_USER)
            createBot().use { origin ->
                val initial = origin.sendCommand("/training_add", messageId = 713L)
                val question = origin.currentMessage(TelegramMessageId(initial.messageId))
                val stale = origin.captureCallback(question, "Да")
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                origin.pauseNextSend(entered, release)
                createBot(listOf(question), 9_000L).use { closer ->
                    val pending =
                        async(Dispatchers.Default) {
                            origin.sendCommandAndCollectReplies("/training_add", messageId = 2_713L)
                        }
                    try {
                        awaitSend(entered)
                        closer.sendCommandAndCollectReplies("/cancel", messageId = 3_101L)
                        assertNull(storage.get(QUESTION_USER))
                        assertNull(messages.get(QUESTION_USER))
                        if (deletionFails) origin.failNextDeleteMessage("Bad Request: message can't be deleted")
                        release.countDown()
                        val late = pending.await().single()
                        val lateQuestion =
                            origin
                                .currentMessage(TelegramMessageId(late.messageId))
                                .copy(inlineKeyboard = origin.sendMessageRequests.last().inlineKeyboard)
                        val lateCallback = origin.captureCallback(lateQuestion, "Да")
                        assertIgnored(origin, stale)
                        assertIgnored(origin, lateCallback)
                        assertAll(
                            { assertNull(storage.get(QUESTION_USER)) },
                            { assertNull(messages.get(QUESTION_USER), "A late send must not restore registration") },
                            { assertRetired(origin, lateQuestion.messageId) },
                            {
                                assertTrue(
                                    origin.deleteMessageAttempts.none { it.messageId == TelegramMessageId(2_713L) },
                                    "An unregistered recovery must not delete its command",
                                )
                            },
                            { assertTrue(repository.saveCalls.isEmpty()) },
                        )
                        if (deletionFails) {
                            assertTrue(origin.currentMessage(lateQuestion.messageId).inlineKeyboard.isEmpty())
                        }
                    } finally {
                        release.countDown()
                        pending.cancelAndJoin()
                    }
                }
            }
        }

    @Test
    fun `successful send arriving after a text transition cannot replace the current question`() =
        runBlocking<Unit> {
            val initialSession = startSessionAwaitingConfirmation(storage, QUESTION_USER)
            val details = (initialSession.state as WaitingConfirmation).details
            val nameSession = initialSession.copy(state = WaitingTechniqueName(details))
            assertTrue(storage.update(QUESTION_USER, initialSession, nameSession))
            createBot().use { origin ->
                val initial = origin.sendCommand("/training_add", messageId = 713L)
                val question = origin.currentMessage(TelegramMessageId(initial.messageId))
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                origin.pauseNextSend(entered, release)
                createBot(listOf(question), 9_000L).use { responder ->
                    val pending =
                        async(Dispatchers.Default) {
                            origin.sendCommandAndCollectReplies("/training_add", messageId = 2_713L)
                        }
                    try {
                        awaitSend(entered)
                        responder.sendTextAndCollectReplies("Omoplata", messageId = 3_101L)
                        val expected = initialSession.copy(state = WaitingAddTechniqueDescription(details, "Omoplata"))
                        assertEquals(expected, storage.get(QUESTION_USER))
                        val current = assertNotNull(messages.get(QUESTION_USER))
                        val currentQuestion = responder.currentMessage(current.messageId)
                        val token = messages.questionToken(QUESTION_USER)
                        release.countDown()
                        val late = pending.await().single()
                        val lateQuestion = origin.currentMessage(TelegramMessageId(late.messageId))

                        assertEquals(expected, storage.get(QUESTION_USER))
                        assertLosingRecovery(current, origin, lateQuestion.messageId, TelegramMessageId(2_713L))
                        assertEquals(token, messages.questionToken(QUESTION_USER))
                        assertIgnored(origin, origin.captureCallback(lateQuestion, "Отменить тренировку"))
                        assertIgnored(origin, origin.captureCallback(question, "Отменить тренировку"))
                        val no = responder.captureCallback(currentQuestion, "Нет")
                        assertTrue(responder.deliverCallbackAndCollectReplies(no).acknowledged)
                        assertEquals(
                            initialSession.copy(
                                state =
                                    WaitingAddAnotherTechnique(
                                        details.copy(techniques = details.techniques + TechniqueDraft("Omoplata")),
                                    ),
                            ),
                            storage.get(QUESTION_USER),
                        )
                        assertIgnored(responder, no)
                        assertTrue(repository.saveCalls.isEmpty())
                    } finally {
                        release.countDown()
                        pending.cancelAndJoin()
                    }
                }
            }
        }

    @ParameterizedTest(name = "first request completes first = {0}")
    @ValueSource(booleans = [true, false])
    fun `first completed recovery wins and late send cannot overwrite it`(firstCompletesFirst: Boolean) =
        runBlocking<Unit> {
            val session = startSessionAwaitingConfirmation(storage, QUESTION_USER)
            createBot().use { first ->
                val initial = first.sendCommand("/training_add", messageId = 713L)
                val original = first.currentMessage(TelegramMessageId(initial.messageId))
                createBot(listOf(original), 9_000L).use { second ->
                    val firstEntered = CountDownLatch(1)
                    val secondEntered = CountDownLatch(1)
                    val firstRelease = CountDownLatch(1)
                    val secondRelease = CountDownLatch(1)
                    first.pauseNextSend(firstEntered, firstRelease)
                    second.pauseNextSend(secondEntered, secondRelease)
                    val firstPending =
                        async(Dispatchers.Default) {
                            first.sendCommandAndCollectReplies("/training_add", messageId = 2_713L)
                        }
                    val secondPending =
                        async(Dispatchers.Default) {
                            second.sendCommandAndCollectReplies("/training_add", messageId = 2_829L)
                        }
                    try {
                        awaitSend(firstEntered)
                        awaitSend(secondEntered)
                        val winner = if (firstCompletesFirst) first else second
                        val loser = if (firstCompletesFirst) second else first
                        val winningPending = if (firstCompletesFirst) firstPending else secondPending
                        val losingPending = if (firstCompletesFirst) secondPending else firstPending
                        (if (firstCompletesFirst) firstRelease else secondRelease).countDown()
                        winningPending.await()
                        val registration = assertNotNull(messages.get(QUESTION_USER))
                        val current = winner.currentMessage(registration.messageId)
                        (if (firstCompletesFirst) secondRelease else firstRelease).countDown()
                        val late = losingPending.await().single()
                        val lateQuestion =
                            loser
                                .currentMessage(TelegramMessageId(late.messageId))
                                .copy(inlineKeyboard = loser.sendMessageRequests.last().inlineKeyboard)
                        assertEquals(session, storage.get(QUESTION_USER))
                        val losingCommand = TelegramMessageId(if (firstCompletesFirst) 2_829L else 2_713L)
                        assertLosingRecovery(registration, loser, lateQuestion.messageId, losingCommand)
                        assertIgnored(winner, winner.captureCallback(original, "Да"))
                        assertIgnored(loser, loser.captureCallback(lateQuestion, "Да"))
                        assertWinningCancel(winner, current)
                    } finally {
                        firstRelease.countDown()
                        secondRelease.countDown()
                        firstPending.cancelAndJoin()
                        secondPending.cancelAndJoin()
                    }
                }
            }
        }

    private fun assertLosingRecovery(
        winner: TrainingCreationMessageSession,
        loser: TestTelegramBot,
        lateMessageId: TelegramMessageId,
        commandId: TelegramMessageId,
    ) {
        assertAll(
            { assertEquals(winner, messages.get(QUESTION_USER), "Late send overwrote the winner") },
            { assertRetired(loser, lateMessageId) },
            { assertTrue(loser.deleteMessageAttempts.none { it.messageId == commandId }) },
        )
    }

    private suspend fun assertWinningCancel(
        winner: TestTelegramBot,
        current: EditedTelegramMessage,
    ) {
        val cancel = winner.captureCallback(current, "Нет")
        val answers = winner.answerCallbackQueryRequests.size
        assertTrue(winner.deliverCallbackAndCollectReplies(cancel).acknowledged)
        assertNull(storage.get(QUESTION_USER), "The winning keyboard must remain usable")
        assertNull(messages.get(QUESTION_USER))
        assertIgnored(winner, cancel)
        assertEquals(
            listOf(cancel.id, cancel.id),
            winner.answerCallbackQueryRequests.drop(answers).map { it.callbackQueryId },
        )
        assertTrue(repository.saveCalls.isEmpty())
    }

    private fun TestTelegramBot.pauseNextSend(
        entered: CountDownLatch,
        release: CountDownLatch,
    ) {
        beforeNextSendMessageResponse {
            entered.countDown()
            check(release.await(5, SECONDS)) { "Telegram send response was not released" }
        }
    }

    private suspend fun awaitSend(entered: CountDownLatch) {
        assertTrue(withContext(Dispatchers.IO) { entered.await(5, SECONDS) }, "sendMessage was not reached")
    }

    private fun assertRetired(
        bot: TestTelegramBot,
        messageId: TelegramMessageId,
    ) {
        assertTrue(
            bot.deleteMessageAttempts.any { it.messageId == messageId && it.succeeded } ||
                bot.replyMarkupEdits.any { it.messageId == messageId && it.succeeded && it.inlineKeyboard.isEmpty() },
            "The stale question must be deleted or have its keyboard removed",
        )
    }

    private suspend fun assertIgnored(
        bot: TestTelegramBot,
        callback: CallbackQuery,
    ) {
        val session = storage.get(QUESTION_USER)
        val registration = messages.get(QUESTION_USER)
        val calls = bot.apiCalls.size
        val answers = bot.answerCallbackQueryRequests.size
        val saves = repository.saveCalls.toList()
        val result = bot.deliverCallbackAndCollectReplies(callback)
        assertTrue(result.acknowledged)
        assertTrue(result.replies.isEmpty())
        assertEquals(listOf("answerCallbackQuery"), bot.apiCalls.drop(calls))
        assertEquals(listOf(callback.id), bot.answerCallbackQueryRequests.drop(answers).map { it.callbackQueryId })
        assertEquals(session, storage.get(QUESTION_USER))
        assertEquals(registration, messages.get(QUESTION_USER))
        assertEquals(saves, repository.saveCalls)
    }

    private fun createBot(
        existing: List<EditedTelegramMessage> = emptyList(),
        firstMessageId: Long = 5_000L,
    ) = TestTelegramBot(
        telegramBot,
        chatId = QUESTION_CHAT.value,
        firstBotMessageId = firstMessageId,
        existingMessages = existing,
    )
}
