package morningpilates.telegram.command

import com.github.kotlintelegrambot.entities.ChatId
import morningpilates.diagnostics.RequestDiagnostics
import morningpilates.telegram.logging.TelegramHandlers

fun TelegramHandlers.registerStartCommand() {
    command("start") {
        bot
            .sendMessage(
                chatId = ChatId.fromId(message.chat.id),
                text =
                    """
                    Morning Pilates Bot 🥋

                    Твой личный дневник тренировок по BJJ.

                    Записывай тренировки, сохраняй изученные техники и отслеживай свой прогресс.

                    Заполни свой профиль через /profile — имя, пояс, вес и другие данные.
                    Все поля необязательны. Это можно сделать ещё до первой тренировки.

                    Доступные команды:

                    /profile — личный профиль спортсмена
                    /training_add — записать тренировку
                    /training_get_last — посмотреть последнюю тренировку
                    /training_get_date — найти тренировки за дату
                    /help — помощь
                    """.trimIndent(),
            ).onSuccess { RequestDiagnostics.success() }
    }
}
