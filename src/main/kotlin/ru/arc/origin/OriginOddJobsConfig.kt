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
    val range: Double,
    val workRange: Double,
    val venues: List<OriginOddJobVenue>,
    val handoffNpcId: Int,
    val handoffNpcUuid: UUID,
    val handoffName: String,
    val text: ConfigSection,
) {
    fun venue(kind: OriginOddJobKind) = venues.first { it.kind == kind }
    fun line(key: String, vararg values: Pair<String, Any>): String = values.fold(text.string(key)) { line, (name, value) ->
        line.replace("{$name}", value.toString())
    }

    companion object {
        fun load(source: Config) = OriginOddJobsConfig(
            source.boolean("enabled", true),
            source.integer("timeout-seconds", 180).coerceIn(60, 600) * 1_000L,
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
            source.string("jobs.auction.handoff-name"), source.section("text"),
        )
    }
}

internal enum class OriginOddJobStationId { HAY, FEED, WATER, BLANK, FIRE, ANVIL, QUENCH }

internal data class OriginOddJobStation(val point: Location, val block: Block, val materials: Set<Material>) {
    fun valid(): Boolean = block.world.isChunkLoaded(block.x shr 4, block.z shr 4) && block.type in materials
}

internal fun originOddJobStation(stage: OriginOddJobStage): OriginOddJobStationId? = when (stage) {
    OriginOddJobStage.YARD_HAY -> OriginOddJobStationId.HAY
    OriginOddJobStage.YARD_FEED -> OriginOddJobStationId.FEED
    OriginOddJobStage.YARD_WATER -> OriginOddJobStationId.WATER
    OriginOddJobStage.FORGE_BLANK -> OriginOddJobStationId.BLANK
    OriginOddJobStage.FORGE_HEAT, OriginOddJobStage.FORGE_HEATING -> OriginOddJobStationId.FIRE
    OriginOddJobStage.FORGE_PLACE, OriginOddJobStage.FORGE_HAMMER -> OriginOddJobStationId.ANVIL
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

internal fun originOddJobGuide(config: OriginOddJobsConfig, job: OriginOddJobSession, now: Long): String = config.line(
    when (job.stage) {
        OriginOddJobStage.YARD_HAY -> "mountyard.hay"
        OriginOddJobStage.YARD_FEED -> "mountyard.feed"
        OriginOddJobStage.YARD_WATER -> "mountyard.water"
        OriginOddJobStage.FORGE_BLANK -> "forge.blank"
        OriginOddJobStage.FORGE_HEAT -> "forge.heat"
        OriginOddJobStage.FORGE_HEATING -> when {
            now - job.stageStartedAt > ODD_JOB_HEAT_LIMIT_MILLIS -> "forge.heat-missed"
            now - job.stageStartedAt >= ODD_JOB_HEAT_READY_MILLIS -> "forge.heat-ready"
            else -> "forge.heating"
        }
        OriginOddJobStage.FORGE_PLACE -> "forge.place"
        OriginOddJobStage.FORGE_HAMMER -> if (originOddJobHammerReady(job, now)) "forge.hammer-ready" else "forge.hammer-wait"
        OriginOddJobStage.FORGE_QUENCH -> "forge.quench"
        OriginOddJobStage.AUCTION_ITEM, OriginOddJobStage.AUCTION_PACK -> "auction.choices-guide"
        OriginOddJobStage.AUCTION_DELIVER -> "auction.deliver-guide"
        OriginOddJobStage.RETURN -> "return-guide"
        OriginOddJobStage.PAYOUT -> "paying"
    }, "count" to job.progress, "name" to config.venue(job.kind).name,
    "seconds" to waiterSeconds(ODD_JOB_HEAT_READY_MILLIS - (now - job.stageStartedAt)),
)

internal fun originOddJobsScreen(
    config: OriginOddJobsConfig,
    venue: OriginOddJobVenue,
    job: OriginOddJobSession?,
    amount: Int,
    status: SideJobRewardStatus?,
    now: Long,
    notice: String? = null,
    start: () -> Unit = {},
    chooseItem: (OriginAuctionParcel) -> Unit = {},
    choosePacking: (OriginAuctionPacking) -> Unit = {},
    cancel: () -> Unit = {},
): PaperDialogScreen {
    val prefix = venue.kind.name.lowercase(Locale.ROOT)
    fun itemName(parcel: OriginAuctionParcel) = config.line("auction.${parcel.name.lowercase(Locale.ROOT)}")
    fun button(id: String, label: String, close: Boolean = false, red: Boolean = false, action: () -> Unit) = PaperDialogButton(
        PaperDialogActionId.of(id), waiterText(label, TextColor.color(if (red) 0xFF6B61 else 0x9BD48D)),
        width = 300, closeDialogBeforeAction = close, onClick = { action() },
    )
    val body = buildList {
        if (job == null) {
            add(config.line("$prefix.introduction"))
            add(config.line("pay", "amount" to amount))
            add(config.line("shared-limit"))
            val gate = originOddJobStatusKey(status, now)
            add(notice ?: if (gate != null) config.line(gate, "seconds" to waiterSeconds((status?.nextAtMillis ?: now) - now))
                else config.line("remaining", "count" to requireNotNull(status).remainingOrders))
        } else if (job.kind == OriginOddJobKind.AUCTION && job.stage in setOf(OriginOddJobStage.AUCTION_ITEM, OriginOddJobStage.AUCTION_PACK)) {
            val parcel = requireNotNull(job.parcel)
            add(config.line("auction.manifest", "item" to itemName(parcel), "rule" to config.line("auction.${parcel.name.lowercase(Locale.ROOT)}-rule")))
            add(config.line(if (job.stage == OriginOddJobStage.AUCTION_ITEM) "auction.choose-item" else "auction.choose-packing"))
            notice?.let(::add)
        } else {
            add(originOddJobGuide(config, job, now))
            notice?.let(::add)
        }
    }
    return PaperDialogScreen(
        id = "odd-jobs.$prefix", title = waiterText(config.line("$prefix.title"), TextColor.color(0xFFB277)),
        body = body.map { PaperDialogBody(waiterText(it), width = 300) },
        buttons = buildList {
            if (job == null && notice == null && originOddJobStatusKey(status, now) == null)
                add(button("odd_job_start", config.line("start"), close = venue.kind != OriginOddJobKind.AUCTION, action = start))
            if (job?.stage == OriginOddJobStage.AUCTION_ITEM) OriginAuctionParcel.entries.forEach { item ->
                add(button("odd_job_item_${item.name.lowercase(Locale.ROOT)}", itemName(item)) { chooseItem(item) })
            }
            if (job?.stage == OriginOddJobStage.AUCTION_PACK) OriginAuctionPacking.entries.forEach { packing ->
                val key = packing.name.lowercase(Locale.ROOT).replace('_', '-')
                add(button("odd_job_pack_${packing.name.lowercase(Locale.ROOT)}", config.line("auction.$key")) { choosePacking(packing) })
            }
            if (job != null && job.stage !in setOf(OriginOddJobStage.AUCTION_ITEM, OriginOddJobStage.AUCTION_PACK, OriginOddJobStage.PAYOUT))
                add(button("odd_job_continue", config.line("continue"), close = true) {})
            if (job != null && job.stage != OriginOddJobStage.PAYOUT)
                add(button("odd_job_cancel", config.line("cancel"), close = true, red = true, action = cancel))
        }, columns = 1,
    )
}
