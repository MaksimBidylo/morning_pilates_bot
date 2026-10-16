package morningpilates.profile

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BeltDateRuleTest {
    @Test
    fun `date requires belt while clearing an empty date is allowed`() {
        val today = LocalDate.of(2026, 10, 16)
        val empty = AthleteProfile()
        assertFailsWith<IllegalArgumentException> {
            empty.withInput(ProfileField.BeltDate, "01.01.2020", today)
        }
        assertEquals(empty, empty.withInput(ProfileField.BeltDate, null, today))
        for (belt in BjjBelt.selectable) {
            val profile = AthleteProfile(belt = belt)
            val filled = profile.withInput(ProfileField.BeltDate, "01.01.2020", today)
            assertEquals(LocalDate.of(2020, 1, 1), filled.beltDate)
            assertEquals(profile, filled.withInput(ProfileField.BeltDate, null, today))
        }
    }
}
