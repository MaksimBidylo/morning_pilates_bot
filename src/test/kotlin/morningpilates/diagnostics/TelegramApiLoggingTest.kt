package morningpilates.diagnostics

import kotlinx.coroutines.runBlocking
import morningpilates.telegram.logging.TelegramApiLogging
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TelegramApiLoggingTest {
    @ParameterizedTest
    @ValueSource(strings = ["api_error", "invalid_body", "transport", "http_error"])
    fun `API and transport errors cannot become success even when caller ignores them`(mode: String) =
        runBlocking {
            val body =
                when (mode) {
                    "api_error" -> """{"ok":false,"description":"private-canary"}"""
                    "invalid_body" -> "private-canary"
                    else -> """{"ok":true,"result":true}"""
                }
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor(TelegramApiLogging())
                    .addInterceptor(
                        Interceptor { chain ->
                            if (mode == "transport") throw IOException("https://private-canary/bot-private-token")
                            Response
                                .Builder()
                                .request(chain.request())
                                .protocol(Protocol.HTTP_1_1)
                                .code(if (mode == "http_error") 503 else 200)
                                .message("private-canary")
                                .body(body.toResponseBody())
                                .build()
                        },
                    ).build()
            LogCapture().use { logs ->
                RequestDiagnostics.process("command") {
                    runCatching {
                        client
                            .newCall(
                                Request.Builder().url("https://telegram.invalid/bot-private-token/sendMessage").build(),
                            ).execute()
                            .use { assertEquals(body, it.body?.string()) }
                    }
                    RequestDiagnostics.success()
                }
                assertTrue(logs.completions.single().contains("result=technical_failure"))
                assertFalse(logs.rendered.contains("private-canary"))
                assertFalse(logs.rendered.contains("private-token"))
            }
        }

    @Test
    fun `polling errors use safe SDK error hook without inventing a request correlation`() {
        LogCapture().use { logs ->
            RequestDiagnostics.pollingFailure()
            assertEquals(listOf("event=telegram_polling_failure result=technical_failure"), logs.messages)
        }
    }
}
