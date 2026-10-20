package ru.arc.origin

import ru.arc.core.LifecycleTaskScope
import ru.ruscrafting.ranks.api.RankQuestApi
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.logging.Level
import java.util.logging.Logger

/** Best-effort quest delivery after the owning gameplay transaction has succeeded. */
internal class OriginQuestProgress(
    private val tasks: LifecycleTaskScope,
    private val provider: () -> RankQuestApi?,
    private val logger: Logger,
) {
    fun orderCompleted(kind: OriginSideJobKind, playerId: UUID, requestId: UUID) =
        record("origin_sidejob", requestId, playerId, "sidejob.complete:${kind.name.lowercase(Locale.ROOT)}")

    fun furnitureDelivered(playerId: UUID, requestId: UUID, productId: String) =
        record("origin_furniture", requestId, playerId, "furniture.craft:$productId")

    private fun record(source: String, requestId: UUID, playerId: UUID, objective: String) {
        val token = runCatching(tasks::token).getOrNull() ?: return
        val eventId = "$requestId:$playerId"
        fun send(attempt: Int) {
            if (!tasks.isCurrent(token)) return
            val write = runCatching {
                val service = provider()
                if (service == null && attempt == 0) return
                checkNotNull(service) { "ArcRanks quest service became unavailable" }.record(source, eventId, playerId, objective, 1L)
            }.getOrElse { CompletableFuture.failedFuture(it) }
            write.whenComplete { _, failure ->
                if (failure != null) {
                    val retry = if (attempt < 3) runCatching {
                        tasks.runLater(token, 100L * (attempt + 1)) { send(attempt + 1) }
                    }.getOrNull() else null
                    if (retry == null) logger.log(Level.WARNING,
                        "Could not retain Origin quest event source=$source event=$eventId objective=$objective", failure)
                }
            }
        }
        send(0)
    }
}
