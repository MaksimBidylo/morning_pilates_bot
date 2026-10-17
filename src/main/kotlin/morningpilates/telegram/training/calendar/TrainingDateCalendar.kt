package morningpilates.telegram.training.calendar

import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup
import com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton
import morningpilates.telegram.training.reply.TrainingReply
import java.time.YearMonth

private const val YEARS_PER_PAGE = 12
private const val DAYS_PER_WEEK = 7
private const val MONTHS_PER_ROW = 3
private const val MAX_YEAR = 9999
private val MONTH_NAMES =
    listOf(
        "Январь",
        "Февраль",
        "Март",
        "Апрель",
        "Май",
        "Июнь",
        "Июль",
        "Август",
        "Сентябрь",
        "Октябрь",
        "Ноябрь",
        "Декабрь",
    )

enum class CalendarLevel { Days, Months, Years }

data class TrainingDateCalendar(
    val month: YearMonth,
    val level: CalendarLevel = CalendarLevel.Days,
    val yearPage: Int = month.year / YEARS_PER_PAGE * YEARS_PER_PAGE,
) {
    fun navigate(action: String): TrainingDateCalendar =
        when {
            action == "previous" -> {
                copy(month = month.minusMonths(1))
            }

            action == "next" -> {
                copy(month = month.plusMonths(1))
            }

            action == "months" -> {
                copy(level = CalendarLevel.Months)
            }

            action == "years" -> {
                copy(level = CalendarLevel.Years, yearPage = month.year / YEARS_PER_PAGE * YEARS_PER_PAGE)
            }

            action == "back" -> {
                copy(level = if (level == CalendarLevel.Years) CalendarLevel.Months else CalendarLevel.Days)
            }

            action == "earlier" -> {
                copy(yearPage = yearPage - YEARS_PER_PAGE)
            }

            action == "later" -> {
                copy(yearPage = yearPage + YEARS_PER_PAGE)
            }

            action.startsWith("year-") -> {
                copy(month = month.withYear(action.removePrefix("year-").toInt()), level = CalendarLevel.Months)
            }

            action.startsWith("month-") -> {
                copy(month = month.withMonth(action.removePrefix("month-").toInt()), level = CalendarLevel.Days)
            }

            else -> {
                error("Unsupported calendar action")
            }
        }

    internal fun reply(
        base: TrainingReply,
        prefix: String,
        token: String,
    ): TrainingReply {
        val rows =
            when (level) {
                CalendarLevel.Days -> dayRows()
                CalendarLevel.Months -> monthRows()
                CalendarLevel.Years -> yearRows()
            }
        val buttons =
            rows.map { row -> row.map { (title, action) -> button(title, "$prefix$action:$token") } }
        return base.copy(
            replyMarkup = InlineKeyboardMarkup.create(buttons + base.replyMarkup?.inlineKeyboard.orEmpty()),
        )
    }

    private fun dayRows(): List<List<Pair<String, String>>> {
        val heading =
            listOfNotNull(
                if (month > YearMonth.of(0, 1)) "‹" to "previous" else null,
                MONTH_NAMES[month.monthValue - 1] to "months",
                month.year.toString() to "years",
                if (month < YearMonth.of(MAX_YEAR, 12)) "›" to "next" else null,
            )
        val offset = month.atDay(1).dayOfWeek.value - 1
        val cells =
            (0 until offset).map { "·" to "ignore" } +
                (1..month.lengthOfMonth()).map { it.toString() to "day-$it" }
        val padded = cells + List((DAYS_PER_WEEK - cells.size % DAYS_PER_WEEK) % DAYS_PER_WEEK) { "·" to "ignore" }
        val weekdays = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс").map { it to "ignore" }
        return listOf(heading, weekdays) + padded.chunked(DAYS_PER_WEEK)
    }

    private fun monthRows(): List<List<Pair<String, String>>> =
        listOf(listOf(month.year.toString() to "years")) +
            MONTH_NAMES.mapIndexed { index, title -> title to "month-${index + 1}" }.chunked(MONTHS_PER_ROW) +
            listOf(listOf("Назад" to "back"))

    private fun yearRows(): List<List<Pair<String, String>>> {
        val navigation =
            listOfNotNull(
                if (yearPage > 0) "‹ Годы" to "earlier" else null,
                "$yearPage–${minOf(yearPage + YEARS_PER_PAGE - 1, MAX_YEAR)}" to "ignore",
                if (yearPage + YEARS_PER_PAGE <= MAX_YEAR) "Годы ›" to "later" else null,
            )
        return listOf(navigation) +
            (yearPage..minOf(yearPage + YEARS_PER_PAGE - 1, MAX_YEAR))
                .map { it.toString() to "year-$it" }
                .chunked(MONTHS_PER_ROW) +
            listOf(listOf("Назад" to "back"))
    }
}

private fun button(
    title: String,
    data: String,
) = InlineKeyboardButton.CallbackData(title, data)
