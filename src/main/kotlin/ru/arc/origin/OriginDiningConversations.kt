package ru.arc.origin

import ru.arc.core.TaskScheduler
import ru.arc.core.Tasks
import ru.arc.origin.scene.OriginSceneCoordinator
import ru.arc.origin.scene.OriginSceneExecution
import ru.arc.origin.scene.OriginSceneExecutionEffects
import ru.arc.origin.scene.OriginSceneExecutionPhase
import ru.arc.origin.scene.OriginSceneRecoveryPolicy
import java.util.UUID
import kotlin.math.ceil

/** Owns ambient dining conversations without competing with service cycles. */
internal class OriginDiningConversations(
    private val coordinator: OriginSceneCoordinator,
    variants: List<Variant>,
    private val effects: Effects,
    private val config: Config = Config(),
    private val scheduler: TaskScheduler? = null,
    private val mayStart: (Variant) -> Boolean = { true },
) : AutoCloseable {
    data class Variant(
        val id: String,
        val venueId: String,
        val dialogue: OriginDiningDialogue,
    ) {
        init {
            require(id.isNotBlank()) { "conversation variant id must not be blank" }
            require(venueId.isNotBlank()) { "conversation venue id must not be blank" }
            require(dialogue.lines.isNotEmpty() && dialogue.lines.all(String::isNotBlank)) {
                "conversation variant $id must contain non-blank lines"
            }
        }

        val actorIds: Set<Int> get() = setOf(dialogue.firstNpcId, dialogue.secondNpcId)

        init {
            require(dialogue.firstNpcId != dialogue.secondNpcId) {
                "conversation variant $id requires two distinct actors"
            }
        }
    }

    data class Config(
        val minimumLineMillis: Long = 900L,
        val maximumLineMillis: Long = 7_000L,
        val charactersPerSecond: Double = 10.0,
        val pauseBetweenLinesMillis: Long = 600L,
        val gestureEveryLines: Int = 3,
        val pairCooldownMillis: Long = 15_000L,
        val venueCooldownMillis: Long = 3_000L,
        val executionTimeoutMillis: Long = 120_000L,
    ) {
        init {
            require(minimumLineMillis in 100L..30_000L)
            require(maximumLineMillis in minimumLineMillis..60_000L)
            require(charactersPerSecond.isFinite() && charactersPerSecond in 1.0..80.0)
            require(pauseBetweenLinesMillis in 0L..10_000L)
            require(gestureEveryLines in 1..32)
            require(pairCooldownMillis >= 0L)
            require(venueCooldownMillis >= 0L)
            require(executionTimeoutMillis >= maximumLineMillis)
        }

        fun lineDurationMillis(text: String): Long =
            ceil(text.codePointCount(0, text.length) * 1_000.0 / charactersPerSecond)
                .toLong()
                .coerceIn(minimumLineMillis, maximumLineMillis)

        companion object {
            fun load(source: ru.arc.config.Config): Config {
                val minimum = source.integer("conversation.minimum-line-millis", 900).toLong().coerceIn(100L, 30_000L)
                val maximum = source.integer("conversation.maximum-line-millis", 7_000).toLong().coerceIn(minimum, 60_000L)
                val timeout = source.integer("conversation.execution-timeout-millis", 120_000)
                    .toLong()
                    .coerceIn(10_000L, 300_000L)
                    .coerceAtLeast(maximum)
                return Config(
                    minimumLineMillis = minimum,
                    maximumLineMillis = maximum,
                    charactersPerSecond = source.real("conversation.characters-per-second", 10.0)
                        .takeIf(Double::isFinite)
                        ?.coerceIn(1.0, 80.0)
                        ?: 10.0,
                    pauseBetweenLinesMillis = source.integer("conversation.pause-between-lines-millis", 600)
                        .toLong()
                        .coerceIn(0L, 10_000L),
                    gestureEveryLines = source.integer("conversation.gesture-every-lines", 3).coerceIn(1, 32),
                    pairCooldownMillis = source.integer("conversation.pair-cooldown-millis", 15_000)
                        .toLong()
                        .coerceAtLeast(0L),
                    venueCooldownMillis = source.integer("conversation.venue-cooldown-millis", 3_000)
                        .toLong()
                        .coerceAtLeast(0L),
                    executionTimeoutMillis = timeout,
                )
            }
        }
    }

    data class ActorSnapshot(
        val actorId: Int,
        val venueId: String,
        val available: Boolean,
        val audiencePresent: Boolean,
    )

    enum class Gesture { NOD, TOAST }

    interface Effects {
        fun snapshot(actorId: Int): ActorSnapshot?
        fun face(ownerToken: UUID, actorId: Int, targetActorId: Int)
        fun speak(ownerToken: UUID, actorId: Int, text: String, ttlMillis: Long)
        fun gesture(ownerToken: UUID, gesture: Gesture, actorIds: Set<Int>)
        fun cleanup(ownerToken: UUID)
        fun reportFailure(ownerToken: UUID, stage: String, failure: Exception)
    }

    private data class PairKey(val venueId: String, val firstActorId: Int, val secondActorId: Int) {
        val id: String get() = "$venueId:$firstActorId-$secondActorId"
    }

    private data class Active(
        val lease: ru.arc.origin.scene.OriginSceneLease,
        val variant: Variant,
        val pairKey: PairKey,
        val startedAt: Long,
        var observedAt: Long,
    ) {
        lateinit var execution: OriginSceneExecution
    }

    private val variantsByVenue = variants.groupBy(Variant::venueId)
    private val activeByVenue = mutableMapOf<String, Active>()
    private val lastTalkedAt = mutableMapOf<PairKey, Long>()
    private val nextVariantIndexByPair = mutableMapOf<PairKey, Int>()
    private val venueCooldownUntil = mutableMapOf<String, Long>()
    private var closed = false

    init {
        require(variants.map(Variant::id).toSet().size == variants.size) { "conversation variant ids must be unique" }
    }

    fun tick(nowMillis: Long) {
        if (closed) return
        activeByVenue.values.toList().forEach { active ->
            active.observedAt = nowMillis
            if (active.execution.phase == OriginSceneExecutionPhase.RUNNING && !stillValid(active)) {
                active.execution.interrupt("audience-or-actor-unavailable", OriginSceneRecoveryPolicy.KEEP_CURRENT_POSITION)
            }
        }
        variantsByVenue.forEach { (venueId, venueVariants) ->
            if (activeByVenue.containsKey(venueId)) return@forEach
            if (venueCooldownUntil[venueId]?.let { nowMillis < it } == true) return@forEach
            val grouped = venueVariants
                .groupBy(::pairKey)
                .entries
                .asSequence()
                .filter { (pair, _) ->
                    val previous = lastTalkedAt[pair]
                    previous == null || nowMillis >= previous + config.pairCooldownMillis
                }
                .mapNotNull { (pair, pairVariants) ->
                    val nextIndex = nextVariantIndexByPair.getOrDefault(pair, 0) % pairVariants.size
                    val selected = pairVariants[nextIndex]
                    val first = effects.snapshot(selected.dialogue.firstNpcId)
                    val second = effects.snapshot(selected.dialogue.secondNpcId)
                    if (first == null || second == null || !eligible(selected, first, second) || !mayStart(selected)) null
                    else pair to selected
                }
                .sortedWith(compareBy<Pair<PairKey, Variant>> { lastTalkedAt.getOrDefault(it.first, Long.MIN_VALUE) }
                    .thenBy { it.first.firstActorId }
                    .thenBy { it.first.secondActorId }
                    .thenBy { it.second.id })
                .toList()
            for ((pair, variant) in grouped) {
                val lease = coordinator.tryAcquire(
                    sceneId = "origin-dining-conversation:$venueId",
                    cycleId = pair.id,
                    actorIds = variant.actorIds,
                    nowMillis = nowMillis,
                    ignoreDue = true,
                ) ?: continue
                launch(venueId, pair, variant, lease, nowMillis)
                break
            }
        }
    }

    fun cancelActor(actorId: Int, reason: String) {
        activeByVenue.values.toList()
            .filter { actorId in it.variant.actorIds }
            .forEach { active ->
                if (active.execution.phase == OriginSceneExecutionPhase.RUNNING) {
                    active.execution.interrupt(reason, OriginSceneRecoveryPolicy.KEEP_CURRENT_POSITION)
                }
            }
    }

    override fun close() {
        if (closed) return
        closed = true
        activeByVenue.values.toList().forEach { it.execution.close() }
    }

    private fun launch(
        venueId: String,
        pair: PairKey,
        variant: Variant,
        lease: ru.arc.origin.scene.OriginSceneLease,
        nowMillis: Long,
    ) {
        val active = Active(lease, variant, pair, nowMillis, nowMillis)
        val execution = OriginSceneExecution(
            stepIds = variant.dialogue.lines.indices.map { "line-$it" },
            timeoutTicks = ticks(config.executionTimeoutMillis),
            effects = object : OriginSceneExecutionEffects {
                override fun execute(stepIndex: Int) = showLine(active, stepIndex)
                override fun cleanup(keepMounted: Boolean) = effects.cleanup(active.lease.token)
                override fun returnHome(reason: String, immediate: Boolean) = Unit
                override fun release(reason: String) = release(active, reason)
                override fun reportFailure(stage: String, failure: Exception) =
                    effects.reportFailure(active.lease.token, stage, failure)
            },
            scheduler = scheduler ?: Tasks.scheduler,
        )
        active.execution = execution
        activeByVenue[venueId] = active
        lastTalkedAt[pair] = nowMillis
        val pairVariants = variantsByVenue.getValue(venueId).filter { pairKey(it) == pair }
        nextVariantIndexByPair[pair] =
            ((pairVariants.indexOfFirst { it.id == variant.id } + 1).coerceAtLeast(1)) % pairVariants.size
        execution.start()
    }

    private fun showLine(active: Active, index: Int) {
        if (!stillValid(active)) {
            active.execution.interrupt("audience-or-actor-unavailable", OriginSceneRecoveryPolicy.KEEP_CURRENT_POSITION)
            return
        }
        val line = active.variant.dialogue.line(index)
        val other = if (line.npcId == active.variant.dialogue.firstNpcId) {
            active.variant.dialogue.secondNpcId
        } else {
            active.variant.dialogue.firstNpcId
        }
        val duration = config.lineDurationMillis(line.text)
        effects.face(active.lease.token, line.npcId, other)
        effects.speak(active.lease.token, line.npcId, line.text, duration)
        if ((index + 1) % config.gestureEveryLines == 0) {
            effects.gesture(active.lease.token, gestureFor(line.text), active.variant.actorIds)
        }
        active.execution.after(ticks(duration + config.pauseBetweenLinesMillis)) {
            active.execution.advance(index + 1)
            if (active.execution.phase == OriginSceneExecutionPhase.RETURNING) {
                active.execution.completeReturn("complete")
            }
        }
    }

    private fun stillValid(active: Active): Boolean {
        val first = effects.snapshot(active.variant.dialogue.firstNpcId) ?: return false
        val second = effects.snapshot(active.variant.dialogue.secondNpcId) ?: return false
        return eligible(active.variant, first, second)
    }

    private fun eligible(variant: Variant, first: ActorSnapshot, second: ActorSnapshot): Boolean =
        first.actorId == variant.dialogue.firstNpcId &&
            second.actorId == variant.dialogue.secondNpcId &&
            first.venueId == variant.venueId && second.venueId == variant.venueId &&
            first.available && second.available &&
            (first.audiencePresent || second.audiencePresent)

    private fun pairKey(variant: Variant): PairKey {
        val first = minOf(variant.dialogue.firstNpcId, variant.dialogue.secondNpcId)
        val second = maxOf(variant.dialogue.firstNpcId, variant.dialogue.secondNpcId)
        return PairKey(variant.venueId, first, second)
    }

    private fun release(active: Active, reason: String) {
        if (activeByVenue[active.pairKey.venueId] !== active) return
        activeByVenue.remove(active.pairKey.venueId)
        if (reason == "complete") {
            venueCooldownUntil[active.pairKey.venueId] = active.observedAt + config.venueCooldownMillis
        }
        coordinator.release(active.lease, active.observedAt, cooldownMillis = 0L)
    }

    private fun gestureFor(text: String): Gesture {
        val normalized = text.lowercase()
        return if (
            normalized.contains("выпьем") ||
            normalized.contains("выпить") ||
            normalized.contains("выпью") ||
            normalized.contains("круж") ||
            normalized.contains("здоров") ||
            normalized.contains("за встреч")
        ) Gesture.TOAST else Gesture.NOD
    }

    private fun ticks(millis: Long): Long = ((millis + 49L) / 50L).coerceAtLeast(1L)
}
