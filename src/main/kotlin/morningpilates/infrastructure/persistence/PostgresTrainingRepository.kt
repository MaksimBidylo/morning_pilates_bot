package morningpilates.infrastructure.persistence

import morningpilates.infrastructure.security.TelegramIdProtector
import morningpilates.training.SavedTraining
import morningpilates.training.TechniqueDraft
import morningpilates.training.TrainingDetails
import morningpilates.training.TrainingId
import morningpilates.training.TrainingRepository
import morningpilates.user.TelegramUserId
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

class PostgresTrainingRepository(
    private val dsl: DSLContext,
    private val protector: TelegramIdProtector,
) : TrainingRepository {
    override fun findLatest(telegramUserId: TelegramUserId): Result<SavedTraining?> =
        runCatching {
            val training =
                dsl.fetchOne(
                    "SELECT t.id, t.training_date, t.start_time, t.note FROM trainings t " +
                        "JOIN users u ON u.id = t.user_id WHERE u.telegram_id_lookup = ? " +
                        "ORDER BY t.training_date DESC, t.start_time DESC, t.created_at DESC LIMIT 1",
                    protector.lookup(telegramUserId.value),
                ) ?: return@runCatching null
            val trainingId = TrainingId(checkNotNull(training.get("id", UUID::class.java)))
            toSavedTraining(training, loadTechniques(dsl, trainingId))
        }

    override fun findByDate(
        telegramUserId: TelegramUserId,
        date: LocalDate,
    ): Result<List<SavedTraining>> =
        runCatching {
            val trainings =
                dsl.fetch(
                    "SELECT t.id, t.training_date, t.start_time, t.note FROM trainings t " +
                        "JOIN users u ON u.id = t.user_id WHERE u.telegram_id_lookup = ? AND t.training_date = ? " +
                        "ORDER BY t.start_time DESC, t.created_at DESC",
                    protector.lookup(telegramUserId.value),
                    date,
                )
            if (trainings.isEmpty()) return@runCatching emptyList()
            val trainingIds = trainings.map { checkNotNull(it.get("id", UUID::class.java)) }
            val techniquesByTraining = loadTechniquesByTraining(trainingIds)
            trainings.map { training ->
                val id = checkNotNull(training.get("id", UUID::class.java))
                toSavedTraining(training, techniquesByTraining[id].orEmpty())
            }
        }

    override fun save(
        telegramUserId: TelegramUserId,
        trainingId: TrainingId,
        details: TrainingDetails,
    ): Result<Unit> =
        runCatching {
            dsl.transaction { configuration ->
                val transactionalDsl = DSL.using(configuration)
                val userId = PostgresUserRepository(transactionalDsl, protector).getOrCreate(telegramUserId)
                if (insertTrainingIfAbsent(transactionalDsl, userId, trainingId, details)) {
                    insertTechniques(transactionalDsl, trainingId, details.techniques)
                } else {
                    require(matchesExistingTraining(transactionalDsl, userId, trainingId, details)) {
                        "Training ID is already used for a different user or training data"
                    }
                }
            }
        }

    private fun toSavedTraining(
        training: Record,
        techniques: List<TechniqueDraft>,
    ): SavedTraining {
        val trainingId = TrainingId(checkNotNull(training.get("id", UUID::class.java)))
        return SavedTraining(
            id = trainingId,
            details =
                TrainingDetails(
                    date = checkNotNull(training.get("training_date", LocalDate::class.java)),
                    time = checkNotNull(training.get("start_time", LocalTime::class.java)),
                    techniques = techniques,
                    note = training.get("note", String::class.java),
                ),
        )
    }

    private fun insertTrainingIfAbsent(
        transactionalDsl: DSLContext,
        userId: UUID,
        trainingId: TrainingId,
        details: TrainingDetails,
    ): Boolean =
        transactionalDsl.execute(
            "INSERT INTO trainings (id, user_id, training_date, start_time, note) VALUES (?, ?, ?, ?, ?) " +
                "ON CONFLICT (id) DO NOTHING",
            trainingId.value,
            userId,
            details.date,
            details.time,
            details.note,
        ) == 1

    private fun insertTechniques(
        transactionalDsl: DSLContext,
        trainingId: TrainingId,
        techniques: List<TechniqueDraft>,
    ) {
        techniques.forEachIndexed { index, technique ->
            transactionalDsl.execute(
                "INSERT INTO training_techniques (id, training_id, position, name, description) " +
                    "VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(),
                trainingId.value,
                index + 1,
                technique.name,
                technique.description,
            )
        }
    }

    private fun matchesExistingTraining(
        transactionalDsl: DSLContext,
        userId: UUID,
        trainingId: TrainingId,
        details: TrainingDetails,
    ): Boolean {
        val matches =
            transactionalDsl
                .fetchOne(
                    "SELECT user_id = ? AND training_date = ? AND start_time = CAST(? AS TIME) " +
                        "AND note IS NOT DISTINCT FROM CAST(? AS TEXT) AS matches FROM trainings WHERE id = ?",
                    userId,
                    details.date,
                    details.time,
                    details.note,
                    trainingId.value,
                )?.get("matches", Boolean::class.java)
        if (matches != true) {
            return false
        }
        return loadTechniques(transactionalDsl, trainingId) == details.techniques
    }

    private fun loadTechniquesByTraining(trainingIds: List<UUID>): Map<UUID, List<TechniqueDraft>> {
        val trainingId = DSL.field(DSL.name("training_id"), UUID::class.java)
        val position = DSL.field(DSL.name("position"), Int::class.java)
        val name = DSL.field(DSL.name("name"), String::class.java)
        val description = DSL.field(DSL.name("description"), String::class.java)
        return dsl
            .select(trainingId, position, name, description)
            .from(DSL.table(DSL.name("training_techniques")))
            .where(trainingId.`in`(trainingIds))
            .orderBy(trainingId, position)
            .fetch()
            .groupBy(
                keySelector = { checkNotNull(it.get(trainingId)) },
                valueTransform = { TechniqueDraft(checkNotNull(it.get(name)), it.get(description)) },
            )
    }

    private fun loadTechniques(
        context: DSLContext,
        trainingId: TrainingId,
    ): List<TechniqueDraft> =
        context
            .fetch(
                "SELECT name, description FROM training_techniques WHERE training_id = ? ORDER BY position",
                trainingId.value,
            ).map {
                TechniqueDraft(
                    name = checkNotNull(it.get("name", String::class.java)),
                    description = it.get("description", String::class.java),
                )
            }
}
