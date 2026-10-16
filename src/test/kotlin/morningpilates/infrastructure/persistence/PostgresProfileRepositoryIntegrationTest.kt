package morningpilates.infrastructure.persistence

import morningpilates.infrastructure.security.TelegramIdProtector
import morningpilates.profile.AthleteProfile
import morningpilates.profile.BjjBelt
import morningpilates.profile.ProfileField
import morningpilates.training.TrainingDetails
import morningpilates.training.TrainingId
import morningpilates.user.TelegramUserId
import org.flywaydb.core.Flyway
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Tag("integration")
@Testcontainers
class PostgresProfileRepositoryIntegrationTest {
    companion object {
        @Container
        @JvmField
        val postgres = postgresTestContainer()
    }

    private val user = TelegramUserId(42L)
    private val other = TelegramUserId(43L)
    private val protector = TelegramIdProtector(ByteArray(32) { it.toByte() }, ByteArray(32) { (it + 32).toByte() })
    private val full =
        AthleteProfile(
            firstName = "Анна",
            lastName = "Иванова",
            birthDate = LocalDate.of(2000, 2, 29),
            weight = BigDecimal("65.25"),
            belt = BjjBelt.Blue,
            rankMarks = 2,
            beltDate = LocalDate.of(2025, 1, 1),
            bjjStart = YearMonth.of(2020, 3),
        )

    @BeforeEach
    fun reset() = postgres.resetPublicSchema()

    @Test
    fun `empty lookup creates neither profile nor user`() {
        PostgresDatabase.connect(postgres.databaseProperties()).use { database ->
            val profiles = PostgresProfileRepository(database.dsl, protector)
            assertEquals(AthleteProfile(), profiles.find(user).getOrThrow())
            assertEquals(0, database.dsl.fetchCount(DSL.table("users")))
            assertEquals(0, database.dsl.fetchCount(DSL.table("athlete_profiles")))
        }
    }

    @Test
    fun `all fields persist after reconnect without a first training and use protected identity`() {
        PostgresDatabase.connect(postgres.databaseProperties()).use { database ->
            PostgresProfileRepository(database.dsl, protector).save(user, full).getOrThrow()
            assertEquals(0, database.dsl.fetchCount(DSL.table("trainings")))
            val stored = database.dsl.fetchSingle("SELECT telegram_id_encrypted, telegram_id_lookup FROM users")
            assertEquals(user.value, protector.decrypt(stored.get("telegram_id_encrypted", ByteArray::class.java)))
            assertTrue(
                protector.lookup(user.value).contentEquals(stored.get("telegram_id_lookup", ByteArray::class.java)),
            )
        }
        PostgresDatabase.connect(postgres.databaseProperties()).use { database ->
            assertEquals(full, PostgresProfileRepository(database.dsl, protector).find(user).getOrThrow())
        }
    }

    @Test
    fun `single optional field upserts and clearing leaves other data unchanged`() {
        PostgresDatabase.connect(postgres.databaseProperties()).use { database ->
            val profiles = PostgresProfileRepository(database.dsl, protector)
            profiles.save(user, AthleteProfile(belt = BjjBelt.White, rankMarks = 0)).getOrThrow()
            assertEquals(AthleteProfile(belt = BjjBelt.White, rankMarks = 0), profiles.find(user).getOrThrow())
            profiles.save(user, full).getOrThrow()
            for (field in ProfileField.entries) {
                val changed = profiles.find(user).getOrThrow().withInput(field, null, LocalDate.of(2026, 10, 16))
                profiles.save(user, changed).getOrThrow()
                assertEquals(changed, profiles.find(user).getOrThrow())
            }
            assertEquals(AthleteProfile(), profiles.find(user).getOrThrow())
            assertEquals(1, database.dsl.fetchCount(DSL.table("users")))
            assertEquals(1, database.dsl.fetchCount(DSL.table("athlete_profiles")))
        }
    }

    @Test
    fun `users cannot read or overwrite each others profiles and diary shares same user`() {
        PostgresDatabase.connect(postgres.databaseProperties()).use { database ->
            val profiles = PostgresProfileRepository(database.dsl, protector)
            val trainings = PostgresTrainingRepository(database.dsl, protector)
            profiles.save(user, full).getOrThrow()
            assertEquals(AthleteProfile(), profiles.find(other).getOrThrow())
            profiles.save(other, AthleteProfile(firstName = "Другой")).getOrThrow()
            profiles.save(other, AthleteProfile(firstName = "Изменённый")).getOrThrow()
            assertEquals(full, profiles.find(user).getOrThrow())
            val trainingId = TrainingId(UUID.randomUUID())
            val details = TrainingDetails(LocalDate.of(2026, 10, 16), LocalTime.NOON, emptyList(), null)
            trainings.save(user, trainingId, details).getOrThrow()
            assertEquals(2, database.dsl.fetchCount(DSL.table("users")))
            assertNull(trainings.findLatest(other).getOrThrow())
            assertEquals(details, trainings.findLatest(user).getOrThrow()?.details)
            assertTrue(!trainings.delete(other, trainingId).getOrThrow())
            assertTrue(trainings.delete(user, trainingId).getOrThrow())
            assertEquals(full, profiles.find(user).getOrThrow())
        }
    }

    @Test
    fun `failed profile insert rolls back creation of user and failed update preserves profile`() {
        PostgresDatabase.connect(postgres.databaseProperties()).use { database ->
            val profiles = PostgresProfileRepository(database.dsl, protector)
            assertTrue(profiles.save(user, AthleteProfile(weight = BigDecimal.ZERO)).isFailure)
            assertEquals(0, database.dsl.fetchCount(DSL.table("users")))
            profiles.save(user, full).getOrThrow()
            assertTrue(profiles.save(user, full.copy(rankMarks = 5)).isFailure)
            assertEquals(full, profiles.find(user).getOrThrow())
        }
    }

    @Test
    fun `belt changes persist reset dependencies and zero remains distinct from null`() {
        PostgresDatabase.connect(postgres.databaseProperties()).use { database ->
            val profiles = PostgresProfileRepository(database.dsl, protector)
            val zero = full.copy(rankMarks = 0)
            profiles.save(user, zero).getOrThrow()
            assertEquals(0, profiles.find(user).getOrThrow().rankMarks)
            val changed =
                profiles
                    .find(user)
                    .getOrThrow()
                    .withInput(ProfileField.Belt, "Purple", LocalDate.of(2026, 10, 16))
            profiles.save(user, changed).getOrThrow()
            val stored = profiles.find(user).getOrThrow()
            assertNull(stored.rankMarks)
            assertNull(stored.beltDate)
            assertEquals(BjjBelt.Purple, stored.belt)
            profiles.save(user, full.withInput(ProfileField.Belt, null, LocalDate.of(2026, 10, 16))).getOrThrow()
            val cleared = profiles.find(user).getOrThrow()
            assertNull(cleared.belt)
            assertNull(cleared.rankMarks)
            assertNull(cleared.beltDate)
        }
    }

    @Test
    fun `V5 preserves V4 profiles and supports ten dans only for black belts`() {
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .target("4")
            .load()
            .migrate()
        val existing = full.copy(belt = BjjBelt.Black, rankMarks = 4)
        postgres.createConnection("").use { connection ->
            val profiles = PostgresProfileRepository(DSL.using(connection, SQLDialect.POSTGRES), protector)
            profiles.save(user, existing).getOrThrow()
            assertTrue(profiles.save(user, existing.copy(rankMarks = 10)).isFailure)
        }
        PostgresDatabase.connect(postgres.databaseProperties()).use { database ->
            val profiles = PostgresProfileRepository(database.dsl, protector)
            assertEquals(existing, profiles.find(user).getOrThrow())
            profiles.save(user, existing.copy(rankMarks = 10)).getOrThrow()
            assertEquals(existing.copy(rankMarks = 10), profiles.find(user).getOrThrow())
            assertTrue(profiles.save(user, existing.copy(rankMarks = 11)).isFailure)
            assertTrue(profiles.save(other, full.copy(rankMarks = 5)).isFailure)
            assertTrue(profiles.save(other, AthleteProfile(rankMarks = 5)).isFailure)
            assertEquals(existing.copy(rankMarks = 10), profiles.find(user).getOrThrow())
            profiles.save(user, existing.copy(rankMarks = null)).getOrThrow()
            assertEquals(existing.copy(rankMarks = null), profiles.find(user).getOrThrow())
        }
    }

    @Test
    fun `V4 preserves existing user training and admin flag`() {
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .target("3")
            .load()
            .migrate()
        val trainingId = TrainingId(UUID.randomUUID())
        val details = TrainingDetails(LocalDate.of(2026, 10, 16), LocalTime.NOON, emptyList(), "До миграции")
        val owner =
            postgres.createConnection("").use { connection ->
                val dsl = DSL.using(connection, SQLDialect.POSTGRES)
                PostgresTrainingRepository(dsl, protector).save(user, trainingId, details).getOrThrow()
                val id = PostgresUserRepository(dsl, protector).getOrCreate(user)
                dsl.execute("UPDATE users SET is_admin = true WHERE id = ?", id)
                id
            }
        PostgresDatabase.connect(postgres.databaseProperties()).use { database ->
            val profiles = PostgresProfileRepository(database.dsl, protector)
            profiles.save(user, full).getOrThrow()
            assertEquals(owner, PostgresUserRepository(database.dsl, protector).getOrCreate(user))
            assertEquals(
                true,
                database.dsl.fetchSingle("SELECT is_admin FROM users WHERE id = ?", owner).get(0, Boolean::class.java),
            )
            assertEquals(
                details,
                PostgresTrainingRepository(database.dsl, protector).findLatest(user).getOrThrow()?.details,
            )
            assertEquals(full, profiles.find(user).getOrThrow())
        }
    }
}
