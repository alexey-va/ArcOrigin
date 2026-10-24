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
import ru.arc.paper.menu.DialogTables
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

internal data class OriginYardRider(val npcId: Int, val npcUuid: UUID, val name: String)

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
    val yardAnimalIds: List<Int> = listOf(422, 423, 442, 445, 446),
    val yardRider: OriginYardRider? = null,
    val yardRideAnchorIds: List<String> = emptyList(),
    val yardDeliveryAnchorIds: List<String> = emptyList(),
    val yardGateAnchorIds: List<String> = emptyList(),
    val yardFeedingMillis: Long = 5_000,
    val yardRouteTimeoutMillis: Long = 60_000,
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
                val key = "jobs.${kind.key()}"
                OriginOddJobVenue(kind, source.integer("$key.npc-id"), UUID.fromString(source.string("$key.npc-uuid")), source.string("$key.name"),
                    if (kind == OriginOddJobKind.AUCTION) null else source.string("$key.scene"),
                    source.stringList("$key.actor-ids").map(String::toInt).toSet())
            }.also { venues ->
                require(venues.map { it.npcId }.distinct().size == venues.size && venues.all { it.npcId >= 0 })
                require(venues.filter { it.scene != null }.all { it.scene!!.isNotBlank() && it.npcId in it.actorIds })
            },
            source.integer("jobs.auction.handoff-npc-id"), UUID.fromString(source.string("jobs.auction.handoff-npc-uuid")),
            source.string("jobs.auction.handoff-name"), source.section("text"), rewardPolicy,
            source.stringList("jobs.mountyard.animal-ids").map(String::toInt).also { require(it.isNotEmpty()) },
            source.integer("jobs.mountyard.rider-npc-id", -1).takeIf { it >= 0 }?.let { id ->
                OriginYardRider(id, UUID.fromString(source.string("jobs.mountyard.rider-npc-uuid")),
                    source.string("jobs.mountyard.rider-name", "Всадник"))
            },
            source.stringList("jobs.mountyard.ride-anchor-ids"),
            source.stringList("jobs.mountyard.delivery-anchor-ids"),
            source.stringList("jobs.mountyard.stall-gate-anchor-ids"),
            source.integer("jobs.mountyard.feeding-seconds", 5).coerceIn(1, 30) * 1_000L,
            source.integer("jobs.mountyard.route-timeout-seconds", 60).coerceIn(15, 180) * 1_000L,
        )
    }
}

internal enum class OriginOddJobStationId { HAY, FEED, WATER, WELL, BRUSH, SADDLE_RACK, BLANK, FIRE, ANVIL, QUENCH, ORE_STOCK, ENGRAVING, HAUL_DELIVERY }

internal fun originOddJobWorkpieceTarget(stage: OriginOddJobStage): Boolean = stage in setOf(
    OriginOddJobStage.FORGE_HEATING, OriginOddJobStage.FORGE_INSPECT, OriginOddJobStage.FORGE_HAMMER,
    OriginOddJobStage.ENGRAVE_CARVE, OriginOddJobStage.ENGRAVE_COLLECT)

internal fun originOddJobWorkpieceGlows(job: OriginOddJobSession, now: Long): Boolean = originOddJobWorkpieceTarget(job.stage) &&
    (job.stage != OriginOddJobStage.FORGE_HEATING || now - job.stageStartedAt >= originOddJobHeatReady(job))

internal data class OriginOddJobStation(val point: Location, val block: Block, val materials: Set<Material>,
    val accessBlocks: List<Block> = emptyList(), val interactionBounds: OriginWorkshopAabb? = null) {
    fun valid(): Boolean = block.world.isChunkLoaded(block.x shr 4, block.z shr 4) && block.type in materials
}

/** Resolve the native connected inventory; adjacent single chests remain separate stations. */
internal fun originOddJobStationBlocks(block: Block): List<Block> {
    val data = block.blockData as? org.bukkit.block.data.type.Chest ?: return listOf(block)
    if (data.type == org.bukkit.block.data.type.Chest.Type.SINGLE) return listOf(block)
    val holder = (block.state as? org.bukkit.block.Chest)?.inventory?.holder as? org.bukkit.block.DoubleChest ?: return listOf(block)
    val left = (holder.leftSide as? org.bukkit.block.Chest)?.block ?: return listOf(block)
    val right = (holder.rightSide as? org.bukkit.block.Chest)?.block ?: return listOf(block)
    return if (left != right && (left == block || right == block)) listOf(left, right) else listOf(block)
}

internal fun originOddJobStation(stage: OriginOddJobStage): OriginOddJobStationId? = when (stage) {
    OriginOddJobStage.YARD_HAY -> OriginOddJobStationId.HAY
    OriginOddJobStage.YARD_BUCKET -> OriginOddJobStationId.WELL
    OriginOddJobStage.YARD_WELL -> OriginOddJobStationId.WATER
    OriginOddJobStage.YARD_BRUSH, OriginOddJobStage.CLEAN_TOOL -> OriginOddJobStationId.BRUSH
    OriginOddJobStage.YARD_TACK, OriginOddJobStage.YARD_LEAD_RACK -> OriginOddJobStationId.SADDLE_RACK
    OriginOddJobStage.FORGE_BLANK -> OriginOddJobStationId.BLANK
    OriginOddJobStage.FORGE_HEAT, OriginOddJobStage.FORGE_HEATING -> OriginOddJobStationId.FIRE
    OriginOddJobStage.FORGE_INSPECT, OriginOddJobStage.FORGE_PLACE, OriginOddJobStage.FORGE_HAMMER -> OriginOddJobStationId.ANVIL
    OriginOddJobStage.FORGE_QUENCH -> OriginOddJobStationId.QUENCH
    OriginOddJobStage.HAUL_PICKUP -> OriginOddJobStationId.ORE_STOCK
    OriginOddJobStage.HAUL_PUSH, OriginOddJobStage.HAUL_UNLOAD -> OriginOddJobStationId.HAUL_DELIVERY
    OriginOddJobStage.ENGRAVE_BLANK -> OriginOddJobStationId.BLANK
    OriginOddJobStage.ENGRAVE_PLACE, OriginOddJobStage.ENGRAVE_CARVE, OriginOddJobStage.ENGRAVE_COLLECT -> OriginOddJobStationId.ENGRAVING
    else -> null
}

internal fun originOddJobRequiredStations(kind: OriginOddJobKind): Set<OriginOddJobStationId> = when (kind) {
    OriginOddJobKind.MOUNTYARD -> setOf(OriginOddJobStationId.HAY, OriginOddJobStationId.WATER,
        OriginOddJobStationId.WELL, OriginOddJobStationId.BRUSH, OriginOddJobStationId.SADDLE_RACK)
    OriginOddJobKind.FORGE -> setOf(OriginOddJobStationId.BLANK, OriginOddJobStationId.FIRE, OriginOddJobStationId.ANVIL,
        OriginOddJobStationId.QUENCH, OriginOddJobStationId.ORE_STOCK, OriginOddJobStationId.ENGRAVING, OriginOddJobStationId.HAUL_DELIVERY)
    OriginOddJobKind.FORGE_HAUL -> setOf(OriginOddJobStationId.ORE_STOCK, OriginOddJobStationId.HAUL_DELIVERY)
    OriginOddJobKind.FORGE_ENGRAVE -> setOf(OriginOddJobStationId.BLANK, OriginOddJobStationId.ENGRAVING)
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
    // Only the authored supply blocks qualify, including the owner's concrete replacement supports.
    val supplySupports = setOf(Material.BARREL, Material.WHITE_CONCRETE)
    yard.anchors["water-source"]?.let { around(OriginOddJobStationId.WELL, it, supplySupports, 0, 1.02) }
    yard.anchors["brush-source"]?.let { around(OriginOddJobStationId.BRUSH, it, supplySupports, 0, 1.02) }
    yard.anchors["saddle-source"]?.let { around(OriginOddJobStationId.SADDLE_RACK, it, supplySupports, 0, 1.02) }
    val feed = yard.anchors["job-feed-floor"] ?: OriginScenePoint(48.5, 70.05, -134.5)
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
    forge.anchors["heat-chain"]?.let { anchor ->
        val x = floor(anchor.x).toInt(); val y = floor(anchor.y).toInt(); val z = floor(anchor.z).toInt()
        if (world.isChunkLoaded(x shr 4, z shr 4)) {
            val block = world.getBlockAt(x, y, z)
            if (block.type == Material.IRON_CHAIN) {
                // The authored forge has a guard rail in front of the lava. Work with tongs through that rail.
                val rail = (-1..1).map { world.getBlockAt(x - 2, y - 1, z + it) }.filter { it.type == Material.IRON_BARS }
                put(OriginOddJobStationId.FIRE, OriginOddJobStation(anchor.inWorld(world), block, setOf(Material.IRON_CHAIN), rail))
            }
        }
    }
    forge.propSurfaces["player-work-surface"]?.let { surface(OriginOddJobStationId.ANVIL, it) }
    forge.propSurfaces["apprentice-work-surface"]?.let { surface(OriginOddJobStationId.ENGRAVING, it) }
    around(OriginOddJobStationId.ORE_STOCK, forge.anchors.getValue("job-haul-stock"),
        setOf(Material.CHEST, Material.TRAPPED_CHEST, Material.BARREL), 0, 0.9)
    forge.anchors["job-haul-delivery"]?.let { anchor ->
        val x = floor(anchor.x).toInt(); val z = floor(anchor.z).toInt()
        if (world.isChunkLoaded(x shr 4, z shr 4)) {
            val support = world.getBlockAt(x, floor(anchor.y - 0.01).toInt(), z)
            if (support.type.isSolid) put(OriginOddJobStationId.HAUL_DELIVERY,
                OriginOddJobStation(anchor.inWorld(world), support, setOf(support.type)))
        }
    }
    around(OriginOddJobStationId.QUENCH, forge.anchors.getValue("quench-cauldron"), setOf(Material.WATER_CAULDRON), 2, 0.9)
}

internal fun originOddJobStatusKey(status: SideJobRewardStatus?, now: Long): String? = when {
    status == null -> "checking"
    status.unresolved -> "unconfirmed"
    !status.available -> "cash-unavailable"
    status.remainingOrders <= 0 -> "daily-limit"
    else -> null
}

internal fun OriginOddJobVariant.key(): String = name.lowercase(Locale.ROOT).replace('_', '-')
internal fun originOddJobClock(millis: Long): String = (millis.coerceAtLeast(0) / 1_000).let { "%d:%02d".format(it / 60, it % 60) }
internal fun originOddJobGuide(config: OriginOddJobsConfig, job: OriginOddJobSession, now: Long): String {
    val key = when (job.stage) {
        OriginOddJobStage.FORGE_BLANK -> when (job.variant) {
            OriginOddJobVariant.FORGE_REPAIR -> "forge-repair-stock"
            OriginOddJobVariant.FORGE_PAIR -> "forge-pair-stock"
            else -> "forge-blank"
        }
        OriginOddJobStage.FORGE_HEATING -> when {
            now - job.stageStartedAt >= originOddJobHeatReady(job) -> "forge-heat-ready"
            else -> "forge-heating"
        }
        OriginOddJobStage.YARD_HANDOFF -> if (job.variant == OriginOddJobVariant.YARD_LESSON) "yard-lesson-mount" else "yard-handoff"
        OriginOddJobStage.YARD_DELIVER -> if (job.variant == OriginOddJobVariant.YARD_LESSON) "yard-lesson-meet" else "yard-deliver"
        OriginOddJobStage.CLEAN_PUSH -> if (job.cleaning?.let { it.collected == it.total } == true) "clean-push-full" else "clean-push"
        OriginOddJobStage.CLEAN_CART -> if ((job.cleaning?.collected ?: 0) > 0) "clean-cart-full" else "clean-cart"
        OriginOddJobStage.RETURN -> when (job.kind) {
            OriginOddJobKind.FORGE -> "forge-return"
            OriginOddJobKind.MOUNTYARD -> "yard-complete"
            else -> "return"
        }
        else -> job.stage.name.lowercase(Locale.ROOT).replace('_', '-')
    }
    val f = job.forge; val y = job.yard
    return config.line("guide.$key", "name" to config.venue(job.employer).name,
        "strikes" to (f?.remainingStrikes ?: 0), "animal" to (y?.animalName ?: ""),
        "rider" to (config.yardRider?.name ?: "Всадник"),
        "product" to (f?.product?.title ?: "Заготовка"), "metal" to (f?.metal?.title ?: job.engraving?.metal?.title ?: ""),
        "cargo" to (job.haul?.cargo?.title ?: ""),
        "word" to (job.engraving?.word ?: ""), "letters" to ((job.engraving?.strokes ?: 0) / 2),
        "percent" to (((now - job.stageStartedAt).coerceAtLeast(0) * 100 / originOddJobHeatReady(job)).coerceAtMost(100)),
        "route-point" to ((y?.routeIndex ?: 0) + 1).coerceAtMost((y?.routeTotal ?: 1).coerceAtLeast(1)),
        "route-total" to (y?.routeTotal ?: 0), "collected" to (job.cleaning?.collected ?: 0),
        "clean-total" to (job.cleaning?.total ?: 0),
        "brush" to (y?.brushStrokes ?: 0), "brush-total" to ODD_JOB_GROOM_STROKES,
        "seconds" to waiterSeconds(originOddJobHeatReady(job) - (now - job.stageStartedAt)))
}

internal fun originOddJobChoices(config: OriginOddJobsConfig, job: OriginOddJobSession): List<Pair<Int, String>> {
    fun line(key: String, vararg values: Pair<String, Any>) = config.line(key, *values)
    val auction = job.auction
    fun lotName(lot: OriginAuctionLot) = line("auction.lot", "item" to line("auction.${lot.parcel.name.lowercase()}"),
        "serial" to lot.serial, "condition" to line(if (lot.restored) "auction.restored" else "auction.intact"))
    return when (job.stage) {
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
    shift: OriginWorkShift? = null, paymentGuide: String? = null,
    start: () -> Unit = {}, resume: () -> Unit = {}, finishShift: () -> Unit = {},
    openShop: (() -> Unit)? = null,
): PaperDialogScreen {
    val prefix = venue.kind.key()
    val yard = venue.kind == OriginOddJobKind.MOUNTYARD
    val gate = originOddJobStatusKey(status, now)
    fun button(id: String, key: String, color: Int, action: () -> Unit) = PaperDialogButton(
        PaperDialogActionId.of(id), waiterText(config.line(key), TextColor.color(color)),
        width = 155, closeDialogBeforeAction = true, onClick = { action() })
    val body = buildList {
        if (shift == null) {
            add(PaperDialogBody(waiterText(config.line("$prefix.introduction", "amount" to amount)), 310))
            add(DialogTables.body(buildList {
                add(waiterText(config.line("table.duration")) to waiterText(config.line("table.duration-value", "minutes" to config.shiftMillis / 60_000)))
                add(waiterText(config.line(if(yard) "table.yard-pay" else "table.pay")) to waiterText(config.line("table.pay-value", "amount" to amount), TextColor.color(0x9BD48D)))
                if (status != null) add(waiterText(config.line("table.quota")) to waiterText(config.line(if(yard) "table.yard-quota-value" else "table.quota-value", "count" to status.remainingOrders)))
            }, null, if (yard) DialogTables.Frame.UNCOMMON else DialogTables.Frame.COMMON, 310, DialogTables.Columns.VALUE_WIDE))
            val reason = notice ?: gate?.let { config.line(it, "seconds" to waiterSeconds((status?.nextAtMillis ?: now) - now)) }
            reason?.let { add(PaperDialogBody(waiterText(it), 310)) }
        } else {
            add(DialogTables.filledBody(rows = listOf(
                waiterText(config.line("table.time-left")) to waiterText(originOddJobClock(shift.closesAt - now)),
                waiterText(config.line(if(yard) "table.yard-completed" else "table.completed")) to waiterText(config.line("table.completed-value", "done" to shift.completedOrders, "amount" to shift.earnedAmount), TextColor.color(0x9BD48D)),
                waiterText(config.line("table.active")) to waiterText(if (job == null) "0" else "1"))
                .map { (label, value) -> listOf(label, value) },
                headers = null, width = 310, columnWeights = listOf(1, 1)))
            add(PaperDialogBody(waiterText(job?.let { originOddJobGuide(config, it, now) }
                ?: paymentGuide ?: config.line("shift.assigning")), 310))
            add(PaperDialogBody(waiterText(config.line("shift.leave-hint")), 310))
            notice?.let { add(PaperDialogBody(waiterText(it), 310)) }
        }
    }
    return PaperDialogScreen(id = "odd-jobs.$prefix", title = waiterText(config.line("$prefix.title"), TextColor.color(0xFFB277)),
        body = body, buttons = buildList {
            if (shift == null) {
                if (notice == null && gate == null) add(button("odd_job_start", "start", 0x9BD48D, start))
                if (venue.kind == OriginOddJobKind.FORGE_HAUL && openShop != null)
                    add(button("odd_job_shop", "forge-haul.shop", 0xFFFFFF, openShop))
                add(button("odd_job_later", "later", 0xFFFFFF) {})
            } else {
                add(button("odd_job_continue", "continue", 0xFFFFFF, resume).copy(
                    closeDialogBeforeAction = job?.kind != OriginOddJobKind.AUCTION || originOddJobChoices(config, job).isEmpty()))
                add(button("odd_job_finish", "shift.finish", 0xFF6B61, finishShift))
            }
        }, columns = 2)
}

/** The only work dialog is a document check; physical stations never open a task-selection board. */
internal fun originOddJobWorkScreen(config: OriginOddJobsConfig, job: OriginOddJobSession, now: Long,
    notice: String? = null, choose: (Int) -> Unit = {}): PaperDialogScreen {
    val a = requireNotNull(job.auction)
    val body = buildList {
        add(originOddJobGuide(config, job, now))
        if (job.stage in setOf(OriginOddJobStage.AUCTION_ITEM, OriginOddJobStage.AUCTION_INSPECT, OriginOddJobStage.AUCTION_PACK)) {
            add(config.line("auction.manifest", "item" to config.line("auction.${a.lot.parcel.name.lowercase()}"),
                "serial" to originAuctionManifestSerial(job), "condition" to config.line(if (a.lot.restored) "auction.restored" else "auction.intact")))
            if (job.stage in setOf(OriginOddJobStage.AUCTION_ITEM, OriginOddJobStage.AUCTION_INSPECT))
                add(config.line("auction.inventory-record", "serial" to a.lot.serial))
        }
        if (job.stage == OriginOddJobStage.AUCTION_LOAD) add(config.line("auction.load-rule"))
        if (job.stage == OriginOddJobStage.AUCTION_RECEIPT) add(config.line("auction.receipt", "serial" to a.lots.sumOf { it.serial }))
        notice?.let(::add)
    }
    return PaperDialogScreen(id = "odd-jobs.auction-work", title = waiterText(config.line("auction.work-title"), TextColor.color(0xFFB277)),
        body = body.map { PaperDialogBody(waiterText(it), 310) }, buttons = originOddJobChoices(config, job).map { (index, label) ->
            PaperDialogButton(PaperDialogActionId.of("odd_job_choice_$index"), waiterText(label, TextColor.color(0x9BD48D)),
                width = 310, closeDialogBeforeAction = job.stage == OriginOddJobStage.AUCTION_RECEIPT ||
                    job.stage == OriginOddJobStage.AUCTION_PACK && a.lots.size == 1 ||
                    job.stage == OriginOddJobStage.AUCTION_LOAD && a.loaded.size + 1 == a.lots.size ||
                    job.stage == OriginOddJobStage.AUCTION_INSPECT && index == 1,
                onClick = { choose(index) })
        }, columns = 1)
}
