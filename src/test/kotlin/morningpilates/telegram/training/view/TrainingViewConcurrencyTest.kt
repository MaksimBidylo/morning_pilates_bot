package morningpilates.telegram.training.view

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
import morningpilates.telegram.support.SentTelegramMessage
import morningpilates.telegram.support.TelegramDeleteAttempt
import morningpilates.telegram.support.TestTelegramBot
import morningpilates.telegram.training.creation.TrainingCreationMessageSessionStorage
import morningpilates.training.FakeTrainingRepository
import morningpilates.training.SavedTraining
import morningpilates.training.TrainingDetails
import morningpilates.training.TrainingFlowService
import morningpilates.training.TrainingId
import morningpilates.training.TrainingQueryService
import morningpilates.training.TrainingRepository
import morningpilates.user.TelegramUserId
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val CONCURRENT_USER = TelegramUserId(42L)
private val CONCURRENT_CHAT = TelegramChatId(4_200L)
private val CONCURRENT_TRAINING =
    SavedTraining(
        TrainingId(UUID.fromString("00000000-0000-0000-0000-000000000098")),
        TrainingDetails(LocalDate.of(2026, 10, 12), LocalTime.of(6, 30)),
    )

internal class TrainingViewConcurrencyTest {
    private val repository = FakeTrainingRepository()
    private val nextReadPause = AtomicReference<(() -> Unit)?>(null)
    private val queries =
        object : TrainingRepository by repository {
            override fun findLatest(telegramUserId: TelegramUserId): Result<SavedTraining?> {
                val result = repository.findLatest(telegramUserId)
                nextReadPause.getAndSet(null)?.invoke()
                return result
            }
        }
    private val storage = InMemoryFlowStorage()
    private val messages = TrainingCreationMessageSessionStorage()
    private val manager = FlowSessionManager(storage, listOf(TrainingFlowService(repository)))
    private val telegramBot =
        TelegramBot(
            TelegramProperties("test-token"),
            storage,
            manager,
            messages,
            TrainingQueryService(queries),
            deleteTraining = queries::delete,
        )

    @Test
    fun `retry finishing after cancel never reopens the view or changes the draft`() =
        runBlocking<Unit> {
            createBot().use { origin ->
                origin.sendCommand("/training_add", messageId = 713L)
                origin.sendTextAndCollectReplies("14.10.2026", messageId = 829L)
                origin.sendTextAndCollectReplies("7:45", messageId = 947L)
                val session = assertNotNull(storage.get(CONCURRENT_USER))
                val registration = assertNotNull(messages.get(CONCURRENT_USER))
                val question = origin.currentMessage(registration.messageId)
                repository.findLatestResults[CONCURRENT_USER] = Result.failure(IllegalStateException("Unavailable"))
                val sent = origin.sendCommand("/training_get_last", messageId = 2_713L)
                val error = origin.currentMessage(TelegramMessageId(sent.messageId))
                val retry = origin.captureCallback(error, "Повторить попытку")
                repository.findLatestResults[CONCURRENT_USER] = Result.success(CONCURRENT_TRAINING)
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                nextReadPause.set {
                    entered.countDown()
                    check(release.await(5, SECONDS)) { "Retry response was not released" }
                }

                // Separate SDK dispatchers share the same production handlers and view storage.
                createBot(listOf(question, error), firstMessageId = 9_000L).use { closer ->
                    val before = origin.apiCalls.size
                    val pending = async(Dispatchers.Default) { origin.deliverCallbackAndCollectReplies(retry) }
                    try {
                        assertTrue(withContext(Dispatchers.IO) { entered.await(5, SECONDS) })
                        assertTrue(closer.sendCommandAndCollectReplies("/cancel", messageId = 3_101L).isEmpty())
                        val deletedView = TelegramDeleteAttempt(CONCURRENT_CHAT, error.messageId, true)
                        assertTrue(deletedView in closer.deleteMessageAttempts)
                        assertEquals(session, storage.get(CONCURRENT_USER))
                        release.countDown()

                        val result = pending.await()

                        assertTrue(result.acknowledged)
                        assertTrue(result.replies.isEmpty(), "A completed stale retry must not send a new view")
                        assertEquals(listOf("answerCallbackQuery"), origin.apiCalls.drop(before))
                        assertEquals(1, origin.answerCallbackQueryRequests.size)
                        assertEquals(session, storage.get(CONCURRENT_USER))
                        assertEquals(registration, messages.get(CONCURRENT_USER))
                        assertEquals(question, origin.currentMessage(registration.messageId))
                        assertEquals(listOf(CONCURRENT_USER, CONCURRENT_USER), repository.findLatestCalls)
                        assertTrue(closer.sendCommandAndCollectReplies("/cancel", messageId = 3_102L).isEmpty())
                        assertNull(
                            storage.get(CONCURRENT_USER),
                            "The next cancel must reach creation, not a resurrected view",
                        )
                        assertEquals("Создание тренировки отменено.", closer.currentMessage(question.messageId).text)
                        assertTrue(repository.saveCalls.isEmpty())
                    } finally {
                        release.countDown()
                        pending.cancelAndJoin()
                    }
                }
            }
        }

    @Test
    fun `successful edit response arriving after cancel cannot register the view again`() =
        runBlocking<Unit> {
            repository.findLatestResults[CONCURRENT_USER] = Result.success(CONCURRENT_TRAINING)
            createBot().use { origin ->
                val sent = origin.sendCommand("/training_get_last", messageId = 2_713L)
                val original = origin.currentMessage(TelegramMessageId(sent.messageId))
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                repository.findLatestResults[CONCURRENT_USER] =
                    Result.success(
                        CONCURRENT_TRAINING.copy(details = CONCURRENT_TRAINING.details.copy(note = "Обновлено")),
                    )
                origin.beforeNextEditMessageTextResponse {
                    entered.countDown()
                    check(release.await(5, SECONDS)) { "Telegram response was not released" }
                }
                createBot(listOf(original), firstMessageId = 9_000L).use { closer ->
                    val before = origin.apiCalls.size
                    val pending =
                        async(Dispatchers.Default) {
                            origin.sendCommandAndCollectReplies("/training_get_last", messageId = 2_714L)
                        }
                    try {
                        assertTrue(withContext(Dispatchers.IO) { entered.await(5, SECONDS) })
                        assertTrue(closer.sendCommandAndCollectReplies("/cancel", messageId = 3_101L).isEmpty())
                        val deletedView = TelegramDeleteAttempt(CONCURRENT_CHAT, original.messageId, true)
                        assertTrue(deletedView in closer.deleteMessageAttempts)
                        release.countDown()
                        assertTrue(pending.await().isEmpty())

                        val replies = closer.sendCommandAndCollectReplies("/cancel", messageId = 3_102L)

                        assertEquals(
                            listOf(SentTelegramMessage(CONCURRENT_CHAT.value, "Нет активного действия.")),
                            replies,
                            "A late Telegram success must not restore a closed registration",
                        )
                        assertEquals(listOf("editMessageText"), origin.apiCalls.drop(before))
                        assertTrue(origin.deleteMessageAttempts.none { it.messageId == TelegramMessageId(2_714L) })
                        assertNull(storage.get(CONCURRENT_USER))
                        assertNull(messages.get(CONCURRENT_USER))
                        assertTrue(repository.saveCalls.isEmpty())
                    } finally {
                        release.countDown()
                        pending.cancelAndJoin()
                    }
                }
            }
        }

    private fun createBot(
        existing: List<EditedTelegramMessage> = emptyList(),
        firstMessageId: Long = 5_000L,
    ) = TestTelegramBot(
        telegramBot,
        chatId = CONCURRENT_CHAT.value,
        firstBotMessageId = firstMessageId,
        existingMessages = existing,
    )
}
