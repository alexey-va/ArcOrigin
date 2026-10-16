package ru.arc.origin

import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.npc.NPC
import org.bukkit.Material
import org.bukkit.entity.LivingEntity
import ru.arc.logging.ArcLogging.warn
import ru.arc.origin.scene.OriginSceneResources
import java.util.UUID

/** One chef works on the earliest unfinished preparation while completed dishes await collection. */
internal fun waiterKitchenOrders(orders: List<Pair<OriginWaiterVenue, OriginWaiterJob>>, now: Long) = orders
    .filter { (_, job) -> job.stage == OriginWaiterStage.PICKUP && job.family != OriginWaiterFamily.ASSEMBLED }
    .sortedWith(compareBy<Pair<OriginWaiterVenue, OriginWaiterJob>> { if (now < it.second.readyAt) 0 else 1 }
        .thenBy { it.second.readyAt }.thenBy { it.second.id }.thenBy { it.first.id })

internal fun waiterKitchenProgress(job: OriginWaiterJob, preparationMillis: Long, now: Long): Double =
    if (preparationMillis <= 0) 1.0 else (1.0 - (job.readyAt - now).toDouble() / preparationMillis).coerceIn(0.0, 1.0)

/** These hand phases share the station's preparation clock; a ready chef has no work pose. */
internal fun waiterKitchenHand(config: OriginWaiterConfig, job: OriginWaiterJob, now: Long): Material? {
    if (now >= job.readyAt) return null
    val progress = waiterKitchenProgress(job, config.preparationMillis, now)
    if (job.course == OriginWaiterCourse.DRINK) return if (progress < 0.78) Material.GLASS_BOTTLE else Material.POTION
    return if (progress < 0.18) config.recipes[job.dish.id]?.firstNotNullOfOrNull(config.ingredients::get) ?: Material.BOWL
        else Material.WOODEN_SHOVEL
}

/** The dining owner supplies exclusive actor claims; this helper owns only temporary presentation. */
internal class OriginWaiterKitchen(
    private val config: OriginWaiterConfig,
    private val claim: (OriginWaiterVenue) -> Boolean,
    private val release: (OriginWaiterVenue) -> Unit,
    private val owns: (OriginWaiterVenue) -> Boolean = { true },
) : AutoCloseable {
    private class Owned(val venue: OriginWaiterVenue, val actor: NPC) {
        val entity = actor.entity
        val resources = OriginSceneResources()
        val bubbleOwner = "player-waiter-kitchen:${venue.chefId}:${UUID.randomUUID()}"
        var hand: Material? = null
        var nextGestureAt = 0L
        var nextBubbleAt = 0L
        var lines = emptyList<String>()
        var stopping = false
        var restoreActor = true
    }

    private val owners = mutableMapOf<Int, Owned>()
    private var closed = false

    fun update(orders: List<Pair<OriginWaiterVenue, OriginWaiterJob>>, now: Long) {
        if (closed) return
        val byChef = waiterKitchenOrders(orders, now).groupBy { it.first.chefId }
        owners.values.toList().forEach { owned ->
            if (owned.stopping || owned.venue.chefId !in byChef || actor(owned.venue.chefId)?.entity !== owned.entity ||
                !ownsActor(owned)) stop(owned)
        }
        byChef.forEach { (chefId, tickets) ->
            var owned = owners[chefId]
            if (owned?.stopping == true) return@forEach
            try {
                if (owned == null) {
                    val actor = actor(chefId) ?: return@forEach
                    val venue = tickets.first().first
                    if (!claim(venue)) return@forEach
                    owned = Owned(venue, actor).also { owners[chefId] = it }
                }
                show(checkNotNull(owned), tickets, now)
            } catch (failure: Exception) {
                warn("ORIGIN_WAITER_KITCHEN phase=PRESENTATION_FAILED chef=$chefId", failure)
                owned?.let(::stop)
            }
        }
    }

    private fun show(owned: Owned, tickets: List<Pair<OriginWaiterVenue, OriginWaiterJob>>, now: Long) {
        val (venue, job) = tickets.first()
        val preparing = now < job.readyAt
        if (preparing) {
            val layout = config.stations[venue.id]
            val hand = waiterKitchenHand(config, job, now)
            if (hand != null && hand != owned.hand) {
                owned.resources.equip(owned.actor, hand)
                owned.hand = hand
            }
            if (layout != null && now >= owned.nextGestureAt) {
                val progress = waiterKitchenProgress(job, config.preparationMillis, now)
                val elapsed = (config.preparationMillis - (job.readyAt - now)).coerceAtLeast(0)
                val point = waiterStationCookingFrame(layout, progress, elapsed).contact
                owned.resources.facePoint(owned.actor, point.inWorld(owned.entity.world))
                (owned.entity as? LivingEntity)?.swingMainHand()
                owned.nextGestureAt = now + config.actionMillis
            }
        } else restore(owned)

        val ready = tickets.filter { now >= it.second.readyAt }
        val lines = buildList {
            if (preparing) add(config.line("chef-cooking", "dish" to job.dish.name,
                "seconds" to waiterSeconds(job.readyAt - now), "count" to tickets.size - ready.size))
            ready.firstOrNull()?.second?.let { finished ->
                add(config.line("chef-ready", "dish" to finished.dish.name, "count" to ready.size))
            }
        }.filter(String::isNotBlank)
        if (lines != owned.lines || now >= owned.nextBubbleAt) {
            if (lines.isEmpty()) OriginNpcHolograms.clearTemporaryBubble(venue.chefId, owned.bubbleOwner)
            else OriginNpcHolograms.showTemporaryBubble(venue.chefId, lines, 60, owned.bubbleOwner)
            owned.lines = lines
            owned.nextBubbleAt = now + 1_000
        }
    }

    private fun restore(owned: Owned) {
        val failures = owned.resources.cleanup()
        if (failures.isNotEmpty()) throw IllegalStateException("Waiter chef restoration failed: ${owned.venue.chefId}").apply {
            failures.forEach { addSuppressed(it.failure) }
        }
        owned.hand = null
        owned.nextGestureAt = 0
    }

    /** Keep a failed cleanup owned, so a subsequent update/close retries before another actor claim. */
    private fun stop(owned: Owned) {
        owned.stopping = true
        // Normal finish/Dining reload restores the snapshot. Once superseded, the new
        // actor owner wins: this helper owns no world props that would need removal.
        owned.restoreActor = owned.restoreActor && ownsActor(owned)
        var failure: Exception? = null
        try { OriginNpcHolograms.clearTemporaryBubble(owned.venue.chefId, owned.bubbleOwner) }
        catch (problem: Exception) { failure = problem }
        try { if (owned.restoreActor) restore(owned) }
        catch (problem: Exception) { failure?.addSuppressed(problem) ?: run { failure = problem } }
        if (failure == null) {
            try {
                release(owned.venue)
                owners.remove(owned.venue.chefId, owned)
            } catch (problem: Exception) { failure = problem }
        }
        failure?.let { warn("ORIGIN_WAITER_KITCHEN phase=CLEANUP_FAILED chef=${owned.venue.chefId}", it) }
    }

    private fun ownsActor(owned: Owned): Boolean = try { owns(owned.venue) }
    catch (failure: Exception) {
        warn("ORIGIN_WAITER_KITCHEN phase=OWNERSHIP_CHECK_FAILED chef=${owned.venue.chefId}", failure)
        false
    }

    private fun actor(id: Int): NPC? = CitizensAPI.getNPCRegistry().getById(id)?.takeIf {
        it.isSpawned && it.entity.isValid && it.entity.world.name == OriginDiningLayout.WORLD
    }

    override fun close() {
        closed = true
        owners.values.toList().forEach(::stop)
    }
}
