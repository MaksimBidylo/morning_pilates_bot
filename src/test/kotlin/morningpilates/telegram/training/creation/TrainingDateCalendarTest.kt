package morningpilates.telegram.training.creation

import com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton
import morningpilates.telegram.training.calendar.CalendarLevel
import morningpilates.telegram.training.calendar.TrainingDateCalendar
import morningpilates.training.TrainingFlowState.WaitingDate
import morningpilates.training.parseTrainingDate
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrainingDateCalendarTest {
    @ParameterizedTest
    @CsvSource("2023,2,28", "2024,2,29", "2026,10,31", "2026,11,30", "0,2,29", "9999,12,31")
    fun `grid starts on Monday contains only real dates and fits callback limits`(
        year: Int,
        month: Int,
        days: Int,
    ) {
        val calendar = TrainingDateCalendar(YearMonth.of(year, month))
        val rows =
            calendar
                .reply(checkNotNull(promptForCreation(WaitingDate)).withCreationQuestionToken(TOKEN))
                .replyMarkup!!
                .inlineKeyboard
        val cells = rows.drop(2).dropLast(1).flatten()
        assertTrue(rows.drop(2).dropLast(1).all { it.size == 7 })
        assertEquals(
            calendar.month
                .atDay(1)
                .dayOfWeek.value - 1,
            cells.indexOfFirst { it.text == "1" },
        )
        assertEquals((1..days).map(Int::toString), cells.map { it.text }.filter { it != "·" })
        for (day in 1..days) {
            val date = calendar.month.atDay(day)
            assertEquals(date, parseTrainingDate(date.toString()))
        }
        for (button in rows.flatten()) {
            assertTrue((button as InlineKeyboardButton.CallbackData).callbackData.toByteArray().size <= 64)
        }
    }

    @Test
    fun `year and month selection and back preserve selected month`() {
        val initial = TrainingDateCalendar(YearMonth.of(2026, 10))
        assertEquals(YearMonth.of(2026, 9), initial.navigate("previous").month)
        assertEquals(YearMonth.of(2026, 11), initial.navigate("next").month)
        val years = initial.navigate("years")
        assertEquals(CalendarLevel.Years, years.level)
        assertEquals(years.yearPage + 12, years.navigate("later").yearPage)
        assertEquals(years, years.navigate("later").navigate("earlier"))
        assertEquals(CalendarLevel.Months, years.navigate("back").level)
        assertEquals(CalendarLevel.Days, years.navigate("back").navigate("back").level)
        val months = years.navigate("year-2028")
        assertEquals(CalendarLevel.Months, months.level)
        assertEquals(YearMonth.of(2028, 2), months.navigate("month-2").month)
        assertEquals(CalendarLevel.Days, months.navigate("month-2").level)
    }

    @Test
    fun `calendar boundaries expose no navigation outside four digit years`() {
        for ((month, missing) in listOf(YearMonth.of(0, 1) to "‹", YearMonth.of(9999, 12) to "›")) {
            val calendar = TrainingDateCalendar(month)
            val base = checkNotNull(promptForCreation(WaitingDate)).withCreationQuestionToken(TOKEN)
            assertFalse(
                calendar
                    .reply(base)
                    .replyMarkup!!
                    .inlineKeyboard
                    .flatten()
                    .any { it.text == missing },
            )
            val years =
                calendar
                    .navigate("years")
                    .reply(base)
                    .replyMarkup!!
                    .inlineKeyboard
                    .flatten()
            assertFalse(years.any { it.text == if (month.year == 0) "‹ Годы" else "Годы ›" })
        }
    }
}

private const val TOKEN = "12345678.1234567890123"
