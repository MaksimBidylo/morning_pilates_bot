package morningpilates.telegram.support

import com.github.kotlintelegrambot.entities.CallbackQuery
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal suspend fun TestTelegramBot.deliverViewCallbackOnce(callback: CallbackQuery): CallbackInteraction {
    val before = answerCallbackQueryRequests.size
    val result = deliverCallbackAndCollectReplies(callback)
    assertTrue(result.acknowledged)
    assertEquals(before + 1, answerCallbackQueryRequests.size)
    assertEquals(callback.id, answerCallbackQueryRequests.last().callbackQueryId)
    return result
}

internal suspend fun TrainingDateViewTestFixture.assertIgnoredViewCallback(
    bot: TestTelegramBot,
    callback: CallbackQuery,
    notice: String? = null,
) {
    val before = bot.apiCalls.size
    val reads = repository.findByDateCalls.toList()
    val latest = repository.findLatestCalls.toList()
    val saves = repository.saveCalls.toList()
    val previousEvents = events.toList()
    val session = storage.get(user)
    val creation = messages.get(user)
    val token = messages.questionToken(user)

    val result = bot.deliverViewCallbackOnce(callback)

    assertTrue(result.replies.isEmpty())
    assertEquals(listOf("answerCallbackQuery"), bot.apiCalls.drop(before))
    assertEquals(TelegramCallbackAnswer(callback.id, notice), bot.answerCallbackQueryRequests.last())
    assertEquals(reads, repository.findByDateCalls)
    assertEquals(latest, repository.findLatestCalls)
    assertEquals(saves, repository.saveCalls)
    assertEquals(previousEvents, events)
    assertEquals(session, storage.get(user))
    assertEquals(creation, messages.get(user))
    assertEquals(token, messages.questionToken(user))
}
