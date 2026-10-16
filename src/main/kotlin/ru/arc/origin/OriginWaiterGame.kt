package ru.arc.origin

import net.citizensnpcs.api.CitizensAPI
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import ru.arc.config.Config
import ru.arc.config.ConfigSection
import ru.arc.core.LifecycleTaskScope
import ru.arc.logging.ArcLogging.info
import ru.arc.logging.ArcLogging.warn
import ru.arc.origin.menu.OriginMenus
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogBody
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogScreen
import java.util.UUID

internal data class OriginWaiterVenue(val id: String, val staffId: Int, val sneakOnly: Boolean)

internal data class OriginWaiterConfig(
    val venues: List<OriginWaiterVenue>,
    val lifetimeMillis: Long,
    val preparationMillis: Long,
    val interactionRange: Double,
    val text: ConfigSection,
) {
    fun line(key: String, vararg values: Pair<String, Any>): String = values.fold(text.string(key)) { line, (name, value) ->
        line.replace("{$name}", value.toString())
    }

    companion object {
        fun load(source: Config) = OriginWaiterConfig(
            venues = source.stringList("waiter-game.venue-ids").map { id ->
                OriginWaiterVenue(id, source.integer("waiter-game.venues.$id.staff-id"),
                    source.boolean("waiter-game.venues.$id.sneak-only", false))
            }.also { venues ->
                require(venues.map { it.id }.distinct().size == venues.size)
                require(venues.map { it.staffId }.distinct().size == venues.size)
                require(venues.all { it.staffId >= 0 })
            },
            lifetimeMillis = source.integer("waiter-game.timeout-seconds", 180).coerceIn(60, 600) * 1000L,
            preparationMillis = source.integer("waiter-game.preparation-seconds", 10).coerceIn(1, 30) * 1000L,
            interactionRange = source.real("waiter-game.interaction-range", 4.5).takeIf(Double::isFinite)?.coerceIn(2.0, 6.0) ?: 4.5,
            text = source.section("waiter-game.text"),
        )
    }
}

/** Plain configuration substitutions never interpret NPC names as MiniMessage. */
internal fun waiterText(text: String, color: TextColor = TextColor.color(0xE8DFD2)): Component {
    val chunks = text.split("💰")
    return chunks.drop(1).fold(Component.text(chunks.first(), color)) { result, chunk ->
        result.append(Component.text("💰", NamedTextColor.WHITE)).append(Component.text(chunk, color))
    }.decoration(TextDecoration.ITALIC, false)
}

internal fun waiterNpcName(raw: String): String = PlainTextComponentSerializer.plainText()
    .serialize(LegacyComponentSerializer.legacyAmpersand().deserialize(raw.replace('§', '&')))

internal fun waiterScreen(
    config: OriginWaiterConfig,
    venue: OriginWaiterVenue,
    job: OriginWaiterJob?,
    amount: Int,
    status: WaiterRewardStatus?,
    now: Long,
    notice: String? = null,
    start: () -> Unit = {},
    pickup: () -> Unit = {},
    cancel: () -> Unit = {},
): PaperDialogScreen {
    val lines = if (job == null) buildList {
        add(config.line("introduction"))
        add(config.line("pay", "amount" to amount))
        when {
            notice != null -> add(notice)
            status == null -> add(config.line("checking"))
            status.unresolved -> add(config.line("unconfirmed"))
            !status.available -> add(config.line("unavailable"))
            status.remainingOrders <= 0 -> add(config.line("daily-limit"))
            status.nextAtMillis > now -> add(config.line("cooldown", "seconds" to waiterSeconds(status.nextAtMillis - now)))
            else -> add(config.line("remaining", "count" to status.remainingOrders))
        }
    } else buildList {
        val target = job.target
        add(config.line("guest", "guest" to target.guestName))
        if (job.stage != OriginWaiterStage.TAKE_ORDER) add(config.line("dish", "dish" to target.dishName))
        add(config.line(when (job.stage) {
            OriginWaiterStage.TAKE_ORDER -> "take-order"
            OriginWaiterStage.PICKUP -> if (now >= job.readyAt) "pickup-ready" else "preparing"
            OriginWaiterStage.DELIVER -> "deliver"
            OriginWaiterStage.PAYOUT -> "paying"
        }, "seconds" to waiterSeconds(job.readyAt - now), "guest" to target.guestName))
        notice?.let(::add)
    }
    fun button(id: String, key: String, color: Int, action: () -> Unit) = PaperDialogButton(
        id = PaperDialogActionId.of(id), label = waiterText(config.line(key), TextColor.color(color)),
        width = 300, closeDialogBeforeAction = true, onClick = { action() },
    )
    return PaperDialogScreen(
        id = "dining.waiter.${venue.id}",
        title = waiterText(config.line("title"), TextColor.color(0xFFB277)),
        body = lines.map { PaperDialogBody(waiterText(it), width = 300) },
        buttons = buildList {
            if (job == null && notice == null && status != null && status.available && !status.unresolved &&
                status.remainingOrders > 0 && status.nextAtMillis <= now) {
                add(button("waiter_start", "start", 0x9BD48D, start))
            }
            if (job?.stage == OriginWaiterStage.PICKUP) add(button("waiter_pickup", "pickup", 0x9BD48D, pickup))
            if (job != null && job.stage != OriginWaiterStage.PAYOUT) add(button("waiter_cancel", "cancel", 0xFF6B61, cancel))
        },
        columns = 1,
    )
}

internal fun waiterSeconds(millis: Long): Long = ((millis.coerceAtLeast(0) + 999) / 1000).coerceAtLeast(1)

/** Owns one player delivery; dining retains the actual guest, table, meal and actor lease. */
internal class OriginWaiterGame(
    private val config: OriginWaiterConfig,
    private val reserve: (OriginWaiterVenue) -> OriginWaiterTarget?,
    private val targetValid: (OriginWaiterTarget) -> Boolean,
    private val serve: (OriginWaiterTarget) -> Boolean,
    private val release: (OriginWaiterTarget) -> Unit,
    private val guestVenue: (Int) -> String?,
    private val playerVenue: (Player) -> String?,
    private val debounce: (Player) -> Boolean,
    private val carry: OriginWaiterCarry,
    private val rewards: OriginWaiterRewards = OriginWaiterRewards(),
    private val tasks: LifecycleTaskScope = LifecycleTaskScope(),
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val progress = OriginWaiterProgress(config.lifetimeMillis, config.preparationMillis)
    private val requests = mutableMapOf<UUID, UUID>()
    private val glowing = mutableMapOf<UUID, LivingEntity>()
    private var closed = false

    init { tasks.runTimer(1, 1, carry::tick) }

    fun reserves(guestId: Int): Boolean = progress.entries().any { it.second.target.guestId == guestId }

    fun interact(player: Player, npcId: Int): Boolean {
        val venue = config.venues.firstOrNull { it.staffId == npcId }
        if (venue != null) {
            // Remi's ordinary click still belongs to his existing Citizens shop command.
            if (venue.sneakOnly && !player.isSneaking) return false
            if (!near(player, npcId)) return true
            if (debounce(player)) return true
            val current = progress.current(player.uniqueId)
            if (current != null && current.target.venueId != venue.id) {
                feedback(player, "other-venue")
                return true
            }
            OriginMenus.beginDialogFlow(player)
            if (current != null) show(player, venue, current) else status(player, venue, start = false)
            return true
        }
        val current = progress.current(player.uniqueId) ?: return false
        val clickedVenue = guestVenue(npcId) ?: return false
        if (debounce(player)) return true
        if (!near(player, npcId)) { feedback(player, "come-closer"); return true }
        if (!targetValid(current.target)) { stop(player.uniqueId, "target-gone"); feedback(player, "target-gone"); return true }
        when (val step = progress.guest(player.uniqueId, npcId, clickedVenue, clock())) {
            is OriginWaiterStep.Rejected -> reject(player, step.reason)
            is OriginWaiterStep.Changed -> {
                if (step.job.stage == OriginWaiterStage.PAYOUT) deliver(player, step.job)
                else {
                    feedback(player, "order-taken", "dish" to current.target.dishName)
                    guide(player, step.job, announce = false)
                    player.playSound(player.location, Sound.UI_BUTTON_CLICK, SoundCategory.PLAYERS, 0.35f, 1.15f)
                }
            }
        }
        return true
    }

    private fun status(player: Player, venue: OriginWaiterVenue, start: Boolean) {
        if (closed || !near(player, venue.staffId) || progress.current(player.uniqueId) != null) return
        val playerId = player.uniqueId
        if (playerId in requests) { feedback(player, "checking"); return }
        val request = UUID.randomUUID()
        requests[playerId] = request
        val epoch = tasks.token()
        if (!start) OriginMenus.openDialog(player, waiterScreen(config, venue, null, rewards.amount, null, clock()),
            onDismiss = { requests.remove(playerId, request) })
        else feedback(player, "checking")
        rewards.status(playerId).whenComplete { state, failure ->
            tasks.runSync(epoch) {
                if (!requests.remove(playerId, request) || closed || !near(player, venue.staffId) || progress.current(playerId) != null) return@runSync
                if (failure != null) {
                    warn("ORIGIN_WAITER phase=STATUS_FAILED player={}", playerId, failure)
                    if (start) feedback(player, "unavailable")
                    else show(player, venue, null, notice = config.line("unavailable"))
                    return@runSync
                }
                val now = clock()
                if (!start) {
                    show(player, venue, null, state)
                    return@runSync
                }
                // The start action already closed its dialog flow. A delayed rejection
                // must remain visible without attempting to reopen that closed flow.
                val rejected = when {
                    state.unresolved -> "unconfirmed"
                    !state.available -> "unavailable"
                    state.remainingOrders <= 0 -> "daily-limit"
                    state.nextAtMillis > now -> "cooldown"
                    else -> null
                }
                if (rejected != null) {
                    feedback(player, rejected, "seconds" to waiterSeconds(state.nextAtMillis - now))
                    return@runSync
                }
                val target = reserve(venue)
                if (target == null) { feedback(player, "no-guests"); return@runSync }
                val job = progress.begin(playerId, target, now)
                if (job == null) { release(target); return@runSync }
                info("ORIGIN_WAITER phase=STARTED player={} request={} venue={} guest={} table={} dish={}",
                    playerId, job.id, venue.id, target.guestId, target.tableId, target.dishId)
                guide(player, job)
            }
        }
    }

    private fun show(player: Player, venue: OriginWaiterVenue, job: OriginWaiterJob?, state: WaiterRewardStatus? = null, notice: String? = null) {
        if (closed || !near(player, venue.staffId)) return
        OriginMenus.openDialog(player,
            waiterScreen(config, venue, job, rewards.amount, state, clock(), notice,
                start = { status(player, venue, start = true) },
                pickup = { pickup(player, venue, job?.id) },
                cancel = {
                    if (near(player, venue.staffId) && progress.current(player.uniqueId)?.id == job?.id) {
                        stop(player.uniqueId, "cancelled")
                        feedback(player, "cancelled")
                    }
                }),
            reopen = { if (near(player, venue.staffId)) {
                val current = progress.current(player.uniqueId)
                if (current != null) show(player, venue, current) else status(player, venue, start = false)
            } },
        )
    }

    private fun pickup(player: Player, venue: OriginWaiterVenue, requestId: UUID?) {
        val job = progress.current(player.uniqueId)?.takeIf { it.id == requestId } ?: return
        if (!near(player, venue.staffId)) { feedback(player, "come-closer"); return }
        if (!targetValid(job.target)) { stop(player.uniqueId, "target-gone"); feedback(player, "target-gone"); return }
        when (val step = progress.pickup(player.uniqueId, venue.staffId, venue.id, clock())) {
            is OriginWaiterStep.Rejected -> reject(player, step.reason)
            is OriginWaiterStep.Changed -> {
                val pickedUp = runCatching { carry.pickup(player, job.target.dishId) }.onFailure {
                    warn("ORIGIN_WAITER phase=CARRY_FAILED player={} request={}", player.uniqueId, job.id, it)
                }.getOrDefault(false)
                if (!pickedUp) {
                    stop(player.uniqueId, "dish-unavailable")
                    feedback(player, "dish-unavailable")
                    return
                }
                guide(player, step.job)
                player.playSound(player.location, Sound.ENTITY_ITEM_PICKUP, SoundCategory.PLAYERS, 0.35f, 0.9f)
                info("ORIGIN_WAITER phase=PICKED_UP player={} request={} guest={}", player.uniqueId, job.id, job.target.guestId)
            }
        }
    }

    private fun deliver(player: Player, job: OriginWaiterJob) {
        if (!serve(job.target)) {
            stop(player.uniqueId, "serve-failed")
            feedback(player, "target-gone")
            return
        }
        carry.remove(player.uniqueId)
        clearGlow(player.uniqueId)
        feedback(player, "paying")
        info("ORIGIN_WAITER phase=SERVED player={} request={} table={}", player.uniqueId, job.id, job.target.tableId)
        rewards.pay(player.uniqueId, job.id,
            stillValid = { !closed && progress.current(player.uniqueId)?.id == job.id && player.isOnline },
        ) { outcome ->
            val finished = progress.finish(player.uniqueId, job.id) ?: return@pay
            release(finished.target)
            if (player.isOnline) when (outcome) {
                WaiterRewardOutcome.PAID, WaiterRewardOutcome.ALREADY_PAID -> {
                    feedback(player, "paid", "amount" to rewards.amount)
                    player.playSound(player.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.PLAYERS, 0.45f, 1.15f)
                }
                WaiterRewardOutcome.COOLDOWN -> feedback(player, "payment-cooldown")
                WaiterRewardOutcome.DAILY_LIMIT -> feedback(player, "daily-limit")
                WaiterRewardOutcome.UNCONFIRMED -> feedback(player, "unconfirmed")
                WaiterRewardOutcome.UNAVAILABLE -> feedback(player, "unavailable")
                WaiterRewardOutcome.CANCELLED -> feedback(player, "cancelled")
            }
            info("ORIGIN_WAITER phase=FINISHED player={} request={} outcome={}", player.uniqueId, job.id, outcome)
        }
    }

    fun tick(now: Long) {
        progress.entries().forEach { (playerId, job) ->
            val player = Bukkit.getPlayer(playerId)
            val venue = config.venues.first { it.id == job.target.venueId }
            val reason = when {
                player == null || !player.isOnline -> "offline"
                player.isDead -> "cancelled"
                job.stage == OriginWaiterStage.PAYOUT -> null
                now >= job.expiresAt -> "expired"
                playerVenue(player) != venue.id && !near(player, venue.staffId) -> "left-venue"
                !targetValid(job.target) -> "target-gone"
                else -> null
            }
            if (reason != null) {
                stop(playerId, reason)
                if (player?.isOnline == true) feedback(player, reason)
            } else if (player != null && job.stage != OriginWaiterStage.PAYOUT) guide(player, job)
        }
    }

    private fun guide(player: Player, job: OriginWaiterJob, announce: Boolean = true) {
        val npcId = if (job.stage == OriginWaiterStage.PICKUP) job.target.staffId else job.target.guestId
        val entity = actor(npcId)
        if (glowing[player.uniqueId] !== entity) {
            clearGlow(player.uniqueId)
            if (entity != null) {
                OriginServices.viewerGlow.setEntityGlowingFor(entity, player, true)
                glowing[player.uniqueId] = entity
            }
        }
        if (announce) feedback(player, when (job.stage) {
            OriginWaiterStage.TAKE_ORDER -> "guide-order"
            OriginWaiterStage.PICKUP -> "guide-pickup"
            OriginWaiterStage.DELIVER -> "guide-deliver"
            OriginWaiterStage.PAYOUT -> "paying"
        }, "guest" to job.target.guestName, "dish" to job.target.dishName,
            "control" to if (config.venues.first { it.id == job.target.venueId }.sneakOnly) "присядь и ПКМ" else "ПКМ",
            "staff" to waiterNpcName(CitizensAPI.getNPCRegistry().getById(job.target.staffId)?.name ?: config.line("kitchen")))
    }

    private fun reject(player: Player, reason: OriginWaiterRejection) {
        val key = when (reason) {
            OriginWaiterRejection.NO_JOB -> "cancelled"
            OriginWaiterRejection.EXPIRED -> "expired"
            OriginWaiterRejection.WRONG_VENUE -> "other-venue"
            OriginWaiterRejection.WRONG_GUEST -> "wrong-guest"
            OriginWaiterRejection.WRONG_STAFF -> "wrong-staff"
            OriginWaiterRejection.TAKE_ORDER_FIRST -> "take-order"
            OriginWaiterRejection.PICKUP_FIRST -> "pickup-first"
            OriginWaiterRejection.PREPARING -> "preparing"
            OriginWaiterRejection.ALREADY_CARRYING -> "deliver"
            OriginWaiterRejection.PAYOUT_PENDING -> "paying"
        }
        val job = progress.current(player.uniqueId)
        feedback(player, key, "guest" to (job?.target?.guestName ?: ""),
            "seconds" to waiterSeconds((job?.readyAt ?: 0) - clock()))
        if (reason == OriginWaiterRejection.EXPIRED) stop(player.uniqueId, "expired")
    }

    private fun actor(id: Int): LivingEntity? = CitizensAPI.getNPCRegistry().getById(id)
        ?.takeIf { it.isSpawned && it.entity.world.name == OriginDiningLayout.WORLD }?.entity as? LivingEntity

    private fun near(player: Player, npcId: Int): Boolean {
        if (!player.isOnline || player.isDead || player.world.name != OriginDiningLayout.WORLD) return false
        val entity = actor(npcId) ?: return false
        return player.world == entity.world && player.location.distanceSquared(entity.location) <= config.interactionRange * config.interactionRange
    }

    private fun feedback(player: Player, key: String, vararg values: Pair<String, Any>) =
        player.sendActionBar(waiterText(config.line(key, *values)))

    private fun clearGlow(playerId: UUID) {
        val entity = glowing.remove(playerId) ?: return
        val player = Bukkit.getPlayer(playerId) ?: return
        if (entity.isValid) OriginServices.viewerGlow.setEntityGlowingFor(entity, player, false)
    }

    fun stop(playerId: UUID, reason: String) {
        requests.remove(playerId)
        carry.remove(playerId)
        clearGlow(playerId)
        val job = progress.cancel(playerId) ?: return
        release(job.target)
        info("ORIGIN_WAITER phase=CANCELLED player={} request={} reason={}", playerId, job.id, reason)
    }

    override fun close() {
        closed = true
        tasks.close()
        requests.clear()
        progress.entries().forEach { (playerId, _) -> stop(playerId, "reload") }
        carry.close()
        rewards.close()
    }
}
