package morningpilates.telegram.profile

import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup
import com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton
import morningpilates.profile.AthleteProfile
import morningpilates.profile.BjjBelt
import morningpilates.profile.ProfileField
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private const val MONTHS_PER_YEAR = 12
private val DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.uuuu")
private val MONTH_FORMAT = DateTimeFormatter.ofPattern("MM.uuuu")

internal fun ProfileField.title(): String =
    when (this) {
        ProfileField.FirstName -> "Имя"
        ProfileField.LastName -> "Фамилия"
        ProfileField.BirthDate -> "Дата рождения"
        ProfileField.Weight -> "Вес"
        ProfileField.Belt -> "Пояс BJJ"
        ProfileField.Stripes -> "Полоски"
        ProfileField.BeltDate -> "Дата получения пояса"
        ProfileField.BjjStart -> "Начало занятий BJJ"
    }

internal fun ProfileField.prompt(): String =
    when (this) {
        ProfileField.FirstName, ProfileField.LastName -> "Введите значение (1–100 символов)."
        ProfileField.BirthDate, ProfileField.BeltDate -> "Введите дату: ДД.ММ.ГГГГ, не позднее сегодняшней."
        ProfileField.Weight -> "Введите вес в кг: больше 0 и не более 500, до двух знаков после запятой."
        ProfileField.Belt -> "Выберите текущий пояс. При смене пояса дата его получения будет очищена."
        ProfileField.Stripes -> "Выберите количество полосок: от 0 до 4."
        ProfileField.BjjStart -> "Введите месяц и год первого занятия BJJ: ММ.ГГГГ, не позднее текущего месяца."
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
        Полоски: ${stripes ?: "—"}
        Дата получения пояса: ${beltDate?.format(DATE_FORMAT) ?: "—"}
        Начало занятий BJJ: ${bjjStart?.format(MONTH_FORMAT) ?: "—"}
        Стаж BJJ: ${months?.let { "${it / MONTHS_PER_YEAR} г. ${it % MONTHS_PER_YEAR} мес." } ?: "—"}

        Все поля необязательны. Выберите поле для изменения.
        """.trimIndent()
}

internal fun profileActions(field: ProfileField?): List<Pair<String, String>> =
    if (field == null) {
        ProfileField.entries.map { it.title() to it.name } + ("Закрыть профиль" to "close")
    } else {
        val choices =
            when (field) {
                ProfileField.Belt -> BjjBelt.entries.map { it.title to "value-${it.name}" }
                ProfileField.Stripes -> (0..4).map { it.toString() to "value-$it" }
                else -> emptyList()
            }
        choices + listOf("Очистить значение" to "clear", "Отменить редактирование" to "cancel")
    }

internal fun profileKeyboard(
    field: ProfileField?,
    token: String,
): InlineKeyboardMarkup =
    InlineKeyboardMarkup.create(
        profileActions(field).map { (title, action) ->
            listOf(InlineKeyboardButton.CallbackData(title, "profile:$action:$token"))
        },
    )
