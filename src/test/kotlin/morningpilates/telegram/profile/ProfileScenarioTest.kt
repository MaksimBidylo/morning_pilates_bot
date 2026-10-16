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
import morningpilates.telegram.support.EditedTelegramMessage
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfileScenarioTest {
    private val user = TelegramUserId(42L)
    private val profiles = FakeProfileRepository()
    private val trainings = FakeTrainingRepository()
    private val flows = InMemoryFlowStorage()
    private var nextInputId = 600L
    private var cardId: TelegramMessageId? = null

    @Test
    fun `view edit multiple fields and finish reuse one card and delete user messages`() =
        runBlocking {
            bot().use { bot ->
                val sent = bot.sendCommandAndCollectReplies("/profile", messageId = 501).single()
                cardId = TelegramMessageId(sent.messageId)
                assertEquals(listOf("Редактировать"), buttons(bot))
                assertTrue(sent.text.contains("Имя: —"))
                assertTrue(profiles.profiles.isEmpty())
                edit(bot, "Имя")
                input(bot, "Анна", 502)
                assertTrue(current(bot).text.contains("Имя: Анна"))
                assertTrue("Завершить редактирование" in buttons(bot))
                assertTrue("Фамилия" in buttons(bot))
                edit(bot, "Фамилия")
                input(bot, "Иванова", 503)
                press(bot, "Завершить редактирование")
                assertEquals(listOf("Редактировать"), buttons(bot))
                assertEquals(1, bot.sendMessageRequests.size)
                assertTrue(bot.editedMessages.all { it.messageId == cardId })
                assertEquals(listOf(501L, 502L, 503L), bot.deletedMessages.map { it.messageId })
                assertEquals(AthleteProfile(firstName = "Анна", lastName = "Иванова"), profiles.profiles[user])
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
    fun `each text field can be saved changed and cleared while staying in edit mode`(
        field: String,
        value: String,
    ) = runBlocking {
        bot().use { bot ->
            edit(bot, field)
            input(bot, value)
            assertEquals(1, profiles.saves)
            assertTrue("Завершить редактирование" in buttons(bot))
            edit(bot, field)
            input(bot, if (field in listOf("Имя", "Фамилия")) "Другое" else value)
            edit(bot, field)
            press(bot, "Очистить")
            assertEquals(AthleteProfile(), profiles.profiles[user])
            assertEquals(3, profiles.saves)
            assertEquals(1, bot.sendMessageRequests.size)
            assertTrue("Завершить редактирование" in buttons(bot))
        }
    }

    @Test
    fun `repeated profile reloads saved data and resets field and mode on same card`() =
        runBlocking {
            bot().use { bot ->
                edit(bot, "Имя")
                input(bot, "Анна")
                edit(bot, "Фамилия")
                val old = bot.captureCallback(current(bot), "Очистить")
                profiles.profiles[user] = profiles.profiles.getValue(user).copy(firstName = "Актуальное")
                assertTrue(bot.sendCommandAndCollectReplies("/profile", messageId = 777).isEmpty())
                assertEquals(listOf("Редактировать"), buttons(bot))
                assertTrue(current(bot).text.contains("Имя: Актуальное"))
                assertTrue(bot.deliverCallbackAndCollectReplies(old).replies.isEmpty())
                assertEquals(1, profiles.saves)
                assertEquals(1, bot.sendMessageRequests.size)
                assertTrue(bot.deletedMessages.any { it.messageId == 777L })
            }
        }

    @Test
    fun `cancel field keeps editing and finish invalidates field and menu callbacks`() =
        runBlocking {
            profiles.profiles[user] = AthleteProfile(firstName = "Анна")
            bot().use { bot ->
                edit(bot, "Имя")
                val clear = bot.captureCallback(current(bot), "Очистить")
                press(bot, "Назад")
                assertTrue("Завершить редактирование" in buttons(bot))
                assertFalse("Очистить" in buttons(bot))
                val field = bot.captureCallback(current(bot), "Имя")
                press(bot, "Завершить редактирование")
                val edits = bot.editedMessages.size
                bot.deliverCallbackAndCollectReplies(clear)
                bot.deliverCallbackAndCollectReplies(field)
                assertEquals(edits, bot.editedMessages.size)
                assertEquals(0, profiles.saves)
                assertEquals("Анна", profiles.profiles[user]?.firstName)
                assertEquals(listOf("Редактировать"), buttons(bot))
            }
        }

    @Test
    fun `cancel input and finish editing do not require a working database`() =
        runBlocking {
            bot().use { bot ->
                edit(bot, "Имя")
                profiles.readFailure = true
                press(bot, "Назад")
                assertFalse("Очистить" in buttons(bot))
                assertTrue("Завершить редактирование" in buttons(bot))
                press(bot, "Завершить редактирование")
                assertEquals(listOf("Редактировать"), buttons(bot))
                assertEquals(0, profiles.saves)
            }
        }

    @Test
    fun `field screen hides menu and back returns without changing data`() =
        runBlocking {
            profiles.profiles[user] = AthleteProfile(firstName = "Анна", belt = BjjBelt.Blue)
            bot().use { bot ->
                open(bot)
                assertEquals(listOf("Редактировать"), buttons(bot))
                press(bot, "Редактировать")
                val menu = buttons(bot)
                val oldField = bot.captureCallback(current(bot), "Фамилия")
                val oldFinish = bot.captureCallback(current(bot), "Завершить редактирование")
                press(bot, "Имя")
                assertEquals(listOf("Очистить", "Назад"), buttons(bot))
                assertTrue(current(bot).text.contains("Введите значение"))
                val back = bot.captureCallback(current(bot), "Назад")
                val clear = bot.captureCallback(current(bot), "Очистить")
                val edits = bot.editedMessages.size
                bot.deliverCallbackAndCollectReplies(oldField)
                bot.deliverCallbackAndCollectReplies(oldFinish)
                assertEquals(edits, bot.editedMessages.size)
                assertEquals(listOf("Очистить", "Назад"), buttons(bot))
                press(bot, "Назад")
                assertEquals(menu, buttons(bot))
                assertEquals("Анна", profiles.profiles[user]?.firstName)
                assertEquals(0, profiles.saves)
                press(bot, "Фамилия")
                bot.deliverCallbackAndCollectReplies(back)
                bot.deliverCallbackAndCollectReplies(clear)
                assertTrue(current(bot).text.contains("Фамилия"))
                assertEquals(listOf("Очистить", "Назад"), buttons(bot))
                input(bot, "Иванова")
                assertEquals(menu, buttons(bot))
                assertEquals("Иванова", profiles.profiles[user]?.lastName)
                press(bot, "Фамилия")
                press(bot, "Очистить")
                assertEquals(menu, buttons(bot))
                assertNull(profiles.profiles[user]?.lastName)
                assertEquals(1, bot.sendMessageRequests.size)
            }
        }

    @Test
    fun `fixed choices are limited to selected field and menu callbacks stay invalid with its token`() =
        runBlocking {
            profiles.profiles[user] = AthleteProfile(belt = BjjBelt.Blue)
            bot().use { bot ->
                edit(bot, "Пояс BJJ")
                assertEquals(
                    listOf("Белый", "Синий", "Пурпур", "Коричневый", "Чёрный", "Очистить", "Назад"),
                    buttons(bot),
                )
                val back = bot.captureCallback(current(bot), "Назад")
                val forged = back.copy(data = back.data.replace("profile:back:", "profile:FirstName:"))
                val finish = back.copy(data = back.data.replace("profile:back:", "profile:finish:"))
                val edits = bot.editedMessages.size
                bot.deliverCallbackAndCollectReplies(forged)
                bot.deliverCallbackAndCollectReplies(finish)
                assertEquals(edits, bot.editedMessages.size)
                press(bot, "Назад")
                press(bot, "Страйпы")
                assertEquals(listOf("0", "1", "2", "3", "4", "Очистить", "Назад"), buttons(bot))
                press(bot, "0")
                assertTrue("Завершить редактирование" in buttons(bot))
                assertFalse("Назад" in buttons(bot))
                assertEquals(0, profiles.profiles[user]?.rankMarks)
            }
        }

    @Test
    fun `buttons check owner chat message token and mode`() =
        runBlocking {
            bot().use { bot ->
                open(bot)
                for (interaction in listOf(
                    bot.pressInlineButtonAndCollectReplies(current(bot), "Редактировать", senderId = 43),
                    bot.pressInlineButtonAndCollectReplies(current(bot), "Редактировать", callbackChatId = 43),
                    bot.pressInlineButtonAndCollectReplies(current(bot), "Редактировать", callbackChatType = "group"),
                    bot.pressInlineButtonAndCollectReplies(current(bot), "Редактировать", callbackDataSuffix = "bad"),
                )) {
                    assertTrue(interaction.acknowledged)
                    assertTrue(interaction.replies.isEmpty())
                }
                val edit = bot.captureCallback(current(bot), "Редактировать")
                val forged = edit.copy(data = edit.data.replace("profile:edit:", "profile:FirstName:"))
                bot.deliverCallbackAndCollectReplies(forged)
                bot.deliverCallbackAndCollectReplies(edit.copy(message = edit.message?.copy(messageId = 99999)))
                assertTrue(bot.editedMessages.isEmpty())
                press(bot, "Редактировать")
                val edits = bot.editedMessages.size
                bot.deliverCallbackAndCollectReplies(edit)
                assertEquals(edits, bot.editedMessages.size)
                assertEquals(0, profiles.saves)
            }
        }

    @Test
    fun `duplicate clear cannot erase later input`() =
        runBlocking {
            bot().use { bot ->
                edit(bot, "Имя")
                val clear = bot.captureCallback(current(bot), "Очистить")
                bot.deliverCallbackAndCollectReplies(clear)
                edit(bot, "Имя")
                input(bot, "Новое имя")
                bot.deliverCallbackAndCollectReplies(clear)
                assertEquals("Новое имя", profiles.profiles[user]?.firstName)
                assertEquals(2, profiles.saves)
            }
        }

    @Test
    fun `invalid input updates same card deletes processed message and allows retry`() =
        runBlocking {
            bot().use { bot ->
                edit(bot, "Дата рождения")
                input(bot, "31.02.2000", 701)
                assertTrue(current(bot).text.contains("существующую дату"))
                assertEquals(listOf("Очистить", "Назад"), buttons(bot))
                assertEquals(0, profiles.saves)
                assertTrue(bot.deletedMessages.any { it.messageId == 701L })
                input(bot, "01.01.2000", 702)
                assertEquals(LocalDate.of(2000, 1, 1), profiles.profiles[user]?.birthDate)
                assertTrue("Завершить редактирование" in buttons(bot))
                assertFalse(current(bot).text.contains("существующую дату"))
                assertEquals(1, bot.sendMessageRequests.size)
            }
        }

    @Test
    fun `failure to delete command or input does not interrupt saved data or further editing`() =
        runBlocking {
            bot().use { bot ->
                bot.failNextDeleteMessage("Forbidden")
                open(bot)
                assertEquals(listOf("Редактировать"), buttons(bot))
                edit(bot, "Имя")
                bot.failNextDeleteMessage("Forbidden")
                input(bot, "Анна", 801)
                assertEquals("Анна", profiles.profiles[user]?.firstName)
                assertTrue(bot.deleteMessageAttempts.any { it.messageId.value == 801L && !it.succeeded })
                edit(bot, "Фамилия")
                input(bot, "Иванова", 802)
                assertEquals("Иванова", profiles.profiles[user]?.lastName)
                assertTrue(bot.deletedMessages.any { it.messageId == 802L })
                assertEquals(1, bot.sendMessageRequests.size)
            }
        }

    @Test
    fun `save failure preserves input step and removes processed message`() =
        runBlocking {
            bot().use { bot ->
                edit(bot, "Имя")
                profiles.saveFailure = true
                input(bot, "Анна", 803)
                assertTrue(current(bot).text.contains("базе данных"))
                assertTrue("Очистить" in buttons(bot))
                assertEquals(0, profiles.saves)
                assertTrue(bot.deletedMessages.any { it.messageId == 803L })
                profiles.saveFailure = false
                input(bot, "Анна")
                assertEquals("Анна", profiles.profiles[user]?.firstName)
            }
        }

    @Test
    fun `committed save invalidates old action even when card update fails`() =
        runBlocking {
            bot().use { bot ->
                edit(bot, "Имя")
                val clear = bot.captureCallback(current(bot), "Очистить")
                bot.failNextEditMessageText("Unavailable")
                input(bot, "Анна", 804)
                assertEquals("Анна", profiles.profiles[user]?.firstName)
                assertTrue(bot.deletedMessages.any { it.messageId == 804L })
                bot.deliverCallbackAndCollectReplies(clear)
                assertEquals(1, profiles.saves)
                open(bot)
                assertTrue(current(bot).text.contains("Имя: Анна"))
                assertEquals(listOf("Редактировать"), buttons(bot))
                assertEquals(1, bot.sendMessageRequests.size)
            }
        }

    @Test
    fun `failed mode switch keeps old view usable and missing card can recover`() =
        runBlocking {
            bot().use { bot ->
                open(bot)
                bot.failNextEditMessageText("Unavailable")
                press(bot, "Редактировать")
                assertEquals(listOf("Редактировать"), buttons(bot))
                press(bot, "Редактировать")
                assertTrue("Имя" in buttons(bot))
                bot.failNextEditMessageText("Bad Request: message to edit not found")
                val oldId = cardId
                open(bot)
                assertTrue(cardId != oldId)
                assertEquals(listOf("Редактировать"), buttons(bot))
            }
        }

    @Test
    fun `finishing edit invalidates its buttons even if delivery fails`() =
        runBlocking {
            bot().use { bot ->
                edit(bot, "Имя")
                press(bot, "Назад")
                val field = bot.captureCallback(current(bot), "Имя")
                bot.failNextEditMessageText("Unavailable")
                press(bot, "Завершить редактирование")
                bot.deliverCallbackAndCollectReplies(field)
                assertEquals(0, profiles.saves)
                bot.sendCommandAndCollectReplies("/training_add")
                assertNotNull(flows.get(user))
            }
        }

    @Test
    fun `profile refuses active diary creation and date input without consuming them`() =
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
                open(bot)
                assertEquals(listOf("Редактировать"), buttons(bot))
            }
        }

    @Test
    fun `diary refuses edit menu even between fields and works after finish`() =
        runBlocking {
            bot().use { bot ->
                edit(bot, "Имя")
                input(bot, "Анна")
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
                press(bot, "Завершить редактирование")
                bot.sendCommandAndCollectReplies("/training_add")
                assertNotNull(flows.get(user))
            }
        }

    @ParameterizedTest
    @CsvSource("/cancel", "/cancel_all")
    fun `cancel commands invalidate whole edit without deleting saved data`(command: String) =
        runBlocking {
            bot().use { bot ->
                edit(bot, "Имя")
                input(bot, "Анна")
                edit(bot, "Фамилия")
                val callback = bot.captureCallback(current(bot), "Очистить")
                bot.sendCommandAndCollectReplies(command)
                bot.deliverCallbackAndCollectReplies(callback)
                assertEquals("Анна", profiles.profiles[user]?.firstName)
                assertEquals(1, profiles.saves)
                bot.sendCommandAndCollectReplies("/training_add")
                assertNotNull(flows.get(user))
            }
        }

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
                val callback = bot.captureCallback(current(bot), "Имя")
                bot.deliverCallbackAndCollectReplies(
                    callback.copy(data = callback.data.replace("FirstName:", "RankMarks:")),
                )
                assertEquals(0, profiles.saves)
                press(bot, "Пояс BJJ")
                press(bot, "Белый")
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
    fun `black belt uses dan through ten and clears dependencies on every color change`() =
        runBlocking {
            profiles.profiles[user] =
                AthleteProfile(
                    belt = BjjBelt.Brown,
                    rankMarks = 4,
                    beltDate = LocalDate.of(2020, 1, 1),
                )
            bot().use { bot ->
                edit(bot, "Пояс BJJ")
                assertTrue(listOf("Белый", "Синий", "Пурпур", "Коричневый", "Чёрный").all { it in buttons(bot) })
                assertFalse("Красный" in buttons(bot))
                press(bot, "Чёрный")
                assertTrue(current(bot).text.contains("Дан: —"))
                assertNull(profiles.profiles[user]?.beltDate)
                press(bot, "Дан")
                assertTrue((0..10).all { it.toString() in buttons(bot) })
                input(bot, "11")
                assertTrue(current(bot).text.contains("от 0 до 10"))
                press(bot, "10")
                assertTrue(current(bot).text.contains("Дан: 10"))
                press(bot, "Пояс BJJ")
                press(bot, "Чёрный")
                assertEquals(10, profiles.profiles[user]?.rankMarks)
                press(bot, "Пояс BJJ")
                press(bot, "Синий")
                assertNull(profiles.profiles[user]?.rankMarks)
                assertTrue(current(bot).text.contains("Страйпы: —"))
                press(bot, "Страйпы")
                assertFalse("5" in buttons(bot))
                press(bot, "4")
                press(bot, "Пояс BJJ")
                press(bot, "Пурпур")
                assertNull(profiles.profiles[user]?.rankMarks)
                press(bot, "Страйпы")
                press(bot, "0")
                press(bot, "Пояс BJJ")
                press(bot, "Очистить")
                assertNull(profiles.profiles[user]?.belt)
                assertNull(profiles.profiles[user]?.rankMarks)
                assertNull(profiles.profiles[user]?.beltDate)
                assertFalse("Страйпы" in buttons(bot))
                assertFalse(current(bot).text.contains("Страйпы:"))
            }
        }

    @Test
    fun `read failure is reported without empty profile or deletion of command`() =
        runBlocking {
            profiles.readFailure = true
            bot().use { bot ->
                assertTrue(
                    bot
                        .sendCommandAndCollectReplies("/profile", messageId = 900)
                        .single()
                        .text
                        .contains("базе данных"),
                )
                assertTrue(bot.deleteMessageAttempts.isEmpty())
                assertEquals(0, profiles.saves)
            }
        }

    @Test
    fun `restart rejects previous launch buttons and reloads saved data`() =
        runBlocking {
            val old =
                bot().use { bot ->
                    edit(bot, "Имя")
                    val callback = bot.captureCallback(current(bot), "Очистить")
                    input(bot, "Анна")
                    callback
                }
            bot().use { bot ->
                val stale = bot.deliverCallbackAndCollectReplies(old)
                assertTrue(stale.acknowledged)
                assertTrue(stale.replies.isEmpty())
                open(bot)
                assertTrue(current(bot).text.contains("Имя: Анна"))
                assertEquals(listOf("Редактировать"), buttons(bot))
            }
        }

    @Test
    fun `private chat only and profiles remain isolated`() =
        runBlocking {
            bot("group").use { bot -> assertTrue(bot.sendCommandAndCollectReplies("/profile").isEmpty()) }
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
                input(bot, "Своё")
                val other = bot.sendCommandAndCollectReplies("/profile", senderId = 43).single()
                assertTrue(other.text.contains("Имя: —"))
                assertEquals(listOf("Редактировать"), other.inlineButtonTexts)
            }
        }

    private fun current(bot: TestTelegramBot): EditedTelegramMessage = bot.currentMessage(checkNotNull(cardId))

    private fun buttons(bot: TestTelegramBot): List<String> = current(bot).inlineKeyboard.flatten().map { it.text }

    private suspend fun open(bot: TestTelegramBot) {
        val replies = bot.sendCommandAndCollectReplies("/profile")
        replies.singleOrNull()?.let { cardId = TelegramMessageId(it.messageId) }
    }

    private suspend fun press(
        bot: TestTelegramBot,
        title: String,
    ) {
        val interaction = bot.pressInlineButtonAndCollectReplies(current(bot), title)
        assertTrue(interaction.acknowledged)
        interaction.replies.singleOrNull()?.let { cardId = TelegramMessageId(it.messageId) }
    }

    private suspend fun edit(
        bot: TestTelegramBot,
        field: String,
    ) {
        if (cardId == null) open(bot)
        if ("Редактировать" in buttons(bot)) press(bot, "Редактировать")
        press(bot, field)
    }

    private suspend fun input(
        bot: TestTelegramBot,
        value: String,
        messageId: Long = nextInputId++,
    ) {
        assertTrue(bot.sendTextAndCollectReplies(value, messageId = messageId).isEmpty())
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
