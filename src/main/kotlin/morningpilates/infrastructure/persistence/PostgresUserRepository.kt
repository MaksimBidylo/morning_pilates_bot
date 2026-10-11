package morningpilates.infrastructure.persistence

import morningpilates.infrastructure.security.TelegramIdProtector
import morningpilates.user.UserRepository
import org.jooq.DSLContext
import java.util.UUID

class PostgresUserRepository(
    private val dsl: DSLContext,
    private val protector: TelegramIdProtector,
) : UserRepository {
    private companion object {
        const val FIND_USER_ID_BY_TELEGRAM_ID_SQL = "SELECT id FROM users WHERE telegram_id_lookup = ?"
    }

    override fun getOrCreate(telegramUserId: Long): UUID {
        val lookup = protector.lookup(telegramUserId)
        val existingUserId =
            dsl
                .fetchOne(FIND_USER_ID_BY_TELEGRAM_ID_SQL, lookup)
                ?.get("id", UUID::class.java)
        if (existingUserId != null) {
            return existingUserId
        }

        val userId = UUID.randomUUID()
        val insertedRows =
            dsl.execute(
                "INSERT INTO users (id, telegram_id_encrypted, telegram_id_lookup) VALUES (?, ?, ?) " +
                    "ON CONFLICT (telegram_id_lookup) DO NOTHING",
                userId,
                protector.encrypt(telegramUserId),
                lookup,
            )
        return if (insertedRows == 1) {
            userId
        } else {
            dsl
                .fetchSingle(FIND_USER_ID_BY_TELEGRAM_ID_SQL, lookup)
                .get("id", UUID::class.java)
        }
    }
}
