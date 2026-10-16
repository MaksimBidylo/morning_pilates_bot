package morningpilates.profile

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AthleteProfileTest {
    private val today = LocalDate.of(2026, 10, 16)

    @ParameterizedTest
    @CsvSource(
        "FirstName, Иван",
        "LastName, Иванов",
        "BirthDate, 29.02.2000",
        "Weight, 85.25",
        "Belt, Blue",
        "RankMarks, 0",
        "BeltDate, 16.10.2026",
        "BjjStart, 10.2026",
    )
    fun `each field can be filled and cleared independently`(
        field: ProfileField,
        value: String,
    ) {
        val original = AthleteProfile()
        val filled = original.withInput(field, value, today)
        assertEquals(original, filled.withInput(field, null, today))
    }

    @ParameterizedTest
    @CsvSource(
        "BirthDate, 29.02.2025",
        "BirthDate, 17.10.2026",
        "BirthDate, 01.01.0000",
        "BeltDate, 31.04.2026",
        "BeltDate, 01.01.2027",
        "BirthDate, 2000-01-01",
        "Weight, -1",
        "Weight, 0",
        "Weight, NaN",
        "Weight, 500.01",
        "Weight, 80.123",
        "Weight, 1e2",
        "Belt, Orange",
        "Belt, Coral",
        "Belt, Red",
        "RankMarks, -1",
        "RankMarks, 5",
        "RankMarks, 1.5",
        "BjjStart, 13.2025",
        "BjjStart, 11.2026",
        "BjjStart, 01.0000",
    )
    fun `invalid values are rejected`(
        field: ProfileField,
        value: String,
    ) {
        assertFailsWith<IllegalArgumentException> { AthleteProfile().withInput(field, value, today) }
    }

    @Test
    fun `names are independent of Telegram and preserve literal markup`() {
        val profile = AthleteProfile().withInput(ProfileField.FirstName, " <Иван> ", today)
        assertEquals("<Иван>", profile.firstName)
        assertNull(profile.lastName)
        for (value in listOf("", " ", "x".repeat(101), "Иван\nИванов")) {
            assertFailsWith<IllegalArgumentException> { profile.withInput(ProfileField.LastName, value, today) }
        }
    }

    @Test
    fun `weight accepts comma and maximum boundary`() {
        assertEquals(BigDecimal("85.25"), AthleteProfile().withInput(ProfileField.Weight, "85,25", today).weight)
        assertEquals(BigDecimal("5E+2"), AthleteProfile().withInput(ProfileField.Weight, "500", today).weight)
    }

    @Test
    fun `belt changes and clearing reset date but preserve independent stripes`() {
        val profile = AthleteProfile(belt = BjjBelt.White, beltDate = today, rankMarks = 4)
        assertEquals(today, profile.withInput(ProfileField.Belt, "White", today).beltDate)
        val changed = profile.withInput(ProfileField.Belt, "Blue", today)
        assertNull(changed.beltDate)
        assertEquals(4, changed.rankMarks)
        assertNull(profile.withInput(ProfileField.Belt, null, today).beltDate)
        assertNull(AthleteProfile(beltDate = today).withInput(ProfileField.Belt, null, today).beltDate)
    }

    @Test
    fun `black belt accepts zero through ten dans and rejects values outside range`() {
        val profile = AthleteProfile(belt = BjjBelt.Black)
        for (value in 0..10) {
            assertEquals(value, profile.withInput(ProfileField.RankMarks, value.toString(), today).rankMarks)
        }
        for (value in listOf("-1", "11", "1.5", "дан")) {
            assertFailsWith<IllegalArgumentException> { profile.withInput(ProfileField.RankMarks, value, today) }
        }
        assertNull(profile.copy(rankMarks = 10).withInput(ProfileField.RankMarks, null, today).rankMarks)
    }

    @Test
    fun `switching rank units clears count while choosing same black belt preserves it`() {
        val color = AthleteProfile(belt = BjjBelt.Brown, rankMarks = 4)
        assertNull(color.withInput(ProfileField.Belt, "Black", today).rankMarks)
        val black = AthleteProfile(belt = BjjBelt.Black, rankMarks = 10, beltDate = today)
        assertEquals(black, black.withInput(ProfileField.Belt, "Black", today))
        assertNull(black.withInput(ProfileField.Belt, "Brown", today).rankMarks)
        assertNull(black.withInput(ProfileField.Belt, null, today).rankMarks)
    }

    @Test
    fun `age and experience are derived at display time`() {
        val profile = AthleteProfile(birthDate = LocalDate.of(2000, 10, 17), bjjStart = YearMonth.of(2025, 11))
        assertEquals(25, profile.age(today))
        assertEquals(26, profile.age(today.plusDays(1)))
        assertEquals(11L, profile.experienceMonths(today))
        assertEquals(12L, profile.experienceMonths(today.plusMonths(1)))
        assertEquals(0L, AthleteProfile(bjjStart = YearMonth.from(today)).experienceMonths(today))
        assertNull(AthleteProfile().age(today))
        assertNull(AthleteProfile().experienceMonths(today))
    }
}
