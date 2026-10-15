package morningpilates.telegram.training.view

import morningpilates.telegram.api.TelegramChatId
import morningpilates.telegram.api.TelegramMessageId
import morningpilates.training.SavedTraining
import morningpilates.user.TelegramUserId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@JvmInline
value class TrainingViewToken(
    val value: String,
)

enum class TrainingViewAction(
    val callbackName: String,
    val buttonText: String,
) {
    Delete("delete", "Удалить тренировку"),
    ConfirmDelete("delete-yes", "Да, удалить"),
    CancelDelete("delete-no", "Отмена"),
    Previous("previous", "Назад"),
    Next("next", "Следующая"),
    ChangeDate("date", "Выбрать другую дату"),
    Retry("retry", "Повторить попытку"),
    Finish("finish", "Завершить просмотр"),
}

data class ActiveTrainingView(
    val chatId: TelegramChatId,
    val messageId: TelegramMessageId,
    val token: TrainingViewToken,
    val actions: Set<TrainingViewAction>,
    val dateSearch: TrainingDateSearch? = null,
    val latestTraining: SavedTraining? = null,
    val deletionTarget: SavedTraining? = null,
)

private val VIEW_LAUNCH_FORMAT = Regex("[0-9a-f]{8}")

class TrainingViewStorage {
    private val launchToken = UUID.randomUUID().toString().take(8)
    private val views = ConcurrentHashMap<TelegramUserId, ActiveTrainingView>()

    internal fun newToken(): TrainingViewToken = TrainingViewToken("$launchToken.${UUID.randomUUID()}")

    internal fun isFromPreviousLaunch(callbackData: String): Boolean {
        val token = callbackData.substringAfterLast(':')
        val parts = token.split('.', limit = 2)
        val launch = parts.first()
        val marked = parts.size == 2 && launch.matches(VIEW_LAUNCH_FORMAT)
        val uuid = parts.last()
        val validUuid = runCatching { UUID.fromString(uuid).toString() == uuid }.getOrDefault(false)
        val knownFormat = parts.size == 1 || marked
        return validUuid && knownFormat && launch != launchToken
    }

    fun get(userId: TelegramUserId): ActiveTrainingView? = views[userId]

    fun putIfCurrent(
        userId: TelegramUserId,
        expected: ActiveTrainingView?,
        view: ActiveTrainingView,
    ): Boolean =
        if (expected == null) {
            views.putIfAbsent(userId, view) == null
        } else {
            views.replace(userId, expected, view)
        }

    internal fun isCurrent(
        userId: TelegramUserId,
        expected: ActiveTrainingView?,
    ): Boolean = views[userId] == expected

    fun remove(
        userId: TelegramUserId,
        expected: ActiveTrainingView,
    ): Boolean = views.remove(userId, expected)
}
