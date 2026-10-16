package morningpilates.telegram.command

import com.github.kotlintelegrambot.dispatcher.Dispatcher
import com.github.kotlintelegrambot.dispatcher.command
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.ParseMode

private val HELP_TEXT =
    """
    <b>Помощь 🥋</b>

    Morning Pilates Bot — твой личный дневник тренировок по BJJ.
    Записывай, ищи и удаляй свои тренировки в личном чате с ботом.

    <b>Доступные команды</b>

    /profile — посмотреть и заполнить личный профиль
    /training_add — записать тренировку
    /training_get_last — посмотреть последнюю тренировку
    /training_get_date — найти тренировки за дату
    /cancel — отменить текущее действие
    /cancel_all — отменить все незавершённые действия
    /start — приветствие
    /help — показать эту справку

    <b>Основные действия</b>

    Профиль необязателен: дневником можно пользоваться без него.
    Чтобы заполнить профиль, открой /profile, нажми «Редактировать» и выбери поле.

    Для записи тренировки введи /training_add, укажи дату и время и следуй подсказкам.
    Техники и заметку можно пропустить. В конце подтверди сохранение.

    Открой тренировку через /training_get_last или /training_get_date.
    Для удаления нажми «Удалить тренировку» в её карточке и подтверди действие.

    Если передумал, используй /cancel. Для отмены всех текущих действий — /cancel_all.
    """.trimIndent()

fun Dispatcher.registerHelpCommand() {
    command("help") {
        bot.sendMessage(
            chatId = ChatId.fromId(message.chat.id),
            text = HELP_TEXT,
            parseMode = ParseMode.HTML,
        )
    }
}
