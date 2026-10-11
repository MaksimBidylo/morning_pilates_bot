package morningpilates.telegram.command

import kotlinx.coroutines.runBlocking
import morningpilates.telegram.TestTelegramBot
import kotlin.test.Test
import kotlin.test.assertEquals

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
        TestTelegramBot(
            chatId = chatId,
            chatType = chatType,
            registerCommands = { registerStartCommand() },
        ).use { bot ->
            val reply = bot.sendCommand("/start")

            assertEquals(chatId, reply.chatId)
            assertEquals("Morning Pilates Bot работает 🥋", reply.text)
        }
    }
}
