package morningpilates.telegram

import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.dispatcher.Dispatcher
import com.github.kotlintelegrambot.webhook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import okhttp3.FormBody
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

internal class TestTelegramBot(
    private val chatId: Long,
    private val chatType: String = "private",
    registerCommands: Dispatcher.() -> Unit,
) : AutoCloseable {
    private val replies = Channel<Result<SentTelegramMessage>>(Channel.UNLIMITED)
    private var nextUpdateId = 1
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
            dispatch(registerCommands)
        }

    init {
        check(bot.startWebhook()) { "Could not start test bot dispatcher" }
    }

    suspend fun sendCommand(
        command: String,
        senderId: Long = 42L,
    ): SentTelegramMessage =
        withTimeout(5_000) {
            val updateId = nextUpdateId++
            bot.processUpdate(
                """
                {"update_id":$updateId,"message":{"message_id":$updateId,"date":0,
                  "from":{"id":$senderId,"is_bot":false,"first_name":"Test"},
                  "chat":{"id":$chatId,"type":"$chatType"},
                  "text":"$command",
                  "entities":[{"type":"bot_command","offset":0,"length":${command.length}}]}}
                """.trimIndent(),
            )
            replies.receive().getOrThrow()
        }

    override fun close() {
        bot.stopPolling()
        replies.cancel()
    }

    private fun telegramApiInterceptor(): Interceptor =
        Interceptor { chain ->
            val request = chain.request()
            val reply =
                runCatching {
                    check(request.url.pathSegments.last() == "sendMessage") {
                        "Test bot only supports sendMessage"
                    }
                    val body = request.body as FormBody
                    val fields = (0 until body.size).associate { body.name(it) to body.value(it) }
                    SentTelegramMessage(fields.getValue("chat_id").toLong(), fields.getValue("text"))
                }
            replies.trySend(reply).getOrThrow()
            Response
                .Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(
                    """
                    {"ok":true,"result":{"message_id":2,"date":0,
                      "chat":{"id":$chatId,"type":"$chatType"}}}
                    """.trimIndent().toResponseBody("application/json".toMediaType()),
                ).build()
        }
}

internal data class SentTelegramMessage(
    val chatId: Long,
    val text: String,
)
