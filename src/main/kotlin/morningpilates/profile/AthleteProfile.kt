package morningpilates.profile

import java.math.BigDecimal
import java.time.LocalDate
import java.time.Period
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

enum class ProfileField {
    FirstName,
    LastName,
    BirthDate,
    Weight,
    Belt,
    Stripes,
    BeltDate,
    BjjStart,
}

enum class BjjBelt(
    val title: String,
) {
    White("Белый"),
    Blue("Синий"),
    Purple("Пурпур"),
    Brown("Коричневый"),
    Black("Чёрный"),
    Coral("Коралловый"),
    Red("Красный"),
}

private const val MAX_NAME_LENGTH = 100
private const val DATE_INPUT_LENGTH = 10
private const val MONTH_INPUT_LENGTH = 7
private const val MAX_STRIPES = 4
private val MAX_WEIGHT = BigDecimal("500")
private val PROFILE_DATE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.uuuu").withResolverStyle(ResolverStyle.STRICT)
private val PROFILE_MONTH_FORMAT = DateTimeFormatter.ofPattern("MM.uuuu").withResolverStyle(ResolverStyle.STRICT)

data class AthleteProfile(
    val firstName: String? = null,
    val lastName: String? = null,
    val birthDate: LocalDate? = null,
    val weight: BigDecimal? = null,
    val belt: BjjBelt? = null,
    val stripes: Int? = null,
    val beltDate: LocalDate? = null,
    val bjjStart: YearMonth? = null,
) {
    fun age(today: LocalDate): Int? = birthDate?.let { Period.between(it, today).years }

    fun experienceMonths(today: LocalDate): Long? =
        bjjStart?.let {
            java.time.temporal.ChronoUnit.MONTHS
                .between(it, YearMonth.from(today))
        }

    fun withInput(
        field: ProfileField,
        input: String?,
        today: LocalDate,
    ): AthleteProfile {
        val value = input?.trim()
        return when (field) {
            ProfileField.FirstName -> {
                copy(firstName = value?.validName())
            }

            ProfileField.LastName -> {
                copy(lastName = value?.validName())
            }

            ProfileField.BirthDate -> {
                copy(birthDate = value?.profileDate(today))
            }

            ProfileField.Weight -> {
                copy(weight = value?.profileWeight())
            }

            ProfileField.Belt -> {
                val next = value?.let { text -> BjjBelt.entries.singleOrNull { it.name == text } }
                require(value == null || next != null) { "Выберите пояс кнопкой." }
                copy(belt = next, beltDate = if (next != null && next == belt) beltDate else null)
            }

            ProfileField.Stripes -> {
                val next = value?.toIntOrNull()
                require(value == null || next in 0..MAX_STRIPES) { "Количество полосок: целое число от 0 до 4." }
                copy(stripes = next)
            }

            ProfileField.BeltDate -> {
                copy(beltDate = value?.profileDate(today))
            }

            ProfileField.BjjStart -> {
                copy(bjjStart = value?.profileMonth(today))
            }
        }
    }
}

private fun String.validName(): String {
    require(
        isNotBlank() && length <= MAX_NAME_LENGTH && none { it.isISOControl() },
    ) { "Введите от 1 до 100 символов без переносов строк." }
    return this
}

private fun String.profileDate(today: LocalDate): LocalDate {
    val date = runCatching { LocalDate.parse(this, PROFILE_DATE_FORMAT) }.getOrNull()
    require(length == DATE_INPUT_LENGTH && date != null && date.year > 0 && !date.isAfter(today)) {
        "Введите существующую дату в формате ДД.ММ.ГГГГ, не позднее сегодняшней."
    }
    return date
}

private fun String.profileMonth(today: LocalDate): YearMonth {
    val month = runCatching { YearMonth.parse(this, PROFILE_MONTH_FORMAT) }.getOrNull()
    require(length == MONTH_INPUT_LENGTH && month != null && month.year > 0 && month <= YearMonth.from(today)) {
        "Введите месяц и год в формате ММ.ГГГГ, не позднее текущего месяца."
    }
    return month
}

private fun String.profileWeight(): BigDecimal {
    val number = replace(',', '.').toBigDecimalOrNull()
    require(
        matches(Regex("[0-9]+([.,][0-9]{1,2})?")) && number != null && number > BigDecimal.ZERO &&
            number <= MAX_WEIGHT,
    ) {
        "Введите вес больше 0 и не более 500 кг, до двух знаков после запятой."
    }
    return number.stripTrailingZeros()
}
