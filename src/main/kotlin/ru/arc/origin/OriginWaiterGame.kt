package ru.arc.origin

import net.citizensnpcs.api.CitizensAPI
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import ru.arc.config.Config
import ru.arc.config.ConfigSection
import ru.arc.core.LifecycleTaskScope
import ru.arc.logging.ArcLogging.info
import ru.arc.logging.ArcLogging.warn
import ru.arc.origin.menu.OriginMenus
import ru.arc.paper.menu.DialogTables
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogBody
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogScreen
import java.util.UUID
import java.time.Duration
import net.kyori.adventure.title.Title

internal data class OriginWaiterVenue(val id: String, val staffId: Int, val sneakOnly: Boolean, val chefId: Int = staffId, val chefSceneId: String? = null)

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
    val rewardPolicy: OriginSideJobRewardPolicy = OriginSideJobRewardPolicy(),
    val eatingMillis: Long = 6_000,
    val actionMillis: Long = 1_600,
    val soakMillis: Long = 3_000,
    val disputeEveryOrders: Int = 4,
    val recipes: Map<String, List<String>> = emptyMap(),
    val ingredients: Map<String, Material> = emptyMap(),
    val washing: Map<String, List<OriginWaiterWash>> = emptyMap(),
    val stations: Map<String, OriginWaiterStationsLayout> = emptyMap(),
    val reservationRetryMillis: Long = 1_000,
    val disputeActionMillis: Long = 1_600,
    val brawl: OriginWaiterBrawlConfig = OriginWaiterBrawlConfig(),
) {
    fun line(key: String, vararg values: Pair<String, Any>): String = values.fold(rewardPolicy.limits(text.string(key))) { line, (name, value) ->
        line.replace("{$name}", value.toString())
    }

    companion object {
        fun load(source: Config, rewardPolicy: OriginSideJobRewardPolicy = OriginSideJobRewardPolicy()) = OriginWaiterConfig(
            rewardPolicy = rewardPolicy,
            eatingMillis = source.integer("waiter-game.eating-seconds", 6).coerceIn(5, 40) * 1000L,
            actionMillis = source.integer("waiter-game.work-action-millis", 1600).coerceIn(700, 4000).toLong(),
            soakMillis = source.integer("waiter-game.soak-seconds", 3).coerceIn(1, 10) * 1000L,
            brawl = OriginWaiterBrawlConfig.load(source),
            reservationRetryMillis = source.integer("waiter-game.reservation-retry-millis", 1000).coerceIn(250, 5000).toLong(),
            disputeActionMillis = source.integer("waiter-game.dispute-action-millis", 1600).coerceIn(1000, 4000).toLong(),
            disputeEveryOrders = source.integer("waiter-game.dispute-every-orders", 4).coerceIn(0, 20),
            recipes = source.stringList("waiter-game.recipe-ids").associateWith { source.stringList("waiter-game.recipes.$it.ingredients").take(4) },
            ingredients = source.stringList("waiter-game.ingredient-ids").associateWith {
                Material.matchMaterial(source.string("waiter-game.ingredients.$it.material")) ?: Material.BOWL },
            washing = source.stringList("waiter-game.recipe-ids").associateWith { dish ->
                source.stringList("waiter-game.recipes.$dish.wash").mapNotNull { runCatching { OriginWaiterWash.valueOf(it.uppercase()) }.getOrNull() }
                    .filter { it != OriginWaiterWash.DRY } + OriginWaiterWash.DRY },
            stations = source.stringList("waiter-game.venue-ids").associateWith { venue ->
                OriginWaiterStationsLayout(OriginDiningLayout.configPoint(source, "waiter-game.venues.$venue.prep"),
                    OriginDiningLayout.configPoint(source, "waiter-game.venues.$venue.sink"),
                    OriginDiningLayout.configPoint(source, "waiter-game.venues.$venue.rack"),
                    source.real("waiter-game.venues.$venue.stand-height", 0.0).takeIf(Double::isFinite)?.coerceIn(0.0, 1.1) ?: 0.0,
                    cook = source.string("waiter-game.venues.$venue.cook", "").takeIf(String::isNotBlank)?.let {
                        OriginDiningLayout.configPoint(source, "waiter-game.venues.$venue.cook") }) },
            venues = source.stringList("waiter-game.venue-ids").map { id ->
                OriginWaiterVenue(id, source.integer("waiter-game.venues.$id.staff-id"),
                    source.boolean("waiter-game.venues.$id.sneak-only", false),
                    source.integer("waiter-game.venues.$id.chef-id", source.integer("waiter-game.venues.$id.staff-id")),
                    source.string("waiter-game.venues.$id.chef-scene", "").takeIf(String::isNotBlank))
            }.also { venues ->
                require(venues.map { it.id }.distinct().size == venues.size)
                require(venues.map { it.staffId }.distinct().size == venues.size)
                require(venues.all { it.staffId >= 0 })
            },
            lifetimeMillis = source.integer("waiter-game.timeout-seconds", 180).coerceIn(60, 600) * 1000L,
            preparationMillis = source.integer("waiter-game.preparation-seconds", 6).coerceIn(1, 30) * 1000L,
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

internal data class OriginWaiterReceipt(val shift: OriginWorkShift, val endedAt: Long, val unpaidOrders: Int, val paid: Boolean = true)

internal fun waiterReceiptLines(config: OriginWaiterConfig, receipt: OriginWaiterReceipt): List<String> = buildList {
    add(config.line("receipt-title", "time" to waiterTime(receipt.endedAt - receipt.shift.startedAt)))
    add(config.line(if (receipt.paid) "receipt-score" else "receipt-pending", "count" to receipt.shift.completedOrders, "amount" to receipt.shift.earnedAmount))
    if (receipt.unpaidOrders > 0) add(config.line("receipt-unpaid", "count" to receipt.unpaidOrders))
}

internal fun waiterReceipt(config: OriginWaiterConfig, receipt: OriginWaiterReceipt): Component =
    waiterReceiptLines(config, receipt).foldIndexed(Component.newline()) { index, result, line ->
        result.append(waiterText("  $line", TextColor.color(if (index == 0) 0xFFB277 else if (index == 1) 0x9BD48D else 0xE8DFD2)))
            .append(Component.newline())
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
    finish: () -> Unit = {},
): PaperDialogScreen {
    val available = shift == null && notice == null && status != null && status.available && !status.unresolved &&
        status.remainingOrders > 0
    fun button(id: String, key: String, color: Int, action: () -> Unit = {}) = PaperDialogButton(
        id = PaperDialogActionId.of(id), label = waiterText(config.line(key), TextColor.color(color)),
        width = 155, closeDialogBeforeAction = true, onClick = { action() },
    )
    val rows = if (shift == null) buildList {
        add(waiterText(config.line("label-duration")) to waiterText(config.line("duration", "minutes" to config.shiftMillis / 60_000)))
        add(waiterText(config.line("label-pay")) to waiterText(config.line("pay", "amount" to amount), TextColor.color(0x9BD48D)))
        if (status != null) add(waiterText(config.line("label-remaining")) to waiterText(config.line("remaining", "count" to status.remainingOrders)))
    } else listOf(
        waiterText(config.line("label-time")) to waiterText(waiterTime((if (shift.acceptsOrders(now)) shift.closesAt else shift.hardStopAt) - now)),
        waiterText(config.line("label-completed")) to waiterText(config.line("completed", "count" to shift.completedOrders, "amount" to shift.earnedAmount), TextColor.color(0x9BD48D)),
        waiterText(config.line("label-active")) to waiterText(config.line("active-orders", "count" to jobs.count { it.stage != OriginWaiterStage.PAYOUT })),
    )
    val state = notice ?: if (shift == null) when {
        status == null -> config.line("checking")
        status.unresolved -> config.line("unconfirmed")
        !status.available -> config.line("unavailable")
        status.remainingOrders <= 0 -> config.line("daily-limit")
        else -> null
    } else config.line("finish-help")
    return PaperDialogScreen(
        id = "dining.waiter.${venue.id}",
        title = waiterText(config.line("title", "venue" to config.line("venue-${venue.id}")), TextColor.color(0xFFB277)),
        body = buildList {
            if (shift == null) add(PaperDialogBody(waiterText(config.line("shift-introduction")), width = 310))
            add(DialogTables.body(rows, headers = null, frame = DialogTables.Frame.EPIC, width = 310,
                columns = DialogTables.Columns.VALUE_WIDE))
            state?.let { add(PaperDialogBody(waiterText(it), width = 310)) }
            if (shift == null && receipt != null) add(PaperDialogBody(waiterText(config.line("last-shift",
                "count" to receipt.shift.completedOrders, "amount" to receipt.shift.earnedAmount)), width = 310))
        },
        buttons = buildList {
            if (available) add(button("waiter_start", "start-shift", 0x9BD48D, start))
            if (shift != null) {
                add(button("waiter_continue", "continue-shift", 0xFFFFFF))
                if (!shift.finishRequested) add(button("waiter_finish", "finish-shift", 0xFF6B61, finish))
            } else add(button("waiter_cancel", "cancel-entry", 0xFFFFFF))
        },
        columns = 2,
    )
}

internal fun waiterJobProgress(job: OriginWaiterJob?): Float = when (job?.stage) {
    OriginWaiterStage.TAKE_ORDER -> 0f
    OriginWaiterStage.CLARIFY -> 0.1f
    OriginWaiterStage.ASSEMBLE -> 0.1f + 0.2f * job.assembled / job.recipe.size.coerceAtLeast(1)
    OriginWaiterStage.PICKUP, OriginWaiterStage.REWORK -> 0.3f
    OriginWaiterStage.DELIVER -> 0.4f
    OriginWaiterStage.EATING -> 0.55f
    OriginWaiterStage.COLLECT -> 0.65f
    OriginWaiterStage.RETURN_DISHES -> 0.7f
    OriginWaiterStage.WASH -> 0.7f + 0.3f * job.washed / job.washing.size
    OriginWaiterStage.PAYOUT -> 1f
    null -> 0f
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
    private val finishEating: (OriginWaiterTarget) -> Boolean,
    private val collectPlate: (OriginWaiterTarget) -> Boolean,
    private val release: (OriginWaiterTarget) -> Unit,
    private val guestVenue: (Int) -> String?,
    private val playerVenue: (Player) -> String?,
    private val debounce: (Player) -> Boolean,
    private val carry: OriginWaiterCarry,
    private val rewards: OriginSideJobRewards = OriginSideJobRewards(policy = config.rewardPolicy,
        captureRecovery = { step -> ArcOriginPlugin.instance.capture({ true }, step) },
        quests = ArcOriginPlugin.instance.questProgress),
    private val tasks: LifecycleTaskScope = LifecycleTaskScope(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val otherWork: (UUID) -> Boolean = { OriginOddJobsModule.isWorking(it) || OriginWorkshopGame.isWorking(it) },
    private val stations: OriginWaiterStations = OriginWaiterStations(),
    private val mealDisplay: (OriginWaiterTarget) -> ItemDisplay? = { null },
    private val brawl: OriginWaiterBrawl? = null,
    private val updateKitchen: (List<Pair<OriginWaiterVenue, OriginWaiterJob>>, Long) -> Unit = { _, _ -> },
) : AutoCloseable {
    private class Session(val venue: OriginWaiterVenue, var shift: OriginWorkShift, var remaining: Int) {
        var admitted = 0
        var nextDispute = 4
        var disputes = 0
        var inFlight: UUID? = null
        var payoutAttempt = 0L
        var settling = false
        var retryAt = 0L
        var reserveAt = 0L
        var lastFeedback: Pair<String, Long>? = null
        var lastWait: String? = null
        var nextWaitTitleAt = 0L
        var introduced = false
        var focusedRequest: UUID? = null
        val bossBar = BossBar.bossBar(Component.empty(), 0f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS)
        var endedAt: Long? = null
        var unpaidOrders = 0
        var phase = OriginWorkShiftPhase.OPENING
    }

    private val progress = OriginWaiterProgress(config.lifetimeMillis, config.preparationMillis, config.eatingMillis, config.actionMillis, config.soakMillis)
    private val sessions = mutableMapOf<UUID, Session>()
    private val receipts = mutableMapOf<UUID, OriginWaiterReceipt>()
    private val requests = mutableMapOf<UUID, UUID>()
    private val glowing = mutableMapOf<UUID, Entity>()
    private data class WorkAction(val job: OriginWaiterJob, val action: String, val index: Int, val startedAt: Long, val endsAt: Long)
    private val actions = mutableMapOf<UUID, WorkAction>()
    private data class Intervention(val npcId: Int, val stage: OriginWaiterDisputeStage, val endsAt: Long)
    private val interventions = mutableMapOf<UUID, Intervention>()
    private var closed = false

    init {
        rewards.recover(setOf(OriginSideJobKind.WAITER))
        tasks.runTimer(1, 1) {
        carry.tick()
        val now = clock()
        stations.tick(now)
        brawl?.tick(now)
        interventions.keys.toList().forEach { completeIntervention(it, now) }
        actions.keys.toList().forEach { completeWorkAction(it, now) }
    } }

    fun reserves(guestId: Int): Boolean = progress.entries().any { it.second.target.guestId == guestId }
    fun isWorking(playerId: UUID): Boolean = playerId in sessions
    fun awaitsTable(venueId: String?, now: Long): Boolean = !closed && sessions.any { (playerId, session) ->
        session.venue.id == venueId && session.endedAt == null && session.shift.acceptsOrders(now) &&
            progress.tickets(playerId).let { tickets ->
                (tickets.isEmpty() || tickets.any { it.stage != OriginWaiterStage.PAYOUT }) &&
                    tickets.size < capacity(session, tickets) && tickets.size < session.remaining
            }
    }

    fun interact(player: Player, npcId: Int): Boolean {
        if (closed) return false
        val venue = config.venues.firstOrNull { it.staffId == npcId }
        if (venue != null) {
            if (venue.sneakOnly && !player.isSneaking) return false
            if (!near(player, npcId)) return true
            if (debounce(player)) return true
            val session = sessions[player.uniqueId]
            if (session != null && session.venue.id != venue.id) { feedback(player, "other-venue"); return true }
            OriginMenus.beginDialogFlow(player)
            if (session == null) status(player, venue, start = false) else show(player, venue)
            return true
        }
        return interactGuest(player, npcId)
    }

    private fun interactGuest(player: Player, npcId: Int, atPlate: Boolean = false): Boolean {
        val session = sessions[player.uniqueId] ?: return false
        if (closed || session.endedAt != null || !present(player, session.venue)) return true
        val clickedVenue = guestVenue(npcId) ?: return false
        if (debounce(player)) return true
        if (player.uniqueId in actions) return true
        val objectLocation = if (atPlate) progress.tickets(player.uniqueId).firstOrNull { it.target.guestId == npcId }
            ?.target?.let(mealDisplay)?.location else actor(npcId)?.location
        if (objectLocation == null || objectLocation.world != player.world ||
            objectLocation.distanceSquared(player.location) > config.interactionRange * config.interactionRange) {
            feedback(player, "come-closer"); return true
        }
        val now = clock()
        refresh(player.uniqueId, now)
        if (progress.dispute(player.uniqueId) != null) {
            if (!atPlate) calm(player, npcId, now)
            return true
        }
        val before = progress.tickets(player.uniqueId).firstOrNull { it.target.guestId == npcId }
        if (before != null && before.stage != OriginWaiterStage.PAYOUT && !targetValid(before.target)) {
            cancelTicket(player.uniqueId, before, "target-gone")
            feedback(player, "target-gone")
            return true
        }
        if (before?.stage == OriginWaiterStage.COLLECT && !atPlate) { feedback(player, "collect-plate"); return true }
        if (atPlate && before?.stage != OriginWaiterStage.COLLECT) return true
        when (val step = progress.guest(player.uniqueId, npcId, clickedVenue, now)) {
            is OriginWaiterStep.Rejected -> reject(player, step.reason, before)
            is OriginWaiterStep.Changed -> {
                session.focusedRequest = step.job.id
                if (before?.stage == OriginWaiterStage.DELIVER) {
                    carry.remove(player.uniqueId)
                    if (!serve(before.servingTarget())) {
                        cancelTicket(player.uniqueId, step.job, "serve-failed")
                        feedback(player, "target-gone")
                        return true
                    }
                    info("ORIGIN_WAITER phase=SERVED player={} request={} table={}", player.uniqueId, step.job.id, step.job.target.tableId)
                    feedback(player, if (step.job.course == OriginWaiterCourse.DRINK) "drink-served" else "meal-served")
                } else if (before?.stage == OriginWaiterStage.COLLECT) {
                    val collected = runCatching { carry.pickupPlate(player, step.job.dish.id) && collectPlate(step.job.servingTarget()) }
                        .onFailure { warn("ORIGIN_WAITER phase=PLATE_FAILED player={} request={}", player.uniqueId, step.job.id, it) }
                        .getOrDefault(false)
                    if (!collected) {
                        carry.remove(player.uniqueId)
                        cancelTicket(player.uniqueId, step.job, "plate-unavailable")
                        feedback(player, "target-gone")
                        return true
                    }
                    feedback(player, "plate-collected")
                } else say(step.job, if (before?.stage == OriginWaiterStage.CLARIFY) "guest-revised" else "guest-order")
                fillOrders(player.uniqueId, now)
                guide(player)
                player.playSound(player.location, Sound.UI_BUTTON_CLICK, SoundCategory.PLAYERS, 0.35f, 1.15f)
            }
        }
        return true
    }

    fun interactMeal(player: Player, tableId: String): Boolean {
        if (!isWorking(player.uniqueId)) return false
        val job = progress.tickets(player.uniqueId).firstOrNull { it.target.tableId == tableId } ?: return false
        return interactGuest(player, job.target.guestId, atPlate = true)
    }

    /** The same action handles right-clicking a visible prop or its underlying counter. */
    fun interactWorkstation(player: Player): Boolean {
        val session = sessions[player.uniqueId] ?: return false
        if (closed || session.endedAt != null || !present(player, session.venue)) return false
        val hit = stations.aimed(player, config.interactionRange) ?: return false
        if (debounce(player)) return true
        if (player.uniqueId in actions) return true
        val now = clock()
        refresh(player.uniqueId, now)
        val job = progress.current(player.uniqueId, hit.requestId) ?: return true
        val action = hit.action
        if (action == "water" && progress.dispute(player.uniqueId)?.stage == OriginWaiterDisputeStage.WATER) {
            calm(player, session.venue.staffId, now)
            return true
        }
        if (!targetValid(job.target)) { cancelTicket(player.uniqueId, job, "target-gone"); return true }
        when (action) {
            "pickup" -> { pickup(player, session.venue, job.id, job.dish.id, atWorkstation = true); return true }
            "remake" -> { remake(player, session.venue, job.id, atWorkstation = true); return true }
        }
        val attempt = stationStep(player.uniqueId, session.venue.id, job.id, action, hit.stepIndex, now, commit = false)
            ?: return false
        if (attempt is OriginWaiterStep.Rejected) { reject(player, attempt.reason, job); return true }
        val layout = config.stations[session.venue.id] ?: return true
        val duration = if (action == "wash:SOAK") config.soakMillis else config.actionMillis
        val label = config.line(if (action.startsWith("ingredient:")) "station-assemble" else "station-${action.substringAfter(':').lowercase()}")
        if (!stations.begin(player, job, action, layout, duration, now, label) { choice ->
                choice.dishId?.let { if (choice.emptyPlate) carry.plateItem(it) else carry.dishItem(it) }
            }) return true
        actions[player.uniqueId] = WorkAction(job, action, hit.stepIndex, now, now + duration)
        if (job.stage == OriginWaiterStage.RETURN_DISHES) carry.remove(player.uniqueId)
        player.swingMainHand()
        guide(player)
        return true
    }

    private fun stationStep(playerId: UUID, venueId: String, requestId: UUID, action: String, index: Int,
        now: Long, commit: Boolean): OriginWaiterStep? = when {
        action.startsWith("ingredient:") -> progress.assemble(playerId, requestId, venueId, action.substringAfter(':'), index,
            now, commit = commit, completedAnimation = commit)
        action.startsWith("wash:") -> OriginWaiterWash.entries.firstOrNull { it.name == action.substringAfter(':') }?.let {
            progress.wash(playerId, requestId, venueId, it, index, now, commit = commit, completedAnimation = commit)
        }
        else -> null
    }

    /** Completion, payment and the next clickable prop occur only after the physical motion has finished. */
    private fun completeWorkAction(playerId: UUID, now: Long) {
        val action = actions[playerId] ?: return
        val session = sessions[playerId] ?: return
        val player = Bukkit.getPlayer(playerId)
        if (player == null || !present(player, session.venue) || player.isDead || !targetValid(action.job.target) ||
            now >= session.shift.hardStopAt) {
            cancelTicket(playerId, action.job, "work-interrupted")
            return
        }
        if (now < action.endsAt) return
        actions.remove(playerId)
        when (val step = stationStep(playerId, session.venue.id, action.job.id, action.action, action.index, now, commit = true)) {
            is OriginWaiterStep.Changed -> {
                if (step.job.stage == OriginWaiterStage.PAYOUT) settleNext(playerId, now)
                else if (step.job.stage == OriginWaiterStage.TAKE_ORDER) feedback(player, "table-ready")
                else if (step.job.stage == OriginWaiterStage.PICKUP && action.job.course == OriginWaiterCourse.DRINK)
                    feedback(player, "main-preparing")
            }
            is OriginWaiterStep.Rejected -> { stations.cancelAction(playerId); reject(player, step.reason, action.job) }
            null -> stations.cancelAction(playerId)
        }
        guide(player)
    }

    private fun say(job: OriginWaiterJob, key: String) {
        OriginNpcHolograms.showTemporaryBubble(job.target.guestId, listOf(config.line(key, "dish" to job.dish.name)),
            160, "player-waiter:${job.id}")
    }

    private fun calm(player: Player, npcId: Int, now: Long) {
        val dispute = progress.dispute(player.uniqueId) ?: return
        val target = progress.disputeGuest(player.uniqueId)
        val expected = if (dispute.stage == OriginWaiterDisputeStage.WATER) sessions[player.uniqueId]?.venue?.staffId else target?.target?.guestId
        if (npcId != expected) {
            feedback(player, if (dispute.stage == OriginWaiterDisputeStage.WATER) "dispute-get-water" else "dispute-wrong-guest",
                "guest" to target?.target?.guestName.orEmpty())
            return
        }
        if (player.uniqueId in interventions || brawl?.isReady(player.uniqueId) == false) return
        if (dispute.stage == OriginWaiterDisputeStage.WATER && !runCatching { carry.pickupWater(player) }.getOrDefault(false)) {
            feedback(player, "dish-unavailable")
            return
        }
        if (brawl != null && !brawl.beginIntervention(player.uniqueId, dispute.stage, now, config.disputeActionMillis)) {
            if (dispute.stage == OriginWaiterDisputeStage.WATER) carry.remove(player.uniqueId)
            return
        }
        interventions[player.uniqueId] = Intervention(npcId, dispute.stage, now + config.disputeActionMillis)
        player.swingMainHand()
        guide(player)
    }

    private fun completeIntervention(playerId: UUID, now: Long) {
        val pending = interventions[playerId] ?: return
        val dispute = progress.dispute(playerId) ?: run { interventions.remove(playerId); return }
        val player = Bukkit.getPlayer(playerId) ?: return
        val session = sessions[playerId] ?: return
        if (!present(player, session.venue) || session.endedAt != null) { stop(playerId, "left-venue"); return }
        if (brawl != null && !brawl.isActive(playerId)) { progress.endDispute(playerId, now); interventions.remove(playerId); carry.remove(playerId); return }
        if (now < pending.endsAt || brawl?.isReady(playerId) == false) return
        interventions.remove(playerId)
        val target = progress.disputeGuest(playerId)
        if (dispute.stage != pending.stage || !progress.calm(playerId, pending.npcId, now)) return
        if (dispute.stage == OriginWaiterDisputeStage.BRING_WATER) carry.remove(player.uniqueId)
        feedback(player, "dispute-${dispute.kind.name.lowercase()}-${dispute.stage.name.lowercase()}",
            "guest" to target?.target?.guestName.orEmpty(),
            "next" to progress.disputeGuest(player.uniqueId)?.target?.guestName.orEmpty())
        if (progress.dispute(playerId) == null) brawl?.stop(playerId)
        player.playSound(player.location, Sound.ENTITY_VILLAGER_YES, SoundCategory.PLAYERS, 0.35f, 1.0f)
        guide(player)
    }

    private fun status(player: Player, venue: OriginWaiterVenue, start: Boolean) {
        if (otherWork(player.uniqueId)) { feedback(player, "other-work"); return }
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
                if (otherWork(playerId)) { feedback(player, "other-work"); return@runSync }
                if (failure != null) {
                    warn("ORIGIN_WAITER phase=STATUS_FAILED player={}", playerId, failure)
                    if (start) feedback(player, "unavailable") else show(player, venue, notice = config.line("unavailable"))
                    return@runSync
                }
                if (state.recoverableShiftId != null) {
                    rewards.settle(playerId, state.recoverableShiftId) { result ->
                        if (!closed && !isWorking(playerId) && !otherWork(playerId) && near(player, venue.staffId)) {
                            if (result.outcome in setOf(SideJobRewardOutcome.PAID, SideJobRewardOutcome.ALREADY_PAID)) status(player, venue, start)
                            else show(player, venue, notice = config.line(if (result.outcome == SideJobRewardOutcome.UNCONFIRMED) "unconfirmed" else "unavailable"))
                        }
                    }
                    return@runSync
                }
                val now = clock()
                if (!start) { show(player, venue, state); return@runSync }
                val rejected = when {
                    state.unresolved -> "unconfirmed"
                    !state.available -> "unavailable"
                    state.remainingOrders <= 0 -> "daily-limit"
                    else -> null
                }
                if (rejected != null) {
                    feedback(player, rejected, "seconds" to waiterSeconds(state.nextAtMillis - now))
                    return@runSync
                }
                if (otherWork(playerId)) { feedback(player, "other-work"); return@runSync }
                val session = Session(venue, OriginWorkShift.begin(now, config.shiftMillis, config.graceMillis), state.remainingOrders)
                    .also { it.nextDispute = config.disputeEveryOrders }
                sessions[playerId] = session
                fillOrders(playerId, now)
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
                finish = { if (current()) stop(player.uniqueId, "finished-early") }),
            reopen = { if (near(player, venue.staffId)) {
                if (isWorking(player.uniqueId)) show(player, venue) else status(player, venue, start = false)
            } },
        )
    }

    private fun capacity(session: Session, tickets: List<OriginWaiterJob>): Int =
        if (session.admitted == 0 || session.admitted == 1 && tickets.any { it.stage == OriginWaiterStage.TAKE_ORDER || it.clearingPreviousMeal }) 1 else 2

    private fun fillOrders(playerId: UUID, now: Long) {
        val session = sessions[playerId] ?: return
        if (!session.shift.acceptsOrders(now) || now < session.reserveAt) return
        var tickets = progress.tickets(playerId)
        if (tickets.isNotEmpty() && tickets.all { it.stage == OriginWaiterStage.PAYOUT }) return
        while (tickets.size < capacity(session, tickets) && tickets.size < session.remaining) {
            val target = reserve(session.venue) ?: run { session.reserveAt = now + config.reservationRetryMillis; return }
            val rush = session.shift.phase(now) == OriginWorkShiftPhase.RUSH
            val family = OriginWaiterFamily.entries[(session.admitted + if (rush) 1 else 0) % OriginWaiterFamily.entries.size]
            val supported = when {
                family == OriginWaiterFamily.DRINK_FIRST && target.drink == null -> OriginWaiterFamily.MEAL
                family == OriginWaiterFamily.REVISED && target.replacement == null -> OriginWaiterFamily.MEAL
                family == OriginWaiterFamily.ASSEMBLED && config.recipes[target.dishId].isNullOrEmpty() -> OriginWaiterFamily.MEAL
                else -> family
            }
            val job = progress.begin(playerId, target, now, supported,
                if (rush) config.rushFreshnessMillis else config.freshnessMillis, session.shift.hardStopAt,
                recipe = if (supported == OriginWaiterFamily.ASSEMBLED) config.recipes[target.dishId].orEmpty() else emptyList(),
                washing = config.washing[target.dishId].orEmpty().ifEmpty {
                    listOf(OriginWaiterWash.SCRAPE, OriginWaiterWash.SOAP, OriginWaiterWash.RINSE, OriginWaiterWash.DRY) })
            if (job == null) { release(target); session.reserveAt = now + config.reservationRetryMillis; return }
            session.admitted++
            info("ORIGIN_WAITER phase=ORDER_RESERVED player={} shift={} request={} guest={} family={}",
                playerId, session.shift.id, job.id, target.guestId, job.family)
            tickets = progress.tickets(playerId)
        }
        if (playerId !in actions && config.disputeEveryOrders > 0 && session.admitted >= session.nextDispute) {
            val dispute = progress.beginDispute(playerId, OriginWaiterDisputeKind.entries[session.disputes % OriginWaiterDisputeKind.entries.size], now)
            if (dispute != null) {
                val first = checkNotNull(progress.current(playerId, dispute.first)).target
                val second = checkNotNull(progress.current(playerId, dispute.second)).target
                if (brawl != null && !brawl.start(playerId, first, second, now, dispute.kind)) {
                    progress.endDispute(playerId, now)
                    session.nextDispute = session.admitted + 1
                } else {
                    session.nextDispute = session.admitted + config.disputeEveryOrders
                    session.disputes++
                    Bukkit.getPlayer(playerId)?.let { feedback(it, "dispute-start") }
                }
            }
        }
    }

    private fun pickup(player: Player, venue: OriginWaiterVenue, requestId: UUID, dishId: String, atWorkstation: Boolean = false) {
        val job = progress.current(player.uniqueId, requestId) ?: return
        if (!atWorkstation && !near(player, venue.staffId)) { feedback(player, "come-closer"); return }
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
                guide(player)
                player.playSound(player.location, Sound.ENTITY_ITEM_PICKUP, SoundCategory.PLAYERS, 0.35f, 0.9f)
            }
        }
    }

    private fun remake(player: Player, venue: OriginWaiterVenue, requestId: UUID, atWorkstation: Boolean = false) {
        if (!atWorkstation && !near(player, venue.staffId)) return
        when (val step = progress.remake(player.uniqueId, requestId, venue.staffId, venue.id, clock())) {
            is OriginWaiterStep.Rejected -> reject(player, step.reason, progress.current(player.uniqueId, requestId))
            is OriginWaiterStep.Changed -> {
                feedback(player, "remaking", "guest" to step.job.target.guestName)
                guide(player)
            }
        }
    }

    /** Save completed orders without paying; only idempotent storage operations may retry. */
    private fun settleNext(playerId: UUID, now: Long) {
        val session = sessions[playerId] ?: return
        if (closed || session.inFlight != null || now < session.retryAt) return
        val job = progress.tickets(playerId).firstOrNull { it.stage == OriginWaiterStage.PAYOUT } ?: return
        session.inFlight = job.id
        val attempt = ++session.payoutAttempt
        rewards.credit(OriginSideJobKind.WAITER, playerId, session.shift.id, job.id, job.quality) { result ->
            if (closed || sessions[playerId] !== session || session.inFlight != job.id || session.payoutAttempt != attempt) return@credit
            session.inFlight = null
            val player = Bukkit.getPlayer(playerId)?.takeIf(Player::isOnline)
            val outcome = result.outcome
            if (outcome == SideJobRewardOutcome.RETRY) {
                session.retryAt = clock() + 1_000
                return@credit
            }
            val finished = progress.finish(playerId, job.id) ?: return@credit
            release(finished.target)
            when (outcome) {
                SideJobRewardOutcome.CREDITED -> {
                    session.shift = session.shift.record(job.id, rewards.amount, job.quality)
                    session.remaining = (session.remaining - 1).coerceAtLeast(0)
                    session.retryAt = 0
                    player?.let {
                        feedback(it, "order-credited", "amount" to session.shift.earnedAmount, "count" to session.shift.completedOrders)
                        it.playSound(it.location, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.PLAYERS, 0.45f, 1.15f)
                    }
                    if (session.remaining == 0) stop(playerId, "daily-limit") else fillOrders(playerId, clock())
                }
                else -> {
                    // A rejected ledger record is terminal. Never turn it into a fresh identity.
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
        kitchen(now)
        sessions.toMap().forEach { (playerId, session) ->
            val player = Bukkit.getPlayer(playerId)
            if (session.endedAt == null) {
                val reason = when {
                    player == null || !player.isOnline -> "offline"
                    player.isDead -> "cancelled"
                    now >= session.shift.hardStopAt -> "shift-ended"
                    !present(player, session.venue) -> "left-venue"
                    else -> null
                }
                if (reason != null) stop(playerId, reason)
                else {
                    completeWorkAction(playerId, now)
                    refresh(playerId, now)
                    progress.tickets(playerId).filter { it.stage != OriginWaiterStage.PAYOUT }.forEach { job ->
                        val rejection = if (now >= job.expiresAt && progress.dispute(playerId) == null) "expired"
                            else if (!targetValid(job.target)) "target-gone" else null
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

    private fun kitchen(now: Long) = updateKitchen(sessions.flatMap { (playerId, session) ->
        if (session.endedAt != null || progress.dispute(playerId) != null) emptyList() else progress.tickets(playerId)
            .filter { it.stage == OriginWaiterStage.PICKUP && it.family != OriginWaiterFamily.ASSEMBLED }
            .map { session.venue to it }
    }, now)

    private fun refresh(playerId: UUID, now: Long) {
        if (progress.dispute(playerId) != null && brawl != null && !brawl.isActive(playerId)) {
            progress.endDispute(playerId, now)
            interventions.remove(playerId)
            carry.remove(playerId)
        }
        val carried = progress.carried(playerId)?.id
        val changes = progress.refresh(playerId, now)
        if (carried != null && progress.carried(playerId)?.id != carried) carry.remove(playerId)
        if (progress.dispute(playerId) == null) progress.tickets(playerId)
            .filter { it.stage == OriginWaiterStage.EATING && now >= it.readyAt && now < it.expiresAt }.forEach { job ->
                if (targetValid(job.target) && finishEating(job.servingTarget())) progress.finishEating(playerId, job.id, now)
                else cancelTicket(playerId, job, "meal-unavailable")
            }
        val player = Bukkit.getPlayer(playerId)?.takeIf(Player::isOnline) ?: return
        changes.firstOrNull { it.stage == OriginWaiterStage.CLARIFY }?.let {
            say(it, "guest-clarify")
        }
        changes.firstOrNull { it.stage == OriginWaiterStage.REWORK }?.let {
            feedback(player, "dish-cooled", "guest" to it.target.guestName)
        }
    }

    /** Finish a chosen task before background cooking or clearing can replace its guidance. */
    private fun guidedJob(playerId: UUID, now: Long): OriginWaiterJob? {
        val session = sessions[playerId] ?: return null
        val job = actions[playerId]?.job ?: progress.priority(playerId, now, session.focusedRequest)
            ?: progress.tickets(playerId).firstOrNull()
        session.focusedRequest = job?.id
        return job
    }

    private fun guide(player: Player) {
        val session = sessions[player.uniqueId] ?: return
        if (session.endedAt != null) return
        val now = clock()
        val action = actions[player.uniqueId]
        val job = guidedJob(player.uniqueId, now)
        val dispute = progress.dispute(player.uniqueId)
        val layout = config.stations[session.venue.id]
        val workstation = when {
            dispute?.stage == OriginWaiterDisputeStage.WATER && layout != null -> listOf(
                OriginWaiterStationChoice("water", layout.surface(layout.prep), Material.POTION, true))
            dispute != null -> emptyList()
            job != null && (job.stage != OriginWaiterStage.PICKUP || now >= job.readyAt) -> waiterStationChoices(config, session.venue, job)
            else -> emptyList()
        }
        stations.show(player, job?.id ?: session.shift.id, workstation, layout,
            if (job?.stage == OriginWaiterStage.ASSEMBLE) job.assembled else job?.washed ?: 0,
            job = if (dispute == null) job else null, now = now, preparationMillis = config.preparationMillis,
            cookingLabel = config.line("station-cooking"), readyLabel = config.line("station-ready"),
            item = { choice -> choice.dishId?.let { if (choice.emptyPlate) carry.plateItem(it) else carry.dishItem(it) } })
        val entity: Entity? = if (dispute != null) progress.disputeGuest(player.uniqueId)?.target?.guestId?.let(::actor) else when (job?.stage) {
            OriginWaiterStage.COLLECT -> mealDisplay(job.target)
            OriginWaiterStage.TAKE_ORDER, OriginWaiterStage.CLARIFY, OriginWaiterStage.DELIVER -> actor(job.target.guestId)
            OriginWaiterStage.PICKUP -> if (job.family != OriginWaiterFamily.ASSEMBLED) actor(session.venue.chefId) else null
            else -> null
        }
        if (glowing[player.uniqueId] !== entity) {
            clearGlow(player.uniqueId)
        }
        if (entity != null && entity.isValid) {
            // Entity untracking clears the packet override even when this Bukkit entity is unchanged.
            setGlow(entity, player, true)
            glowing[player.uniqueId] = entity
        }
        val key = if (player.uniqueId in interventions) "dispute-intervening" else if (dispute != null && brawl?.isReady(player.uniqueId) == false) "dispute-approaching" else if (action != null) {
            if (action.action.startsWith("ingredient:")) "station-assemble" else "station-${action.action.substringAfter(':').lowercase()}"
        } else if (dispute != null) "guide-dispute-${dispute.stage.name.lowercase()}" else when (job?.stage) {
            OriginWaiterStage.TAKE_ORDER -> "guide-order"
            OriginWaiterStage.CLARIFY -> "guide-clarify"
            OriginWaiterStage.PICKUP -> if (now < job.readyAt) "guide-preparing" else "guide-pickup"
            OriginWaiterStage.DELIVER -> "guide-deliver"
            OriginWaiterStage.REWORK -> "guide-rework"
            OriginWaiterStage.ASSEMBLE -> if (now < job.readyAt) "guide-preparing" else "guide-assemble"
            OriginWaiterStage.EATING -> "guide-eating"
            OriginWaiterStage.COLLECT -> "guide-collect"
            OriginWaiterStage.RETURN_DISHES -> "guide-return-dishes"
            OriginWaiterStage.WASH -> if (now < job.readyAt) "guide-soaking" else "guide-wash"
            OriginWaiterStage.PAYOUT -> "paying"
            null -> "guide-board"
        }
        val guest = (if (dispute != null) progress.disputeGuest(player.uniqueId) else job)?.target?.guestName.orEmpty()
        val next = config.line(key, "guest" to guest, "dish" to job?.dish?.name.orEmpty(),
            "seconds" to waiterSeconds((job?.readyAt ?: now) - now),
            "ingredient" to config.line("ingredient-${job?.recipe?.getOrNull(job.assembled)}"),
            "action" to config.line("wash-${job?.washing?.getOrNull(job.washed)?.name?.lowercase()}"))
        val remaining = waiterTime((if (session.shift.acceptsOrders(now)) session.shift.closesAt else session.shift.hardStopAt) - now)
        session.bossBar.name(waiterText(next, TextColor.color(0xFFB277))
            .append(waiterText("  ·  $remaining  ·  ${session.shift.earnedAmount} 💰", TextColor.color(0xE8DFD2))))
        session.bossBar.progress(if (action != null) ((now - action.startedAt).toFloat() / (action.endsAt - action.startedAt)).coerceIn(0f, 1f)
            else if (dispute == null) waiterJobProgress(job) else dispute.stage.ordinal.toFloat() / OriginWaiterDisputeStage.entries.size)
        session.bossBar.color(if (job?.carries == true) BossBar.Color.GREEN else BossBar.Color.YELLOW)
        player.showBossBar(session.bossBar)
        val wait = if (action != null || dispute != null) null else when {
            job == null -> "wait-table"
            job.stage == OriginWaiterStage.PICKUP && now < job.readyAt -> "wait-cooking"
            job.stage == OriginWaiterStage.EATING -> "wait-eating"
            job.stage == OriginWaiterStage.PAYOUT -> "paying"
            else -> null
        }
        if (!session.introduced || wait != null && (session.lastWait != wait || now >= session.nextWaitTitleAt)) {
            val subtitle = if (wait == null) next else config.line(wait, "dish" to job?.dish?.name.orEmpty(),
                "guest" to guest, "seconds" to waiterSeconds((job?.readyAt ?: now) - now))
            player.showTitle(Title.title(waiterText(config.line(if (wait == null) "work-started" else "wait-title"), TextColor.color(0xFFB277)),
                waiterText(subtitle), Title.Times.times(Duration.ofMillis(150), Duration.ofMillis(1800), Duration.ofMillis(300))))
            session.introduced = true
            session.nextWaitTitleAt = now + 5_000
        } else if (session.lastWait != null && wait == null) {
            player.showTitle(Title.title(waiterText(config.line("work-ready"), TextColor.color(0x9BD48D)), waiterText(next),
                Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(1200), Duration.ofMillis(250))))
        }
        session.lastWait = wait
        kitchen(now)
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
            OriginWaiterRejection.ASSEMBLE_FIRST -> "assemble-first"
            OriginWaiterRejection.WRONG_INGREDIENT -> "wrong-ingredient"
            OriginWaiterRejection.EATING -> "guide-eating"
            OriginWaiterRejection.COLLECT_FIRST -> "guide-collect"
            OriginWaiterRejection.WASH_FIRST -> "guide-return-dishes"
            OriginWaiterRejection.WRONG_WASH_STEP -> "wrong-wash-step"
            OriginWaiterRejection.STALE_ACTION -> "action-already-done"
            OriginWaiterRejection.DISPUTE -> "dispute-start"
        }
        val target = progress.carried(player.uniqueId) ?: job ?: guidedJob(player.uniqueId, clock())
        feedback(player, key, "guest" to (target?.target?.guestName ?: ""), "dish" to (job?.dish?.name ?: ""),
            "seconds" to waiterSeconds((job?.readyAt ?: 0) - clock()),
            "ingredient" to config.line("ingredient-${job?.recipe?.getOrNull(job.assembled)}"),
            "action" to config.line("wash-${job?.washing?.getOrNull(job.washed)?.name?.lowercase()}"))
        if (reason == OriginWaiterRejection.EXPIRED && job != null) cancelTicket(player.uniqueId, job, "expired")
    }

    private fun cancelTicket(playerId: UUID, job: OriginWaiterJob, reason: String) {
        val hadDispute = progress.dispute(playerId) != null
        val removed = progress.cancel(playerId, job.id) ?: return
        if (removed.carries || hadDispute) carry.remove(playerId)
        if (hadDispute) { brawl?.stop(playerId); interventions.remove(playerId) }
        val currentAction = actions[playerId]
        if (currentAction == null || currentAction.job.id == removed.id) {
            actions.remove(playerId)
            stations.remove(playerId)
        }
        release(removed.target)
        info("ORIGIN_WAITER phase=ORDER_CANCELLED player={} request={} reason={}", playerId, job.id, reason)
    }

    private fun actor(id: Int): LivingEntity? = CitizensAPI.getNPCRegistry().getById(id)
        ?.takeIf { it.isSpawned && it.entity.world.name == OriginDiningLayout.WORLD }?.entity as? LivingEntity

    private fun present(player: Player, venue: OriginWaiterVenue): Boolean =
        player.isOnline && !player.isDead && player.world.name == OriginDiningLayout.WORLD &&
            (playerVenue(player) == venue.id || near(player, venue.staffId) || config.stations[venue.id]?.let { layout ->
                listOf(layout.prep, layout.sink, layout.rack).any {
                    it.inWorld(player.world).distanceSquared(player.location) <= config.interactionRange * config.interactionRange
                }
            } == true)

    private fun near(player: Player, npcId: Int): Boolean {
        if (!player.isOnline || player.isDead || player.world.name != OriginDiningLayout.WORLD) return false
        val entity = actor(npcId) ?: return false
        return player.world == entity.world && player.location.distanceSquared(entity.location) <= config.interactionRange * config.interactionRange
    }

    private fun feedback(player: Player, key: String, vararg values: Pair<String, Any>) {
        val now = clock()
        val text = config.line(key, *values)
        val session = sessions[player.uniqueId]
        if (session?.lastFeedback?.let { it.first == text && now - it.second < 1_800 } == true) return
        session?.lastFeedback = text to now
        val color = if (key in setOf("order-credited", "table-ready", "plate-collected", "meal-served", "drink-served"))
            0x9BD48D else 0xFFB277
        player.sendActionBar(waiterText(text, TextColor.color(color)))
    }

    private fun setGlow(entity: Entity, player: Player, value: Boolean) {
        when (entity) {
            is Display -> OriginServices.viewerGlow.setDisplayGlowingFor(entity, player, value)
            is LivingEntity -> OriginServices.viewerGlow.setEntityGlowingFor(entity, player, value)
        }
    }

    private fun clearGlow(playerId: UUID) {
        val entity = glowing.remove(playerId) ?: return
        val player = Bukkit.getPlayer(playerId) ?: return
        if (entity.isValid) setGlow(entity, player, false)
    }

    fun stop(playerId: UUID, reason: String) {
        requests.remove(playerId)
        actions.remove(playerId)
        interventions.remove(playerId)
        brawl?.stop(playerId)
        stations.remove(playerId)
        carry.remove(playerId)
        clearGlow(playerId)
        val session = sessions[playerId] ?: return
        Bukkit.getPlayer(playerId)?.hideBossBar(session.bossBar)
        session.shift = session.shift.requestFinish()
        if (session.endedAt == null) session.endedAt = clock()
        // A completed handoff is independent of the player's location and of the shift timer.
        progress.tickets(playerId).filter { it.stage != OriginWaiterStage.PAYOUT }.forEach { cancelTicket(playerId, it, reason) }
        info("ORIGIN_WAITER phase=SHIFT_STOPPED player={} shift={} reason={}", playerId, session.shift.id, reason)
        kitchen(clock())
        finishIfReady(playerId, clock())
    }

    private fun finishIfReady(playerId: UUID, now: Long) {
        val session = sessions[playerId] ?: return
        if (progress.tickets(playerId).isNotEmpty() || session.inFlight != null || !session.shift.shouldFinish(now, false)) return
        if (session.settling) return
        session.settling = true
        Bukkit.getPlayer(playerId)?.takeIf(Player::isOnline)?.let { feedback(it, "settling") }
        rewards.settle(playerId, session.shift.id) { result ->
            if (closed || sessions[playerId] !== session) return@settle
            if (result.outcome == SideJobRewardOutcome.RETRY) { session.settling = false; return@settle }
            completeShift(playerId, session, result, now)
        }
    }

    private fun completeShift(playerId: UUID, session: Session, result: SideJobRewardResult, now: Long) {
        sessions.remove(playerId, session)
        Bukkit.getPlayer(playerId)?.hideBossBar(session.bossBar)
        stations.remove(playerId)
        carry.remove(playerId)
        clearGlow(playerId)
        val confirmed = result.outcome in setOf(SideJobRewardOutcome.PAID, SideJobRewardOutcome.ALREADY_PAID)
        val receipt = OriginWaiterReceipt(if (confirmed) session.shift.confirmed(result.earnings) else session.shift,
            session.endedAt ?: now, session.unpaidOrders, paid = confirmed)
        receipts[playerId] = receipt
        Bukkit.getPlayer(playerId)?.takeIf(Player::isOnline)?.sendMessage(waiterReceipt(config, receipt))
        info("ORIGIN_WAITER phase=SHIFT_FINISHED player={} shift={} orders={} amount={} quality={} unpaid={}",
            playerId, session.shift.id, session.shift.completedOrders, session.shift.earnedAmount, session.shift.quality, session.unpaidOrders)
    }

    override fun close() {
        if (closed) return
        sessions.forEach { (id, session) ->
            progress.tickets(id).filter { it.stage == OriginWaiterStage.PAYOUT && it.id != session.inFlight }.forEach { job ->
                rewards.credit(OriginSideJobKind.WAITER, id, session.shift.id, job.id, job.quality) {}
            }
        }
        closed = true
        tasks.close()
        requests.clear()
        actions.clear()
        interventions.clear()
        brawl?.close()
        // The cashier fences/retains durable claims before the transient dining reservations disappear.
        rewards.close()
        sessions.forEach { (playerId, session) ->
            Bukkit.getPlayer(playerId)?.hideBossBar(session.bossBar)
            carry.remove(playerId)
            clearGlow(playerId)
        }
        progress.clear().forEach { (_, job) -> release(job.target) }
        sessions.clear()
        updateKitchen(emptyList(), clock())
        receipts.clear()
        carry.close()
        stations.close()
    }
}
