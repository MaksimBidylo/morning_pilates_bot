package morningpilates.infrastructure.persistence

import morningpilates.infrastructure.security.TelegramIdProtector
import morningpilates.training.TechniqueDraft
import morningpilates.training.TrainingDetails
import morningpilates.training.TrainingId
import morningpilates.training.TrainingRepository
import morningpilates.user.TelegramUserId
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.util.UUID

class PostgresTrainingRepository(
    private val dsl: DSLContext,
    private val protector: TelegramIdProtector,
) : TrainingRepository {
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
        val techniques =
            transactionalDsl
                .fetch(
                    "SELECT name, description FROM training_techniques WHERE training_id = ? ORDER BY position",
                    trainingId.value,
                ).map {
                    TechniqueDraft(
                        name = it.get("name", String::class.java),
                        description = it.get("description", String::class.java),
                    )
                }
        return techniques == details.techniques
    }
}
