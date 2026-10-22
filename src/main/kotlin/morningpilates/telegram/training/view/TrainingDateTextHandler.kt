package morningpilates.telegram.training.view

import com.github.kotlintelegrambot.entities.ChatId
import morningpilates.diagnostics.RequestDiagnostics
import morningpilates.telegram.logging.TelegramHandlers
import morningpilates.training.INVALID_DATE_REASON
import morningpilates.training.TrainingQueryService
import morningpilates.training.parseTrainingDate
import morningpilates.user.TelegramUserId

internal fun TelegramHandlers.registerTrainingDateTextHandler(
    queryService: TrainingQueryService,
    viewStorage: TrainingViewStorage,
) {
    text {
        if (message.chat.type != "private" || text.startsWith("/")) return@text
        val userId = TelegramUserId(message.from?.id ?: return@text)
        val active = viewStorage.get(userId) ?: return@text
        val search = active.dateSearch ?: return@text
        if (!search.isWaitingForDate) return@text
        val date = parseTrainingDate(text)
        val next =
            if (date == null) {
                RequestDiagnostics.rejected()
                search.copy(warning = "⚠️ $INVALID_DATE_REASON")
            } else {
                queryService.loadDateSearch(userId, date)
            }
        if (bot.showTrainingDateView(userId, active.chatId, viewStorage, active, next)) {
            bot.deleteMessage(ChatId.fromId(message.chat.id), message.messageId)
        }
    }
}
