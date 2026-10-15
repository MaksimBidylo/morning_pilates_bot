package morningpilates.telegram.command

import com.github.kotlintelegrambot.dispatcher.Dispatcher
import com.github.kotlintelegrambot.dispatcher.command
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.ParseMode

fun Dispatcher.registerHelpCommand() {
    command("help") {
        bot.sendMessage(
            chatId = ChatId.fromId(message.chat.id),
            text =
                """
                <b>Помощь 🥋</b>

                Morning Pilates Bot — твой личный дневник тренировок по BJJ.

                <b>Доступные команды</b>

                /training_add — записать тренировку

                /training_get_last — посмотреть последнюю тренировку

                /cancel — отменить текущее действие

                /cancel_all — отменить все незавершённые действия

                /start — приветствие

                /help — показать эту справку

                <b>Как записать тренировку?</b>

                1. Введи /training_add и укажи дату и время.
                2. Добавь техники и их описания, если нужно.
                3. При желании оставь заметку.
                4. Подтверди сохранение.

                После сохранения бот покажет карточку тренировки.

                <b>Полезно знать</b>

                - Техники, описания и заметку можно пропустить.
                - Для отмены текущего действия используй /cancel или кнопку «Отменить тренировку» во время записи.
                - /cancel_all отменяет все незавершённые действия (включая активную тренировку и просмотр карточки).
                - Незавершённая тренировка не сохраняется автоматически.
                """.trimIndent(),
            parseMode = ParseMode.HTML,
        )
    }
}
