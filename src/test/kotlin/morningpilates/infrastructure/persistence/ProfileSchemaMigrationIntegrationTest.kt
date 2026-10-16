package morningpilates.infrastructure.persistence

import morningpilates.infrastructure.security.TelegramIdProtector
import morningpilates.profile.AthleteProfile
import morningpilates.profile.BjjBelt
import morningpilates.user.TelegramUserId
import org.jooq.impl.DSL
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Tag("integration")
@Testcontainers
class ProfileSchemaMigrationIntegrationTest {
    companion object {
        @Container
        @JvmField
        val postgres = postgresTestContainer()
    }

    private val user = TelegramUserId(42)
    private val protector = TelegramIdProtector(ByteArray(32) { it.toByte() }, ByteArray(32) { (it + 32).toByte() })
    private val boundary =
        AthleteProfile(
            firstName = "Я".repeat(50),
            lastName = "Ю".repeat(50),
            weight = BigDecimal("1.5E+2"),
            belt = BjjBelt.Black,
            rankMarks = 10,
            beltDate = LocalDate.of(2020, 1, 1),
        )

    @BeforeEach
    fun reset() = postgres.resetPublicSchema()

    @Test
    fun `V4 profiles preserve ten dans zero and null across reconnects`() {
        val profiles = listOf(boundary, AthleteProfile(belt = BjjBelt.White, rankMarks = 0), AthleteProfile())
        PostgresDatabase.connect(postgres.databaseProperties()).use { database ->
            val repository = PostgresProfileRepository(database.dsl, protector)
            profiles.forEachIndexed { index, profile ->
                repository.save(TelegramUserId(42L + index), profile).getOrThrow()
            }
        }
        repeat(2) {
            PostgresDatabase.connect(postgres.databaseProperties()).use { database ->
                val repository = PostgresProfileRepository(database.dsl, protector)
                profiles.forEachIndexed { index, expected ->
                    assertEquals(expected, repository.find(TelegramUserId(42L + index)).getOrThrow())
                }
                val columns =
                    database.dsl
                        .fetchSingle("SELECT * FROM athlete_profiles LIMIT 1")
                        .fields()
                        .map { it.name }
                assertTrue("rank_marks" in columns)
                assertFalse("stripes" in columns)
                assertEquals(3, database.dsl.fetchCount(DSL.table("athlete_profiles")))
                val version = database.dsl.fetchSingle("SELECT max(version::int)::text FROM flyway_schema_history")
                assertEquals("4", version.get(0))
            }
        }
    }

    @Test
    fun `database accepts boundaries and rejects oversized names weight and beltless date`() {
        PostgresDatabase.connect(postgres.databaseProperties()).use { database ->
            val repository = PostgresProfileRepository(database.dsl, protector)
            repository.save(user, boundary).getOrThrow()
            val invalid =
                listOf(
                    boundary.copy(firstName = "Я".repeat(51)),
                    boundary.copy(lastName = "Ю".repeat(51)),
                    boundary.copy(weight = BigDecimal("150.01")),
                    boundary.copy(belt = null, rankMarks = null),
                )
            for (profile in invalid) {
                assertTrue(repository.save(user, profile).isFailure)
                assertEquals(boundary, repository.find(user).getOrThrow())
                assertTrue(repository.save(TelegramUserId(43), profile).isFailure)
                assertEquals(1, database.dsl.fetchCount(DSL.table("users")))
            }
        }
    }
}
