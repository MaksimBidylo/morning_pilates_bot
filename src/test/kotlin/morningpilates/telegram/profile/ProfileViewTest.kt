package morningpilates.telegram.profile

import morningpilates.profile.AthleteProfile
import morningpilates.profile.ProfileField
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertTrue

class ProfileViewTest {
    @Test
    fun `display calculates current age and experience without formatting user markup`() {
        val profile =
            AthleteProfile(
                firstName = "<Анна & Иван>",
                birthDate = LocalDate.of(2000, 10, 17),
                bjjStart = YearMonth.of(2025, 11),
            )
        val text = profile.text(LocalDate.of(2026, 10, 16))
        assertTrue(text.contains("Возраст (лет): 25"))
        assertTrue(text.contains("Стаж BJJ: 0 г. 11 мес."))
        assertTrue(text.contains("Имя: <Анна & Иван>"))
        val later = profile.text(LocalDate.of(2026, 11, 16))
        assertTrue(later.contains("Возраст (лет): 26"))
        assertTrue(later.contains("Стаж BJJ: 1 г. 0 мес."))
    }

    @Test
    fun `all callback payloads fit Telegram limit`() {
        val token = "12345678.1234567890abcdef"
        for (field in listOf(null) + ProfileField.entries) {
            for ((_, action) in profileActions(field)) {
                assertTrue("profile:$action:$token".toByteArray().size <= 64)
            }
        }
    }
}
