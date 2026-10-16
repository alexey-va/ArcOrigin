package ru.arc.origin

import net.kyori.adventure.text.format.TextColor
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import ru.arc.config.Config
import ru.arc.config.ConfigSection
import ru.arc.origin.scene.OriginScenePlan
import ru.arc.origin.scene.OriginScenePoint
import ru.arc.origin.scene.OriginScenePropSurface
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogBody
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogScreen
import java.util.Locale
import java.util.UUID
import kotlin.math.floor

internal data class OriginOddJobVenue(
    val kind: OriginOddJobKind,
    val npcId: Int,
    val npcUuid: UUID,
    val name: String,
    val scene: String?,
    val actorIds: Set<Int>,
)

internal data class OriginOddJobsConfig(
    val enabled: Boolean,
    val lifetimeMillis: Long,
    val shiftMillis: Long,
    val range: Double,
    val workRange: Double,
    val venues: List<OriginOddJobVenue>,
    val handoffNpcId: Int,
    val handoffNpcUuid: UUID,
    val handoffName: String,
    val text: ConfigSection,
    val rewardPolicy: OriginSideJobRewardPolicy = OriginSideJobRewardPolicy(),
) {
    fun venue(kind: OriginOddJobKind) = venues.first { it.kind == kind }
    fun line(key: String, vararg values: Pair<String, Any>): String = values.fold(rewardPolicy.limits(text.string(key))) { line, (name, value) ->
        line.replace("{$name}", value.toString())
    }

    companion object {
        fun load(source: Config, rewardPolicy: OriginSideJobRewardPolicy = OriginSideJobRewardPolicy()) = OriginOddJobsConfig(
            source.boolean("enabled", true),
            source.integer("timeout-seconds", 240).coerceIn(120, 600) * 1_000L,
            source.integer("shift-seconds", 600).coerceIn(480, 600) * 1_000L,
            source.real("interaction-range", 4.5).takeIf(Double::isFinite)?.coerceIn(2.0, 4.5) ?: 4.5,
            source.real("work-range", 36.0).takeIf(Double::isFinite)?.coerceIn(20.0, 64.0) ?: 36.0,
            OriginOddJobKind.entries.map { kind ->
                val key = "jobs.${kind.name.lowercase(Locale.ROOT)}"
                OriginOddJobVenue(kind, source.integer("$key.npc-id"), UUID.fromString(source.string("$key.npc-uuid")), source.string("$key.name"),
                    if (kind == OriginOddJobKind.AUCTION) null else source.string("$key.scene"),
                    source.stringList("$key.actor-ids").map(String::toInt).toSet())
            }.also { venues ->
                require(venues.map { it.npcId }.distinct().size == venues.size && venues.all { it.npcId >= 0 })
                require(venues.filter { it.scene != null }.all { it.scene!!.isNotBlank() && it.npcId in it.actorIds })
            },
            source.integer("jobs.auction.handoff-npc-id"), UUID.fromString(source.string("jobs.auction.handoff-npc-uuid")),
            source.string("jobs.auction.handoff-name"), source.section("text"), rewardPolicy,
        )
    }
}

internal enum class OriginOddJobStationId { HAY, FEED, WATER, BLANK, FIRE, ANVIL, QUENCH }

internal data class OriginOddJobStation(val point: Location, val block: Block, val materials: Set<Material>) {
    fun valid(): Boolean = block.world.isChunkLoaded(block.x shr 4, block.z shr 4) && block.type in materials
}

internal fun originOddJobStation(stage: OriginOddJobStage): OriginOddJobStationId? = when (stage) {
    OriginOddJobStage.YARD_HAY -> OriginOddJobStationId.HAY
    OriginOddJobStage.YARD_FEED, OriginOddJobStage.YARD_LOAD -> OriginOddJobStationId.FEED
    OriginOddJobStage.YARD_DRAIN, OriginOddJobStage.YARD_CLEAN, OriginOddJobStage.YARD_FILL, OriginOddJobStage.YARD_FILLING -> OriginOddJobStationId.WATER
    OriginOddJobStage.FORGE_BLANK -> OriginOddJobStationId.BLANK
    OriginOddJobStage.FORGE_HEAT, OriginOddJobStage.FORGE_HEATING -> OriginOddJobStationId.FIRE
    OriginOddJobStage.FORGE_INSPECT, OriginOddJobStage.FORGE_PLACE, OriginOddJobStage.FORGE_HAMMER -> OriginOddJobStationId.ANVIL
    OriginOddJobStage.FORGE_QUENCH -> OriginOddJobStationId.QUENCH
    else -> null
}

internal fun originOddJobRequiredStations(kind: OriginOddJobKind): Set<OriginOddJobStationId> = when (kind) {
    OriginOddJobKind.MOUNTYARD -> setOf(OriginOddJobStationId.HAY, OriginOddJobStationId.FEED, OriginOddJobStationId.WATER)
    OriginOddJobKind.FORGE -> setOf(OriginOddJobStationId.BLANK, OriginOddJobStationId.FIRE, OriginOddJobStationId.ANVIL, OriginOddJobStationId.QUENCH)
    OriginOddJobKind.AUCTION -> emptySet()
}

/** Bounded resolution in loaded chunks; admission caches the result for work actions and ticks. */
internal fun originOddJobStations(plan: OriginScenePlan, world: World): Map<OriginOddJobStationId, OriginOddJobStation> = buildMap {
    fun surface(id: OriginOddJobStationId, spec: OriginScenePropSurface) {
        val x = floor(spec.near.x).toInt()
        val z = floor(spec.near.z).toInt()
        for (cx in ((x - spec.searchRadius) shr 4)..((x + spec.searchRadius) shr 4))
            for (cz in ((z - spec.searchRadius) shr 4)..((z + spec.searchRadius) shr 4))
                if (!world.isChunkLoaded(cx, cz)) return
        val point = spec.resolve(world) ?: return
        val block = world.getBlockAt(floor(point.x).toInt(), floor(point.y - spec.topOffset + 1e-6).toInt(), floor(point.z).toInt())
        put(id, OriginOddJobStation(point.inWorld(world), block, spec.materials))
    }
    fun around(id: OriginOddJobStationId, anchor: OriginScenePoint, materials: Set<Material>, radius: Int, height: Double) =
        surface(id, OriginScenePropSurface(anchor, materials, radius, height))

    val yard = plan.scene("mount-yard")
    // The top of the hay pile is one block above its authored actor-facing anchor.
    around(OriginOddJobStationId.HAY, yard.anchors.getValue("hay-source").let { it.copy(y = it.y + 1) }, setOf(Material.HAY_BLOCK), 2, 1.02)
    around(OriginOddJobStationId.WATER, yard.anchors.getValue("water-trough"), setOf(Material.WATER_CAULDRON), 2, 0.9)
    val feed = yard.anchors.getValue("feed-floor")
    val feedX = floor(feed.x).toInt()
    val feedZ = floor(feed.z).toInt()
    if (world.isChunkLoaded(feedX shr 4, feedZ shr 4)) {
        val block = world.getBlockAt(feedX, floor(feed.y).toInt() - 1, feedZ)
        if (block.type.isSolid) put(OriginOddJobStationId.FEED,
            OriginOddJobStation(Location(world, feed.x, block.y + 1.02, feed.z), block, setOf(block.type)))
    }
    val forge = plan.scene("forge")
    around(OriginOddJobStationId.BLANK, forge.anchors.getValue("stock-chest"),
        setOf(Material.CHEST, Material.TRAPPED_CHEST, Material.BARREL), 1, 0.9)
    around(OriginOddJobStationId.FIRE, forge.anchors.getValue("furnace-fire"),
        setOf(Material.FURNACE, Material.BLAST_FURNACE, Material.SMOKER, Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.FIRE, Material.SOUL_FIRE), 1, 1.02)
    surface(OriginOddJobStationId.ANVIL, forge.propSurfaces.getValue("master-work-surface"))
    around(OriginOddJobStationId.QUENCH, forge.anchors.getValue("quench-cauldron"), setOf(Material.WATER_CAULDRON), 2, 0.9)
}

internal fun originOddJobStatusKey(status: SideJobRewardStatus?, now: Long): String? = when {
    status == null -> "checking"
    status.unresolved -> "unconfirmed"
    !status.available -> "cash-unavailable"
    status.remainingOrders <= 0 -> "daily-limit"
    status.nextAtMillis > now -> "cooldown"
    else -> null
}

internal fun OriginOddJobVariant.key(): String = name.lowercase(Locale.ROOT).replace('_', '-')
internal fun originOddJobClock(millis: Long): String = (millis.coerceAtLeast(0) / 1_000).let { "%d:%02d".format(it / 60, it % 60) }
internal fun originOddJobGuide(config: OriginOddJobsConfig, job: OriginOddJobSession, now: Long): String {
    val key = when (job.stage) {
        OriginOddJobStage.FORGE_HEATING -> when {
            now - job.stageStartedAt >= originOddJobHeatReady(job) -> "forge-heat-ready"
            else -> "forge-heating"
        }
        OriginOddJobStage.RETURN -> if (job.kind == OriginOddJobKind.FORGE) "forge-return" else "return"
        else -> job.stage.name.lowercase(Locale.ROOT).replace('_', '-')
    }
    val f = job.forge; val y = job.yard
    return config.line("guide.$key", "name" to config.venue(job.kind).name,
        "strikes" to (f?.remainingStrikes ?: 0), "level" to originOddJobWaterLevel(job, now),
        "water" to (y?.waterTarget ?: 0), "compartment" to ((y?.waterCompartment ?: 0) + 1), "clean" to (y?.let { Integer.bitCount(it.cleanMask) } ?: 0),
        "left" to (y?.filled?.get(0) ?: 0), "left-target" to (y?.required?.get(0) ?: 0),
        "right" to (y?.filled?.get(1) ?: 0), "right-target" to (y?.required?.get(1) ?: 0),
        "seconds" to waiterSeconds(originOddJobHeatReady(job) - (now - job.stageStartedAt)))
}

internal fun originOddJobChoices(config: OriginOddJobsConfig, job: OriginOddJobSession): List<Pair<Int, String>> {
    fun line(key: String, vararg values: Pair<String, Any>) = config.line(key, *values)
    val auction = job.auction
    fun lotName(lot: OriginAuctionLot) = line("auction.lot", "item" to line("auction.${lot.parcel.name.lowercase()}"),
        "serial" to lot.serial, "condition" to line(if (lot.restored) "auction.restored" else "auction.intact"))
    return when (job.stage) {
        OriginOddJobStage.YARD_HAY -> List(3) { index -> index to line("mountyard.bundle-${(index - job.offer.seed % 3 + 3) % 3}") }
        OriginOddJobStage.YARD_LOAD -> buildList {
            add(0 to line("mountyard.load-left")); add(1 to line("mountyard.load-right"))
            if (job.yard?.loads?.isNotEmpty() == true) add(2 to line("mountyard.move-last"))
        }
        OriginOddJobStage.AUCTION_ITEM -> originAuctionCandidates(job).mapIndexed { index, lot -> index to lotName(lot) }
        OriginOddJobStage.AUCTION_INSPECT -> listOf(0 to line("auction.accept-record"), 1 to line("auction.verify-record"))
        OriginOddJobStage.AUCTION_PACK -> OriginAuctionPacking.entries.mapIndexed { index, packing ->
            index to line("auction.${packing.name.lowercase().replace('_', '-')}") }
        OriginOddJobStage.AUCTION_LOAD -> requireNotNull(auction).lots.mapIndexedNotNull { index, lot ->
            if (index in auction.loaded) null else index to line("auction.load", "item" to line("auction.${lot.parcel.name.lowercase()}"), "weight" to lot.parcel.weight) }
        OriginOddJobStage.AUCTION_RECEIPT -> List(3) { index -> index to line("auction.receipt-choice", "serial" to
            (requireNotNull(auction).lots.sumOf { it.serial } + if (index == job.offer.seed % 3) 0 else index + 1)) }
        else -> emptyList()
    }
}

internal fun originOddJobsScreen(
    config: OriginOddJobsConfig, venue: OriginOddJobVenue, job: OriginOddJobSession?, amount: Int,
    status: SideJobRewardStatus?, now: Long, notice: String? = null,
    shift: OriginWorkShift? = null, offers: List<OriginOddJobOffer> = emptyList(), paying: Boolean = false, actionsAvailable: Boolean = true,
    start: () -> Unit = {}, accept: (UUID) -> Unit = {}, choose: (Int) -> Unit = {},
    finishShift: () -> Unit = {}, cancel: () -> Unit = {},
): PaperDialogScreen {
    require(venue.kind != OriginOddJobKind.FORGE) { "Forge orders use physical stations and action-bar guidance" }
    val prefix = venue.kind.name.lowercase(Locale.ROOT)
    fun button(id: String, label: String, close: Boolean = false, red: Boolean = false, action: () -> Unit) = PaperDialogButton(
        PaperDialogActionId.of(id), waiterText(label, TextColor.color(if (red) 0xFF6B61 else 0x9BD48D)),
        width = 310, closeDialogBeforeAction = close, onClick = { action() })
    val body = buildList {
        if (shift == null) {
            add(config.line("$prefix.introduction")); add(config.line("pay", "amount" to amount)); add(config.line("shared-limit"))
            val gate = originOddJobStatusKey(status, now)
            add(notice ?: if (gate != null) config.line(gate, "seconds" to waiterSeconds((status?.nextAtMillis ?: now) - now))
                else config.line("remaining", "count" to requireNotNull(status).remainingOrders))
        } else {
            add(config.line("shift.status", "clock" to originOddJobClock((if (shift.acceptsOrders(now)) shift.closesAt else shift.hardStopAt) - now),
                "done" to shift.completedOrders, "amount" to shift.earnedAmount,
                "quality" to if (shift.completedOrders == 0) 100 else shift.quality))
            if (job == null) {
                add(config.line(if (paying) "paying" else if (!shift.acceptsOrders(now)) "shift.closing" else "shift.board"))
                offers.forEachIndexed { index, offer ->
                    add(config.line("shift.offer", "number" to index + 1, "name" to config.line("variants.${offer.variant.key()}.title"),
                        "detail" to config.line("variants.${offer.variant.key()}.detail"),
                        "pace" to config.line(if (offer.urgent) "shift.urgent" else "shift.normal")))
                }
            } else {
                add(config.line("variants.${job.variant.key()}.title")); add(originOddJobGuide(config, job, now))
                job.yard?.let { y ->
                    if (job.stage == OriginOddJobStage.YARD_HAY) add(config.line("mountyard.fresh-rule"))
                    if (job.stage == OriginOddJobStage.YARD_LOAD) {
                        val weights = listOf(1, 2, 3, 2)
                        add(config.line("mountyard.balance", "weight" to y.carried,
                            "left" to y.loads.indices.filter { y.loads[it] == 0 }.sumOf { weights[it] },
                            "right" to y.loads.indices.filter { y.loads[it] == 1 }.sumOf { weights[it] }, "next" to weights.drop(y.loads.size + 1).joinToString(", ")))
                    }
                }
                job.auction?.let { a ->
                    if (job.stage in setOf(OriginOddJobStage.AUCTION_ITEM, OriginOddJobStage.AUCTION_INSPECT, OriginOddJobStage.AUCTION_PACK)) {
                        add(config.line("auction.manifest", "item" to config.line("auction.${a.lot.parcel.name.lowercase()}"),
                            "serial" to originAuctionManifestSerial(job), "condition" to config.line(if (a.lot.restored) "auction.restored" else "auction.intact")))
                        if (job.stage in setOf(OriginOddJobStage.AUCTION_ITEM, OriginOddJobStage.AUCTION_INSPECT))
                            add(config.line("auction.inventory-record", "serial" to a.lot.serial))
                    }
                    if (job.stage == OriginOddJobStage.AUCTION_LOAD) add(config.line("auction.load-rule"))
                    if (job.stage == OriginOddJobStage.AUCTION_RECEIPT) add(config.line("auction.receipt", "serial" to a.lots.sumOf { it.serial }))
                }
            }
            notice?.let(::add)
        }
    }
    return PaperDialogScreen(id = "odd-jobs.$prefix", title = waiterText(config.line("$prefix.title"), TextColor.color(0xFFB277)),
        body = body.map { PaperDialogBody(waiterText(it), width = 310) }, buttons = buildList {
            if (shift == null && notice == null && originOddJobStatusKey(status, now) == null)
                add(button("odd_job_start", config.line("start"), action = start))
            if (shift != null) {
                if (job == null && !paying && shift.acceptsOrders(now)) offers.forEachIndexed { index, offer ->
                    add(button("odd_job_offer_$index", config.line("variants.${offer.variant.key()}.action"), close = venue.kind != OriginOddJobKind.AUCTION) { accept(offer.id) })
                }
                if (job != null) {
                    if (actionsAvailable) originOddJobChoices(config, job).forEach { (index, label) ->
                        add(button("odd_job_choice_$index", label,
                            close = job.kind != OriginOddJobKind.AUCTION || job.stage == OriginOddJobStage.AUCTION_RECEIPT) { choose(index) })
                    }
                    if (!actionsAvailable || originOddJobChoices(config, job).isEmpty()) add(button("odd_job_continue", config.line("continue"), close = true) {})
                    add(button("odd_job_cancel", config.line("cancel"), close = true, red = true, action = cancel))
                }
                if (!shift.finishRequested) add(button("odd_job_finish", config.line(if (job == null) "shift.finish" else "shift.finish-after"), close = true, action = finishShift))
            }
        }, columns = 1)
}
