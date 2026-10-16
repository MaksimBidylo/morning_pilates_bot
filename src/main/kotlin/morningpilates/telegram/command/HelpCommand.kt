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

    <b>Как заполнить профиль?</b>

    Через /profile можно самостоятельно заполнить и изменить свои данные.
    Профиль необязателен: дневником можно пользоваться без него.

    <b>Как записать тренировку?</b>

    1. Введи /training_add и укажи дату и время.
    2. Добавь техники и их описания, если нужно.
    3. При желании оставь заметку.
    4. Подтверди сохранение.

    Техники, описания и заметку можно пропустить.
    После сохранения бот покажет карточку тренировки.

    <b>Как найти тренировку?</b>

    /training_get_last покажет последнюю тренировку.
    /training_get_date запросит дату и покажет все тренировки за этот день.
    Листай карточки кнопками «Назад» и «Следующая».
    «Выбрать другую дату» вернёт к вводу даты.

    <b>Как удалить тренировку?</b>

    1. Открой просмотр через /training_get_last или /training_get_date.
    2. В нужной карточке нажми «Удалить тренировку».
    3. Проверь дату и время и нажми «Да, удалить».

    «Отмена» вернёт к карточке. Удаление нельзя отменить.

    <b>Как отменить действие?</b>

    Если передумал, используй /cancel. Для отмены всех незавершённых действий — /cancel_all.
    Отмена действий не удаляет сохранённые тренировки и данные профиля.
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
