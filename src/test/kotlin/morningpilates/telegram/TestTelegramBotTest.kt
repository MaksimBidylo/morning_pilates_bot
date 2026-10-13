package morningpilates.telegram

import com.github.kotlintelegrambot.dispatcher.callbackQuery
import com.github.kotlintelegrambot.dispatcher.command
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup
import com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val FAILING_COMMAND = "failing"

class TestTelegramBotTest {
    @Test
    fun `test bot returns distinct configured message ids to SDK and caller`() =
        runBlocking {
            val messageIdsSeenBySdk = mutableListOf<Long>()
            TestTelegramBot(
                chatId = 42L,
                firstBotMessageId = 7_341L,
                registerCommands = {
                    command("reply") {
                        val reply = bot.sendMessage(ChatId.fromId(message.chat.id), text = "Same reply").getOrNull()
                        messageIdsSeenBySdk += checkNotNull(reply).messageId
                    }
                },
            ).use { bot ->
                val firstReply = bot.sendCommand("/reply")
                val secondReply = bot.sendCommand("/reply")

                assertEquals(listOf(7_341L, 7_342L), messageIdsSeenBySdk)
                assertEquals(messageIdsSeenBySdk, listOf(firstReply.messageId, secondReply.messageId))
                assertEquals(firstReply, secondReply)
            }
        }

    @Test
    fun `test bot records exact deletion and SDK receives successful result`() =
        runBlocking {
            var deletionResult: Boolean? = null
            TestTelegramBot(
                chatId = 84L,
                registerCommands = {
                    command("delete") {
                        deletionResult =
                            bot
                                .deleteMessage(
                                    chatId = ChatId.fromId(message.chat.id),
                                    messageId = message.messageId,
                                ).getOrNull()
                    }
                },
            ).use { bot ->
                val replies = bot.sendCommandAndCollectReplies("/delete", messageId = 9_135L)

                assertTrue(replies.isEmpty())
                assertEquals(true, deletionResult)
                assertEquals(listOf(DeletedTelegramMessage(chatId = 84L, messageId = 9_135L)), bot.deletedMessages)
            }
        }

    @Test
    fun `same content questions retain their own callback data and message ids`() =
        runBlocking {
            var nextQuestionNumber = 1
            val receivedCallbacks = mutableListOf<Pair<Long, String>>()
            TestTelegramBot(
                chatId = 42L,
                firstBotMessageId = 8_100L,
                registerCommands = {
                    command("question") {
                        bot.sendMessage(
                            chatId = ChatId.fromId(message.chat.id),
                            text = "Same question",
                            replyMarkup =
                                InlineKeyboardMarkup.createSingleButton(
                                    InlineKeyboardButton.CallbackData(
                                        text = "Choose",
                                        callbackData = "question-${nextQuestionNumber++}",
                                    ),
                                ),
                        )
                    }
                    callbackQuery(data = "question-") {
                        receivedCallbacks +=
                            checkNotNull(callbackQuery.message).messageId to checkNotNull(callbackQuery.data)
                    }
                },
            ).use { bot ->
                val firstQuestion = bot.sendCommand("/question")
                val secondQuestion = bot.sendCommand("/question")

                val firstInteraction = bot.pressInlineButtonAndCollectReplies(firstQuestion, "Choose")
                val secondInteraction = bot.pressInlineButtonAndCollectReplies(secondQuestion, "Choose")

                assertEquals(listOf(8_100L to "question-1", 8_101L to "question-2"), receivedCallbacks)
                assertTrue(firstInteraction.acknowledged)
                assertTrue(secondInteraction.acknowledged)
            }
        }

    @Test
    fun `test bot propagates unsupported API calls even when SDK catches interceptor exception`() =
        runBlocking {
            TestTelegramBot(
                chatId = 42L,
                registerCommands = { command("unsupported") { bot.getMe() } },
            ).use { bot ->
                val failure =
                    assertFailsWith<IllegalStateException> {
                        bot.sendCommandAndCollectReplies("/unsupported")
                    }

                assertEquals("Test bot does not support getMe", failure.message)
            }
        }

    @Test
    fun `test bot exposes interceptor request parsing errors`() =
        runBlocking {
            TestTelegramBot(
                chatId = 42L,
                registerCommands = {
                    command("channel") {
                        bot.sendMessage(ChatId.fromChannelUsername("test-channel"), text = "Reply")
                    }
                },
            ).use { bot ->
                val failure =
                    assertFailsWith<NumberFormatException> {
                        bot.sendCommandAndCollectReplies("/channel")
                    }

                assertTrue("test-channel" in failure.message.orEmpty())
            }
        }

    @Test
    fun `test bot propagates exceptions from command handlers`() =
        runBlocking {
            // Given
            val expectedFailure = IllegalStateException("Expected command handler failure")
            TestTelegramBot(
                chatId = 42L,
                registerCommands = { command(FAILING_COMMAND) { throw expectedFailure } },
            ).use { bot ->
                // When
                val actualFailure =
                    assertFailsWith<IllegalStateException> {
                        bot.sendCommandAndCollectReplies("/$FAILING_COMMAND")
                    }

                // Then
                assertEquals(expectedFailure.message, actualFailure.message)
            }
        }
}
