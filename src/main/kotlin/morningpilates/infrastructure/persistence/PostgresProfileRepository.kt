package morningpilates.infrastructure.persistence

import morningpilates.infrastructure.security.TelegramIdProtector
import morningpilates.profile.AthleteProfile
import morningpilates.profile.BjjBelt
import morningpilates.profile.ProfileRepository
import morningpilates.user.TelegramUserId
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

class PostgresProfileRepository(
    private val dsl: DSLContext,
    private val protector: TelegramIdProtector,
) : ProfileRepository {
    override fun find(userId: TelegramUserId): Result<AthleteProfile> =
        runCatching {
            val row =
                dsl.fetchOne(
                    "SELECT p.* FROM athlete_profiles p JOIN users u ON u.id = p.user_id " +
                        "WHERE u.telegram_id_lookup = ?",
                    protector.lookup(userId.value),
                )
            if (row == null) {
                AthleteProfile()
            } else {
                AthleteProfile(
                    firstName = row.get("first_name", String::class.java),
                    lastName = row.get("last_name", String::class.java),
                    birthDate = row.get("birth_date", LocalDate::class.java),
                    weight = row.get("weight", BigDecimal::class.java)?.stripTrailingZeros(),
                    belt = row.get("belt", String::class.java)?.let(BjjBelt::valueOf),
                    rankMarks = row.get("rank_marks", Int::class.javaObjectType),
                    beltDate = row.get("belt_date", LocalDate::class.java),
                    bjjStart = row.get("bjj_start", LocalDate::class.java)?.let(YearMonth::from),
                )
            }
        }

    override fun save(
        userId: TelegramUserId,
        profile: AthleteProfile,
    ): Result<Unit> =
        runCatching {
            dsl.transaction { configuration ->
                val transaction = DSL.using(configuration)
                val owner = PostgresUserRepository(transaction, protector).getOrCreate(userId)
                transaction.execute(
                    """
                    INSERT INTO athlete_profiles (user_id, first_name, last_name, birth_date, weight, belt, rank_marks, belt_date, bjj_start)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (user_id) DO UPDATE SET
                        first_name = EXCLUDED.first_name, last_name = EXCLUDED.last_name,
                        birth_date = EXCLUDED.birth_date, weight = EXCLUDED.weight, belt = EXCLUDED.belt,
                        rank_marks = EXCLUDED.rank_marks, belt_date = EXCLUDED.belt_date, bjj_start = EXCLUDED.bjj_start
                    """.trimIndent(),
                    owner,
                    profile.firstName,
                    profile.lastName,
                    profile.birthDate,
                    profile.weight,
                    profile.belt?.name,
                    profile.rankMarks,
                    profile.beltDate,
                    profile.bjjStart?.atDay(1),
                )
            }
        }
}
