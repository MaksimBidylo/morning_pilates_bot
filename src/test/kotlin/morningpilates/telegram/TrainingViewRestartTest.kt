package morningpilates.telegram

import com.github.kotlintelegrambot.entities.CallbackQuery
import kotlinx.coroutines.runBlocking
import morningpilates.config.telegram.TelegramProperties
import morningpilates.flow.FlowHandler
import morningpilates.flow.model.FlowEvent
import morningpilates.flow.model.FlowState
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
import org.junit.jupiter.params.provider.EnumSource
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val OLD_VIEW_NOTICE = "Этот диалог больше неактуален. Используйте меню команд."
private val RESTART_VIEW_USER = TelegramUserId(42L)
private val RESTART_VIEW_CHAT = TelegramChatId(4_200L)
private val RESTART_TRAINING =
    SavedTraining(
        TrainingId(UUID.fromString("00000000-0000-0000-0000-000000000098")),
        TrainingDetails(LocalDate.of(2026, 10, 12), LocalTime.of(6, 30), note = "Карточка до перезапуска"),
    )

internal class TrainingViewRestartTest {
    private val storage = InMemoryFlowStorage()
    private val messages = TrainingMessageSessionStorage()
    private val repository = FakeTrainingRepository()
    private val events = mutableListOf<FlowEvent>()

    @ParameterizedTest
    @EnumSource(OldViewButton::class)
    fun `previous launch callback gets one notice without changing either view`(scenario: OldViewButton) =
        runBlocking<Unit> {
            val previous = previousView(scenario)
            assertNull(storage.get(RESTART_VIEW_USER))
            assertNull(messages.get(RESTART_VIEW_USER))
            restartedBot(previous.message).use { bot ->
                assertTrue(bot.apiCalls.isEmpty())
                val current =
                    if (scenario.newView) {
                        repository.findLatestResults[RESTART_VIEW_USER] =
                            Result.success(
                                RESTART_TRAINING.copy(details = RESTART_TRAINING.details.copy(note = "Новая карточка")),
                            )
                        val sent = bot.sendCommand("/training_get_last", messageId = 2_714L)
                        bot.currentMessage(TelegramMessageId(sent.messageId))
                    } else {
                        null
                    }
                val calls = repository.findLatestCalls.toList()
                val before = bot.apiCalls.size
                val answers = bot.answerCallbackQueryRequests.size

                val interaction = bot.deliverCallbackAndCollectReplies(previous.callback)

                assertTrue(interaction.acknowledged)
                assertTrue(interaction.replies.isEmpty())
                assertEquals(listOf("answerCallbackQuery"), bot.apiCalls.drop(before))
                assertEquals(
                    listOf(TelegramCallbackAnswer(previous.callback.id, OLD_VIEW_NOTICE)),
                    bot.answerCallbackQueryRequests.drop(answers),
                )
                assertEquals(previous.message, bot.currentMessage(previous.message.messageId))
                if (current != null) assertEquals(current, bot.currentMessage(current.messageId))
                assertEquals(calls, repository.findLatestCalls)
                assertTrue(bot.editedMessages.isEmpty())
                assertTrue(bot.replyMarkupEdits.isEmpty())
                assertTrue(bot.deleteMessageAttempts.drop(if (current == null) 0 else 1).isEmpty())
                assertNull(storage.get(RESTART_VIEW_USER))
                assertNull(messages.get(RESTART_VIEW_USER))
                assertNull(messages.questionToken(RESTART_VIEW_USER))
                assertTrue(events.isEmpty())
                assertTrue(repository.saveCalls.isEmpty())
                if (current != null) {
                    assertTrue(bot.pressInlineButtonAndCollectReplies(current, "Завершить просмотр").acknowledged)
                    assertEquals(
                        TelegramDeleteAttempt(RESTART_VIEW_CHAT, current.messageId, true),
                        bot.deleteMessageAttempts.last(),
                    )
                }
            }
        }

    private suspend fun previousView(scenario: OldViewButton): PreviousTrainingView {
        val previousStorage = InMemoryFlowStorage()
        val previousMessages = TrainingMessageSessionStorage()
        val previousRepository = FakeTrainingRepository()
        previousRepository.findLatestResults[RESTART_VIEW_USER] =
            if (scenario.failed) {
                Result.failure(IllegalStateException("PostgreSQL is unavailable"))
            } else {
                Result.success(RESTART_TRAINING)
            }
        val previousManager = FlowSessionManager(previousStorage, listOf(TrainingFlowService(previousRepository)))
        TestTelegramBot(
            TelegramBot(
                TelegramProperties("test-token"),
                previousStorage,
                previousManager,
                previousMessages,
                TrainingQueryService(previousRepository),
            ),
            chatId = RESTART_VIEW_CHAT.value,
            firstBotMessageId = 6_341L,
        ).use { bot ->
            val replies = bot.sendCommandAndCollectReplies("/training_get_last", messageId = 2_713L)
            assertEquals(1, replies.size, "The previous launch must show the card or the database error screen")
            val message = bot.currentMessage(TelegramMessageId(replies.single().messageId))
            assertTrue(message.inlineKeyboard.flatten().any { it.text == scenario.button })
            val callback = bot.captureCallback(message, scenario.button, RESTART_VIEW_USER)
            assertNull(previousStorage.get(RESTART_VIEW_USER))
            assertTrue(previousRepository.saveCalls.isEmpty())
            return PreviousTrainingView(message, callback)
        }
    }

    private fun restartedBot(previous: EditedTelegramMessage): TestTelegramBot {
        val service = TrainingFlowService(repository)
        val observed =
            object : FlowHandler by service {
                override fun handle(
                    state: FlowState,
                    event: FlowEvent,
                ) = service.handle(state, event).also { events += event }
            }
        val manager = FlowSessionManager(storage, listOf(observed))
        return TestTelegramBot(
            TelegramBot(TelegramProperties("test-token"), storage, manager, messages, TrainingQueryService(repository)),
            chatId = RESTART_VIEW_CHAT.value,
            firstBotMessageId = 8_351L,
            existingMessages = listOf(previous),
        )
    }

    internal enum class OldViewButton(
        val failed: Boolean,
        val button: String,
        val newView: Boolean = false,
    ) {
        CARD(false, "Завершить просмотр"),
        CARD_WITH_NEW_VIEW(false, "Завершить просмотр", true),
        ERROR_RETRY(true, "Повторить попытку"),
        ERROR_FINISH(true, "Завершить просмотр"),
    }
}

private data class PreviousTrainingView(
    val message: EditedTelegramMessage,
    val callback: CallbackQuery,
)
