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
    val shiftMillis: Long = 600_000,
    val graceMillis: Long = 120_000,
    val freshnessMillis: Long = 45_000,
    val rushFreshnessMillis: Long = 30_000,
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
            shiftMillis = source.integer("waiter-game.shift-seconds", 600).coerceIn(480, 600) * 1000L,
            graceMillis = source.integer("waiter-game.finish-grace-seconds", 120).coerceIn(0, 120) * 1000L,
            freshnessMillis = source.integer("waiter-game.freshness-seconds", 45).coerceIn(15, 120) * 1000L,
            rushFreshnessMillis = source.integer("waiter-game.rush-freshness-seconds", 30).coerceIn(15, 120) * 1000L,
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

internal data class OriginWaiterReceipt(val shift: OriginWorkShift, val endedAt: Long, val unpaidOrders: Int)

internal fun waiterReceiptLines(config: OriginWaiterConfig, receipt: OriginWaiterReceipt): List<String> = buildList {
    add(config.line("receipt-title", "time" to waiterTime(receipt.endedAt - receipt.shift.startedAt)))
    add(config.line("shift-score", "count" to receipt.shift.completedOrders, "amount" to receipt.shift.earnedAmount,
        "quality" to receipt.shift.quality))
    if (receipt.unpaidOrders > 0) add(config.line("receipt-unpaid", "count" to receipt.unpaidOrders))
}

internal fun waiterScreen(
    config: OriginWaiterConfig,
    venue: OriginWaiterVenue,
    shift: OriginWorkShift?,
    jobs: List<OriginWaiterJob>,
    amount: Int,
    status: SideJobRewardStatus?,
    now: Long,
    receipt: OriginWaiterReceipt? = null,
    notice: String? = null,
    start: () -> Unit = {},
    pickup: (UUID, String) -> Unit = { _, _ -> },
    remake: (UUID) -> Unit = {},
    focus: (UUID) -> Unit = {},
    refresh: () -> Unit = {},
    finish: () -> Unit = {},
): PaperDialogScreen {
    val lines = if (shift == null) buildList {
        add(config.line("shift-introduction", "minutes" to config.shiftMillis / 60_000, "grace" to config.graceMillis / 60_000))
        add(config.line("pay", "amount" to amount))
        receipt?.let { addAll(waiterReceiptLines(config, it)) }
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
        val closing = !shift.acceptsOrders(now)
        add(config.line(if (closing) "shift-finishing" else "shift-clock",
            "phase" to config.line("phase-${shift.phase(now).name.lowercase()}"),
            "time" to waiterTime((if (closing) shift.hardStopAt else shift.closesAt) - now)))
        add(config.line("shift-score", "count" to shift.completedOrders, "amount" to shift.earnedAmount, "quality" to shift.quality))
        if (!shift.finishRequested) add(config.line("finish-help"))
        if (jobs.isEmpty()) add(config.line("waiting-guests"))
        for (job in jobs) {
            add(config.line("ticket", "guest" to job.target.guestName,
                "kind" to config.line("family-${job.family.name.lowercase()}")))
            if (job.stage != OriginWaiterStage.TAKE_ORDER) {
                add(config.line("dish", "dish" to job.dish.name))
                if (job.family == OriginWaiterFamily.DRINK_FIRST && job.course == OriginWaiterCourse.DRINK)
                    add(config.line("main-after-drink", "dish" to job.target.dishName))
            }
            add(config.line(when (job.stage) {
                OriginWaiterStage.TAKE_ORDER -> "take-order"
                OriginWaiterStage.CLARIFY -> "clarify-first"
                OriginWaiterStage.PICKUP -> if (now >= job.readyAt) "dish-ready" else "preparing"
                OriginWaiterStage.DELIVER -> "deliver"
                OriginWaiterStage.REWORK -> "rework-required"
                OriginWaiterStage.PAYOUT -> "paying"
            }, "seconds" to waiterSeconds(if (now >= job.readyAt) job.freshUntil - now else job.readyAt - now),
                "guest" to job.target.guestName))
            if (job.clarified) add(config.line("revised-instruction", "dish" to job.dish.name))
        }
        notice?.let(::add)
    }
    fun button(id: String, label: String, color: Int, close: Boolean = true, action: () -> Unit) = PaperDialogButton(
        id = PaperDialogActionId.of(id), label = waiterText(label, TextColor.color(color)),
        width = 320, closeDialogBeforeAction = close, onClick = { action() },
    )
    return PaperDialogScreen(
        id = "dining.waiter.${venue.id}",
        title = waiterText(config.line("title"), TextColor.color(0xFFB277)),
        body = lines.map { PaperDialogBody(waiterText(it), width = 320) },
        buttons = buildList {
            if (shift == null && notice == null && status != null && status.available && !status.unresolved &&
                status.remainingOrders > 0 && status.nextAtMillis <= now) {
                add(button("waiter_start", config.line("start-shift"), 0x9BD48D, action = start))
            }
            if (shift != null) {
                val carrying = jobs.any { it.stage == OriginWaiterStage.DELIVER }
                jobs.forEachIndexed { index, job ->
                    if (job.stage != OriginWaiterStage.PAYOUT) add(button("waiter_focus_$index",
                        config.line("focus-guest", "guest" to job.target.guestName), 0xFFFFFF) { focus(job.id) })
                    if (job.stage == OriginWaiterStage.PICKUP && now >= job.readyAt && !carrying) {
                        val choices = if (job.clarified) listOf(OriginWaiterDish(job.target.dishId, job.target.dishName), job.dish) else listOf(job.dish)
                        choices.forEachIndexed { choice, dish -> add(button("waiter_pickup_${index}_$choice",
                            config.line("pickup-dish", "dish" to dish.name, "guest" to job.target.guestName), 0x9BD48D) { pickup(job.id, dish.id) }) }
                    }
                    if (job.stage == OriginWaiterStage.REWORK) add(button("waiter_remake_$index",
                        config.line("remake-dish", "guest" to job.target.guestName), 0x9BD48D) { remake(job.id) })
                }
                add(button("waiter_refresh", config.line("refresh-orders"), 0xFFFFFF, close = false, action = refresh))
                if (!shift.finishRequested) add(button("waiter_finish", config.line("finish-shift"), 0xFFFFFF, action = finish))
            }
        },
        columns = 1,
    )
}

internal fun waiterSeconds(millis: Long): Long = ((millis.coerceAtLeast(0) + 999) / 1000).coerceAtLeast(1)
internal fun waiterTime(millis: Long): String {
    val seconds = (millis.coerceAtLeast(0) + 999) / 1000
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

/** A shift owns two guest tickets; dining owns their physical tables and the durable cashier owns payment. */
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
    private val rewards: OriginSideJobRewards = OriginSideJobRewards(),
    private val tasks: LifecycleTaskScope = LifecycleTaskScope(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val otherWork: (UUID) -> Boolean = { OriginOddJobsModule.isWorking(it) || OriginWorkshopGame.isWorking(it) },
) : AutoCloseable {
    private class Session(val venue: OriginWaiterVenue, var shift: OriginWorkShift, var remaining: Int) {
        var admitted = 0
        var focused: UUID? = null
        var inFlight: UUID? = null
        var payoutAttempt = 0L
        var retryAt = 0L
        var reserveAt = 0L
        var hintUntil = 0L
        var endedAt: Long? = null
        var unpaidOrders = 0
        var phase = OriginWorkShiftPhase.OPENING
    }

    private val progress = OriginWaiterProgress(config.lifetimeMillis, config.preparationMillis)
    private val sessions = mutableMapOf<UUID, Session>()
    private val receipts = mutableMapOf<UUID, OriginWaiterReceipt>()
    private val requests = mutableMapOf<UUID, UUID>()
    private val glowing = mutableMapOf<UUID, LivingEntity>()
    private var closed = false

    init { tasks.runTimer(1, 1, carry::tick) }

    fun reserves(guestId: Int): Boolean = progress.entries().any { it.second.target.guestId == guestId }
    fun isWorking(playerId: UUID): Boolean = playerId in sessions

    fun interact(player: Player, npcId: Int): Boolean {
        if (closed) return false
        val venue = config.venues.firstOrNull { it.staffId == npcId }
        if (venue != null) {
            // Remi's ordinary click still belongs to his existing Citizens shop command.
            if (venue.sneakOnly && !player.isSneaking) return false
            if (!near(player, npcId)) return true
            if (debounce(player)) return true
            val session = sessions[player.uniqueId]
            if (session != null && session.venue.id != venue.id) { feedback(player, "other-venue"); return true }
            OriginMenus.beginDialogFlow(player)
            if (session != null) show(player, venue) else status(player, venue, start = false)
            return true
        }
        val session = sessions[player.uniqueId] ?: return false
        val clickedVenue = guestVenue(npcId) ?: return false
        if (debounce(player)) return true
        if (!near(player, npcId)) { feedback(player, "come-closer"); return true }
        val now = clock()
        refresh(player.uniqueId, now)
        val before = progress.tickets(player.uniqueId).firstOrNull { it.target.guestId == npcId }
        if (before != null && before.stage != OriginWaiterStage.PAYOUT && !targetValid(before.target)) {
            cancelTicket(player.uniqueId, before, "target-gone")
            feedback(player, "target-gone")
            return true
        }
        when (val step = progress.guest(player.uniqueId, npcId, clickedVenue, now)) {
            is OriginWaiterStep.Rejected -> reject(player, step.reason, before)
            is OriginWaiterStep.Changed -> {
                session.focused = null
                if (before?.stage == OriginWaiterStage.DELIVER) {
                    carry.remove(player.uniqueId)
                    if (!serve(before.servingTarget())) {
                        cancelTicket(player.uniqueId, step.job, "serve-failed")
                        feedback(player, "target-gone")
                        return true
                    }
                    if (step.job.stage == OriginWaiterStage.PAYOUT) {
                        info("ORIGIN_WAITER phase=SERVED player={} request={} table={}", player.uniqueId, step.job.id, step.job.target.tableId)
                        feedback(player, "paying")
                        settleNext(player.uniqueId, now)
                    } else feedback(player, "drink-served", "guest" to step.job.target.guestName, "dish" to step.job.dish.name)
                } else feedback(player, if (before?.stage == OriginWaiterStage.CLARIFY) "revised-instruction" else "order-accepted",
                    "dish" to step.job.dish.name, "guest" to step.job.target.guestName)
                fillOrders(player.uniqueId, now)
                guide(player, announce = false)
                player.playSound(player.location, Sound.UI_BUTTON_CLICK, SoundCategory.PLAYERS, 0.35f, 1.15f)
            }
        }
        return true
    }

    private fun status(player: Player, venue: OriginWaiterVenue, start: Boolean) {
        if (closed || !near(player, venue.staffId) || isWorking(player.uniqueId)) return
        val playerId = player.uniqueId
        if (playerId in requests) { feedback(player, "checking"); return }
        val request = UUID.randomUUID()
        requests[playerId] = request
        val epoch = tasks.token()
        if (!start) OriginMenus.openDialog(player,
            waiterScreen(config, venue, null, emptyList(), rewards.amount, null, clock(), receipts[playerId]),
            onDismiss = { requests.remove(playerId, request) })
        else feedback(player, "checking")
        rewards.status(playerId).whenComplete { state, failure ->
            tasks.runSync(epoch) {
                if (!requests.remove(playerId, request) || closed || !near(player, venue.staffId) || isWorking(playerId)) return@runSync
                if (failure != null) {
                    warn("ORIGIN_WAITER phase=STATUS_FAILED player={}", playerId, failure)
                    if (start) feedback(player, "unavailable") else show(player, venue, notice = config.line("unavailable"))
                    return@runSync
                }
                val now = clock()
                if (!start) { show(player, venue, state); return@runSync }
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
                if (otherWork(playerId)) { feedback(player, "other-work"); return@runSync }
                val session = Session(venue, OriginWorkShift.begin(now, config.shiftMillis, config.graceMillis), state.remainingOrders)
                sessions[playerId] = session
                fillOrders(playerId, now)
                if (progress.tickets(playerId).isEmpty()) {
                    sessions.remove(playerId, session)
                    feedback(player, "no-guests")
                    return@runSync
                }
                receipts.remove(playerId)
                info("ORIGIN_WAITER phase=SHIFT_STARTED player={} shift={} venue={}", playerId, session.shift.id, venue.id)
                guide(player)
            }
        }
    }

    private fun show(player: Player, venue: OriginWaiterVenue, state: SideJobRewardStatus? = null, notice: String? = null) {
        if (closed || !near(player, venue.staffId)) return
        val now = clock()
        refresh(player.uniqueId, now)
        val session = sessions[player.uniqueId]
        val shiftId = session?.shift?.id
        fun current(): Boolean = !closed && near(player, venue.staffId) && sessions[player.uniqueId]?.shift?.id == shiftId
        OriginMenus.openDialog(player,
            waiterScreen(config, venue, session?.shift, progress.tickets(player.uniqueId), rewards.amount, state, now,
                receipts[player.uniqueId], notice,
                start = { if (current()) status(player, venue, start = true) },
                pickup = { id, dish -> if (current()) pickup(player, venue, id, dish) },
                remake = { id -> if (current()) remake(player, venue, id) },
                focus = { id -> if (current() && progress.current(player.uniqueId, id)?.stage != OriginWaiterStage.PAYOUT) {
                    session?.focused = id
                    guide(player)
                } },
                refresh = { if (current()) show(player, venue) },
                finish = { if (current()) stop(player.uniqueId, "finished-early") }),
            reopen = { if (near(player, venue.staffId)) {
                if (isWorking(player.uniqueId)) show(player, venue) else status(player, venue, start = false)
            } },
        )
    }

    private fun fillOrders(playerId: UUID, now: Long) {
        val session = sessions[playerId] ?: return
        if (!session.shift.acceptsOrders(now) || now < session.reserveAt) return
        var tickets = progress.tickets(playerId)
        val capacity = if (session.admitted == 0 || session.admitted == 1 && tickets.any { it.stage == OriginWaiterStage.TAKE_ORDER }) 1 else 2
        while (tickets.size < capacity && tickets.size < session.remaining) {
            val target = reserve(session.venue) ?: run { session.reserveAt = now + 5_000; return }
            val rush = session.shift.phase(now) == OriginWorkShiftPhase.RUSH
            val family = OriginWaiterFamily.entries[(session.admitted + if (rush) 1 else 0) % OriginWaiterFamily.entries.size]
            val supported = when {
                family == OriginWaiterFamily.DRINK_FIRST && target.drink == null -> OriginWaiterFamily.MEAL
                family == OriginWaiterFamily.REVISED && target.replacement == null -> OriginWaiterFamily.MEAL
                else -> family
            }
            val job = progress.begin(playerId, target, now, supported,
                if (rush) config.rushFreshnessMillis else config.freshnessMillis, session.shift.hardStopAt)
            if (job == null) { release(target); session.reserveAt = now + 5_000; return }
            session.admitted++
            info("ORIGIN_WAITER phase=ORDER_RESERVED player={} shift={} request={} guest={} family={}",
                playerId, session.shift.id, job.id, target.guestId, job.family)
            tickets = progress.tickets(playerId)
        }
    }

    private fun pickup(player: Player, venue: OriginWaiterVenue, requestId: UUID, dishId: String) {
        val job = progress.current(player.uniqueId, requestId) ?: return
        if (!near(player, venue.staffId)) { feedback(player, "come-closer"); return }
        if (!targetValid(job.target)) { cancelTicket(player.uniqueId, job, "target-gone"); feedback(player, "target-gone"); return }
        val now = clock()
        refresh(player.uniqueId, now)
        when (val step = progress.pickup(player.uniqueId, venue.staffId, venue.id, now, requestId, dishId)) {
            is OriginWaiterStep.Rejected -> reject(player, step.reason, progress.current(player.uniqueId, requestId))
            is OriginWaiterStep.Changed -> {
                val pickedUp = runCatching { carry.pickup(player, step.job.dish.id) }.onFailure {
                    warn("ORIGIN_WAITER phase=CARRY_FAILED player={} request={}", player.uniqueId, job.id, it)
                }.getOrDefault(false)
                if (!pickedUp) {
                    cancelTicket(player.uniqueId, step.job, "dish-unavailable")
                    feedback(player, "dish-unavailable")
                    return
                }
                sessions[player.uniqueId]?.focused = requestId
                guide(player)
                player.playSound(player.location, Sound.ENTITY_ITEM_PICKUP, SoundCategory.PLAYERS, 0.35f, 0.9f)
            }
        }
    }

    private fun remake(player: Player, venue: OriginWaiterVenue, requestId: UUID) {
        if (!near(player, venue.staffId)) return
        when (val step = progress.remake(player.uniqueId, requestId, venue.staffId, venue.id, clock())) {
            is OriginWaiterStep.Rejected -> reject(player, step.reason, progress.current(player.uniqueId, requestId))
            is OriginWaiterStep.Changed -> {
                sessions[player.uniqueId]?.focused = null
                feedback(player, "remaking", "guest" to step.job.target.guestName)
                guide(player, announce = false)
            }
        }
    }

    /** Only a known pre-deposit cooldown may be retried; each accepted handoff survives leaving the venue. */
    private fun settleNext(playerId: UUID, now: Long) {
        val session = sessions[playerId] ?: return
        if (closed || session.inFlight != null || now < session.retryAt) return
        val job = progress.tickets(playerId).firstOrNull { it.stage == OriginWaiterStage.PAYOUT } ?: return
        session.inFlight = job.id
        val attempt = ++session.payoutAttempt
        rewards.pay(OriginSideJobKind.WAITER, playerId, job.id,
            stillValid = { !closed && sessions[playerId] === session && session.payoutAttempt == attempt &&
                progress.current(playerId, job.id)?.stage == OriginWaiterStage.PAYOUT },
        ) { outcome ->
            if (closed || sessions[playerId] !== session || session.inFlight != job.id || session.payoutAttempt != attempt) return@pay
            session.inFlight = null
            val player = Bukkit.getPlayer(playerId)?.takeIf(Player::isOnline)
            if (outcome == SideJobRewardOutcome.COOLDOWN) {
                session.retryAt = clock() + SIDE_JOB_PAYOUT_INTERVAL_MILLIS
                player?.let { feedback(it, "payment-wait", "seconds" to SIDE_JOB_PAYOUT_INTERVAL_MILLIS / 1000) }
                return@pay
            }
            val finished = progress.finish(playerId, job.id) ?: return@pay
            release(finished.target)
            when (outcome) {
                SideJobRewardOutcome.PAID, SideJobRewardOutcome.ALREADY_PAID -> {
                    session.shift = session.shift.record(job.id, rewards.amount, job.quality)
                    session.remaining = (session.remaining - 1).coerceAtLeast(0)
                    session.retryAt = clock() + SIDE_JOB_PAYOUT_INTERVAL_MILLIS
                    player?.let {
                        feedback(it, "order-paid", "amount" to rewards.amount, "count" to session.shift.completedOrders)
                        it.playSound(it.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.PLAYERS, 0.45f, 1.15f)
                    }
                    if (session.remaining == 0) stop(playerId, "daily-limit") else fillOrders(playerId, clock())
                }
                else -> {
                    // An unknown provider result is terminal. Never turn it into a fresh request or replay.
                    session.unpaidOrders++
                    progress.tickets(playerId).filter { it.stage == OriginWaiterStage.PAYOUT }.forEach {
                        progress.finish(playerId, it.id)?.let { waiting -> release(waiting.target); session.unpaidOrders++ }
                    }
                    val reason = when (outcome) {
                        SideJobRewardOutcome.DAILY_LIMIT -> "daily-limit"
                        SideJobRewardOutcome.UNCONFIRMED -> "unconfirmed"
                        SideJobRewardOutcome.CANCELLED -> "cancelled"
                        else -> "unavailable"
                    }
                    player?.let { feedback(it, reason) }
                    stop(playerId, reason)
                }
            }
            info("ORIGIN_WAITER phase=ORDER_FINISHED player={} shift={} request={} outcome={}", playerId, session.shift.id, job.id, outcome)
            finishIfReady(playerId, clock())
        }
    }

    fun tick(now: Long) {
        sessions.toMap().forEach { (playerId, session) ->
            val player = Bukkit.getPlayer(playerId)
            if (session.endedAt == null) {
                val reason = when {
                    player == null || !player.isOnline -> "offline"
                    player.isDead -> "cancelled"
                    now >= session.shift.hardStopAt -> "shift-ended"
                    playerVenue(player) != session.venue.id && !near(player, session.venue.staffId) -> "left-venue"
                    else -> null
                }
                if (reason != null) stop(playerId, reason)
                else {
                    refresh(playerId, now)
                    progress.tickets(playerId).filter { it.stage != OriginWaiterStage.PAYOUT }.forEach { job ->
                        val rejection = if (now >= job.expiresAt) "expired" else if (!targetValid(job.target)) "target-gone" else null
                        if (rejection != null) {
                            cancelTicket(playerId, job, rejection)
                            player?.let { feedback(it, rejection) }
                        }
                    }
                    fillOrders(playerId, now)
                    val phase = session.shift.phase(now)
                    if (phase != session.phase) {
                        session.phase = phase
                        player?.let { feedback(it, "phase-notice-${phase.name.lowercase()}") }
                    }
                    player?.let { guide(it) }
                }
            }
            settleNext(playerId, now)
            finishIfReady(playerId, now)
        }
    }

    private fun refresh(playerId: UUID, now: Long) {
        val carried = progress.carried(playerId)?.id
        val changes = progress.refresh(playerId, now)
        if (carried != null && progress.carried(playerId)?.id != carried) carry.remove(playerId)
        val player = Bukkit.getPlayer(playerId)?.takeIf(Player::isOnline) ?: return
        changes.firstOrNull { it.stage == OriginWaiterStage.CLARIFY }?.let {
            feedback(player, "clarification-requested", "guest" to it.target.guestName)
        }
        changes.firstOrNull { it.stage == OriginWaiterStage.REWORK }?.let {
            feedback(player, "dish-cooled", "guest" to it.target.guestName)
        }
    }

    private fun guide(player: Player, announce: Boolean = true) {
        val session = sessions[player.uniqueId] ?: return
        val now = clock()
        val focused = session.focused?.let { progress.current(player.uniqueId, it) }?.takeIf { it.stage != OriginWaiterStage.PAYOUT }
        val job = progress.carried(player.uniqueId) ?: focused ?: progress.priority(player.uniqueId, now)
        val npcId = when (job?.stage) {
            OriginWaiterStage.TAKE_ORDER, OriginWaiterStage.CLARIFY, OriginWaiterStage.DELIVER -> job.target.guestId
            else -> session.venue.staffId
        }
        val entity = actor(npcId)
        if (glowing[player.uniqueId] !== entity) {
            clearGlow(player.uniqueId)
            if (entity != null) {
                OriginServices.viewerGlow.setEntityGlowingFor(entity, player, true)
                glowing[player.uniqueId] = entity
            }
        }
        if (!announce || now < session.hintUntil) return
        val key = when (job?.stage) {
            OriginWaiterStage.TAKE_ORDER -> "guide-order"
            OriginWaiterStage.CLARIFY -> "guide-clarify"
            OriginWaiterStage.PICKUP -> if (now >= job.readyAt) "guide-pickup" else "guide-preparing"
            OriginWaiterStage.DELIVER -> "guide-deliver"
            OriginWaiterStage.REWORK -> "guide-rework"
            OriginWaiterStage.PAYOUT, null -> "guide-board"
        }
        val next = config.line(key, "guest" to (job?.target?.guestName ?: ""), "dish" to (job?.dish?.name ?: ""),
            "seconds" to waiterSeconds((job?.readyAt ?: now) - now),
            "control" to config.line(if (session.venue.sneakOnly) "control-sneak" else "control-click"),
            "staff" to waiterNpcName(CitizensAPI.getNPCRegistry().getById(session.venue.staffId)?.name ?: config.line("kitchen")))
        feedback(player, "shift-hud", "time" to waiterTime((if (session.shift.acceptsOrders(now)) session.shift.closesAt else session.shift.hardStopAt) - now), "next" to next)
    }

    private fun reject(player: Player, reason: OriginWaiterRejection, job: OriginWaiterJob?) {
        val key = when (reason) {
            OriginWaiterRejection.NO_JOB -> "cancelled"
            OriginWaiterRejection.EXPIRED -> "expired"
            OriginWaiterRejection.WRONG_VENUE -> "other-venue"
            OriginWaiterRejection.WRONG_GUEST -> "wrong-guest"
            OriginWaiterRejection.WRONG_STAFF -> "wrong-staff"
            OriginWaiterRejection.WRONG_DISH -> "wrong-dish"
            OriginWaiterRejection.TAKE_ORDER_FIRST -> "take-order"
            OriginWaiterRejection.CLARIFY_FIRST -> "clarify-first"
            OriginWaiterRejection.PICKUP_FIRST -> "pickup-first"
            OriginWaiterRejection.PREPARING -> "preparing"
            OriginWaiterRejection.ALREADY_CARRYING -> "hands-full"
            OriginWaiterRejection.REWORK_REQUIRED -> "rework-required"
            OriginWaiterRejection.PAYOUT_PENDING -> "paying"
        }
        val target = progress.carried(player.uniqueId) ?: job ?: progress.priority(player.uniqueId, clock())
        feedback(player, key, "guest" to (target?.target?.guestName ?: ""), "dish" to (job?.dish?.name ?: ""),
            "seconds" to waiterSeconds((job?.readyAt ?: 0) - clock()))
        if (reason == OriginWaiterRejection.EXPIRED && job != null) cancelTicket(player.uniqueId, job, "expired")
    }

    private fun cancelTicket(playerId: UUID, job: OriginWaiterJob, reason: String) {
        val removed = progress.cancel(playerId, job.id) ?: return
        if (removed.stage == OriginWaiterStage.DELIVER) carry.remove(playerId)
        release(removed.target)
        if (sessions[playerId]?.focused == job.id) sessions[playerId]?.focused = null
        info("ORIGIN_WAITER phase=ORDER_CANCELLED player={} request={} reason={}", playerId, job.id, reason)
    }

    private fun actor(id: Int): LivingEntity? = CitizensAPI.getNPCRegistry().getById(id)
        ?.takeIf { it.isSpawned && it.entity.world.name == OriginDiningLayout.WORLD }?.entity as? LivingEntity

    private fun near(player: Player, npcId: Int): Boolean {
        if (!player.isOnline || player.isDead || player.world.name != OriginDiningLayout.WORLD) return false
        val entity = actor(npcId) ?: return false
        return player.world == entity.world && player.location.distanceSquared(entity.location) <= config.interactionRange * config.interactionRange
    }

    private fun feedback(player: Player, key: String, vararg values: Pair<String, Any>) {
        sessions[player.uniqueId]?.hintUntil = clock() + 1_800
        player.sendActionBar(waiterText(config.line(key, *values)))
    }

    private fun clearGlow(playerId: UUID) {
        val entity = glowing.remove(playerId) ?: return
        val player = Bukkit.getPlayer(playerId) ?: return
        if (entity.isValid) OriginServices.viewerGlow.setEntityGlowingFor(entity, player, false)
    }

    fun stop(playerId: UUID, reason: String) {
        requests.remove(playerId)
        carry.remove(playerId)
        clearGlow(playerId)
        val session = sessions[playerId] ?: return
        session.shift = session.shift.requestFinish()
        if (session.endedAt == null) session.endedAt = clock()
        // A completed handoff is independent of the player's location and of the shift timer.
        progress.tickets(playerId).filter { it.stage != OriginWaiterStage.PAYOUT }.forEach { cancelTicket(playerId, it, reason) }
        info("ORIGIN_WAITER phase=SHIFT_STOPPED player={} shift={} reason={}", playerId, session.shift.id, reason)
        finishIfReady(playerId, clock())
    }

    private fun finishIfReady(playerId: UUID, now: Long) {
        val session = sessions[playerId] ?: return
        if (progress.tickets(playerId).isNotEmpty() || session.inFlight != null || !session.shift.shouldFinish(now, false)) return
        sessions.remove(playerId, session)
        carry.remove(playerId)
        clearGlow(playerId)
        val receipt = OriginWaiterReceipt(session.shift, session.endedAt ?: now, session.unpaidOrders)
        receipts[playerId] = receipt
        Bukkit.getPlayer(playerId)?.takeIf(Player::isOnline)?.sendMessage(
            waiterText("\n  ${waiterReceiptLines(config, receipt).joinToString("\n  ")}\n"))
        info("ORIGIN_WAITER phase=SHIFT_FINISHED player={} shift={} orders={} amount={} quality={} unpaid={}",
            playerId, session.shift.id, session.shift.completedOrders, session.shift.earnedAmount, session.shift.quality, session.unpaidOrders)
    }

    override fun close() {
        if (closed) return
        closed = true
        tasks.close()
        requests.clear()
        // The cashier fences/retains durable claims before the transient dining reservations disappear.
        rewards.close()
        sessions.keys.toList().forEach { playerId -> carry.remove(playerId); clearGlow(playerId) }
        progress.clear().forEach { (_, job) -> release(job.target) }
        sessions.clear()
        receipts.clear()
        carry.close()
    }
}
