package morningpilates.telegram

import com.github.kotlintelegrambot.dispatcher.command
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private const val FAILING_COMMAND = "failing"

class TestTelegramBotTest {
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
