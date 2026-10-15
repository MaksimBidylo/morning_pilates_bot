package morningpilates.telegram.support

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
import morningpilates.telegram.TelegramBot
import morningpilates.telegram.api.TelegramChatId
import morningpilates.telegram.api.TelegramMessageId
import morningpilates.user.TelegramUserId
import okhttp3.FormBody
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

private const val DEFAULT_SENDER_ID = 42L
private const val UPDATE_TIMEOUT_MILLIS = 5_000L
private val SUPPORTED_API_METHODS =
    setOf("sendMessage", "editMessageText", "editMessageReplyMarkup", "answerCallbackQuery", "deleteMessage")

internal class TestTelegramBot(
    private val chatId: Long,
    private val chatType: String = "private",
    firstBotMessageId: Long = 1_000L,
    existingMessages: List<EditedTelegramMessage> = emptyList(),
    registerCommands: Dispatcher.() -> Unit,
) : AutoCloseable {
    constructor(
        telegramBot: TelegramBot,
        chatId: Long,
        chatType: String = "private",
        firstBotMessageId: Long = 1_000L,
        existingMessages: List<EditedTelegramMessage> = emptyList(),
    ) : this(
        chatId = chatId,
        chatType = chatType,
        firstBotMessageId = firstBotMessageId,
        existingMessages = existingMessages,
        registerCommands = { telegramBot.registeredHandlers().forEach(::addHandler) },
    )

    private val replies = Channel<SentTelegramMessage>(Channel.UNLIMITED)
    private val answeredCallbacks = Channel<String>(Channel.UNLIMITED)
    private val processedUpdates = Channel<Unit>(Channel.UNLIMITED)
    private val handlerFailures = Channel<Throwable>(Channel.UNLIMITED)
    private val apiFailures = Channel<Throwable>(Channel.UNLIMITED)
    private val currentMessages = existingMessages.associateBy { it.messageId }.toMutableMap()
    private val deletions = mutableListOf<DeletedTelegramMessage>()
    private val deletionAttempts = mutableListOf<TelegramDeleteAttempt>()
    private val edits = mutableListOf<EditedTelegramMessage>()
    private val markupEdits = mutableListOf<TelegramReplyMarkupEdit>()
    private val sendRequests = mutableListOf<TelegramSendMessageRequest>()
    private val editReplyMarkups = mutableListOf<String?>()
    private val recordedApiCalls = mutableListOf<String>()
    private val callbackAnswers = mutableListOf<TelegramCallbackAnswer>()
    private var nextUpdateId = 1L
    private var nextIncomingMessageId = 100L
    private var nextBotMessageId = firstBotMessageId
    private var nextEditMessageTextResponse: Pair<Int, String>? = null
    private var nextEditMessageTextPause: (() -> Unit)? = null
    private var nextSendMessageError: String? = null
    private var nextSendMessagePause: (() -> Unit)? = null
    private var nextDeleteMessageError: String? = null
    private var nextEditReplyMarkupError: String? = null

    val replyMarkupEdits: List<TelegramReplyMarkupEdit>
        get() = markupEdits.toList()

    fun failNextEditMessageReplyMarkup(description: String) {
        check(nextEditReplyMarkupError == null) { "An editMessageReplyMarkup error is already pending" }
        nextEditReplyMarkupError = description
    }

    val deletedMessages: List<DeletedTelegramMessage>
        get() = deletions.toList()

    val deleteMessageAttempts: List<TelegramDeleteAttempt>
        get() = deletionAttempts.toList()

    val editedMessages: List<EditedTelegramMessage>
        get() = edits.toList()

    val sendMessageRequests: List<TelegramSendMessageRequest>
        get() = sendRequests.toList()

    val editedReplyMarkups: List<String?>
        get() = editReplyMarkups.toList()

    val answerCallbackQueryRequests: List<TelegramCallbackAnswer>
        get() = callbackAnswers.toList()

    val apiCalls: List<String>
        get() = recordedApiCalls.toList()

    fun currentMessage(messageId: TelegramMessageId): EditedTelegramMessage = currentMessages.getValue(messageId)

    fun failNextSendMessage(description: String) {
        check(nextSendMessageError == null) { "A sendMessage error is already pending" }
        nextSendMessageError = description
    }

    fun beforeNextSendMessageResponse(action: () -> Unit) {
        check(nextSendMessagePause == null) { "A sendMessage pause is already pending" }
        nextSendMessagePause = action
    }

    fun failNextDeleteMessage(description: String) {
        check(nextDeleteMessageError == null) { "A deleteMessage error is already pending" }
        nextDeleteMessageError = description
    }

    fun failNextEditMessageText(description: String) {
        respondToNextEditMessageText(
            statusCode = 400,
            body = """{"ok":false,"error_code":400,"description":${JsonPrimitive(description)}}""",
        )
    }

    fun beforeNextEditMessageTextResponse(action: () -> Unit) {
        check(nextEditMessageTextPause == null) { "An editMessageText pause is already pending" }
        nextEditMessageTextPause = action
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
    ): CallbackInteraction {
        val currentQuestion = currentMessage(question.messageId)
        val button = currentQuestion.inlineKeyboard.flatten().singleOrNull { it.text == buttonText }
        requireNotNull(button) { "Button '$buttonText' is not present on the current message" }
        val callbackId = "test-callback-$nextUpdateId"
        val callbackData = button.callbackData + callbackDataSuffix
        return deliverCallbackAndCollectReplies(
            callbackUpdate(currentQuestion, callbackData, senderId, callbackId, callbackChatId, callbackChatType),
        )
    }

    fun captureCallback(
        question: EditedTelegramMessage,
        buttonText: String,
        senderId: TelegramUserId = TelegramUserId(DEFAULT_SENDER_ID),
    ): CallbackQuery {
        val button = question.inlineKeyboard.flatten().single { it.text == buttonText }
        return checkNotNull(
            callbackUpdate(
                question,
                button.callbackData,
                senderId.value,
                "test-callback-$nextUpdateId",
                question.chatId.value,
                chatType,
            ).callbackQuery,
        )
    }

    suspend fun deliverCallbackAndCollectReplies(callback: CallbackQuery): CallbackInteraction =
        deliverCallbackAndCollectReplies(Update(updateId = nextUpdateId++, callbackQuery = callback))

    private suspend fun deliverCallbackAndCollectReplies(update: Update): CallbackInteraction =
        withTimeout(UPDATE_TIMEOUT_MILLIS) {
            val callback = checkNotNull(update.callbackQuery)
            bot.processUpdate(update)
            processedUpdates.receive()
            throwRecordedFailures()
            CallbackInteraction(
                replies = collectReplies(),
                acknowledged = answeredCallbacks.tryReceive().getOrNull() == callback.id,
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
                check(method in SUPPORTED_API_METHODS) {
                    "Test bot does not support $method"
                }
                recordedApiCalls += method
                val body = request.body as FormBody
                val fields = (0 until body.size).associate { body.name(it) to body.value(it) }
                var responseCode = 200
                val responseBody =
                    when (method) {
                        "sendMessage" -> {
                            val (statusCode, body) = sendMessageResult(fields)
                            responseCode = statusCode
                            body
                        }

                        "editMessageText" -> {
                            val (statusCode, body) = editMessageTextResult(fields)
                            responseCode = statusCode
                            body
                        }

                        "answerCallbackQuery" -> {
                            callbackAnswers +=
                                TelegramCallbackAnswer(fields.getValue("callback_query_id"), fields["text"])
                            answeredCallbacks.trySend(fields.getValue("callback_query_id")).getOrThrow()
                            """{"ok":true,"result":true}"""
                        }

                        "editMessageReplyMarkup" -> {
                            val (statusCode, body) = editMessageReplyMarkupResult(fields)
                            responseCode = statusCode
                            body
                        }

                        "deleteMessage" -> {
                            val (statusCode, body) = deleteMessageResult(fields)
                            responseCode = statusCode
                            body
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

    private fun editMessageTextResult(fields: Map<String, String>): Pair<Int, String> {
        val editedMessageResponse = editMessageTextResponse(fields)
        val configuredResponse = nextEditMessageTextResponse
        nextEditMessageTextResponse = null
        val result =
            if (configuredResponse == null) {
                currentMessages[edits.last().messageId] = edits.last()
                200 to editedMessageResponse
            } else {
                configuredResponse
            }
        val pause = nextEditMessageTextPause
        nextEditMessageTextPause = null
        pause?.invoke()
        return result
    }

    private fun deleteMessageResult(fields: Map<String, String>): Pair<Int, String> {
        val error = nextDeleteMessageError
        nextDeleteMessageError = null
        val attempt =
            TelegramDeleteAttempt(
                TelegramChatId(fields.getValue("chat_id").toLong()),
                TelegramMessageId(fields.getValue("message_id").toLong()),
                succeeded = error == null,
            )
        deletionAttempts += attempt
        return if (error == null) {
            deletions += DeletedTelegramMessage(attempt.chatId.value, attempt.messageId.value)
            200 to """{"ok":true,"result":true}"""
        } else {
            400 to """{"ok":false,"error_code":400,"description":${JsonPrimitive(error)}}"""
        }
    }

    private fun editMessageReplyMarkupResult(fields: Map<String, String>): Pair<Int, String> {
        val error = nextEditReplyMarkupError
        nextEditReplyMarkupError = null
        val edit =
            TelegramReplyMarkupEdit(
                TelegramChatId(fields.getValue("chat_id").toLong()),
                TelegramMessageId(fields.getValue("message_id").toLong()),
                readInlineKeyboard(fields),
                succeeded = error == null,
            )
        markupEdits += edit
        return if (error == null) {
            val previous = currentMessages.getValue(edit.messageId)
            currentMessages[edit.messageId] = previous.copy(inlineKeyboard = edit.inlineKeyboard)
            200 to
                """
                {"ok":true,"result":{"message_id":${edit.messageId.value},"date":0,
                "chat":{"id":${edit.chatId.value},"type":"$chatType"}}}
                """.trimIndent()
        } else {
            400 to """{"ok":false,"error_code":400,"description":${JsonPrimitive(error)}}"""
        }
    }

    private fun editMessageTextResponse(fields: Map<String, String>): String {
        editReplyMarkups += fields["reply_markup"]
        val editedMessage =
            EditedTelegramMessage(
                chatId = TelegramChatId(fields.getValue("chat_id").toLong()),
                messageId = TelegramMessageId(fields.getValue("message_id").toLong()),
                text = fields.getValue("text"),
                inlineKeyboard = readInlineKeyboard(fields),
                parseMode = fields["parse_mode"],
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

    private fun sendMessageResult(fields: Map<String, String>): Pair<Int, String> {
        val request =
            TelegramSendMessageRequest(
                TelegramChatId(fields.getValue("chat_id").toLong()),
                fields.getValue("text"),
                readInlineKeyboard(fields),
                parseMode = fields["parse_mode"],
            )
        sendRequests += request
        val error = nextSendMessageError
        nextSendMessageError = null
        val result =
            if (error == null) {
                200 to sendMessageResponse(request)
            } else {
                400 to """{"ok":false,"error_code":400,"description":${JsonPrimitive(error)}}"""
            }
        val pause = nextSendMessagePause
        nextSendMessagePause = null
        pause?.invoke()
        return result
    }

    private fun sendMessageResponse(request: TelegramSendMessageRequest): String {
        val keyboard = request.inlineKeyboard
        val messageId = nextBotMessageId++
        val message =
            SentTelegramMessage(
                chatId = request.chatId.value,
                text = request.text,
                inlineButtonTexts = keyboard.flatten().map { it.text },
            ).apply { this.messageId = messageId }
        val typedMessageId = TelegramMessageId(messageId)
        currentMessages[typedMessageId] =
            EditedTelegramMessage(
                TelegramChatId(message.chatId),
                typedMessageId,
                message.text,
                keyboard,
                request.parseMode,
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
    val parseMode: String? = null,
)

internal data class TelegramDeleteAttempt(
    val chatId: TelegramChatId,
    val messageId: TelegramMessageId,
    val succeeded: Boolean,
)

internal data class TelegramReplyMarkupEdit(
    val chatId: TelegramChatId,
    val messageId: TelegramMessageId,
    val inlineKeyboard: List<List<EditedTelegramInlineButton>>,
    val succeeded: Boolean,
)

internal data class TelegramSendMessageRequest(
    val chatId: TelegramChatId,
    val text: String,
    val inlineKeyboard: List<List<EditedTelegramInlineButton>>,
    val parseMode: String? = null,
)

internal data class EditedTelegramInlineButton(
    val text: String,
    val callbackData: String,
)

internal data class TelegramCallbackAnswer(
    val callbackQueryId: String,
    val text: String?,
)

internal data class CallbackInteraction(
    val replies: List<SentTelegramMessage>,
    val acknowledged: Boolean,
)

internal fun EditedTelegramMessage.withoutQuestionToken(): EditedTelegramMessage {
    val tokens = mutableSetOf<String>()
    val keyboard =
        inlineKeyboard.map { row ->
            row.map { button ->
                val token = button.callbackData.substringAfterLast(':')
                val tokenFormat = Regex("[A-Za-z0-9_-]{8}\\.[A-Za-z0-9_-]{13}")
                check(token.matches(tokenFormat)) { "Invalid question token: $token" }
                check(button.callbackData.toByteArray(Charsets.UTF_8).size <= 64) {
                    "Callback payload exceeds 64 bytes"
                }
                tokens += token
                button.copy(callbackData = button.callbackData.substringBeforeLast(':'))
            }
        }
    check(tokens.size <= 1) { "Buttons of one question must share a token" }
    return copy(inlineKeyboard = keyboard)
}

internal val EXPECTED_CANCEL_ROW = listOf(EditedTelegramInlineButton("Отменить тренировку", "training:cancel"))

internal fun trainingQuestion(
    chatId: TelegramChatId,
    messageId: TelegramMessageId,
    text: String,
    inlineKeyboard: List<List<EditedTelegramInlineButton>> = emptyList(),
): EditedTelegramMessage = EditedTelegramMessage(chatId, messageId, text, inlineKeyboard + listOf(EXPECTED_CANCEL_ROW))

internal fun sentTrainingQuestion(
    chatId: Long,
    text: String,
    inlineButtonTexts: List<String> = emptyList(),
): SentTelegramMessage = SentTelegramMessage(chatId, text, inlineButtonTexts + "Отменить тренировку")
