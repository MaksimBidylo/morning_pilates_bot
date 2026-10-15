package morningpilates.telegram.api

import com.github.kotlintelegrambot.dispatcher.callbackQuery
import com.github.kotlintelegrambot.dispatcher.command
import com.github.kotlintelegrambot.dispatcher.message
import com.github.kotlintelegrambot.entities.CallbackQuery
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup
import com.github.kotlintelegrambot.entities.Message
import com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton
import com.github.kotlintelegrambot.network.Response
import com.github.kotlintelegrambot.network.ResponseError
import com.github.kotlintelegrambot.network.fold
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import morningpilates.telegram.support.DeletedTelegramMessage
import morningpilates.telegram.support.EditedTelegramInlineButton
import morningpilates.telegram.support.EditedTelegramMessage
import morningpilates.telegram.support.TestTelegramBot
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val FAILING_COMMAND = "failing"

class TelegramSdkCompatibilityTest {
    @Test
    fun `edited keyboard supplies current callback data text and original message id`() =
        runBlocking {
            var questionMessageId: Long? = null
            var receivedCallback: CallbackQuery? = null
            val updatedKeyboard =
                InlineKeyboardMarkup.createSingleButton(InlineKeyboardButton.CallbackData("Да", "new:yes"))
            TestTelegramBot(
                chatId = 84L,
                firstBotMessageId = 7_341L,
                registerCommands = {
                    command("question") {
                        val sent =
                            bot
                                .sendMessage(
                                    ChatId.fromId(message.chat.id),
                                    text = "Before edit",
                                    replyMarkup =
                                        InlineKeyboardMarkup.createSingleRowKeyboard(
                                            InlineKeyboardButton.CallbackData("Да", "old:yes"),
                                            InlineKeyboardButton.CallbackData("Нет", "old:no"),
                                        ),
                                ).getOrNull()
                        questionMessageId = checkNotNull(sent).messageId
                    }
                    message {
                        if (message.text != "change") return@message
                        bot.editMessageText(
                            ChatId.fromId(message.chat.id),
                            messageId = checkNotNull(questionMessageId),
                            text = "After edit",
                            replyMarkup = updatedKeyboard,
                        )
                    }
                    callbackQuery(data = "new:yes") { receivedCallback = callbackQuery }
                },
            ).use { bot ->
                val originalQuestion = bot.sendCommand("/question")
                assertEquals(emptyList(), bot.sendTextAndCollectReplies("change"))
                val updatedQuestion = bot.currentMessage(TelegramMessageId(originalQuestion.messageId))
                assertEquals("After edit", updatedQuestion.text)
                assertEquals(
                    listOf(listOf(EditedTelegramInlineButton("Да", "new:yes"))),
                    updatedQuestion.inlineKeyboard,
                )
                assertFailsWith<IllegalArgumentException> {
                    bot.pressInlineButtonAndCollectReplies(originalQuestion, "Нет")
                }

                val interaction = bot.pressInlineButtonAndCollectReplies(updatedQuestion, "Да")

                assertTrue(interaction.acknowledged)
                assertEquals(emptyList(), interaction.replies)
                val callback = assertNotNull(receivedCallback)
                assertEquals("new:yes", callback.data)
                val callbackMessage = assertNotNull(callback.message)
                assertEquals(originalQuestion.messageId, callbackMessage.messageId)
                assertEquals(84L, callbackMessage.chat.id)
                assertEquals("After edit", callbackMessage.text)
                assertEquals(updatedKeyboard, callbackMessage.replyMarkup)
                assertEquals(listOf("sendMessage", "editMessageText", "answerCallbackQuery"), bot.apiCalls)
            }
        }

    @Test
    fun `cleared keyboard cannot be pressed and failed edit preserves current message`() =
        runBlocking {
            var questionMessageId: Long? = null
            val keyboard = InlineKeyboardMarkup.createSingleButton(InlineKeyboardButton.CallbackData("Да", "choose"))
            TestTelegramBot(
                chatId = 84L,
                registerCommands = {
                    command("question") {
                        val sent =
                            bot
                                .sendMessage(ChatId.fromId(message.chat.id), text = "Question", replyMarkup = keyboard)
                                .getOrNull()
                        questionMessageId = checkNotNull(sent).messageId
                    }
                    message {
                        when (message.text) {
                            "clear" -> {
                                bot.editMessageText(
                                    ChatId.fromId(message.chat.id),
                                    messageId = checkNotNull(questionMessageId),
                                    text = "Cleared",
                                )
                            }

                            "replace" -> {
                                bot.editMessageText(
                                    ChatId.fromId(message.chat.id),
                                    messageId = checkNotNull(questionMessageId),
                                    text = "Replacement",
                                    replyMarkup = keyboard,
                                )
                            }
                        }
                    }
                },
            ).use { bot ->
                val question = bot.sendCommand("/question")
                assertEquals(emptyList(), bot.sendTextAndCollectReplies("clear"))
                val clearedQuestion = bot.currentMessage(TelegramMessageId(question.messageId))
                assertEquals("Cleared", clearedQuestion.text)
                assertEquals(emptyList(), clearedQuestion.inlineKeyboard)
                assertFailsWith<IllegalArgumentException> { bot.pressInlineButtonAndCollectReplies(question, "Да") }
                bot.failNextEditMessageText("Bad Request: message to edit not found")

                assertEquals(emptyList(), bot.sendTextAndCollectReplies("replace"))

                assertEquals(clearedQuestion, bot.currentMessage(TelegramMessageId(question.messageId)))
                assertEquals(listOf("sendMessage", "editMessageText", "editMessageText"), bot.apiCalls)
            }
        }

    @ParameterizedTest
    @ValueSource(strings = ["Bad Request: message is not modified", "Bad Request: message to edit not found"])
    fun `editMessageText exposes Telegram HTTP 400 through the SDK error callback`(description: String) =
        runBlocking {
            var sdkError: ResponseError? = null
            var responseCallbackCalled = false
            var httpCode: Int? = null
            TestTelegramBot(
                chatId = 84L,
                registerCommands = {
                    command("edit") {
                        val result =
                            bot.editMessageText(ChatId.fromId(message.chat.id), messageId = 7_341L, text = "Question")
                        httpCode = result.first?.code()
                        result.fold<Response<Message>>(
                            response = { responseCallbackCalled = true },
                            error = { sdkError = it },
                        )
                    }
                },
            ).use { bot ->
                bot.failNextEditMessageText(description)

                assertEquals(emptyList(), bot.sendCommandAndCollectReplies("/edit"))

                assertEquals(400, httpCode)
                assertFalse(responseCallbackCalled)
                val error = assertNotNull(sdkError)
                assertNull(error.exception)
                val body = Json.parseToJsonElement(assertNotNull(error.errorBody).string()).jsonObject
                assertEquals("false", body.getValue("ok").jsonPrimitive.content)
                assertEquals("400", body.getValue("error_code").jsonPrimitive.content)
                assertEquals(description, body.getValue("description").jsonPrimitive.content)
                assertEquals(listOf("editMessageText"), bot.apiCalls)
            }
        }

    @Test
    fun `editMessageText decodes edited message and deleteMessage returns success`() =
        runBlocking {
            var questionMessageId: Long? = null
            var editedMessageSeenBySdk: Message? = null
            var editHttpSuccess = false
            var deletionResult: Boolean? = null
            val editedText = "Введите \"время\".\nФормат: чч\\мм."
            TestTelegramBot(
                chatId = 84L,
                firstBotMessageId = 7_341L,
                registerCommands = {
                    command("question") {
                        val chatId = ChatId.fromId(message.chat.id)
                        val question = bot.sendMessage(chatId, text = "Введите дату.").getOrNull()
                        questionMessageId = checkNotNull(question).messageId
                    }
                    message {
                        if (message.text != "2026-10-13") return@message
                        val chatId = ChatId.fromId(message.chat.id)
                        bot
                            .editMessageText(
                                chatId = chatId,
                                messageId = checkNotNull(questionMessageId),
                                text = editedText,
                            ).fold<Response<Message>>(
                                response = { apiResponse ->
                                    editHttpSuccess = apiResponse?.ok == true
                                    editedMessageSeenBySdk = apiResponse?.result
                                },
                                error = { failure ->
                                    throw failure.exception ?: IllegalStateException("editMessageText failed")
                                },
                            )
                        deletionResult = bot.deleteMessage(chatId, messageId = message.messageId).getOrNull()
                    }
                },
            ).use { bot ->
                val question = bot.sendCommand("/question", messageId = 321L)

                val replies = bot.sendTextAndCollectReplies("2026-10-13", messageId = 9_135L)

                assertTrue(replies.isEmpty())
                assertTrue(editHttpSuccess)
                assertEquals(true, deletionResult)
                assertEquals("Введите дату.", question.text)
                val editedMessage = checkNotNull(editedMessageSeenBySdk)
                assertEquals(question.messageId, editedMessage.messageId)
                assertEquals(84L, editedMessage.chat.id)
                assertEquals(editedText, editedMessage.text)
                assertEquals(
                    listOf(
                        EditedTelegramMessage(TelegramChatId(84L), TelegramMessageId(question.messageId), editedText),
                    ),
                    bot.editedMessages,
                )
                assertEquals(listOf("sendMessage", "editMessageText", "deleteMessage"), bot.apiCalls)
                assertEquals(listOf(DeletedTelegramMessage(84L, 9_135L)), bot.deletedMessages)
            }
        }

    @Test
    fun `sendMessage and callbacks preserve distinct identities for identical questions`() =
        runBlocking {
            var nextQuestionNumber = 1
            val messageIdsSeenBySdk = mutableListOf<Long>()
            val receivedCallbacks = mutableListOf<Pair<Long, String>>()
            TestTelegramBot(
                chatId = 42L,
                firstBotMessageId = 8_100L,
                registerCommands = {
                    command("question") {
                        val question =
                            bot
                                .sendMessage(
                                    chatId = ChatId.fromId(message.chat.id),
                                    text = "Same question",
                                    replyMarkup =
                                        InlineKeyboardMarkup.createSingleButton(
                                            InlineKeyboardButton.CallbackData(
                                                text = "Choose",
                                                callbackData = "question-${nextQuestionNumber++}",
                                            ),
                                        ),
                                ).getOrNull()
                        messageIdsSeenBySdk += checkNotNull(question).messageId
                    }
                    callbackQuery(data = "question-") {
                        receivedCallbacks +=
                            checkNotNull(callbackQuery.message).messageId to checkNotNull(callbackQuery.data)
                    }
                },
            ).use { bot ->
                val firstQuestion = bot.sendCommand("/question")
                val secondQuestion = bot.sendCommand("/question")

                assertEquals(listOf(8_100L, 8_101L), messageIdsSeenBySdk)
                assertEquals(messageIdsSeenBySdk, listOf(firstQuestion.messageId, secondQuestion.messageId))
                assertEquals(firstQuestion, secondQuestion)

                val firstInteraction = bot.pressInlineButtonAndCollectReplies(firstQuestion, "Choose")
                val secondInteraction = bot.pressInlineButtonAndCollectReplies(secondQuestion, "Choose")

                assertEquals(listOf(8_100L to "question-1", 8_101L to "question-2"), receivedCallbacks)
                assertTrue(firstInteraction.acknowledged)
                assertTrue(secondInteraction.acknowledged)
            }
        }

    @Test
    fun `unsupported API call surfaces exception caught by SDK`() =
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
    fun `malformed sendMessage request exposes parsing failure`() =
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
    fun `handler exception reaches caller`() =
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
