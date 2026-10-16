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

internal enum class ProfileMode { View, Edit }

internal data class ProfileContent(
    val profile: AthleteProfile,
    val mode: ProfileMode = ProfileMode.View,
    val field: ProfileField? = null,
    val warning: String? = null,
) {
    fun text(today: LocalDate): String {
        val instruction =
            when {
                mode == ProfileMode.View -> "Нажмите «Редактировать», чтобы изменить профиль."
                field == null -> "Все поля необязательны. Выберите поле для изменения."
                else -> "${field.title(profile.belt)}\n${field.prompt(profile.belt)}"
            }
        return listOfNotNull(profile.text(today), instruction, warning).joinToString("\n\n")
    }

    fun actions(): List<Pair<String, String>> {
        if (mode == ProfileMode.View) return listOf("Редактировать" to "edit")
        val fields =
            ProfileField.entries
                .filter {
                    it != ProfileField.RankMarks || !rankMarksRange(profile.belt).isEmpty()
                }.map { it.title(profile.belt) to it.name }
        val choices =
            when (field) {
                ProfileField.Belt -> BjjBelt.selectable.map { it.title to "value-${it.name}" }
                ProfileField.RankMarks -> rankMarksRange(profile.belt).map { it.toString() to "value-$it" }
                else -> emptyList()
            }
        val inputActions =
            if (field == null) {
                emptyList()
            } else {
                listOf("Очистить значение" to "clear", "Отменить ввод" to "cancel")
            }
        return choices + inputActions + fields + ("Завершить редактирование" to "finish")
    }

    fun keyboard(token: String): InlineKeyboardMarkup =
        InlineKeyboardMarkup.create(
            actions().map { (title, action) ->
                listOf(InlineKeyboardButton.CallbackData(title, "profile:$action:$token"))
            },
        )
}

internal fun ProfileField.title(belt: BjjBelt? = null): String =
    when (this) {
        ProfileField.FirstName -> "Имя"
        ProfileField.LastName -> "Фамилия"
        ProfileField.BirthDate -> "Дата рождения"
        ProfileField.Weight -> "Вес"
        ProfileField.Belt -> "Пояс BJJ"
        ProfileField.RankMarks -> if (belt == BjjBelt.Black) "Дан" else "Страйпы"
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
            "Выберите текущий пояс. Смена цвета или очистка пояса сбрасывает страйпы/дан и дату получения."
        }

        ProfileField.RankMarks -> {
            val units = if (belt == BjjBelt.Black) "данов" else "страйпов"
            "Выберите количество $units: от 0 до ${rankMarksRange(belt).last}."
        }

        ProfileField.BjjStart -> {
            "Введите месяц и год первого занятия BJJ: ММ.ГГГГ, не позднее текущего месяца."
        }
    }

internal fun AthleteProfile.text(today: LocalDate): String {
    val months = experienceMonths(today)
    return listOfNotNull(
        "Личный профиль 🥋",
        "",
        "Имя: ${firstName ?: "—"}",
        "Фамилия: ${lastName ?: "—"}",
        "Дата рождения: ${birthDate?.format(DATE_FORMAT) ?: "—"}",
        "Возраст (лет): ${age(today) ?: "—"}",
        "Вес (кг): ${weight?.toPlainString() ?: "—"}",
        "Пояс BJJ: ${belt?.title ?: "—"}",
        if (rankMarksRange(belt).isEmpty()) null else "${ProfileField.RankMarks.title(belt)}: ${rankMarks ?: "—"}",
        "Дата получения пояса: ${beltDate?.format(DATE_FORMAT) ?: "—"}",
        "Начало занятий BJJ: ${bjjStart?.format(MONTH_FORMAT) ?: "—"}",
        "Стаж BJJ: ${months?.let { "${it / MONTHS_PER_YEAR} г. ${it % MONTHS_PER_YEAR} мес." } ?: "—"}",
    ).joinToString("\n")
}
