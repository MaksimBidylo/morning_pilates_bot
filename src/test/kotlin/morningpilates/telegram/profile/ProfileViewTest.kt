package morningpilates.telegram.profile

import morningpilates.profile.AthleteProfile
import morningpilates.profile.BjjBelt
import morningpilates.profile.ProfileField
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun `each selected field exposes only its choices clear and back`() {
        for (field in ProfileField.entries) {
            val content = ProfileContent(AthleteProfile(belt = BjjBelt.Black), ProfileMode.Edit, field)
            val expected =
                when (field) {
                    ProfileField.Belt -> BjjBelt.selectable.map { "value-${it.name}" }
                    ProfileField.RankMarks -> (0..10).map { "value-$it" }
                    else -> emptyList()
                } + listOf("clear", "back")
            assertEquals(expected, content.actions().map { it.second })
            assertFalse(content.actions().any { it.second == "finish" })
        }
    }

    @Test
    fun `weight error precedes a single instruction and example`() {
        val content =
            ProfileContent(
                AthleteProfile(),
                ProfileMode.Edit,
                ProfileField.Weight,
                warning = "Некорректный вес.",
            )
        val text = content.text(LocalDate.of(2026, 10, 16))
        val expected =
            """
            Вес

            ⚠️ Некорректный вес.

            Введите значение больше 0 и не более 150 кг, до двух знаков после точки или запятой.

            Например: 85.5
            """.trimIndent()
        assertTrue(text.endsWith(expected))
        assertEquals(1, Regex("Введите").findAll(text).count())
        assertEquals(listOf("Очистить", "Назад"), content.actions().map { it.first })
    }

    @Test
    fun `all callback payloads fit Telegram limit`() {
        val token = "12345678.1234567890abcdef"
        for (belt in listOf(null) + BjjBelt.selectable) {
            for (mode in ProfileMode.entries) {
                for (field in listOf(null) + ProfileField.entries) {
                    val content = ProfileContent(AthleteProfile(belt = belt), mode, field)
                    for ((_, action) in content.actions()) {
                        assertTrue("profile:$action:$token".toByteArray().size <= 64)
                    }
                }
            }
        }
    }
}
