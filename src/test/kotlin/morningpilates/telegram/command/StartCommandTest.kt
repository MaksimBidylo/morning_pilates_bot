package morningpilates.telegram.command

import com.github.kotlintelegrambot.bot
import com.github.kotlintelegrambot.dispatch
import com.github.kotlintelegrambot.webhook
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.FormBody
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class StartCommandTest {
    @Test
    fun `start in private chat sends greeting to original chat`() {
        assertStartReply(chatId = 123456789L, chatType = "private")
    }

    @Test
    fun `start in group chat sends greeting to original negative chat ID`() {
        assertStartReply(chatId = -987654321L, chatType = "group")
    }

    private fun assertStartReply(
        chatId: Long,
        chatType: String,
    ) = runBlocking {
        val outgoingRequest = CompletableDeferred<Request>()
        val telegramApi =
            Interceptor { chain ->
                val request = chain.request()
                outgoingRequest.complete(request)
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
        val bot =
            bot {
                token = "test-token"
                apiUrl = "https://telegram.invalid/"
                coroutineDispatcher = Dispatchers.Default
                httpClientInterceptors = listOf(telegramApi)
                webhook {
                    url = "https://bot.invalid/webhook"
                    createOnStart = false
                }
                dispatch {
                    registerStartCommand()
                }
            }

        try {
            assertTrue(bot.startWebhook())
            val request =
                withTimeout(5_000) {
                    bot.processUpdate(
                        """
                        {"update_id":1,"message":{"message_id":1,"date":0,
                          "from":{"id":42,"is_bot":false,"first_name":"Test"},
                          "chat":{"id":$chatId,"type":"$chatType"},
                          "text":"/start",
                          "entities":[{"type":"bot_command","offset":0,"length":6}]}}
                        """.trimIndent(),
                    )
                    outgoingRequest.await()
                }

            assertEquals("POST", request.method)
            assertEquals("/bottest-token/sendMessage", request.url.encodedPath)
            val body = assertIs<FormBody>(request.body)
            val fields = (0 until body.size).associate { body.name(it) to body.value(it) }
            assertEquals(chatId.toString(), fields["chat_id"])
            assertEquals("Morning Pilates Bot работает 🥋", fields["text"])
        } finally {
            bot.stopPolling()
        }
    }
}
