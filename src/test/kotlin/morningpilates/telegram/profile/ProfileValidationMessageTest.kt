package morningpilates.telegram.profile

import kotlinx.coroutines.runBlocking
import morningpilates.profile.AthleteProfile
import morningpilates.profile.BjjBelt
import morningpilates.profile.FakeProfileRepository
import morningpilates.profile.ProfileField
import morningpilates.telegram.api.TelegramMessageId
import morningpilates.telegram.support.TestTelegramBot
import morningpilates.user.TelegramUserId
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProfileValidationMessageTest {
    @ParameterizedTest
    @EnumSource(ProfileField::class)
    fun `invalid field shows one instruction and error while keeping data and navigation`(field: ProfileField) =
        runBlocking {
            val userId = TelegramUserId(42L)
            val profiles = FakeProfileRepository()
            val original =
                AthleteProfile(
                    firstName = "Анна",
                    lastName = "Иванова",
                    birthDate = LocalDate.of(2000, 1, 1),
                    weight = BigDecimal("70"),
                    belt = BjjBelt.Blue,
                    rankMarks = 1,
                    beltDate = LocalDate.of(2020, 1, 1),
                    bjjStart = YearMonth.of(2019, 1),
                )
            profiles.profiles[userId] = original
            val interaction = ProfileInteraction(profiles, diaryBusy = { false }, closeTrainingView = { _, _ -> })
            TestTelegramBot(chatId = userId.value) { registerProfileHandlers(interaction) }.use { bot ->
                val sent = bot.sendCommandAndCollectReplies("/profile").single()
                val cardId = TelegramMessageId(sent.messageId)
                bot.pressInlineButtonAndCollectReplies(bot.currentMessage(cardId), "Редактировать")
                bot.pressInlineButtonAndCollectReplies(bot.currentMessage(cardId), field.title(original.belt))
                assertFalse(bot.currentMessage(cardId).text.contains("Например:"))
                val actions =
                    bot
                        .currentMessage(cardId)
                        .inlineKeyboard
                        .flatten()
                        .map { it.text }
                assertTrue(bot.sendTextAndCollectReplies(field.invalidExample(), messageId = 901).isEmpty())
                val current = bot.currentMessage(cardId)
                assertTrue(current.text.contains("⚠️ Некоррект"))
                assertEquals(1, Regex("⚠️").findAll(current.text).count())
                assertEquals(1, Regex("Введите|Выберите").findAll(current.text).count())
                assertFalse(current.text.contains("Например:"))
                assertEquals(original, profiles.profiles[userId])
                assertEquals(0, profiles.saves)
                assertEquals(actions, current.inlineKeyboard.flatten().map { it.text })
                assertTrue(listOf("Назад", "Очистить").all { it in actions })
                assertTrue(bot.deletedMessages.any { it.messageId == 901L })
                assertEquals(1, bot.sendMessageRequests.size)
                assertTrue(bot.editedMessages.all { it.messageId == cardId })
                assertTrue(bot.sendTextAndCollectReplies(field.validExample(), messageId = 902).isEmpty())
                assertEquals(1, profiles.saves)
                val corrected = bot.currentMessage(cardId)
                assertTrue(corrected.inlineKeyboard.flatten().any { it.text == "Завершить редактирование" })
                assertFalse(corrected.text.contains("⚠️"))
            }
        }
}

private fun ProfileField.invalidExample(): String =
    when (this) {
        ProfileField.FirstName, ProfileField.LastName -> "Я".repeat(51)
        ProfileField.BirthDate, ProfileField.BeltDate -> "31.02.2020"
        ProfileField.Weight -> "150.01"
        ProfileField.Belt -> "Orange"
        ProfileField.RankMarks -> "5"
        ProfileField.BjjStart -> "13.2020"
    }

private fun ProfileField.validExample(): String =
    when (this) {
        ProfileField.FirstName -> "Иван"
        ProfileField.LastName -> "Петров"
        ProfileField.BirthDate, ProfileField.BeltDate -> "15.06.2000"
        ProfileField.Weight -> "85.5"
        ProfileField.Belt -> "Purple"
        ProfileField.RankMarks -> "2"
        ProfileField.BjjStart -> "03.2020"
    }
