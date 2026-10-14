package ru.arc.origin

import dev.lone.itemsadder.api.CustomStack
import com.google.gson.JsonElement
import com.google.gson.GsonBuilder
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import ru.arc.core.LifecycleTaskScope
import ru.arc.redis.safety.BoundedJsonCodec
import ru.arc.redis.safety.JsonObjectContract
import ru.arc.redis.safety.JsonResourceBounds
import ru.arc.redis.safety.JsonRootContract
import ru.arc.redis.safety.RedisHashDecision
import ru.arc.redis.safety.RedisHashUpdateResult
import ru.arc.redis.safety.RedisHashUpdater
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level

internal data class WorkshopCraftClaim(val request: String, val nextAt: Long) {
    fun validate() {
        require(UUID.fromString(request).toString() == request)
        require(nextAt > 0)
    }
}

internal const val WORKSHOP_COOLDOWN_BYPASS = "arc.origin.workshop.cooldown.bypass"

internal fun workshopClaimAllowed(current: WorkshopCraftClaim?, request: UUID, now: Long, bypass: Boolean = false): Boolean =
    current == null || (current.request != request.toString() && (bypass || now >= current.nextAt))

internal fun workshopReleaseClaim(current: WorkshopCraftClaim?, claim: WorkshopCraftClaim, previous: WorkshopCraftClaim?): RedisHashDecision<WorkshopCraftClaim> =
    when {
        current != claim -> RedisHashDecision.Reject
        previous != null -> RedisHashDecision.Write(previous)
        else -> RedisHashDecision.Delete
    }

internal fun workshopCooldownText(remainingMillis: Long): String {
    val minutes = (remainingMillis / 60_000 + if (remainingMillis % 60_000 > 0) 1 else 0).coerceAtLeast(1)
    return listOfNotNull(
        (minutes / 60).takeIf { it > 0 }?.let { "$it ч" },
        (minutes % 60).takeIf { it > 0 }?.let { "$it мин" },
    ).joinToString(" ")
}

internal fun workshopCraftClaimCodec() = BoundedJsonCodec(
    GsonBuilder().create(), WorkshopCraftClaim::class.java,
    object : JsonRootContract {
        override fun validate(value: JsonElement) {
            JsonObjectContract(allowedFields = setOf("request", "nextAt")).validate(value)
            val request = value.asJsonObject.get("request")
            val nextAt = value.asJsonObject.get("nextAt")
            require(request.isJsonPrimitive && request.asJsonPrimitive.isString)
            require(nextAt.isJsonPrimitive && nextAt.asJsonPrimitive.isNumber)
            require(nextAt.asString.matches(Regex("[1-9][0-9]{0,18}")))
            require(nextAt.asString.toLongOrNull() != null)
        }
    },
    JsonResourceBounds(maxCharacters = 256, maxDepth = 2, maxContainerEntries = 4,
        maxTotalNodes = 8, maxStringCharacters = 64),
) { it.validate() }

internal sealed interface WorkshopCraftPlan {
    data class Ready(val contents: Array<ItemStack?>) : WorkshopCraftPlan
    data object Full : WorkshopCraftPlan
}

/** Add one finished furniture item to a detached snapshot; workshop stock never enters inventory. */
internal fun planWorkshopCraft(
    storage: Array<ItemStack?>,
    reward: ItemStack,
): WorkshopCraftPlan {
    require(!reward.type.isAir)
    val after = storage.map { it?.clone() }.toTypedArray()
    val slot = after.indices.firstOrNull { after[it]?.let { stack ->
        stack.isSimilar(reward) && stack.amount < stack.maxStackSize
    } == true } ?: after.indices.firstOrNull { after[it] == null || after[it]?.type?.isAir == true }
        ?: return WorkshopCraftPlan.Full
    after[slot] = after[slot]?.takeUnless { it.type.isAir }?.also { it.amount++ }
        ?: reward.clone().also { it.amount = 1 }
    return WorkshopCraftPlan.Ready(after)
}

/** Async durable quota, followed by one server-thread furniture delivery. */
internal class OriginWorkshopCraftRewards(
    private val cooldownMillis: Long,
    private val productId: String,
) : AutoCloseable {
    private val redis = ArcOriginPlugin.instance.redisClient
    private val tasks = LifecycleTaskScope()
    @Volatile private var closed = false
    private val pending = ConcurrentHashMap.newKeySet<UUID>()
    private val codec = workshopCraftClaimCodec()
    private val updater = redis?.let { RedisHashUpdater(it, KEY, codec) }

    init {
        require(cooldownMillis in 3_600_000L..604_800_000L)
    }

    fun missing(player: Player): String? = when (val plan = plan(player)) {
        is WorkshopCraftPlan.Ready -> null
        WorkshopCraftPlan.Full -> "Освободи место для готовой мебели."
        null -> "Мастерская временно недоступна."
    }

    fun status(player: Player): CompletableFuture<Long> {
        val bypass = player.hasPermission(WORKSHOP_COOLDOWN_BYPASS)
        return redis?.loadMapEntries(KEY, player.uniqueId.toString())?.thenApply { values ->
            require(values.size == 1)
            val claim = values.single()?.let(codec::decode)
            if (bypass) 0L else claim?.let { (it.nextAt - System.currentTimeMillis()).coerceAtLeast(0L) } ?: 0L
        } ?: CompletableFuture.failedFuture(IllegalStateException("Workshop quota storage unavailable"))
    }

    fun complete(player: Player, requestId: UUID, stillValid: () -> Boolean, callback: (String?) -> Unit) {
        if (closed || !player.isOnline || !stillValid()) return
        val playerId = player.uniqueId
        if (!pending.add(playerId)) return
        val problem = missing(player)
        val store = updater
        if (problem != null || store == null) {
            pending.remove(playerId)
            callback(problem ?: "Мастерская временно недоступна.")
            return
        }
        val now = System.currentTimeMillis()
        val bypass = player.hasPermission(WORKSHOP_COOLDOWN_BYPASS)
        val claim = WorkshopCraftClaim(requestId.toString(), Math.addExact(now, cooldownMillis))
        val generation = tasks.token()
        store.update(playerId.toString()) { current ->
            if (workshopClaimAllowed(current, requestId, now, bypass)) RedisHashDecision.Write(claim)
            else RedisHashDecision.Reject
        }.whenComplete { result, failure ->
            if (closed) {
                if (result is RedisHashUpdateResult.Changed && result.after == claim) releaseUnused(playerId, claim, result.before)
                pending.remove(playerId)
                if (pending.isEmpty()) tasks.close()
                return@whenComplete
            }
            tasks.runSync(generation) {
                pending.remove(playerId)
                if (closed) {
                    if (result is RedisHashUpdateResult.Changed && result.after == claim) releaseUnused(playerId, claim, result.before)
                    if (pending.isEmpty()) tasks.close()
                    return@runSync
                }
                if (failure != null) {
                    ArcOriginPlugin.instance.logger.log(Level.WARNING, "ORIGIN_WORKSHOP_GAME claim unconfirmed player=$playerId request=$requestId", failure)
                    if (!closed && player.isOnline && stillValid()) callback("Не удалось подтвердить сборку. Попробуй позже.")
                    return@runSync
                }
                if (result !is RedisHashUpdateResult.Changed || result.after != claim) {
                    if (!closed && player.isOnline && stillValid()) callback("Сегодня мебель уже получена. Приходи после окончания перерыва.")
                    return@runSync
                }
                // The durable claim precedes the inventory side effect. Unknown crash outcomes
                // retain the quota and are never replayed; inventory changes use one snapshot.
                if (closed || !player.isOnline || !stillValid()) {
                    releaseUnused(playerId, claim, result.before)
                    if (closed && pending.isEmpty()) tasks.close()
                    return@runSync
                }
                val before = player.inventory.storageContents.map { it?.clone() }.toTypedArray()
                when (val plan = plan(player)) {
                    is WorkshopCraftPlan.Ready -> try {
                        player.inventory.storageContents = plan.contents
                    } catch (error: Exception) {
                        runCatching { player.inventory.storageContents = before }
                        ArcOriginPlugin.instance.logger.log(Level.SEVERE, "ORIGIN_WORKSHOP_GAME exchange uncertain player=$playerId request=$requestId", error)
                        callback("Не удалось завершить выдачу. Сообщи администрации; повторная выдача заблокирована.")
                        return@runSync
                    }
                    else -> {
                        releaseUnused(playerId, claim, result.before)
                        callback(missing(player) ?: "Не удалось выдать мебель. Освободи место и начни снова.")
                        return@runSync
                    }
                }
                ArcOriginPlugin.instance.logger.info("ORIGIN_WORKSHOP_GAME reward=DELIVERED player=$playerId request=$requestId product=$productId")
                callback(null)
            }
        }
    }

    private fun plan(player: Player): WorkshopCraftPlan? {
        val reward = CustomStack.getInstance(productId)?.itemStack?.clone() ?: return null
        return planWorkshopCraft(player.inventory.storageContents, reward)
    }

    private fun releaseUnused(playerId: UUID, claim: WorkshopCraftClaim, previous: WorkshopCraftClaim?) {
        updater?.update(playerId.toString()) { current ->
            workshopReleaseClaim(current, claim, previous)
        }?.whenComplete { _, failure ->
            if (failure != null) ArcOriginPlugin.instance.logger.log(Level.WARNING,
                "ORIGIN_WORKSHOP_GAME unused claim retained player=$playerId request=${claim.request}", failure)
        }
    }

    override fun close() {
        closed = true
        // Let an in-flight durable claim finish and release it if the owner reloaded.
        if (pending.isEmpty()) tasks.close()
    }

    private companion object {
        const val KEY = "arc.origin-furniture-craft.v1"
    }
}
