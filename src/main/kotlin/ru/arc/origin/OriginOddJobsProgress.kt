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
    FORGE_NEW(OriginOddJobKind.FORGE), FORGE_REPAIR(OriginOddJobKind.FORGE), FORGE_PAIR(OriginOddJobKind.FORGE),
    FORGE_HOOK(OriginOddJobKind.FORGE), FORGE_HINGE(OriginOddJobKind.FORGE), FORGE_BRACKET(OriginOddJobKind.FORGE),
    HAUL_IRON(OriginOddJobKind.FORGE_HAUL), HAUL_COPPER(OriginOddJobKind.FORGE_HAUL), HAUL_COAL(OriginOddJobKind.FORGE_HAUL),
    ENGRAVE_RUS(OriginOddJobKind.FORGE_ENGRAVE), ENGRAVE_HOME(OriginOddJobKind.FORGE_ENGRAVE), ENGRAVE_CAT(OriginOddJobKind.FORGE_ENGRAVE),
    AUCTION_DISPATCH(OriginOddJobKind.AUCTION), AUCTION_AMEND(OriginOddJobKind.AUCTION), AUCTION_GROUP(OriginOddJobKind.AUCTION),
}
internal fun OriginOddJobVariant.isYardPreparation(): Boolean = this == OriginOddJobVariant.YARD_GROOM || this == OriginOddJobVariant.YARD_SADDLE
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
    private var first = true
    fun offers(phase: OriginWorkShiftPhase): List<OriginOddJobOffer> {
        if (shown.isNotEmpty()) return shown
        if (families.isEmpty()) {
            families = (if (kind == OriginOddJobKind.FORGE)
                listOf(kind, OriginOddJobKind.FORGE_HAUL, OriginOddJobKind.FORGE_ENGRAVE) else listOf(kind)).shuffled(random)
            if (families.size > 1 && families.first() == lastFamily) families = families.drop(1) + families.first()
            if (first && kind == OriginOddJobKind.FORGE) families = listOf(kind) + families.filterNot { it == kind }
        }
        fun bagFor(family: OriginOddJobKind): List<OriginOddJobVariant> {
            var bag = variants[family].orEmpty()
            if (bag.isEmpty()) {
                bag = OriginOddJobVariant.entries.filter { it.kind == family }.shuffled(random)
                if (bag.size > 1 && bag.first() == lastVariant[family]) bag = bag.drop(1) + bag.first()
                if (first && family == OriginOddJobKind.FORGE)
                    bag = listOf(OriginOddJobVariant.FORGE_NEW) + bag.filterNot { it == OriginOddJobVariant.FORGE_NEW }
                variants[family] = bag
            }
            return bag
        }
        val candidates = if (kind == OriginOddJobKind.FORGE) families.take(2).map { bagFor(it).first() }
            else if (kind == OriginOddJobKind.MOUNTYARD) bagFor(kind) else bagFor(kind).take(2)
        shown = candidates.mapIndexed { index, variant ->
            OriginOddJobOffer(UUID.randomUUID(), variant, random.nextInt(1, 10_000),
                kind == OriginOddJobKind.AUCTION && phase == OriginWorkShiftPhase.RUSH && index == 1)
        }
        return shown
    }
    /** A rider's background trip must not idle a worker while another stall horse can drink. */
    fun nextAvailable(phase: OriginWorkShiftPhase, available: (OriginOddJobOffer) -> Boolean): OriginOddJobOffer? {
        offers(phase).firstOrNull(available)?.let { return it }
        if (kind != OriginOddJobKind.MOUNTYARD || shown.any { it.variant == OriginOddJobVariant.YARD_WATER }) return null
        val water = OriginOddJobOffer(UUID.randomUUID(), OriginOddJobVariant.YARD_WATER, random.nextInt(1, 10_000), false)
        if (!available(water)) return null
        shown = shown + water
        return water
    }
    fun accept(id: UUID): OriginOddJobOffer? = shown.firstOrNull { it.id == id }?.also {
        families = families.filterNot { family -> family == it.kind }
        variants[it.kind] = variants.getValue(it.kind).filterNot { variant -> variant == it.variant }
        lastFamily = it.kind; lastVariant[it.kind] = it.variant; shown = emptyList(); first = false
    }
}

internal enum class OriginOddJobStage {
    YARD_HAY, YARD_FEED, YARD_EATING, YARD_BUCKET, YARD_WELL, YARD_WATER, YARD_DRINKING,
    YARD_BRUSH, YARD_GROOM, YARD_TACK, YARD_SADDLE, YARD_LEAD_RACK, YARD_LEAD, YARD_LEAD_OUT, YARD_TIE_FIRST, YARD_TIE_SECOND, YARD_UNTIE_FIRST, YARD_UNTIE_SECOND, YARD_DELIVER, YARD_HANDOFF, YARD_HANDED,
    FORGE_BLANK, FORGE_INSPECT, FORGE_HEAT, FORGE_HEATING, FORGE_PLACE, FORGE_HAMMER, FORGE_QUENCH,
    HAUL_PICKUP, HAUL_LOAD, HAUL_PUSH, HAUL_UNLOAD, HAUL_DELIVER, HAUL_RETURN_CART,
    ENGRAVE_BLANK, ENGRAVE_PLACE, ENGRAVE_CARVE, ENGRAVE_COLLECT,
    AUCTION_ITEM, AUCTION_INSPECT, AUCTION_VERIFY, AUCTION_DOCUMENT, AUCTION_PACK, AUCTION_LOAD, AUCTION_DELIVER, AUCTION_RECEIPT,
    RETURN, PAYOUT,
}
internal enum class OriginOddJobAction { HAY, FEED, WATER, BRUSH, GROOM, TACK, SADDLE, LEAD, ARRIVE, TIE, UNTIE, CARE_DONE, BLANK, HEAT, PLACE, HAMMER, QUENCH, PICKUP, LOAD, CART_ARRIVE, DELIVER, ENGRAVE, COLLECT, HANDOFF, RETURN }
internal enum class OriginAuctionPacking { PADDED_BOX, PAPER_WRAP, SCABBARD }
internal enum class OriginAuctionParcel(val packing: OriginAuctionPacking, val weight: Int) {
    VASE(OriginAuctionPacking.PADDED_BOX, 1), BOOK(OriginAuctionPacking.PAPER_WRAP, 2), BLADE(OriginAuctionPacking.SCABBARD, 3),
}
internal data class OriginAuctionLot(val parcel: OriginAuctionParcel, val serial: Int, val restored: Boolean)
internal data class OriginYardWork(val animalId: Int, val animalName: String, val brushStrokes: Int = 0)
internal enum class OriginForgeProduct(val title: String) { HORSESHOE("Подкова"), HOOK("Крюк"), HINGE("Дверная петля"), BRACKET("Уголок") }
internal enum class OriginForgeMetal(val title: String, val itemModel: String) { IRON("Железо", "iron_ingot"), COPPER("Медь", "copper_ingot") }
internal enum class OriginForgeCargo(val title: String, val itemModel: String) {
    IRON("Железная руда", "raw_iron"), COPPER("Медная руда", "raw_copper"), COAL("Уголь", "coal")
}
internal data class OriginForgeHaul(val cargo: OriginForgeCargo, val delivered: Int = 0, val loads: Int = 3, val loaded: Int = 0)
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
    data class Changed(val job: OriginOddJobSession) : OriginOddJobStep
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
    OriginOddJobStage.HAUL_PICKUP -> requireNotNull(job.haul).let { 0.25f * it.loaded / it.loads }
    OriginOddJobStage.HAUL_LOAD -> requireNotNull(job.haul).let { 0.25f * (it.loaded + 0.5f) / it.loads }
    OriginOddJobStage.HAUL_PUSH -> 0.3f
    OriginOddJobStage.HAUL_UNLOAD -> requireNotNull(job.haul).let { 0.55f + 0.3f * it.delivered / it.loads }
    OriginOddJobStage.HAUL_DELIVER -> requireNotNull(job.haul).let { 0.55f + 0.3f * (it.delivered + 0.5f) / it.loads }
    OriginOddJobStage.HAUL_RETURN_CART -> 0.9f
    OriginOddJobStage.ENGRAVE_BLANK -> 0f
    OriginOddJobStage.ENGRAVE_PLACE -> 0.15f
    OriginOddJobStage.ENGRAVE_CARVE -> requireNotNull(job.engraving).let { 0.25f + 0.6f * it.strokes / it.totalStrokes }
    OriginOddJobStage.ENGRAVE_COLLECT -> 0.95f
    OriginOddJobStage.YARD_BUCKET, OriginOddJobStage.YARD_LEAD_RACK -> 0f
    OriginOddJobStage.YARD_LEAD -> 0.1f
    OriginOddJobStage.YARD_LEAD_OUT -> 0.15f
    OriginOddJobStage.YARD_TIE_FIRST -> 0.23f
    OriginOddJobStage.YARD_TIE_SECOND -> 0.28f
    OriginOddJobStage.YARD_UNTIE_FIRST -> 0.77f
    OriginOddJobStage.YARD_UNTIE_SECOND -> 0.81f
    OriginOddJobStage.YARD_WELL -> 0.25f
    OriginOddJobStage.YARD_BRUSH -> 0.35f
    OriginOddJobStage.YARD_TACK -> 0.65f
    OriginOddJobStage.YARD_EATING, OriginOddJobStage.YARD_DRINKING -> 0.8f
    OriginOddJobStage.YARD_DELIVER -> 0.85f
    OriginOddJobStage.YARD_HANDOFF, OriginOddJobStage.YARD_HANDED -> 0.95f
    OriginOddJobStage.YARD_FEED, OriginOddJobStage.YARD_WATER -> 0.5f
    OriginOddJobStage.YARD_SADDLE -> 0.75f
    OriginOddJobStage.YARD_GROOM -> 0.4f + 0.2f *
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
    fun begin(playerId: UUID, offer: OriginOddJobOffer, now: Long, hardStopAt: Long = now + lifetimeMillis,
        yardTarget: OriginYardWork? = null, employer: OriginOddJobKind = offer.kind): OriginOddJobSession? {
        if (playerId in jobs) return null
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
            OriginOddJobVariant.YARD_GROOM, OriginOddJobVariant.YARD_SADDLE -> OriginOddJobStage.YARD_LEAD_RACK
            OriginOddJobVariant.FORGE_NEW, OriginOddJobVariant.FORGE_HOOK, OriginOddJobVariant.FORGE_HINGE,
            OriginOddJobVariant.FORGE_BRACKET, OriginOddJobVariant.FORGE_REPAIR,
            OriginOddJobVariant.FORGE_PAIR -> OriginOddJobStage.FORGE_BLANK
            OriginOddJobVariant.HAUL_IRON, OriginOddJobVariant.HAUL_COPPER, OriginOddJobVariant.HAUL_COAL -> OriginOddJobStage.HAUL_PICKUP
            OriginOddJobVariant.ENGRAVE_RUS, OriginOddJobVariant.ENGRAVE_HOME, OriginOddJobVariant.ENGRAVE_CAT -> OriginOddJobStage.ENGRAVE_BLANK
            else -> OriginOddJobStage.AUCTION_ITEM
        }
        return OriginOddJobSession(UUID.randomUUID(), offer.kind, stage, now, minOf(hardStopAt, Math.addExact(now, lifetimeMillis)), now, offer,
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
        ).also { jobs[playerId] = it }
    }

    fun act(playerId: UUID, action: OriginOddJobAction, now: Long, zone: Int = 1): OriginOddJobStep = advance(playerId, now) { job ->
        fun next(stage: OriginOddJobStage, updated: OriginOddJobSession = job) = changed(updated.copy(stage = stage, stageStartedAt = now, progress = 0))
        fun wrong() = rejected(OriginOddJobRejection.WRONG_ACTION, job)
        val yard = job.yard; val forge = job.forge
        when (job.stage) {
            OriginOddJobStage.YARD_HAY -> if (action == OriginOddJobAction.HAY) next(OriginOddJobStage.YARD_FEED) else wrong()
            OriginOddJobStage.YARD_FEED -> if (action == OriginOddJobAction.FEED) next(OriginOddJobStage.RETURN) else wrong()
            OriginOddJobStage.YARD_BUCKET -> if (action == OriginOddJobAction.WATER) next(OriginOddJobStage.YARD_WELL) else wrong()
            OriginOddJobStage.YARD_EATING, OriginOddJobStage.YARD_DRINKING, OriginOddJobStage.YARD_HANDED ->
                if (action == OriginOddJobAction.CARE_DONE) next(OriginOddJobStage.RETURN) else wrong()
            OriginOddJobStage.YARD_WELL -> if (action == OriginOddJobAction.WATER) next(OriginOddJobStage.YARD_WATER) else wrong()
            OriginOddJobStage.YARD_WATER -> if (action == OriginOddJobAction.WATER) next(OriginOddJobStage.YARD_DRINKING) else wrong()
            OriginOddJobStage.YARD_LEAD_RACK -> if (action == OriginOddJobAction.TACK) next(OriginOddJobStage.YARD_LEAD) else wrong()
            OriginOddJobStage.YARD_LEAD -> if (action == OriginOddJobAction.LEAD) next(OriginOddJobStage.YARD_LEAD_OUT) else wrong()
            OriginOddJobStage.YARD_LEAD_OUT -> if (action == OriginOddJobAction.ARRIVE) next(OriginOddJobStage.YARD_TIE_FIRST) else wrong()
            OriginOddJobStage.YARD_TIE_FIRST -> if (action == OriginOddJobAction.TIE) next(OriginOddJobStage.YARD_TIE_SECOND) else wrong()
            OriginOddJobStage.YARD_TIE_SECOND -> if (action == OriginOddJobAction.TIE) next(OriginOddJobStage.YARD_BRUSH) else wrong()
            OriginOddJobStage.YARD_UNTIE_FIRST -> if (action == OriginOddJobAction.UNTIE) next(OriginOddJobStage.YARD_UNTIE_SECOND) else wrong()
            OriginOddJobStage.YARD_UNTIE_SECOND -> if (action == OriginOddJobAction.UNTIE) next(OriginOddJobStage.YARD_DELIVER) else wrong()
            OriginOddJobStage.YARD_DELIVER -> if (action == OriginOddJobAction.ARRIVE) next(OriginOddJobStage.YARD_HANDOFF) else wrong()
            OriginOddJobStage.YARD_HANDOFF -> if (action == OriginOddJobAction.HANDOFF) next(OriginOddJobStage.RETURN) else wrong()
            OriginOddJobStage.YARD_BRUSH -> if (action == OriginOddJobAction.BRUSH) next(OriginOddJobStage.YARD_GROOM) else wrong()
            OriginOddJobStage.YARD_GROOM -> if (action != OriginOddJobAction.GROOM) wrong() else {
                val work = requireNotNull(yard).copy(brushStrokes = yard.brushStrokes + 1)
                if (work.brushStrokes == ODD_JOB_GROOM_STROKES) next(OriginOddJobStage.YARD_TACK, job.copy(yard = work))
                else changed(job.copy(yard = work))
            }
            OriginOddJobStage.YARD_TACK -> if (action == OriginOddJobAction.TACK) next(OriginOddJobStage.YARD_SADDLE) else wrong()
            OriginOddJobStage.YARD_SADDLE -> if (action == OriginOddJobAction.SADDLE) next(OriginOddJobStage.YARD_UNTIE_FIRST) else wrong()
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
            OriginOddJobStage.HAUL_LOAD -> if (action != OriginOddJobAction.LOAD) wrong() else {
                val work = requireNotNull(job.haul).let { it.copy(loaded = it.loaded + 1) }
                next(if (work.loaded == work.loads) OriginOddJobStage.HAUL_PUSH else OriginOddJobStage.HAUL_PICKUP, job.copy(haul = work))
            }
            OriginOddJobStage.HAUL_PUSH -> if (action == OriginOddJobAction.CART_ARRIVE) next(OriginOddJobStage.HAUL_UNLOAD) else wrong()
            OriginOddJobStage.HAUL_UNLOAD -> if (action == OriginOddJobAction.PICKUP) next(OriginOddJobStage.HAUL_DELIVER) else wrong()
            OriginOddJobStage.HAUL_DELIVER -> if (action != OriginOddJobAction.DELIVER) wrong() else {
                val work = requireNotNull(job.haul).let { it.copy(delivered = it.delivered + 1) }
                next(if (work.delivered == work.loads) OriginOddJobStage.HAUL_RETURN_CART else OriginOddJobStage.HAUL_UNLOAD, job.copy(haul = work))
            }
            OriginOddJobStage.HAUL_RETURN_CART -> if (action == OriginOddJobAction.CART_ARRIVE) next(OriginOddJobStage.RETURN) else wrong()
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
            OriginOddJobStage.RETURN -> if (action == OriginOddJobAction.RETURN) next(OriginOddJobStage.PAYOUT) else wrong()
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
    private fun advance(playerId: UUID, now: Long, action: (OriginOddJobSession) -> OriginOddJobStep): OriginOddJobStep {
        val job = jobs[playerId] ?: return rejected(OriginOddJobRejection.NO_JOB, null)
        if (job.stage == OriginOddJobStage.PAYOUT) return rejected(OriginOddJobRejection.PAYOUT_PENDING, job)
        if (now >= job.expiresAt) return rejected(OriginOddJobRejection.EXPIRED, job)
        return action(job).also { step ->
            when (step) { is OriginOddJobStep.Changed -> step.job; is OriginOddJobStep.Rejected -> step.job }
                ?.let { jobs[playerId] = it }
        }
    }
    private fun changed(job: OriginOddJobSession) = OriginOddJobStep.Changed(job)
    private fun rejected(reason: OriginOddJobRejection, job: OriginOddJobSession?) = OriginOddJobStep.Rejected(reason, job)
    private fun mistake(reason: OriginOddJobRejection, job: OriginOddJobSession) = rejected(reason, job.copy(quality = (job.quality - 5).coerceAtLeast(0)))
}
