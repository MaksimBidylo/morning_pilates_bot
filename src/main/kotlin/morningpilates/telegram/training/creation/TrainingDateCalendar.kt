package morningpilates.telegram.training.creation

import morningpilates.telegram.training.calendar.TrainingDateCalendar
import morningpilates.telegram.training.reply.TrainingReply

internal const val CALENDAR_PREFIX = "training-calendar:"

internal fun TrainingDateCalendar.reply(base: TrainingReply): TrainingReply =
    reply(
        base.copy(
            text =
                base.text.replace(
                    "📅 Введите дату тренировки.",
                    "📅 Выберите дату в календаре или отправьте её сообщением.",
                ),
        ),
        CALENDAR_PREFIX,
        base.callbackData().first().substringAfterLast(':'),
    )
