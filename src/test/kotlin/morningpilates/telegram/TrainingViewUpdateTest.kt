package morningpilates.telegram

import kotlinx.coroutines.runBlocking
import morningpilates.config.telegram.TelegramProperties
import morningpilates.flow.session.FlowSessionManager
import morningpilates.infrastructure.InMemoryFlowStorage
import morningpilates.training.FakeTrainingRepository
import morningpilates.training.SavedTraining
import morningpilates.training.TrainingDetails
import morningpilates.training.TrainingFlowService
import morningpilates.training.TrainingId
import morningpilates.training.TrainingQueryService
import morningpilates.user.TelegramUserId
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import java.util.stream.Stream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val UPDATE_USER = TelegramUserId(42L)
private val UPDATE_CHAT = TelegramChatId(4_200L)
private val UPDATE_TRAINING =
    SavedTraining(
        TrainingId(UUID.fromString("00000000-0000-0000-0000-000000000097")),
        TrainingDetails(LocalDate.of(2026, 10, 12), LocalTime.of(6, 30)),
    )
private const val FINISH_VIEW_BUTTON = "Завершить просмотр"
private val EMPTY_VIEW_TEXT =
    """
    Тренировок пока нет.

    Запиши свою первую тренировку с помощью команды /training_add.
    """.trimIndent()
private val TRAINING_VIEW_TEXT =
    """
    Последняя тренировка

    <blockquote expandable>Дата: 12.10.2026
    Время: 06:30</blockquote>
    """.trimIndent()

internal class TrainingViewUpdateTest {
    private val repository = FakeTrainingRepository()
    private val storage = InMemoryFlowStorage()
    private val messages = TrainingMessageSessionStorage()
    private val manager = FlowSessionManager(storage, listOf(TrainingFlowService(repository)))

    @ParameterizedTest(name = "{0}")
    @MethodSource("viewTransitions")
    fun `commands reuse one view and are deleted only after displaying each result`(transition: ViewTransition) =
        runBlocking<Unit> {
            createBot().use { bot ->
                var messageId: TelegramMessageId? = null
                val operations = mutableListOf<String>()
                transition.trainingPresent.forEachIndexed { index, present ->
                    repository.findLatestResults[UPDATE_USER] = Result.success(if (present) UPDATE_TRAINING else null)
                    val commandId = TelegramMessageId(2_713L + index)
                    val before = bot.apiCalls.size

                    val replies =
                        bot.sendCommandAndCollectReplies(
                            "/training_get_last",
                            UPDATE_USER.value,
                            commandId.value,
                        )

                    if (index == 0) {
                        messageId = TelegramMessageId(replies.single().messageId)
                    } else {
                        assertTrue(replies.isEmpty(), "Reuse the existing message for every result")
                    }
                    val current = bot.currentMessage(requireNotNull(messageId))
                    assertEquals(UPDATE_CHAT, current.chatId)
                    assertEquals(if (present) TRAINING_VIEW_TEXT else EMPTY_VIEW_TEXT, current.text)
                    assertEquals(if (present) "HTML" else null, current.parseMode)
                    assertEquals(
                        if (present) listOf(listOf(FINISH_VIEW_BUTTON)) else emptyList(),
                        current.inlineKeyboard.map { row -> row.map { it.text } },
                    )
                    operations += bot.apiCalls.drop(before)
                    assertEquals(1, bot.sendMessageRequests.size)
                    assertNull(storage.get(UPDATE_USER))
                    assertNull(messages.get(UPDATE_USER))
                    assertTrue(repository.saveCalls.isEmpty())
                }
                assertEquals(
                    transition.trainingPresent.indices.flatMap {
                        listOf(if (it == 0) "sendMessage" else "editMessageText", "deleteMessage")
                    },
                    operations,
                )
                assertEquals(
                    transition.trainingPresent.indices.map {
                        TelegramDeleteAttempt(UPDATE_CHAT, TelegramMessageId(2_713L + it), true)
                    },
                    bot.deleteMessageAttempts,
                )
                val current = bot.currentMessage(requireNotNull(messageId))
                val before = bot.apiCalls.size
                if (transition.trainingPresent.last()) {
                    assertTrue(bot.pressInlineButtonAndCollectReplies(current, FINISH_VIEW_BUTTON).acknowledged)
                    assertEquals(listOf("deleteMessage", "answerCallbackQuery"), bot.apiCalls.drop(before))
                } else {
                    assertTrue(bot.sendCommandAndCollectReplies("/cancel", messageId = 3_101L).isEmpty())
                    assertEquals(listOf("deleteMessage", "deleteMessage"), bot.apiCalls.drop(before))
                }
                assertTrue(
                    TelegramDeleteAttempt(UPDATE_CHAT, requireNotNull(messageId), true) in bot.deleteMessageAttempts,
                )
                assertEquals(1, bot.sendMessageRequests.size)
            }
        }

    @Test
    fun `card callback is invalid after an empty result and remains invalid when the card returns`() =
        runBlocking<Unit> {
            createBot().use { bot ->
                repository.findLatestResults[UPDATE_USER] = Result.success(UPDATE_TRAINING)
                val sent = bot.sendCommand("/training_get_last", messageId = 2_713L)
                val messageId = TelegramMessageId(sent.messageId)
                val original = bot.currentMessage(messageId)
                val old = bot.captureCallback(original, FINISH_VIEW_BUTTON)
                repository.findLatestResults[UPDATE_USER] = Result.success(null)

                assertTrue(bot.sendCommandAndCollectReplies("/training_get_last", messageId = 2_714L).isEmpty())
                val empty = bot.currentMessage(messageId)
                assertEquals(EMPTY_VIEW_TEXT, empty.text)
                assertTrue(empty.inlineKeyboard.isEmpty())
                val before = bot.apiCalls.size
                assertTrue(bot.deliverCallbackAndCollectReplies(old).acknowledged)
                assertEquals(listOf("answerCallbackQuery"), bot.apiCalls.drop(before))
                assertEquals(empty, bot.currentMessage(messageId))

                repository.findLatestResults[UPDATE_USER] = Result.success(UPDATE_TRAINING)
                assertTrue(bot.sendCommandAndCollectReplies("/training_get_last", messageId = 2_715L).isEmpty())
                val current = bot.currentMessage(messageId)
                assertNotEquals(
                    old.data,
                    current.inlineKeyboard
                        .single()
                        .single()
                        .callbackData,
                    "Returning to a card must authorize a new callback",
                )
                val after = bot.apiCalls.size
                assertTrue(bot.deliverCallbackAndCollectReplies(old).acknowledged)
                assertEquals(listOf("answerCallbackQuery"), bot.apiCalls.drop(after))
                assertEquals(current, bot.currentMessage(messageId))
                assertTrue(bot.pressInlineButtonAndCollectReplies(current, FINISH_VIEW_BUTTON).acknowledged)
                assertEquals(4, bot.deleteMessageAttempts.size)
                assertEquals(TelegramDeleteAttempt(UPDATE_CHAT, messageId, true), bot.deleteMessageAttempts.last())
                assertEquals(3, bot.answerCallbackQueryRequests.size)
                assertEquals(1, bot.sendMessageRequests.size)
                assertTrue(repository.saveCalls.isEmpty())
            }
        }

    @ParameterizedTest(name = "unchanged view contains a training = {0}")
    @ValueSource(booleans = [false, true])
    fun `message is not modified still permits deleting the repeated command`(present: Boolean) =
        runBlocking<Unit> {
            repository.findLatestResults[UPDATE_USER] = Result.success(if (present) UPDATE_TRAINING else null)
            createBot().use { bot ->
                val sent = bot.sendCommand("/training_get_last", messageId = 2_713L)
                val original = bot.currentMessage(TelegramMessageId(sent.messageId))
                bot.failNextEditMessageText("Bad Request: message is not modified")
                val before = bot.apiCalls.size

                val replies = bot.sendCommandAndCollectReplies("/training_get_last", messageId = 2_714L)

                assertTrue(replies.isEmpty())
                assertEquals(listOf("editMessageText", "deleteMessage"), bot.apiCalls.drop(before))
                assertEquals(original, bot.editedMessages.single(), "Text and keyboard must actually be identical")
                assertEquals(original, bot.currentMessage(original.messageId))
                assertEquals(1, bot.sendMessageRequests.size)
                assertEquals(
                    TelegramDeleteAttempt(UPDATE_CHAT, TelegramMessageId(2_714L), true),
                    bot.deleteMessageAttempts.last(),
                )
            }
        }

    @ParameterizedTest
    @ValueSource(strings = ["Bad Request: message to edit not found", "Internal Server Error: retry later"])
    fun `other editing errors do not delete the command or replace the registered card`(error: String) =
        runBlocking<Unit> {
            repository.findLatestResults[UPDATE_USER] = Result.success(UPDATE_TRAINING)
            createBot().use { bot ->
                val sent = bot.sendCommand("/training_get_last", messageId = 2_713L)
                val original = bot.currentMessage(TelegramMessageId(sent.messageId))
                val callback = bot.captureCallback(original, FINISH_VIEW_BUTTON)
                repository.findLatestResults[UPDATE_USER] = Result.success(null)
                bot.failNextEditMessageText(error)
                val before = bot.apiCalls.size

                assertTrue(bot.sendCommandAndCollectReplies("/training_get_last", messageId = 2_714L).isEmpty())

                assertEquals(listOf("editMessageText"), bot.apiCalls.drop(before))
                assertEquals(original, bot.currentMessage(original.messageId))
                assertTrue(bot.deleteMessageAttempts.none { it.messageId == TelegramMessageId(2_714L) })
                assertEquals(1, bot.sendMessageRequests.size)
                assertTrue(bot.deliverCallbackAndCollectReplies(callback).acknowledged)
                assertEquals(original.messageId, bot.deleteMessageAttempts.last().messageId)
                assertTrue(repository.saveCalls.isEmpty())
            }
        }

    @Test
    fun `failed first display leaves the command and does not register a view`() =
        runBlocking<Unit> {
            repository.findLatestResults[UPDATE_USER] = Result.success(null)
            createBot().use { bot ->
                bot.failNextSendMessage("Bad Request: failed to send")
                val before = bot.apiCalls.size
                assertTrue(bot.sendCommandAndCollectReplies("/training_get_last", messageId = 2_713L).isEmpty())
                assertEquals(listOf("sendMessage"), bot.apiCalls.drop(before))
                assertTrue(bot.deleteMessageAttempts.isEmpty())
                repository.findLatestResults[UPDATE_USER] = Result.success(UPDATE_TRAINING)
                val sent = bot.sendCommand("/training_get_last", messageId = 2_714L)
                assertEquals(TRAINING_VIEW_TEXT, sent.text)
                assertTrue(bot.editedMessages.isEmpty())
                assertEquals(
                    listOf(TelegramDeleteAttempt(UPDATE_CHAT, TelegramMessageId(2_714L), true)),
                    bot.deleteMessageAttempts,
                )
            }
        }

    private fun createBot() =
        TestTelegramBot(
            TelegramBot(TelegramProperties("test-token"), storage, manager, messages, TrainingQueryService(repository)),
            chatId = UPDATE_CHAT.value,
        )

    internal data class ViewTransition(
        val name: String,
        val trainingPresent: List<Boolean>,
    ) {
        override fun toString(): String = name
    }

    companion object {
        @JvmStatic
        fun viewTransitions(): Stream<ViewTransition> =
            Stream.of(
                ViewTransition("empty -> empty", listOf(false, false)),
                ViewTransition("training -> empty", listOf(true, false)),
                ViewTransition("empty -> training", listOf(false, true)),
                ViewTransition("training -> empty -> training", listOf(true, false, true)),
                ViewTransition("training -> training", listOf(true, true)),
            )
    }
}
