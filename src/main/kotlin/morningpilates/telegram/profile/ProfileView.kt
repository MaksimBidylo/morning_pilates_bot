package morningpilates.telegram.profile

import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup
import com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton
import morningpilates.profile.AthleteProfile
import morningpilates.profile.BjjBelt
import morningpilates.profile.ProfileField
import morningpilates.profile.rankMarksRange
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private const val MONTHS_PER_YEAR = 12
private val DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.uuuu")
private val MONTH_FORMAT = DateTimeFormatter.ofPattern("MM.uuuu")

internal fun ProfileField.title(belt: BjjBelt? = null): String =
    when (this) {
        ProfileField.FirstName -> "Имя"
        ProfileField.LastName -> "Фамилия"
        ProfileField.BirthDate -> "Дата рождения"
        ProfileField.Weight -> "Вес"
        ProfileField.Belt -> "Пояс BJJ"
        ProfileField.RankMarks -> if (belt == BjjBelt.Black) "Даны" else "Полоски"
        ProfileField.BeltDate -> "Дата получения пояса"
        ProfileField.BjjStart -> "Начало занятий BJJ"
    }

internal fun ProfileField.prompt(belt: BjjBelt?): String =
    when (this) {
        ProfileField.FirstName, ProfileField.LastName -> {
            "Введите значение (1–100 символов)."
        }

        ProfileField.BirthDate, ProfileField.BeltDate -> {
            "Введите дату: ДД.ММ.ГГГГ, не позднее сегодняшней."
        }

        ProfileField.Weight -> {
            "Введите вес в кг: больше 0 и не более 500, до двух знаков после запятой."
        }

        ProfileField.Belt -> {
            "Выберите текущий пояс. Смена пояса очищает дату его получения; " +
                "переход к чёрному или обратно — также полоски/даны."
        }

        ProfileField.RankMarks -> {
            val units = if (belt == BjjBelt.Black) "данов" else "полосок"
            "Выберите количество $units: от 0 до ${rankMarksRange(belt).last}."
        }

        ProfileField.BjjStart -> {
            "Введите месяц и год первого занятия BJJ: ММ.ГГГГ, не позднее текущего месяца."
        }
    }

internal fun AthleteProfile.text(today: LocalDate): String {
    val months = experienceMonths(today)
    return """
        Личный профиль 🥋

        Имя: ${firstName ?: "—"}
        Фамилия: ${lastName ?: "—"}
        Дата рождения: ${birthDate?.format(DATE_FORMAT) ?: "—"}
        Возраст (лет): ${age(today) ?: "—"}
        Вес (кг): ${weight?.toPlainString() ?: "—"}
        Пояс BJJ: ${belt?.title ?: "—"}
        ${ProfileField.RankMarks.title(belt)}: ${rankMarks ?: "—"}
        Дата получения пояса: ${beltDate?.format(DATE_FORMAT) ?: "—"}
        Начало занятий BJJ: ${bjjStart?.format(MONTH_FORMAT) ?: "—"}
        Стаж BJJ: ${months?.let { "${it / MONTHS_PER_YEAR} г. ${it % MONTHS_PER_YEAR} мес." } ?: "—"}

        Все поля необязательны. Выберите поле для изменения.
        """.trimIndent()
}

internal fun profileActions(
    field: ProfileField?,
    belt: BjjBelt? = null,
): List<Pair<String, String>> =
    if (field == null) {
        ProfileField.entries.map { it.title(belt) to it.name } + ("Закрыть профиль" to "close")
    } else {
        val choices =
            when (field) {
                ProfileField.Belt -> BjjBelt.selectable.map { it.title to "value-${it.name}" }
                ProfileField.RankMarks -> rankMarksRange(belt).map { it.toString() to "value-$it" }
                else -> emptyList()
            }
        choices + listOf("Очистить значение" to "clear", "Отменить редактирование" to "cancel")
    }

internal fun profileKeyboard(
    field: ProfileField?,
    token: String,
    belt: BjjBelt?,
): InlineKeyboardMarkup =
    InlineKeyboardMarkup.create(
        profileActions(field, belt).map { (title, action) ->
            listOf(InlineKeyboardButton.CallbackData(title, "profile:$action:$token"))
        },
    )
