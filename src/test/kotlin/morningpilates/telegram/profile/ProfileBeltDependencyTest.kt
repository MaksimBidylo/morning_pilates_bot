package morningpilates.telegram.profile

import kotlinx.coroutines.runBlocking
import morningpilates.infrastructure.InMemoryFlowStorage
import morningpilates.profile.AthleteProfile
import morningpilates.profile.FakeProfileRepository
import morningpilates.telegram.api.TelegramMessageId
import morningpilates.telegram.support.TestTelegramBot
import morningpilates.telegram.training.view.TrainingViewStorage
import morningpilates.user.TelegramUserId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileBeltDependencyTest {
    private val user = TelegramUserId(42L)
    private val profiles = FakeProfileRepository()
    private var cardId: TelegramMessageId? = null

    @Test
    fun `rank input and row are absent without belt and zero differs from empty with belt`() =
        runBlocking {
            bot().use { bot ->
                open(bot)
                assertFalse(current(bot).text.contains("Страйпы:"))
                assertFalse(current(bot).text.contains("Дан:"))
                press(bot, "Редактировать")
                assertFalse("Страйпы" in buttons(bot))
                assertFalse("Дан" in buttons(bot))
                assertFalse("Дата получения пояса" in buttons(bot))
                val callback = bot.captureCallback(current(bot), "Имя")
                bot.deliverCallbackAndCollectReplies(
                    callback.copy(data = callback.data.replace("FirstName:", "RankMarks:")),
                )
                bot.deliverCallbackAndCollectReplies(
                    callback.copy(data = callback.data.replace("FirstName:", "BeltDate:")),
                )
                assertEquals(0, profiles.saves)
                assertTrue("Завершить редактирование" in buttons(bot))
                press(bot, "Пояс BJJ")
                press(bot, "Белый")
                assertTrue("Дата получения пояса" in buttons(bot))
                assertTrue(current(bot).text.contains("Страйпы: —"))
                press(bot, "Страйпы")
                press(bot, "0")
                assertTrue(current(bot).text.contains("Страйпы: 0"))
                assertEquals(0, profiles.profiles[user]?.rankMarks)
                press(bot, "Страйпы")
                press(bot, "Очистить")
                assertTrue(current(bot).text.contains("Страйпы: —"))
                assertNull(profiles.profiles[user]?.rankMarks)
            }
        }

    @Test
    fun `clearing belt hides date and invalidates old date callback`() =
        runBlocking {
            bot().use { bot ->
                open(bot)
                press(bot, "Редактировать")
                press(bot, "Пояс BJJ")
                press(bot, "Синий")
                val oldDate = bot.captureCallback(current(bot), "Дата получения пояса")
                press(bot, "Дата получения пояса")
                bot.sendTextAndCollectReplies("01.01.2020")
                assertTrue("Дата получения пояса" in buttons(bot))
                press(bot, "Пояс BJJ")
                press(bot, "Очистить")
                assertFalse("Дата получения пояса" in buttons(bot))
                assertEquals(AthleteProfile(), profiles.profiles[user])
                val saved = profiles.saves
                assertTrue(bot.deliverCallbackAndCollectReplies(oldDate).acknowledged)
                assertEquals(saved, profiles.saves)
                assertTrue("Завершить редактирование" in buttons(bot))
                assertEquals(1, bot.sendMessageRequests.size)
            }
        }

    private fun current(bot: TestTelegramBot) = bot.currentMessage(checkNotNull(cardId))

    private fun buttons(bot: TestTelegramBot) = current(bot).inlineKeyboard.flatten().map { it.text }

    private suspend fun open(bot: TestTelegramBot) {
        cardId = TelegramMessageId(bot.sendCommandAndCollectReplies("/profile").single().messageId)
    }

    private suspend fun press(
        bot: TestTelegramBot,
        title: String,
    ) {
        assertTrue(bot.pressInlineButtonAndCollectReplies(current(bot), title).acknowledged)
    }

    private fun bot(): TestTelegramBot {
        val interaction = ProfileInteraction(profiles, InMemoryFlowStorage(), TrainingViewStorage())
        return TestTelegramBot(chatId = user.value) { registerProfileHandlers(interaction) }
    }
}
