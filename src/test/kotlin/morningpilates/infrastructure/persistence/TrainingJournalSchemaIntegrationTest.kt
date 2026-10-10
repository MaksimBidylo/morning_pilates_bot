package morningpilates.infrastructure.persistence

import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@Tag("integration")
@Testcontainers
class TrainingJournalSchemaIntegrationTest {
    companion object {
        @Container
        @JvmField
        val postgres = postgresTestContainer()
    }

    @BeforeEach
    fun resetTestSchema() {
        postgres.resetPublicSchema()
    }

    @Test
    fun `saves user and training with ordered techniques and nullable description`() {
        val userId = UUID.randomUUID()
        val trainingId = UUID.randomUUID()
        val firstTechniqueId = UUID.randomUUID()
        val secondTechniqueId = UUID.randomUUID()
        val telegramIdEncrypted = byteArrayOf(0x00, 0x01, 0x7f, 0xff.toByte())
        val telegramIdLookup = byteArrayOf(0xff.toByte(), 0x7f, 0x01, 0x00)
        val trainingDate = LocalDate.of(2026, 10, 10)
        val startTime = LocalTime.of(8, 30)
        val note = "Утренняя тренировка"
        val firstTechniqueName = "Сотня"
        val secondTechniqueName = "Скручивание"
        val secondTechniqueDescription = "Медленно, с контролем дыхания"

        PostgresDatabase.connect(postgres.databaseProperties()).use { database ->
            val dsl = database.dsl
            dsl.execute(
                "INSERT INTO users (id, telegram_id_encrypted, telegram_id_lookup) VALUES (?, ?, ?)",
                userId,
                telegramIdEncrypted,
                telegramIdLookup,
            )
            dsl.execute(
                "INSERT INTO trainings (id, user_id, training_date, start_time, note) VALUES (?, ?, ?, ?, ?)",
                trainingId,
                userId,
                trainingDate,
                startTime,
                note,
            )
            dsl.execute(
                "INSERT INTO training_techniques (id, training_id, position, name, description) VALUES (?, ?, ?, ?, ?)",
                firstTechniqueId,
                trainingId,
                1,
                firstTechniqueName,
                null,
            )
            dsl.execute(
                "INSERT INTO training_techniques (id, training_id, position, name, description) VALUES (?, ?, ?, ?, ?)",
                secondTechniqueId,
                trainingId,
                2,
                secondTechniqueName,
                secondTechniqueDescription,
            )

            assertSavedUser(dsl, userId, telegramIdEncrypted, telegramIdLookup)
            assertSavedTraining(dsl, trainingId, userId, trainingDate, startTime, note)
            assertSavedTechniques(
                dsl,
                trainingId,
                listOf(firstTechniqueId, secondTechniqueId),
                listOf(firstTechniqueName, secondTechniqueName),
                secondTechniqueDescription,
            )
        }
    }

    private fun assertSavedUser(
        dsl: DSLContext,
        userId: UUID,
        telegramIdEncrypted: ByteArray,
        telegramIdLookup: ByteArray,
    ) {
        val user =
            assertNotNull(
                dsl.fetchOne(
                    "SELECT id, telegram_id_encrypted, telegram_id_lookup FROM users WHERE id = ?",
                    userId,
                ),
            )
        assertEquals(userId, user.get("id", UUID::class.java))
        assertContentEquals(telegramIdEncrypted, user.get("telegram_id_encrypted", ByteArray::class.java))
        assertContentEquals(telegramIdLookup, user.get("telegram_id_lookup", ByteArray::class.java))
    }

    private fun assertSavedTraining(
        dsl: DSLContext,
        trainingId: UUID,
        userId: UUID,
        trainingDate: LocalDate,
        startTime: LocalTime,
        note: String,
    ) {
        val training =
            assertNotNull(
                dsl.fetchOne(
                    "SELECT id, user_id, training_date, start_time, note FROM trainings WHERE id = ?",
                    trainingId,
                ),
            )
        assertEquals(trainingId, training.get("id", UUID::class.java))
        assertEquals(userId, training.get("user_id", UUID::class.java))
        assertEquals(trainingDate, training.get("training_date", LocalDate::class.java))
        assertEquals(startTime, training.get("start_time", LocalTime::class.java))
        assertEquals(note, training.get("note", String::class.java))
    }

    private fun assertSavedTechniques(
        dsl: DSLContext,
        trainingId: UUID,
        techniqueIds: List<UUID>,
        techniqueNames: List<String>,
        secondTechniqueDescription: String,
    ) {
        val techniques =
            dsl.fetch(
                "SELECT id, training_id, position, name, description FROM training_techniques " +
                    "WHERE training_id = ? ORDER BY position",
                trainingId,
            )
        assertEquals(2, techniques.size)
        assertEquals(techniqueIds, techniques.map { it.get("id", UUID::class.java) })
        assertEquals(
            listOf(trainingId, trainingId),
            techniques.map { it.get("training_id", UUID::class.java) },
        )
        assertEquals(listOf(1, 2), techniques.map { it.get("position", Int::class.java) })
        assertEquals(techniqueNames, techniques.map { it.get("name", String::class.java) })
        assertNull(techniques[0].get("description", String::class.java))
        assertEquals(secondTechniqueDescription, techniques[1].get("description", String::class.java))
    }
}
