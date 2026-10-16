package morningpilates.telegram.profile

import kotlinx.coroutines.runBlocking
import morningpilates.bootstrap.createTrainingFlowSessionManager
import morningpilates.config.telegram.TelegramProperties
import morningpilates.infrastructure.InMemoryFlowStorage
import morningpilates.profile.AthleteProfile
import morningpilates.profile.BjjBelt
import morningpilates.profile.FakeProfileRepository
import morningpilates.telegram.TelegramBot
import morningpilates.telegram.api.TelegramMessageId
import morningpilates.telegram.support.SentTelegramMessage
import morningpilates.telegram.support.TestTelegramBot
import morningpilates.telegram.training.creation.TrainingCreationMessageSessionStorage
import morningpilates.training.FakeTrainingRepository
import morningpilates.training.TrainingQueryService
import morningpilates.user.TelegramUserId
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileScenarioTest {
    private val user = TelegramUserId(42L)
    private val profiles = FakeProfileRepository()
    private val trainings = FakeTrainingRepository()
    private val flows = InMemoryFlowStorage()

    @Test
    fun `new user opens empty profile and saves just a name`() =
        runBlocking {
            bot().use { bot ->
                val profile = bot.sendCommandAndCollectReplies("/profile").single()
                assertTrue(profile.text.contains("Имя: —"))
                assertTrue(profiles.profiles.isEmpty())
                assertEquals(9, profile.inlineButtonTexts.size)
                bot.pressInlineButtonAndCollectReplies(profile, "Имя")
                val saved = bot.sendTextAndCollectReplies("Анна").single()
                assertTrue(saved.text.contains("Имя: Анна"))
                assertEquals(AthleteProfile(firstName = "Анна"), profiles.profiles[user])
                assertTrue(trainings.saveCalls.isEmpty())
            }
        }

    @ParameterizedTest
    @CsvSource(
        "Имя, Иван",
        "Фамилия, Иванов",
        "Дата рождения, 29.02.2000",
        "Вес, 85.25",
        "Дата получения пояса, 01.01.2020",
        "Начало занятий BJJ, 01.2019",
    )
    fun `text fields can be saved changed and cleared`(
        field: String,
        value: String,
    ) = runBlocking {
        bot().use { bot ->
            edit(bot, field)
            bot.sendTextAndCollectReplies(value).single()
            assertEquals(1, profiles.saves)
            edit(bot, field)
            bot.sendTextAndCollectReplies(if (field in listOf("Имя", "Фамилия")) "Другое" else value).single()
            val prompt = edit(bot, field)
            bot.pressInlineButtonAndCollectReplies(prompt, "Очистить значение")
            assertEquals(AthleteProfile(), profiles.profiles[user])
            assertEquals(3, profiles.saves)
        }
    }

    @Test
    fun `belt and stripes are chosen by buttons and belt change clears its date`() =
        runBlocking {
            profiles.profiles[user] = AthleteProfile(belt = BjjBelt.White, beltDate = LocalDate.of(2020, 1, 1))
            bot().use { bot ->
                val prompt = edit(bot, "Пояс BJJ")
                bot.pressInlineButtonAndCollectReplies(prompt, "Синий")
                assertEquals(BjjBelt.Blue, profiles.profiles[user]?.belt)
                assertNull(profiles.profiles[user]?.beltDate)
                val stripes = edit(bot, "Полоски")
                bot.pressInlineButtonAndCollectReplies(stripes, "4")
                assertEquals(4, profiles.profiles[user]?.stripes)
                val clear = edit(bot, "Полоски")
                bot.pressInlineButtonAndCollectReplies(clear, "Очистить значение")
                assertNull(profiles.profiles[user]?.stripes)
                val clearBelt = edit(bot, "Пояс BJJ")
                bot.pressInlineButtonAndCollectReplies(clearBelt, "Очистить значение")
                assertNull(profiles.profiles[user]?.belt)
            }
        }

    @Test
    fun `invalid input leaves data intact and can be corrected or cancelled`() =
        runBlocking {
            profiles.profiles[user] = AthleteProfile(firstName = "Анна")
            bot().use { bot ->
                edit(bot, "Дата рождения")
                val invalid = bot.sendTextAndCollectReplies("31.02.2000").single()
                assertTrue(invalid.text.contains("существующую дату"))
                assertEquals(0, profiles.saves)
                bot.sendTextAndCollectReplies("01.01.2000").single()
                val prompt = edit(bot, "Имя")
                bot.pressInlineButtonAndCollectReplies(prompt, "Отменить редактирование")
                assertEquals("Анна", profiles.profiles[user]?.firstName)
                assertEquals(1, profiles.saves)
            }
        }

    @Test
    fun `buttons are bound to owner chat message token and allowed actions`() =
        runBlocking {
            bot().use { bot ->
                val profile = bot.sendCommandAndCollectReplies("/profile").single()
                for (interaction in listOf(
                    bot.pressInlineButtonAndCollectReplies(profile, "Имя", senderId = 43),
                    bot.pressInlineButtonAndCollectReplies(profile, "Имя", callbackChatId = 43),
                    bot.pressInlineButtonAndCollectReplies(profile, "Имя", callbackChatType = "group"),
                    bot.pressInlineButtonAndCollectReplies(profile, "Имя", callbackDataSuffix = "bad"),
                )) {
                    assertTrue(interaction.acknowledged)
                    assertTrue(interaction.replies.isEmpty())
                }
                val old = bot.captureCallback(bot.currentMessage(TelegramMessageId(profile.messageId)), "Имя")
                bot.sendCommandAndCollectReplies("/profile")
                assertTrue(bot.deliverCallbackAndCollectReplies(old).replies.isEmpty())
                assertEquals(0, profiles.saves)
            }
        }

    @Test
    fun `duplicate clear cannot erase later input`() =
        runBlocking {
            bot().use { bot ->
                val prompt = edit(bot, "Имя")
                val clear =
                    bot.captureCallback(
                        bot.currentMessage(TelegramMessageId(prompt.messageId)),
                        "Очистить значение",
                    )
                bot.deliverCallbackAndCollectReplies(clear)
                edit(bot, "Имя")
                bot.sendTextAndCollectReplies("Новое имя")
                assertTrue(bot.deliverCallbackAndCollectReplies(clear).replies.isEmpty())
                assertEquals("Новое имя", profiles.profiles[user]?.firstName)
                assertEquals(2, profiles.saves)
            }
        }

    @Test
    fun `profile refuses active creation and date search without consuming diary input`() =
        runBlocking {
            bot().use { bot ->
                bot.sendCommandAndCollectReplies("/training_add")
                val session = flows.get(user)
                assertTrue(
                    bot
                        .sendCommandAndCollectReplies("/profile")
                        .single()
                        .text
                        .contains("дневника"),
                )
                assertEquals(session, flows.get(user))
                bot.sendTextAndCollectReplies("15.10.2026")
                assertNotNull(flows.get(user))
                bot.sendCommandAndCollectReplies("/cancel_all")
                bot.sendCommandAndCollectReplies("/training_get_date")
                assertTrue(
                    bot
                        .sendCommandAndCollectReplies("/profile")
                        .single()
                        .text
                        .contains("дневника"),
                )
                bot.sendCommandAndCollectReplies("/cancel")
                assertTrue(
                    bot
                        .sendCommandAndCollectReplies("/profile")
                        .single()
                        .text
                        .contains("Личный профиль"),
                )
            }
        }

    @Test
    fun `diary commands refuse profile input and cancellation releases it`() =
        runBlocking {
            bot().use { bot ->
                edit(bot, "Имя")
                for (command in listOf("/training_add", "/training_get_date", "/training_get_last")) {
                    assertTrue(
                        bot
                            .sendCommandAndCollectReplies(command)
                            .single()
                            .text
                            .contains("редактирование профиля"),
                    )
                }
                assertNull(flows.get(user))
                assertTrue(trainings.findLatestCalls.isEmpty())
                assertTrue(trainings.findByDateCalls.isEmpty())
                bot.sendTextAndCollectReplies("Анна").single()
                bot.sendCommandAndCollectReplies("/training_add")
                assertNotNull(flows.get(user))
            }
        }

    @ParameterizedTest
    @CsvSource("/cancel", "/cancel_all")
    fun `cancel commands discard edit and invalidate buttons`(command: String) =
        runBlocking {
            bot().use { bot ->
                val prompt = edit(bot, "Имя")
                val callback =
                    bot.captureCallback(
                        bot.currentMessage(TelegramMessageId(prompt.messageId)),
                        "Очистить значение",
                    )
                bot.sendCommandAndCollectReplies(command)
                assertTrue(bot.deliverCallbackAndCollectReplies(callback).replies.isEmpty())
                assertEquals(0, profiles.saves)
                bot.sendCommandAndCollectReplies("/training_add")
                assertNotNull(flows.get(user))
            }
        }

    @Test
    fun `storage errors preserve edit for retry and display failure cannot repeat committed action`() =
        runBlocking {
            bot().use { bot ->
                val prompt = edit(bot, "Имя")
                profiles.saveFailure = true
                assertTrue(
                    bot
                        .sendTextAndCollectReplies("Анна")
                        .single()
                        .text
                        .contains("базе данных"),
                )
                assertEquals(0, profiles.saves)
                profiles.saveFailure = false
                val clear =
                    bot.captureCallback(
                        bot.currentMessage(TelegramMessageId(prompt.messageId)),
                        "Очистить значение",
                    )
                bot.failNextSendMessage("Unavailable")
                assertTrue(bot.sendTextAndCollectReplies("Анна").isEmpty())
                assertEquals("Анна", profiles.profiles[user]?.firstName)
                assertTrue(bot.deliverCallbackAndCollectReplies(clear).replies.isEmpty())
                assertTrue(
                    bot
                        .sendCommandAndCollectReplies("/profile")
                        .single()
                        .text
                        .contains("Имя: Анна"),
                )
            }
        }

    @Test
    fun `read failure does not present an empty profile`() =
        runBlocking {
            profiles.readFailure = true
            bot().use { bot ->
                assertTrue(
                    bot
                        .sendCommandAndCollectReplies("/profile")
                        .single()
                        .text
                        .contains("базе данных"),
                )
                assertEquals(0, profiles.saves)
            }
        }

    @Test
    fun `restart rejects old buttons and opens saved data`() =
        runBlocking {
            val old =
                bot().use { bot ->
                    val prompt = edit(bot, "Имя")
                    val callback =
                        bot.captureCallback(
                            bot.currentMessage(TelegramMessageId(prompt.messageId)),
                            "Очистить значение",
                        )
                    bot.sendTextAndCollectReplies("Анна")
                    callback
                }
            bot().use { bot ->
                val stale = bot.deliverCallbackAndCollectReplies(old)
                assertTrue(stale.acknowledged)
                assertTrue(stale.replies.isEmpty())
                assertTrue(
                    bot
                        .sendCommandAndCollectReplies("/profile")
                        .single()
                        .text
                        .contains("Имя: Анна"),
                )
                assertEquals(1, profiles.saves)
            }
        }

    @Test
    fun `private chat only and users remain isolated`() =
        runBlocking {
            bot("group").use { bot ->
                assertTrue(bot.sendCommandAndCollectReplies("/profile").isEmpty())
            }
            bot().use { bot ->
                edit(bot, "Имя")
                assertTrue(
                    bot
                        .sendTextAndCollectReplies("Чужое", senderId = 43)
                        .single()
                        .text
                        .contains("нет активного действия"),
                )
                assertEquals(0, profiles.saves)
                bot.sendTextAndCollectReplies("Своё")
                assertTrue(
                    bot
                        .sendCommandAndCollectReplies("/profile", senderId = 43)
                        .single()
                        .text
                        .contains("Имя: —"),
                )
            }
        }

    private suspend fun edit(
        bot: TestTelegramBot,
        field: String,
    ): SentTelegramMessage {
        val profile = bot.sendCommandAndCollectReplies("/profile").single()
        return bot.pressInlineButtonAndCollectReplies(profile, field).replies.single()
    }

    private fun bot(chatType: String = "private"): TestTelegramBot =
        TestTelegramBot(
            TelegramBot(
                profiles,
                TelegramProperties("test-token"),
                flows,
                createTrainingFlowSessionManager(flows, trainings),
                TrainingCreationMessageSessionStorage(),
                TrainingQueryService(trainings),
                trainings::delete,
            ),
            chatId = 42L,
            chatType = chatType,
        )
}
