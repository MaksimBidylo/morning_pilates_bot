package morningpilates.telegram

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.Dispatcher
import com.github.kotlintelegrambot.dispatcher.handlers.Handler
import com.github.kotlintelegrambot.dispatcher.message
import com.github.kotlintelegrambot.entities.CallbackQuery
import com.github.kotlintelegrambot.entities.Chat
import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup
import com.github.kotlintelegrambot.entities.Message
import com.github.kotlintelegrambot.entities.MessageEntity
import com.github.kotlintelegrambot.entities.MessageEntity.Type.BOT_COMMAND
import com.github.kotlintelegrambot.entities.Update
import com.github.kotlintelegrambot.entities.User
import com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton
import com.github.kotlintelegrambot.webhook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

private const val DEFAULT_SENDER_ID = 42L
private const val UPDATE_TIMEOUT_MILLIS = 5_000L

internal class TestTelegramBot(
    private val chatId: Long,
    private val chatType: String = "private",
    firstBotMessageId: Long = 1_000L,
    registerCommands: Dispatcher.() -> Unit,
) : AutoCloseable {
    constructor(
        telegramBot: TelegramBot,
        chatId: Long,
        chatType: String = "private",
        firstBotMessageId: Long = 1_000L,
    ) : this(
        chatId = chatId,
        chatType = chatType,
        firstBotMessageId = firstBotMessageId,
        registerCommands = { telegramBot.registeredHandlers().forEach(::addHandler) },
    )

    private val replies = Channel<SentTelegramMessage>(Channel.UNLIMITED)
    private val answeredCallbacks = Channel<String>(Channel.UNLIMITED)
    private val processedUpdates = Channel<Unit>(Channel.UNLIMITED)
    private val handlerFailures = Channel<Throwable>(Channel.UNLIMITED)
    private val apiFailures = Channel<Throwable>(Channel.UNLIMITED)
    private val currentMessages = mutableMapOf<TelegramMessageId, EditedTelegramMessage>()
    private val deletions = mutableListOf<DeletedTelegramMessage>()
    private val edits = mutableListOf<EditedTelegramMessage>()
    private val recordedApiCalls = mutableListOf<String>()
    private var nextUpdateId = 1L
    private var nextIncomingMessageId = 100L
    private var nextBotMessageId = firstBotMessageId
    private var nextEditMessageTextResponse: Pair<Int, String>? = null

    val deletedMessages: List<DeletedTelegramMessage>
        get() = deletions.toList()

    val editedMessages: List<EditedTelegramMessage>
        get() = edits.toList()

    val apiCalls: List<String>
        get() = recordedApiCalls.toList()

    fun currentMessage(messageId: TelegramMessageId): EditedTelegramMessage = currentMessages.getValue(messageId)

    fun failNextEditMessageText(description: String) {
        respondToNextEditMessageText(
            statusCode = 400,
            body = """{"ok":false,"error_code":400,"description":${JsonPrimitive(description)}}""",
        )
    }

    fun respondToNextEditMessageText(
        statusCode: Int,
        body: String,
    ) {
        check(nextEditMessageTextResponse == null) { "An editMessageText response is already pending" }
        nextEditMessageTextResponse = statusCode to body
    }

    private val bot =
        bot {
            token = "test-token"
            apiUrl = "https://telegram.invalid/"
            coroutineDispatcher = Dispatchers.Default
            httpClientInterceptors = listOf(telegramApiInterceptor())
            webhook {
                url = "https://bot.invalid/webhook"
                createOnStart = false
            }
            dispatch {
                registerCommands()
                recordHandlerFailures()
                // Keep this last: handlers run sequentially. Commands must not consume the update.
                message { processedUpdates.send(Unit) }
                addHandler(
                    object : Handler {
                        override fun checkUpdate(update: Update): Boolean = update.callbackQuery != null

                        override suspend fun handleUpdate(
                            bot: Bot,
                            update: Update,
                        ) {
                            processedUpdates.send(Unit)
                        }
                    },
                )
            }
        }

    init {
        check(bot.startWebhook()) { "Could not start test bot dispatcher" }
    }

    suspend fun sendCommand(
        command: String,
        senderId: Long = DEFAULT_SENDER_ID,
        messageId: Long = nextIncomingMessageId++,
    ): SentTelegramMessage = sendCommandAndCollectReplies(command, senderId, messageId).single()

    suspend fun sendCommandAndCollectReplies(
        command: String,
        senderId: Long? = DEFAULT_SENDER_ID,
        messageId: Long = nextIncomingMessageId++,
    ): List<SentTelegramMessage> = sendMessageAndCollectReplies(command, senderId, messageId, isCommand = true)

    suspend fun sendTextAndCollectReplies(
        text: String,
        senderId: Long? = DEFAULT_SENDER_ID,
        messageId: Long = nextIncomingMessageId++,
    ): List<SentTelegramMessage> = sendMessageAndCollectReplies(text, senderId, messageId, isCommand = false)

    suspend fun pressInlineButtonAndCollectReplies(
        question: SentTelegramMessage,
        buttonText: String,
        senderId: Long = DEFAULT_SENDER_ID,
        callbackDataSuffix: String = "",
        callbackChatId: Long = question.chatId,
        callbackChatType: String = chatType,
    ): CallbackInteraction =
        pressInlineButtonAndCollectReplies(
            question = currentMessage(TelegramMessageId(question.messageId)),
            buttonText = buttonText,
            senderId = senderId,
            callbackDataSuffix = callbackDataSuffix,
            callbackChatId = callbackChatId,
            callbackChatType = callbackChatType,
        )

    suspend fun pressInlineButtonAndCollectReplies(
        question: EditedTelegramMessage,
        buttonText: String,
        senderId: Long = DEFAULT_SENDER_ID,
        callbackDataSuffix: String = "",
        callbackChatId: Long = question.chatId.value,
        callbackChatType: String = chatType,
    ): CallbackInteraction =
        withTimeout(UPDATE_TIMEOUT_MILLIS) {
            val currentQuestion = currentMessage(question.messageId)
            val button = currentQuestion.inlineKeyboard.flatten().singleOrNull { it.text == buttonText }
            requireNotNull(button) { "Button '$buttonText' is not present on the current message" }
            val callbackId = "test-callback-$nextUpdateId"
            val callbackData = button.callbackData + callbackDataSuffix
            bot.processUpdate(
                callbackUpdate(currentQuestion, callbackData, senderId, callbackId, callbackChatId, callbackChatType),
            )
            processedUpdates.receive()
            throwRecordedFailures()
            CallbackInteraction(
                replies = collectReplies(),
                acknowledged = answeredCallbacks.tryReceive().getOrNull() == callbackId,
            )
        }

    private suspend fun sendMessageAndCollectReplies(
        text: String,
        senderId: Long?,
        messageId: Long,
        isCommand: Boolean,
    ): List<SentTelegramMessage> =
        withTimeout(UPDATE_TIMEOUT_MILLIS) {
            bot.processUpdate(messageUpdate(text, senderId, messageId, isCommand))
            processedUpdates.receive()
            throwRecordedFailures()
            collectReplies()
        }

    private fun throwRecordedFailures() {
        val apiFailure = apiFailures.tryReceive().getOrNull()
        val handlerFailure = handlerFailures.tryReceive().getOrNull()
        (apiFailure ?: handlerFailure)?.let { throw it }
    }

    private fun collectReplies(): List<SentTelegramMessage> =
        buildList {
            while (true) {
                val reply = replies.tryReceive().getOrNull() ?: break
                add(reply)
            }
        }

    override fun close() {
        bot.stopPolling()
        replies.cancel()
        answeredCallbacks.cancel()
        processedUpdates.cancel()
        handlerFailures.cancel()
        apiFailures.cancel()
    }

    private fun messageUpdate(
        text: String,
        senderId: Long?,
        messageId: Long,
        isCommand: Boolean,
    ): Update {
        val updateId = nextUpdateId++
        return Update(
            updateId = updateId,
            message =
                Message(
                    messageId = messageId,
                    from = senderId?.let { User(id = it, isBot = false, firstName = "Test") },
                    date = 0,
                    chat = Chat(id = chatId, type = chatType),
                    text = text,
                    entities =
                        if (isCommand) {
                            listOf(MessageEntity(type = BOT_COMMAND, offset = 0, length = text.length))
                        } else {
                            null
                        },
                ),
        )
    }

    private fun callbackUpdate(
        question: EditedTelegramMessage,
        callbackData: String,
        senderId: Long,
        callbackId: String,
        callbackChatId: Long,
        callbackChatType: String,
    ): Update =
        Update(
            updateId = nextUpdateId++,
            callbackQuery =
                CallbackQuery(
                    id = callbackId,
                    from = User(id = senderId, isBot = false, firstName = "Test"),
                    message =
                        Message(
                            messageId = question.messageId.value,
                            date = 0,
                            chat = Chat(id = callbackChatId, type = callbackChatType),
                            text = question.text,
                            replyMarkup =
                                question.inlineKeyboard.takeIf { it.isNotEmpty() }?.let { keyboard ->
                                    InlineKeyboardMarkup.create(
                                        keyboard.map { row ->
                                            row.map { InlineKeyboardButton.CallbackData(it.text, it.callbackData) }
                                        },
                                    )
                                },
                        ),
                    data = callbackData,
                    chatInstance = "test-chat-instance",
                ),
        )

    private fun Dispatcher.recordHandlerFailures() {
        registeredHandlers().forEach { handler ->
            removeHandler(handler)
            addHandler(
                object : Handler by handler {
                    override suspend fun handleUpdate(
                        bot: Bot,
                        update: Update,
                    ) {
                        runCatching { handler.handleUpdate(bot, update) }
                            .onFailure { handlerFailures.send(it) }
                            .getOrThrow()
                    }
                },
            )
        }
    }

    private fun telegramApiInterceptor(): Interceptor =
        Interceptor { chain ->
            runCatching {
                val request = chain.request()
                val method = request.url.pathSegments.last()
                check(method in setOf("sendMessage", "editMessageText", "answerCallbackQuery", "deleteMessage")) {
                    "Test bot does not support $method"
                }
                recordedApiCalls += method
                val body = request.body as FormBody
                val fields = (0 until body.size).associate { body.name(it) to body.value(it) }
                var responseCode = 200
                val responseBody =
                    when (method) {
                        "sendMessage" -> {
                            sendMessageResponse(fields)
                        }

                        "editMessageText" -> {
                            val editedMessageResponse = editMessageTextResponse(fields)
                            val configuredResponse = nextEditMessageTextResponse
                            nextEditMessageTextResponse = null
                            if (configuredResponse == null) {
                                currentMessages[edits.last().messageId] = edits.last()
                                editedMessageResponse
                            } else {
                                val (statusCode, configuredBody) = configuredResponse
                                responseCode = statusCode
                                configuredBody
                            }
                        }

                        "answerCallbackQuery" -> {
                            answeredCallbacks.trySend(fields.getValue("callback_query_id")).getOrThrow()
                            """{"ok":true,"result":true}"""
                        }

                        "deleteMessage" -> {
                            deletions +=
                                DeletedTelegramMessage(
                                    chatId = fields.getValue("chat_id").toLong(),
                                    messageId = fields.getValue("message_id").toLong(),
                                )
                            """{"ok":true,"result":true}"""
                        }

                        else -> {
                            error("Unsupported method: $method")
                        }
                    }
                Response
                    .Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(responseCode)
                    .message(if (responseCode == 400) "Bad Request" else "OK")
                    .body(responseBody.toResponseBody("application/json".toMediaType()))
                    .build()
            }.onFailure { apiFailures.trySend(it).getOrThrow() }.getOrThrow()
        }

    private fun readInlineKeyboard(fields: Map<String, String>): List<List<EditedTelegramInlineButton>> =
        fields["reply_markup"]
            ?.let { Json.parseToJsonElement(it).jsonObject["inline_keyboard"] }
            ?.jsonArray
            ?.map { row ->
                row.jsonArray.map { button ->
                    val buttonFields = button.jsonObject
                    EditedTelegramInlineButton(
                        text = buttonFields.getValue("text").jsonPrimitive.content,
                        callbackData = buttonFields.getValue("callback_data").jsonPrimitive.content,
                    )
                }
            }.orEmpty()

    private fun editMessageTextResponse(fields: Map<String, String>): String {
        val editedMessage =
            EditedTelegramMessage(
                chatId = TelegramChatId(fields.getValue("chat_id").toLong()),
                messageId = TelegramMessageId(fields.getValue("message_id").toLong()),
                text = fields.getValue("text"),
                inlineKeyboard = readInlineKeyboard(fields),
            )
        edits += editedMessage
        return """
            {"ok":true,"result":{
                "message_id":${editedMessage.messageId.value},"date":0,
                "chat":{"id":${editedMessage.chatId.value},"type":"$chatType"},
                "text":${JsonPrimitive(editedMessage.text)}
            }}
            """.trimIndent()
    }

    private fun sendMessageResponse(fields: Map<String, String>): String {
        val keyboard = readInlineKeyboard(fields)
        val messageId = nextBotMessageId++
        val message =
            SentTelegramMessage(
                chatId = fields.getValue("chat_id").toLong(),
                text = fields.getValue("text"),
                inlineButtonTexts = keyboard.flatten().map { it.text },
            ).apply { this.messageId = messageId }
        val typedMessageId = TelegramMessageId(messageId)
        currentMessages[typedMessageId] =
            EditedTelegramMessage(
                TelegramChatId(message.chatId),
                typedMessageId,
                message.text,
                keyboard,
            )
        replies.trySend(message).getOrThrow()
        return """
            {"ok":true,"result":{
                "message_id":$messageId,"date":0,
                "chat":{"id":${message.chatId},"type":"$chatType"}
            }}
            """.trimIndent()
    }
}

private fun TelegramBot.registeredHandlers(): List<Handler> {
    val botField = TelegramBot::class.java.getDeclaredField("bot").apply { isAccessible = true }
    val sdkBot = botField.get(this) as Bot
    val dispatcherField = Bot::class.java.getDeclaredField("dispatcher").apply { isAccessible = true }
    return (dispatcherField.get(sdkBot) as Dispatcher).registeredHandlers()
}

private fun Dispatcher.registeredHandlers(): List<Handler> {
    val handlersField = Dispatcher::class.java.getDeclaredField("commandHandlers").apply { isAccessible = true }
    return (handlersField.get(this) as Set<*>).map { it as Handler }
}

internal data class SentTelegramMessage(
    val chatId: Long,
    val text: String,
    val inlineButtonTexts: List<String> = emptyList(),
) {
    var messageId: Long = 0L
        internal set
}

internal data class DeletedTelegramMessage(
    val chatId: Long,
    val messageId: Long,
)

internal data class EditedTelegramMessage(
    val chatId: TelegramChatId,
    val messageId: TelegramMessageId,
    val text: String,
    val inlineKeyboard: List<List<EditedTelegramInlineButton>> = emptyList(),
)

internal data class EditedTelegramInlineButton(
    val text: String,
    val callbackData: String,
)

internal data class CallbackInteraction(
    val replies: List<SentTelegramMessage>,
    val acknowledged: Boolean,
)
