package morningpilates.telegram.training.view

import com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton
import morningpilates.telegram.api.TelegramChatId
import morningpilates.telegram.api.TelegramMessageId
import morningpilates.telegram.api.registerAcknowledgedCallback
import morningpilates.telegram.logging.TelegramHandlers
import morningpilates.training.TrainingQueryService
import morningpilates.user.TelegramUserId

// View tokens are longer than creation tokens; keep every callback within Telegram's 64-byte limit.
internal const val SEARCH_CALENDAR_PREFIX = "date:"

internal fun TelegramHandlers.registerTrainingSearchCalendar(
    storage: TrainingViewStorage,
    queryService: TrainingQueryService,
) {
    if (!storage.dateCalendarEnabled) return
    registerAcknowledgedCallback(SEARCH_CALENDAR_PREFIX, storage::isFromPreviousLaunch) handle@{
        val user = TelegramUserId(callbackQuery.from.id)
        val active = storage.get(user) ?: return@handle
        val search = active.dateSearch?.takeIf { it.isWaitingForDate } ?: return@handle
        val calendar = search.calendar ?: return@handle
        val message = callbackQuery.message ?: return@handle
        if (message.chat.type != "private" || TelegramChatId(message.chat.id) != active.chatId ||
            TelegramMessageId(message.messageId) != active.messageId
        ) {
            return@handle
        }
        val buttons =
            search
                .toTrainingViewReply(active.token, active.actions)
                .replyMarkup
                ?.inlineKeyboard
                .orEmpty()
        if (buttons.flatten().none {
                (it as? InlineKeyboardButton.CallbackData)?.callbackData == callbackQuery.data
            }
        ) {
            return@handle
        }
        val action = callbackQuery.data.removePrefix(SEARCH_CALENDAR_PREFIX).substringBeforeLast(':')
        if (action == "ignore") return@handle
        val claimed = active.copy(token = storage.newToken(), actions = emptySet())
        if (!storage.putIfCurrent(user, active, claimed)) return@handle
        if (action.startsWith("day-")) {
            val date = calendar.month.atDay(action.removePrefix("day-").toInt())
            bot.showCommittedTrainingViewContent(
                user,
                storage,
                claimed,
                TrainingViewContent(dateSearch = queryService.loadDateSearch(user, date)),
            )
        } else {
            val next = search.copy(calendar = calendar.navigate(action), warning = null)
            if (!bot.showTrainingDateView(user, active.chatId, storage, claimed, next)) {
                storage.putIfCurrent(user, claimed, active)
            }
        }
    }
}
