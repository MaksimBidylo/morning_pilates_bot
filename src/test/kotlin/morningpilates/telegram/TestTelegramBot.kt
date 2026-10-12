package morningpilates.telegram

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.Dispatcher
import com.github.kotlintelegrambot.dispatcher.handlers.Handler
import com.github.kotlintelegrambot.dispatcher.message
import com.github.kotlintelegrambot.entities.CallbackQuery
import com.github.kotlintelegrambot.entities.Chat
import com.github.kotlintelegrambot.entities.Message
import com.github.kotlintelegrambot.entities.MessageEntity
import com.github.kotlintelegrambot.entities.MessageEntity.Type.BOT_COMMAND
import com.github.kotlintelegrambot.entities.Update
import com.github.kotlintelegrambot.entities.User
import com.github.kotlintelegrambot.webhook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
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
    registerCommands: Dispatcher.() -> Unit,
) : AutoCloseable {
    constructor(
        telegramBot: TelegramBot,
        chatId: Long,
        chatType: String = "private",
    ) : this(
        chatId = chatId,
        chatType = chatType,
        registerCommands = { telegramBot.registeredHandlers().forEach(::addHandler) },
    )

    private val replies = Channel<Result<SentTelegramMessage>>(Channel.UNLIMITED)
    private val answeredCallbacks = Channel<String>(Channel.UNLIMITED)
    private val processedUpdates = Channel<Unit>(Channel.UNLIMITED)
    private val handlerFailures = Channel<Throwable>(Channel.UNLIMITED)
    private val callbackDataByMessage = mutableMapOf<SentTelegramMessage, Map<String, String>>()
    private var nextUpdateId = 1L
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
    ): SentTelegramMessage = sendCommandAndCollectReplies(command, senderId).single()

    suspend fun sendCommandAndCollectReplies(
        command: String,
        senderId: Long? = DEFAULT_SENDER_ID,
    ): List<SentTelegramMessage> = sendMessageAndCollectReplies(command, senderId, isCommand = true)

    suspend fun sendTextAndCollectReplies(
        text: String,
        senderId: Long? = DEFAULT_SENDER_ID,
    ): List<SentTelegramMessage> = sendMessageAndCollectReplies(text, senderId, isCommand = false)

    suspend fun pressInlineButtonAndCollectReplies(
        question: SentTelegramMessage,
        buttonText: String,
        senderId: Long = DEFAULT_SENDER_ID,
    ): CallbackInteraction =
        withTimeout(UPDATE_TIMEOUT_MILLIS) {
            require(buttonText in question.inlineButtonTexts)
            val callbackId = "test-callback-$nextUpdateId"
            val callbackData = callbackDataByMessage.getValue(question).getValue(buttonText)
            bot.processUpdate(callbackUpdate(question, callbackData, senderId, callbackId))
            processedUpdates.receive()
            handlerFailures.tryReceive().getOrNull()?.let { throw it }
            CallbackInteraction(
                replies = collectReplies(),
                acknowledged = answeredCallbacks.tryReceive().getOrNull() == callbackId,
            )
        }

    private suspend fun sendMessageAndCollectReplies(
        text: String,
        senderId: Long?,
        isCommand: Boolean,
    ): List<SentTelegramMessage> =
        withTimeout(UPDATE_TIMEOUT_MILLIS) {
            bot.processUpdate(messageUpdate(text, senderId, isCommand))
            processedUpdates.receive()
            handlerFailures.tryReceive().getOrNull()?.let { throw it }
            collectReplies()
        }

    private fun collectReplies(): List<SentTelegramMessage> =
        buildList {
            while (true) {
                val reply = replies.tryReceive().getOrNull() ?: break
                add(reply.getOrThrow())
            }
        }

    override fun close() {
        bot.stopPolling()
        replies.cancel()
        answeredCallbacks.cancel()
        processedUpdates.cancel()
        handlerFailures.cancel()
    }

    private fun messageUpdate(
        text: String,
        senderId: Long?,
        isCommand: Boolean,
    ): Update {
        val updateId = nextUpdateId++
        return Update(
            updateId = updateId,
            message =
                Message(
                    messageId = updateId,
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
        question: SentTelegramMessage,
        callbackData: String,
        senderId: Long,
        callbackId: String,
    ): Update =
        Update(
            updateId = nextUpdateId++,
            callbackQuery =
                CallbackQuery(
                    id = callbackId,
                    from = User(id = senderId, isBot = false, firstName = "Test"),
                    message =
                        Message(
                            messageId = 2,
                            date = 0,
                            chat = Chat(id = question.chatId, type = chatType),
                            text = question.text,
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
            val request = chain.request()
            val body = request.body as FormBody
            val fields = (0 until body.size).associate { body.name(it) to body.value(it) }
            val responseBody =
                when (request.url.pathSegments.last()) {
                    "sendMessage" -> {
                        val reply =
                            runCatching {
                                val buttons =
                                    fields["reply_markup"]
                                        ?.let { Json.parseToJsonElement(it).jsonObject["inline_keyboard"] }
                                        ?.jsonArray
                                        ?.flatMap { row -> row.jsonArray.map { button -> button.jsonObject } }
                                        .orEmpty()
                                val callbackData =
                                    buttons
                                        .mapNotNull { button ->
                                            val data =
                                                button["callback_data"]?.jsonPrimitive?.content
                                                    ?: return@mapNotNull null
                                            button.getValue("text").jsonPrimitive.content to data
                                        }.toMap()
                                val message =
                                    SentTelegramMessage(
                                        chatId = fields.getValue("chat_id").toLong(),
                                        text = fields.getValue("text"),
                                        inlineButtonTexts = buttons.map { it.getValue("text").jsonPrimitive.content },
                                    )
                                callbackDataByMessage[message] = callbackData
                                message
                            }
                        replies.trySend(reply).getOrThrow()
                        """{"ok":true,"result":{"message_id":2,"date":0,"chat":{"id":$chatId,"type":"$chatType"}}}"""
                    }

                    "answerCallbackQuery" -> {
                        answeredCallbacks.trySend(fields.getValue("callback_query_id")).getOrThrow()
                        """{"ok":true,"result":true}"""
                    }

                    else -> {
                        error("Test bot only supports sendMessage and answerCallbackQuery")
                    }
                }
            Response
                .Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(responseBody.toResponseBody("application/json".toMediaType()))
                .build()
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
)

internal data class CallbackInteraction(
    val replies: List<SentTelegramMessage>,
    val acknowledged: Boolean,
)
