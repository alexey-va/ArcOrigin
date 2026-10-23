package ru.arc.origin

import java.util.UUID
import kotlin.random.Random

internal enum class OriginOddJobKind { MOUNTYARD, FORGE, FORGE_HAUL, FORGE_ENGRAVE, AUCTION;
    val rewardKind: OriginSideJobKind get() = when (this) {
        FORGE, FORGE_HAUL, FORGE_ENGRAVE -> OriginSideJobKind.FORGE
        MOUNTYARD -> OriginSideJobKind.MOUNTYARD
        AUCTION -> OriginSideJobKind.AUCTION
    }
    fun key(): String = name.lowercase().replace('_', '-')
}
internal enum class OriginOddJobVariant(val kind: OriginOddJobKind) {
    YARD_RATIONS(OriginOddJobKind.MOUNTYARD), YARD_WATER(OriginOddJobKind.MOUNTYARD),
    YARD_GROOM(OriginOddJobKind.MOUNTYARD), YARD_SADDLE(OriginOddJobKind.MOUNTYARD),
    YARD_LESSON(OriginOddJobKind.MOUNTYARD), YARD_CLEANUP(OriginOddJobKind.MOUNTYARD),
    FORGE_NEW(OriginOddJobKind.FORGE), FORGE_REPAIR(OriginOddJobKind.FORGE), FORGE_PAIR(OriginOddJobKind.FORGE),
    FORGE_HOOK(OriginOddJobKind.FORGE), FORGE_HINGE(OriginOddJobKind.FORGE), FORGE_BRACKET(OriginOddJobKind.FORGE),
    HAUL_IRON(OriginOddJobKind.FORGE_HAUL), HAUL_COPPER(OriginOddJobKind.FORGE_HAUL), HAUL_COAL(OriginOddJobKind.FORGE_HAUL),
    ENGRAVE_RUS(OriginOddJobKind.FORGE_ENGRAVE), ENGRAVE_HOME(OriginOddJobKind.FORGE_ENGRAVE), ENGRAVE_CAT(OriginOddJobKind.FORGE_ENGRAVE),
    AUCTION_DISPATCH(OriginOddJobKind.AUCTION), AUCTION_AMEND(OriginOddJobKind.AUCTION), AUCTION_GROUP(OriginOddJobKind.AUCTION),
}
internal fun OriginOddJobVariant.isYardPreparation(): Boolean = this in setOf(
    OriginOddJobVariant.YARD_GROOM, OriginOddJobVariant.YARD_SADDLE, OriginOddJobVariant.YARD_LESSON)
internal data class OriginOddJobOffer(val id: UUID, val variant: OriginOddJobVariant, val seed: Int, val urgent: Boolean) {
    val kind: OriginOddJobKind get() = variant.kind
}

/** Edgar rotates all three crafts; specialists retain their own work. Each bag is stable until accepted. */
internal class OriginOddJobDispatch(private val kind: OriginOddJobKind, private val random: Random = Random.Default) {
    private var families = emptyList<OriginOddJobKind>()
    private val variants = mutableMapOf<OriginOddJobKind, List<OriginOddJobVariant>>()
    private val lastVariant = mutableMapOf<OriginOddJobKind, OriginOddJobVariant>()
    private var lastFamily: OriginOddJobKind? = null
    private var shown = emptyList<OriginOddJobOffer>()
    private var yardShortOrders = 3
    fun offers(phase: OriginWorkShiftPhase): List<OriginOddJobOffer> {
        if (shown.isNotEmpty()) return shown
        if (kind == OriginOddJobKind.MOUNTYARD) {
            val remaining = OriginOddJobVariant.entries.filter { it.kind == kind }.toMutableList()
            val ordered = buildList {
                while (remaining.isNotEmpty()) {
                    var ticket = random.nextInt(remaining.sumOf { if (it.isYardPreparation()) 1 else 6 })
                    val chosen = remaining.first { variant ->
                        ticket -= if (variant.isYardPreparation()) 1 else 6
                        ticket < 0
                    }
                    add(chosen)
                    remaining.remove(chosen)
                }
            }
            shown = ordered.map { OriginOddJobOffer(UUID.randomUUID(), it, random.nextInt(1, 10_000), false) }
            return shown
        }
        if (families.isEmpty()) {
            families = (if (kind == OriginOddJobKind.FORGE)
                listOf(kind, OriginOddJobKind.FORGE_HAUL, OriginOddJobKind.FORGE_ENGRAVE) else listOf(kind)).shuffled(random)
            if (families.size > 1 && families.first() == lastFamily) families = families.drop(1) + families.first()
        }
        fun bagFor(family: OriginOddJobKind): List<OriginOddJobVariant> {
            var bag = variants[family].orEmpty()
            if (bag.isEmpty()) {
                bag = OriginOddJobVariant.entries.filter { it.kind == family }.shuffled(random)
                if (bag.size > 1 && bag.first() == lastVariant[family]) bag = bag.drop(1) + bag.first()
                variants[family] = bag
            }
            return bag
        }
        val candidates = if (kind == OriginOddJobKind.FORGE) families.take(2).map { bagFor(it).first() }
            else bagFor(kind).take(2)
        shown = candidates.mapIndexed { index, variant ->
            OriginOddJobOffer(UUID.randomUUID(), variant, random.nextInt(1, 10_000),
                kind == OriginOddJobKind.AUCTION && phase == OriginWorkShiftPhase.RUSH && index == 1)
        }
        return shown
    }
    /** Prefer three short jobs between preparation chains; unavailable short jobs never block usable work. */
    fun nextAvailable(phase: OriginWorkShiftPhase, available: (OriginOddJobOffer) -> Boolean): OriginOddJobOffer? {
        if (kind == OriginOddJobKind.MOUNTYARD && yardShortOrders < 3)
            offers(phase).firstOrNull { !it.variant.isYardPreparation() && available(it) }?.let { return it }
        offers(phase).firstOrNull(available)?.let { return it }
        return null
    }
    fun accept(id: UUID): OriginOddJobOffer? = shown.firstOrNull { it.id == id }?.also {
        if (kind == OriginOddJobKind.MOUNTYARD) {
            yardShortOrders = if (it.variant.isYardPreparation()) 0 else (yardShortOrders + 1).coerceAtMost(3)
        } else {
            families = families.filterNot { family -> family == it.kind }
            variants[it.kind] = variants.getValue(it.kind).filterNot { variant -> variant == it.variant }
        }
        lastFamily = it.kind; lastVariant[it.kind] = it.variant; shown = emptyList()
    }
}

internal enum class OriginOddJobStage {
    YARD_HAY, YARD_FEED, YARD_EATING, YARD_BUCKET, YARD_WELL, YARD_WATER, YARD_DRINKING,
    YARD_BRUSH, YARD_GROOM, YARD_TACK, YARD_SADDLE, YARD_LEAD_RACK, YARD_LEAD, YARD_LEAD_OUT, YARD_TIE_FIRST, YARD_TIE_HORSE_FIRST, YARD_TIE_SECOND, YARD_TIE_HORSE_SECOND, YARD_UNTIE_FIRST, YARD_UNTIE_SECOND, YARD_DELIVER, YARD_HANDOFF, YARD_HANDED,
    YARD_LESSON_WALK,
    YARD_BRUSH_RETURN, YARD_PAD_RACK, YARD_PAD, YARD_GIRTH, YARD_BRIDLE_RACK, YARD_BRIDLE,
    YARD_STIRRUP_LEFT, YARD_STIRRUP_RIGHT, YARD_UNTACK_BRIDLE, YARD_BRIDLE_RETURN,
    YARD_UNTACK_SADDLE, YARD_SADDLE_RETURN, YARD_UNTACK_PAD, YARD_PAD_RETURN,
    YARD_LEAD_RETURN, YARD_BUCKET_RETURN, CLEAN_TOOL_RETURN,
    CLEAN_TOOL, CLEAN_CART, CLEAN_PUSH, CLEAN_SCOOP, CLEAN_LOAD, CLEAN_UNLOAD, CLEAN_RETURN, CLEAN_PARK,
    FORGE_BLANK, FORGE_INSPECT, FORGE_HEAT, FORGE_HEATING, FORGE_PLACE, FORGE_HAMMER, FORGE_QUENCH,
    HAUL_PICKUP, HAUL_LOAD, HAUL_ATTACH, HAUL_PUSH, HAUL_UNLOAD,
    ENGRAVE_BLANK, ENGRAVE_PLACE, ENGRAVE_CARVE, ENGRAVE_COLLECT,
    AUCTION_ITEM, AUCTION_INSPECT, AUCTION_VERIFY, AUCTION_DOCUMENT, AUCTION_PACK, AUCTION_LOAD, AUCTION_DELIVER, AUCTION_RECEIPT,
    RETURN, PAYOUT,
}
internal enum class OriginOddJobAction { HAY, FEED, WATER, BRUSH, GROOM, TACK, SADDLE, LEAD, ARRIVE, TIE, UNTIE, CARE_DONE, BLANK, HEAT, PLACE, HAMMER, QUENCH, PICKUP, LOAD, ATTACH_CART, CART_ARRIVE, UNLOAD, ENGRAVE, COLLECT, HANDOFF, RETURN, SCOOP, PARK_CART, GEAR }
internal enum class OriginAuctionPacking { PADDED_BOX, PAPER_WRAP, SCABBARD }
internal enum class OriginAuctionParcel(val packing: OriginAuctionPacking, val weight: Int) {
    VASE(OriginAuctionPacking.PADDED_BOX, 1), BOOK(OriginAuctionPacking.PAPER_WRAP, 2), BLADE(OriginAuctionPacking.SCABBARD, 3),
}
internal data class OriginAuctionLot(val parcel: OriginAuctionParcel, val serial: Int, val restored: Boolean)
internal data class OriginYardWork(val animalId: Int, val animalName: String, val brushStrokes: Int = 0,
    val routeIndex: Int = 0, val routeTotal: Int = 0, val tieMask: Int = 0, val pendingTieSide: Int? = null,
    val borrowed: Set<OriginYardGear> = emptySet(), val equipped: Set<OriginYardGear> = emptySet(),
    val girthTight: Boolean = false, val leftStirrup: Boolean = false, val rightStirrup: Boolean = false)
internal data class OriginYardCleaningWork(val collected: Int = 0, val total: Int = 3)
internal enum class OriginForgeProduct(val title: String) { HORSESHOE("Подкова"), HOOK("Крюк"), HINGE("Дверная петля"), BRACKET("Уголок") }
internal enum class OriginForgeMetal(val title: String, val itemModel: String) { IRON("Железо", "iron_ingot"), COPPER("Медь", "copper_ingot") }
internal enum class OriginForgeCargo(val title: String, val itemModel: String) {
    IRON("Железная руда", "raw_iron"), COPPER("Медная руда", "raw_copper"), COAL("Уголь", "coal")
}
internal data class OriginForgeHaul(val cargo: OriginForgeCargo)
internal data class OriginForgeEngraving(val word: String, val metal: OriginForgeMetal, val strokes: Int = 0) {
    val totalStrokes: Int get() = word.length * 2
}
internal data class OriginForgeWork(val target: List<Int>, val shape: List<Int>,
    val product: OriginForgeProduct = OriginForgeProduct.HORSESHOE, val metal: OriginForgeMetal = OriginForgeMetal.IRON) {
    val remainingStrikes: Int get() = target.indices.sumOf { (target[it] - shape[it]).coerceAtLeast(0) }
}
internal data class OriginAuctionWork(
    val lots: List<OriginAuctionLot>, val index: Int = 0, val approved: Boolean = false,
    val selected: Int? = null, val packed: Int = 0, val loaded: List<Int> = emptyList(),
) { val lot: OriginAuctionLot get() = lots[index] }
internal data class OriginOddJobSession(
    val id: UUID, val kind: OriginOddJobKind, val stage: OriginOddJobStage,
    val startedAt: Long, val expiresAt: Long, val stageStartedAt: Long,
    val offer: OriginOddJobOffer, val quality: Int = 100, val progress: Int = 0,
    val yard: OriginYardWork? = null, val forge: OriginForgeWork? = null, val auction: OriginAuctionWork? = null,
    val haul: OriginForgeHaul? = null, val engraving: OriginForgeEngraving? = null,
    val employer: OriginOddJobKind = offer.kind,
    val cleaning: OriginYardCleaningWork? = null,
    val completedActions: Int = 0,
) {
    val variant: OriginOddJobVariant get() = offer.variant
    val parcel: OriginAuctionParcel? get() = auction?.lot?.parcel
    fun finalQuality(now: Long): Int = (quality - if (offer.urgent && now - startedAt > 100_000) 10 else 0).coerceAtLeast(0)
}
internal enum class OriginOddJobRejection {
    NO_JOB, EXPIRED, WRONG_ACTION, TOO_EARLY,
    WRONG_ITEM, WRONG_PACKING, WRONG_ZONE,
    MANIFEST_MISMATCH, WRONG_RECEIPT, PAYOUT_PENDING,
}
internal sealed interface OriginOddJobStep {
    data class Changed(val job: OriginOddJobSession, val workUnitId: UUID? = null) : OriginOddJobStep
    data class Rejected(val reason: OriginOddJobRejection, val job: OriginOddJobSession?) : OriginOddJobStep
}
internal const val ODD_JOB_HEAT_READY_MILLIS = 4_000L
internal const val ODD_JOB_HAMMER_READY_MILLIS = 600L
internal const val ODD_JOB_GROOM_STROKES = 3
internal fun originOddJobHammerReady(job: OriginOddJobSession, now: Long): Boolean = job.stage in setOf(OriginOddJobStage.FORGE_HAMMER, OriginOddJobStage.ENGRAVE_CARVE) &&
    now - job.stageStartedAt >= ODD_JOB_HAMMER_READY_MILLIS
internal fun originOddJobHeatReady(job: OriginOddJobSession): Long = ODD_JOB_HEAT_READY_MILLIS +
    if (job.variant == OriginOddJobVariant.FORGE_NEW && job.offer.seed % 2 == 1) 1_500 else 0

internal fun originOddJobNewForgeShape(job: OriginOddJobSession): OriginJobProp = when {
    job.forge?.remainingStrikes == 0 -> OriginJobProp.FORGE_HORSESHOE
    job.progress == 0 -> OriginJobProp.FORGE_HEATED
    job.progress == 1 -> OriginJobProp.FORGE_SHAPED_1
    else -> OriginJobProp.FORGE_SHAPED_2
}

/** Progress is the current order's physical work, while the caption carries shift time and count. */
internal fun originOddJobProgress(job: OriginOddJobSession, now: Long): Float = when (job.stage) {
    OriginOddJobStage.FORGE_BLANK, OriginOddJobStage.FORGE_INSPECT, OriginOddJobStage.YARD_HAY, OriginOddJobStage.AUCTION_ITEM -> 0f
    OriginOddJobStage.FORGE_HEAT, OriginOddJobStage.AUCTION_INSPECT -> 0.15f
    OriginOddJobStage.FORGE_HEATING -> 0.2f + 0.2f * ((now - job.stageStartedAt).toFloat() / originOddJobHeatReady(job)).coerceIn(0f, 1f)
    OriginOddJobStage.FORGE_PLACE -> 0.4f
    OriginOddJobStage.FORGE_HAMMER -> 0.45f + 0.35f * (job.progress.toFloat() / (job.progress + (job.forge?.remainingStrikes ?: 0)).coerceAtLeast(1))
    OriginOddJobStage.FORGE_QUENCH -> 0.85f
    OriginOddJobStage.HAUL_PICKUP -> 0f
    OriginOddJobStage.HAUL_LOAD -> 0.15f
    OriginOddJobStage.HAUL_ATTACH -> 0.3f
    OriginOddJobStage.HAUL_PUSH -> 0.45f
    OriginOddJobStage.HAUL_UNLOAD -> 0.9f
    OriginOddJobStage.ENGRAVE_BLANK -> 0f
    OriginOddJobStage.ENGRAVE_PLACE -> 0.15f
    OriginOddJobStage.ENGRAVE_CARVE -> requireNotNull(job.engraving).let { 0.25f + 0.6f * it.strokes / it.totalStrokes }
    OriginOddJobStage.ENGRAVE_COLLECT -> 0.95f
    OriginOddJobStage.YARD_BUCKET, OriginOddJobStage.YARD_LEAD_RACK -> 0f
    OriginOddJobStage.YARD_LEAD -> 0.1f
    OriginOddJobStage.YARD_LEAD_OUT -> 0.15f
    OriginOddJobStage.YARD_TIE_FIRST -> 0.23f
    OriginOddJobStage.YARD_TIE_HORSE_FIRST -> 0.26f
    OriginOddJobStage.YARD_TIE_SECOND -> 0.29f
    OriginOddJobStage.YARD_TIE_HORSE_SECOND -> 0.32f
    OriginOddJobStage.YARD_UNTIE_FIRST -> 0.63f
    OriginOddJobStage.YARD_UNTIE_SECOND -> 0.65f
    OriginOddJobStage.YARD_WELL -> 0.25f
    OriginOddJobStage.YARD_BRUSH -> 0.35f
    OriginOddJobStage.YARD_TACK -> 0.46f
    OriginOddJobStage.YARD_EATING, OriginOddJobStage.YARD_DRINKING -> 0.8f
    OriginOddJobStage.YARD_DELIVER -> 0.68f
    OriginOddJobStage.YARD_HANDOFF, OriginOddJobStage.YARD_HANDED -> 0.72f
    OriginOddJobStage.YARD_LESSON_WALK -> requireNotNull(job.yard).let { 0.72f + 0.12f * it.routeIndex / it.routeTotal.coerceAtLeast(1) }
    OriginOddJobStage.CLEAN_TOOL -> 0f
    OriginOddJobStage.CLEAN_CART -> if (requireNotNull(job.cleaning).collected == job.cleaning.total) 0.6f else 0.05f
    OriginOddJobStage.CLEAN_PUSH -> if (requireNotNull(job.cleaning).collected == job.cleaning.total) 0.65f else 0.1f
    OriginOddJobStage.CLEAN_SCOOP, OriginOddJobStage.CLEAN_LOAD -> requireNotNull(job.cleaning).let { 0.15f + 0.45f * it.collected / it.total }
    OriginOddJobStage.CLEAN_UNLOAD -> 0.75f
    OriginOddJobStage.CLEAN_RETURN -> 0.85f
    OriginOddJobStage.CLEAN_PARK -> 0.95f
    OriginOddJobStage.YARD_FEED, OriginOddJobStage.YARD_WATER -> 0.5f
    OriginOddJobStage.YARD_SADDLE -> 0.49f
    OriginOddJobStage.YARD_BRUSH_RETURN -> 0.36f
    OriginOddJobStage.YARD_PAD_RACK, OriginOddJobStage.YARD_PAD -> 0.42f
    OriginOddJobStage.YARD_GIRTH -> 0.52f
    OriginOddJobStage.YARD_BRIDLE_RACK, OriginOddJobStage.YARD_BRIDLE -> 0.55f
    OriginOddJobStage.YARD_STIRRUP_LEFT, OriginOddJobStage.YARD_STIRRUP_RIGHT -> 0.60f
    OriginOddJobStage.YARD_UNTACK_BRIDLE, OriginOddJobStage.YARD_BRIDLE_RETURN -> 0.86f
    OriginOddJobStage.YARD_UNTACK_SADDLE, OriginOddJobStage.YARD_SADDLE_RETURN -> 0.90f
    OriginOddJobStage.YARD_UNTACK_PAD, OriginOddJobStage.YARD_PAD_RETURN -> 0.94f
    OriginOddJobStage.YARD_LEAD_RETURN, OriginOddJobStage.YARD_BUCKET_RETURN, OriginOddJobStage.CLEAN_TOOL_RETURN -> 0.98f
    OriginOddJobStage.YARD_GROOM -> 0.35f + 0.01f *
        requireNotNull(job.yard).brushStrokes / ODD_JOB_GROOM_STROKES
    OriginOddJobStage.AUCTION_VERIFY, OriginOddJobStage.AUCTION_DOCUMENT -> 0.3f
    OriginOddJobStage.AUCTION_PACK -> 0.45f
    OriginOddJobStage.AUCTION_LOAD -> 0.6f
    OriginOddJobStage.AUCTION_DELIVER -> 0.75f
    OriginOddJobStage.AUCTION_RECEIPT -> 0.9f
    OriginOddJobStage.RETURN, OriginOddJobStage.PAYOUT -> 1f
}.coerceIn(0f, 1f)
internal fun originAuctionCandidates(job: OriginOddJobSession): List<OriginAuctionLot> {
    val work = requireNotNull(job.auction); val lot = work.lot
    val candidates = listOf(lot, lot.copy(serial = lot.serial + 7), lot.copy(restored = !lot.restored))
    val offset = (job.offer.seed + work.index) % 3
    return List(3) { candidates[(it + offset) % 3] }
}
internal fun originAuctionManifestSerial(job: OriginOddJobSession): Int = requireNotNull(job.auction).let {
    it.lot.serial - if (job.variant == OriginOddJobVariant.AUCTION_AMEND && !it.approved) 1 else 0
}

/** Explicit craft rules; transient progress never owns a durable payment claim. */
internal class OriginOddJobsProgress(private val lifetimeMillis: Long) {
    private val jobs = linkedMapOf<UUID, OriginOddJobSession>()
    init { require(lifetimeMillis > ODD_JOB_HEAT_READY_MILLIS + 1_500) }
    fun current(playerId: UUID): OriginOddJobSession? = jobs[playerId]
    fun entries(): List<Pair<UUID, OriginOddJobSession>> = jobs.toList()
    fun begin(playerId: UUID, offer: OriginOddJobOffer, now: Long, hardStopAt: Long = Long.MAX_VALUE,
        yardTarget: OriginYardWork? = null, employer: OriginOddJobKind = offer.kind): OriginOddJobSession? {
        if (playerId in jobs) return null
        if (offer.variant == OriginOddJobVariant.YARD_LESSON && (yardTarget?.routeTotal ?: 0) <= 0) return null
        val n = offer.seed
        val target = listOf(2 + n % 2, 2 + n / 2 % 2, 2 + n / 3 % 2)
        val shape = when (offer.variant) {
            OriginOddJobVariant.FORGE_REPAIR -> target.mapIndexed { i, v -> if (i == n % 3) v - 2 else v }
            OriginOddJobVariant.FORGE_PAIR -> target.mapIndexed { i, v -> v - if (i == n % 3) 0 else 1 }
            OriginOddJobVariant.FORGE_NEW -> target.mapIndexed { i, v -> v - if (i == n % 3) 2 else 1 }
            OriginOddJobVariant.FORGE_HOOK, OriginOddJobVariant.FORGE_HINGE, OriginOddJobVariant.FORGE_BRACKET -> target.map { it - 1 }
            else -> listOf(0, 0, 0)
        }
        val parcel = OriginAuctionParcel.entries[n % 3]
        val lots = if (offer.variant == OriginOddJobVariant.AUCTION_GROUP)
            listOf(OriginAuctionLot(OriginAuctionParcel.VASE, n + 100, false), OriginAuctionLot(OriginAuctionParcel.BLADE, n + 200, n % 2 == 0))
        else listOf(OriginAuctionLot(parcel, n + 100, n % 2 == 0))
        val stage = when (offer.variant) {
            OriginOddJobVariant.YARD_WATER -> OriginOddJobStage.YARD_BUCKET
            OriginOddJobVariant.YARD_RATIONS -> OriginOddJobStage.YARD_HAY
            OriginOddJobVariant.YARD_GROOM, OriginOddJobVariant.YARD_SADDLE, OriginOddJobVariant.YARD_LESSON -> OriginOddJobStage.YARD_LEAD_RACK
            OriginOddJobVariant.YARD_CLEANUP -> OriginOddJobStage.CLEAN_TOOL
            OriginOddJobVariant.FORGE_NEW, OriginOddJobVariant.FORGE_HOOK, OriginOddJobVariant.FORGE_HINGE,
            OriginOddJobVariant.FORGE_BRACKET, OriginOddJobVariant.FORGE_REPAIR,
            OriginOddJobVariant.FORGE_PAIR -> OriginOddJobStage.FORGE_BLANK
            OriginOddJobVariant.HAUL_IRON, OriginOddJobVariant.HAUL_COPPER, OriginOddJobVariant.HAUL_COAL -> OriginOddJobStage.HAUL_PICKUP
            OriginOddJobVariant.ENGRAVE_RUS, OriginOddJobVariant.ENGRAVE_HOME, OriginOddJobVariant.ENGRAVE_CAT -> OriginOddJobStage.ENGRAVE_BLANK
            else -> OriginOddJobStage.AUCTION_ITEM
        }
        val orderLifetime = if (offer.variant.isYardPreparation()) Math.multiplyExact(lifetimeMillis, 2L) else lifetimeMillis
        return OriginOddJobSession(UUID.randomUUID(), offer.kind, stage, now, minOf(hardStopAt, Math.addExact(now, orderLifetime)), now, offer,
            yard = if (offer.kind == OriginOddJobKind.MOUNTYARD) yardTarget ?: OriginYardWork(422, "Буран") else null,
            forge = if (offer.kind == OriginOddJobKind.FORGE) OriginForgeWork(target, shape, when (offer.variant) {
                OriginOddJobVariant.FORGE_HOOK -> OriginForgeProduct.HOOK
                OriginOddJobVariant.FORGE_HINGE -> OriginForgeProduct.HINGE
                OriginOddJobVariant.FORGE_BRACKET -> OriginForgeProduct.BRACKET
                else -> OriginForgeProduct.HORSESHOE
            }, if (offer.variant == OriginOddJobVariant.FORGE_HINGE) OriginForgeMetal.COPPER else OriginForgeMetal.IRON) else null,
            auction = if (offer.kind == OriginOddJobKind.AUCTION) OriginAuctionWork(lots) else null,
            haul = if (offer.kind == OriginOddJobKind.FORGE_HAUL) OriginForgeHaul(when (offer.variant) {
                OriginOddJobVariant.HAUL_COPPER -> OriginForgeCargo.COPPER
                OriginOddJobVariant.HAUL_COAL -> OriginForgeCargo.COAL
                else -> OriginForgeCargo.IRON
            }) else null,
            engraving = if (offer.kind == OriginOddJobKind.FORGE_ENGRAVE) OriginForgeEngraving(when (offer.variant) {
                OriginOddJobVariant.ENGRAVE_HOME -> "ДОМ"
                OriginOddJobVariant.ENGRAVE_CAT -> "КОТ"
                else -> "РУС"
            }, if (n % 2 == 0) OriginForgeMetal.COPPER else OriginForgeMetal.IRON) else null,
            employer = employer,
            cleaning = if (offer.variant == OriginOddJobVariant.YARD_CLEANUP) OriginYardCleaningWork() else null,
        ).also { jobs[playerId] = it }
    }

    fun act(playerId: UUID, action: OriginOddJobAction, now: Long, zone: Int = 1): OriginOddJobStep = advance(playerId, now, action) { job ->
        fun next(stage: OriginOddJobStage, updated: OriginOddJobSession = job) = changed(updated.copy(stage = stage, stageStartedAt = now, progress = 0))
        fun wrong() = rejected(OriginOddJobRejection.WRONG_ACTION, job)
        val yard = job.yard; val forge = job.forge
        fun borrow(gear: OriginYardGear, stage: OriginOddJobStage) = next(stage,
            job.copy(yard = requireNotNull(yard).copy(borrowed = yard.borrowed + gear)))
        fun giveBack(gear: OriginYardGear, stage: OriginOddJobStage): OriginOddJobStep =
            if (action != OriginOddJobAction.GEAR || gear !in requireNotNull(yard).borrowed || gear in yard.equipped) wrong()
            else next(stage, job.copy(yard = yard.copy(borrowed = yard.borrowed - gear)))
        fun preparationDone(work: OriginYardWork = requireNotNull(yard)) = next(OriginOddJobStage.RETURN,
            job.copy(yard = work.copy(borrowed = emptySet(), equipped = emptySet())))
        when (job.stage) {
            OriginOddJobStage.YARD_HAY -> if (action == OriginOddJobAction.HAY) borrow(OriginYardGear.HAY, OriginOddJobStage.YARD_FEED) else wrong()
            OriginOddJobStage.YARD_FEED -> if (action == OriginOddJobAction.FEED) next(OriginOddJobStage.RETURN, job.copy(yard = requireNotNull(yard).copy(borrowed = yard.borrowed - OriginYardGear.HAY))) else wrong()
            OriginOddJobStage.YARD_BUCKET -> if (action == OriginOddJobAction.WATER) borrow(OriginYardGear.BUCKET, OriginOddJobStage.YARD_WELL) else wrong()
            OriginOddJobStage.YARD_EATING -> if (action == OriginOddJobAction.CARE_DONE) next(OriginOddJobStage.RETURN) else wrong()
            OriginOddJobStage.YARD_DRINKING -> if (action == OriginOddJobAction.CARE_DONE) next(OriginOddJobStage.YARD_BUCKET_RETURN) else wrong()
            OriginOddJobStage.YARD_HANDED -> if (action == OriginOddJobAction.CARE_DONE) preparationDone() else wrong()
            OriginOddJobStage.YARD_WELL -> if (action == OriginOddJobAction.WATER) next(OriginOddJobStage.YARD_WATER) else wrong()
            OriginOddJobStage.YARD_WATER -> if (action == OriginOddJobAction.WATER) next(OriginOddJobStage.YARD_DRINKING) else wrong()
            OriginOddJobStage.YARD_LEAD_RACK -> if (action == OriginOddJobAction.TACK) borrow(OriginYardGear.LEAD, OriginOddJobStage.YARD_LEAD) else wrong()
            OriginOddJobStage.YARD_LEAD -> if (action == OriginOddJobAction.LEAD) next(OriginOddJobStage.YARD_LEAD_OUT) else wrong()
            OriginOddJobStage.YARD_LEAD_OUT -> if (action == OriginOddJobAction.ARRIVE) next(OriginOddJobStage.YARD_BRUSH) else wrong()
            OriginOddJobStage.YARD_DELIVER -> if (action == OriginOddJobAction.ARRIVE) next(OriginOddJobStage.YARD_HANDOFF) else wrong()
            OriginOddJobStage.YARD_HANDOFF -> if (action == OriginOddJobAction.HANDOFF)
                next(if (job.variant == OriginOddJobVariant.YARD_LESSON) OriginOddJobStage.YARD_LESSON_WALK else OriginOddJobStage.YARD_HANDED) else wrong()
            OriginOddJobStage.YARD_LESSON_WALK -> if (action != OriginOddJobAction.ARRIVE) wrong() else {
                val work = requireNotNull(yard).copy(routeIndex = yard.routeIndex + 1)
                if (work.routeIndex == work.routeTotal) preparationDone(work) else next(job.stage, job.copy(yard = work))
            }
            OriginOddJobStage.CLEAN_TOOL -> if (action == OriginOddJobAction.PICKUP) borrow(OriginYardGear.SHOVEL, OriginOddJobStage.CLEAN_CART) else wrong()
            OriginOddJobStage.CLEAN_CART -> if (action == OriginOddJobAction.ATTACH_CART) next(OriginOddJobStage.CLEAN_PUSH) else wrong()
            OriginOddJobStage.CLEAN_PUSH -> when {
                requireNotNull(job.cleaning).collected < job.cleaning.total && action == OriginOddJobAction.PARK_CART -> next(OriginOddJobStage.CLEAN_SCOOP)
                job.cleaning.collected == job.cleaning.total && action == OriginOddJobAction.CART_ARRIVE -> next(OriginOddJobStage.CLEAN_UNLOAD)
                else -> wrong()
            }
            OriginOddJobStage.CLEAN_SCOOP -> if (action == OriginOddJobAction.SCOOP) next(OriginOddJobStage.CLEAN_LOAD) else wrong()
            OriginOddJobStage.CLEAN_LOAD -> if (action != OriginOddJobAction.LOAD) wrong() else {
                val work = requireNotNull(job.cleaning).copy(collected = job.cleaning.collected + 1)
                next(if (work.collected == work.total) OriginOddJobStage.CLEAN_CART else OriginOddJobStage.CLEAN_SCOOP, job.copy(cleaning = work))
            }
            OriginOddJobStage.CLEAN_UNLOAD -> if (action == OriginOddJobAction.UNLOAD) next(OriginOddJobStage.CLEAN_RETURN) else wrong()
            OriginOddJobStage.CLEAN_RETURN -> if (action == OriginOddJobAction.CART_ARRIVE) next(OriginOddJobStage.CLEAN_PARK) else wrong()
            OriginOddJobStage.CLEAN_PARK -> if (action == OriginOddJobAction.PARK_CART) next(OriginOddJobStage.CLEAN_TOOL_RETURN) else wrong()
            OriginOddJobStage.YARD_BRUSH -> if (action == OriginOddJobAction.BRUSH) borrow(OriginYardGear.BRUSH, OriginOddJobStage.YARD_GROOM) else wrong()
            OriginOddJobStage.YARD_GROOM -> if (action != OriginOddJobAction.GROOM) wrong() else {
                val work = requireNotNull(yard).copy(brushStrokes = yard.brushStrokes + 1)
                if (work.brushStrokes == ODD_JOB_GROOM_STROKES) next(OriginOddJobStage.YARD_BRUSH_RETURN, job.copy(yard = work))
                else changed(job.copy(yard = work))
            }
            OriginOddJobStage.YARD_BRUSH_RETURN -> giveBack(OriginYardGear.BRUSH, OriginOddJobStage.YARD_TACK)
            OriginOddJobStage.YARD_TACK -> if (action == OriginOddJobAction.TACK) borrow(OriginYardGear.SADDLE, OriginOddJobStage.YARD_SADDLE) else wrong()
            OriginOddJobStage.YARD_SADDLE -> if (action == OriginOddJobAction.SADDLE && OriginYardGear.SADDLE in requireNotNull(yard).borrowed &&
                OriginYardGear.SADDLE !in yard.equipped)
                next(OriginOddJobStage.YARD_DELIVER, job.copy(yard = yard.copy(equipped = yard.equipped + OriginYardGear.SADDLE))) else wrong()
            OriginOddJobStage.YARD_BUCKET_RETURN -> giveBack(OriginYardGear.BUCKET, OriginOddJobStage.RETURN)
            OriginOddJobStage.CLEAN_TOOL_RETURN -> giveBack(OriginYardGear.SHOVEL, OriginOddJobStage.RETURN)
            OriginOddJobStage.FORGE_BLANK, OriginOddJobStage.FORGE_INSPECT ->
                if (action == OriginOddJobAction.BLANK) next(OriginOddJobStage.FORGE_HEAT) else wrong()
            OriginOddJobStage.FORGE_HEAT -> if (action == OriginOddJobAction.HEAT) next(OriginOddJobStage.FORGE_HEATING) else wrong()
            OriginOddJobStage.FORGE_HEATING -> when {
                action != OriginOddJobAction.HEAT -> wrong()
                now - job.stageStartedAt < originOddJobHeatReady(job) -> rejected(OriginOddJobRejection.TOO_EARLY, job)
                else -> next(OriginOddJobStage.FORGE_PLACE)
            }
            OriginOddJobStage.FORGE_PLACE -> if (action == OriginOddJobAction.PLACE) next(OriginOddJobStage.FORGE_HAMMER) else wrong()
            OriginOddJobStage.FORGE_HAMMER -> when {
                action != OriginOddJobAction.HAMMER -> wrong()
                !originOddJobHammerReady(job, now) -> rejected(OriginOddJobRejection.TOO_EARLY, job)
                else -> {
                    val work = requireNotNull(forge)
                    val index = work.shape.indices.first { work.shape[it] < work.target[it] }
                    val updated = job.copy(stageStartedAt = now, progress = job.progress + 1,
                        forge = work.copy(shape = work.shape.mapIndexed { i, v -> v + if (i == index) 1 else 0 }))
                    if (updated.forge!!.remainingStrikes == 0) next(OriginOddJobStage.FORGE_QUENCH, updated)
                    else changed(updated)
                }
            }
            OriginOddJobStage.FORGE_QUENCH -> if (action == OriginOddJobAction.QUENCH) next(OriginOddJobStage.RETURN) else wrong()
            OriginOddJobStage.HAUL_PICKUP -> if (action == OriginOddJobAction.PICKUP) next(OriginOddJobStage.HAUL_LOAD) else wrong()
            OriginOddJobStage.HAUL_LOAD -> if (action == OriginOddJobAction.LOAD) next(OriginOddJobStage.HAUL_ATTACH) else wrong()
            OriginOddJobStage.HAUL_ATTACH -> if (action == OriginOddJobAction.ATTACH_CART) next(OriginOddJobStage.HAUL_PUSH) else wrong()
            OriginOddJobStage.HAUL_PUSH -> if (action == OriginOddJobAction.CART_ARRIVE) next(OriginOddJobStage.HAUL_UNLOAD) else wrong()
            OriginOddJobStage.HAUL_UNLOAD -> if (action == OriginOddJobAction.UNLOAD) next(OriginOddJobStage.PAYOUT) else wrong()
            OriginOddJobStage.ENGRAVE_BLANK -> if (action == OriginOddJobAction.BLANK) next(OriginOddJobStage.ENGRAVE_PLACE) else wrong()
            OriginOddJobStage.ENGRAVE_PLACE -> if (action == OriginOddJobAction.PLACE) next(OriginOddJobStage.ENGRAVE_CARVE) else wrong()
            OriginOddJobStage.ENGRAVE_CARVE -> when {
                action != OriginOddJobAction.ENGRAVE -> wrong()
                !originOddJobHammerReady(job, now) -> rejected(OriginOddJobRejection.TOO_EARLY, job)
                else -> {
                    val work = requireNotNull(job.engraving).let { it.copy(strokes = it.strokes + 1) }
                    val updated = job.copy(engraving = work, stageStartedAt = now)
                    if (work.strokes == work.totalStrokes) next(OriginOddJobStage.ENGRAVE_COLLECT, updated) else changed(updated)
                }
            }
            OriginOddJobStage.ENGRAVE_COLLECT -> if (action == OriginOddJobAction.COLLECT) next(OriginOddJobStage.RETURN) else wrong()
            OriginOddJobStage.AUCTION_VERIFY -> if (action == OriginOddJobAction.HANDOFF)
                next(OriginOddJobStage.AUCTION_DOCUMENT, job.copy(auction = requireNotNull(job.auction).copy(approved = true))) else wrong()
            OriginOddJobStage.AUCTION_DOCUMENT -> if (action == OriginOddJobAction.RETURN) next(OriginOddJobStage.AUCTION_INSPECT) else wrong()
            OriginOddJobStage.AUCTION_DELIVER -> if (action == OriginOddJobAction.HANDOFF) next(OriginOddJobStage.AUCTION_RECEIPT) else wrong()
            OriginOddJobStage.RETURN -> if (action == OriginOddJobAction.RETURN && (job.kind != OriginOddJobKind.MOUNTYARD || requireNotNull(yard).borrowed.isEmpty())) next(OriginOddJobStage.PAYOUT) else wrong()
            else -> wrong()
        }
    }

    /** Native choices are validated again by the controller against the exact station/NPC and order UUID. */
    fun choose(playerId: UUID, choice: Int, now: Long): OriginOddJobStep = advance(playerId, now) { job ->
        fun next(stage: OriginOddJobStage, updated: OriginOddJobSession = job) = changed(updated.copy(stage = stage, stageStartedAt = now))
        when (job.stage) {
            OriginOddJobStage.AUCTION_ITEM -> {
                val work = requireNotNull(job.auction); val lot = originAuctionCandidates(job).getOrNull(choice)
                if (lot != work.lot) mistake(OriginOddJobRejection.WRONG_ITEM, job)
                else next(OriginOddJobStage.AUCTION_INSPECT, job.copy(auction = work.copy(selected = choice)))
            }
            OriginOddJobStage.AUCTION_INSPECT -> {
                val work = requireNotNull(job.auction); val mismatch = originAuctionManifestSerial(job) != work.lot.serial
                when {
                    choice == 1 && mismatch -> next(OriginOddJobStage.AUCTION_VERIFY)
                    choice == 0 && !mismatch -> next(OriginOddJobStage.AUCTION_PACK)
                    else -> mistake(OriginOddJobRejection.MANIFEST_MISMATCH, job)
                }
            }
            OriginOddJobStage.AUCTION_PACK -> {
                val work = requireNotNull(job.auction)
                if (OriginAuctionPacking.entries.getOrNull(choice) != work.lot.parcel.packing) mistake(OriginOddJobRejection.WRONG_PACKING, job)
                else if (work.index + 1 < work.lots.size) next(OriginOddJobStage.AUCTION_ITEM,
                    job.copy(auction = work.copy(index = work.index + 1, selected = null, packed = work.packed + 1)))
                else next(if (work.lots.size == 1) OriginOddJobStage.AUCTION_DELIVER else OriginOddJobStage.AUCTION_LOAD,
                    job.copy(auction = work.copy(packed = work.packed + 1)))
            }
            OriginOddJobStage.AUCTION_LOAD -> {
                val work = requireNotNull(job.auction)
                val remaining = work.lots.indices.filter { it !in work.loaded }
                if (choice !in remaining || work.lots[choice].parcel.weight != remaining.maxOf { work.lots[it].parcel.weight })
                    mistake(OriginOddJobRejection.WRONG_PACKING, job)
                else {
                    val loaded = work.loaded + choice
                    next(if (loaded.size == work.lots.size) OriginOddJobStage.AUCTION_DELIVER else job.stage, job.copy(auction = work.copy(loaded = loaded)))
                }
            }
            OriginOddJobStage.AUCTION_RECEIPT -> if (choice == job.offer.seed % 3) next(OriginOddJobStage.RETURN)
                else mistake(OriginOddJobRejection.WRONG_RECEIPT, job)
            else -> rejected(OriginOddJobRejection.WRONG_ACTION, job)
        }
    }

    fun finish(playerId: UUID, requestId: UUID): OriginOddJobSession? = jobs[playerId]
        ?.takeIf { it.id == requestId && it.stage == OriginOddJobStage.PAYOUT }?.let { jobs.remove(playerId) }
    fun cancel(playerId: UUID): OriginOddJobSession? = jobs.remove(playerId)
    private fun advance(playerId: UUID, now: Long, workAction: OriginOddJobAction? = null,
        action: (OriginOddJobSession) -> OriginOddJobStep): OriginOddJobStep {
        val job = jobs[playerId] ?: return rejected(OriginOddJobRejection.NO_JOB, null)
        if (job.stage == OriginOddJobStage.PAYOUT) return rejected(OriginOddJobRejection.PAYOUT_PENDING, job)
        if (now >= job.expiresAt) return rejected(OriginOddJobRejection.EXPIRED, job)
        val step = action(job).let { result ->
            if (result is OriginOddJobStep.Changed && job.kind == OriginOddJobKind.MOUNTYARD && workAction != null &&
                workAction !in setOf(OriginOddJobAction.ARRIVE, OriginOddJobAction.CART_ARRIVE, OriginOddJobAction.CARE_DONE, OriginOddJobAction.RETURN) &&
                (workAction != OriginOddJobAction.HANDOFF || job.variant == OriginOddJobVariant.YARD_LESSON)) {
                val ordinal = Math.addExact(job.completedActions, 1)
                result.copy(job = result.job.copy(completedActions = ordinal),
                    workUnitId = UUID.nameUUIDFromBytes("${job.id}:${job.stage.name}:$ordinal".toByteArray(Charsets.UTF_8)))
            } else result
        }
        return step.also {
            when (step) { is OriginOddJobStep.Changed -> step.job; is OriginOddJobStep.Rejected -> step.job }
                ?.let { jobs[playerId] = it }
        }
    }
    private fun changed(job: OriginOddJobSession) = OriginOddJobStep.Changed(job)
    private fun rejected(reason: OriginOddJobRejection, job: OriginOddJobSession?) = OriginOddJobStep.Rejected(reason, job)
    private fun mistake(reason: OriginOddJobRejection, job: OriginOddJobSession) = rejected(reason, job.copy(quality = (job.quality - 5).coerceAtLeast(0)))
}
