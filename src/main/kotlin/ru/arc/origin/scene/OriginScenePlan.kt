package ru.arc.origin.scene

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Particle
import org.bukkit.World
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.npc.NpcRouteBounds
import ru.arc.npc.NpcRouteProfile
import ru.arc.origin.util.SoundUtils
import java.nio.file.Path
import java.util.Locale

internal data class OriginScenePoint(
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float = 0f,
    val pitch: Float = 0f,
    val explicitPose: Boolean = false,
) {
    init {
        require(x.isFinite() && y.isFinite() && z.isFinite()) { "scene points must have finite coordinates" }
        require(yaw.isFinite() && pitch.isFinite()) { "scene poses must have finite yaw and pitch" }
    }

    fun inWorld(world: World): Location = Location(world, x, y, z, yaw, pitch)
}

internal data class OriginSceneActor(
    val id: Int,
    val home: OriginScenePoint,
    val deniedDenizenFlags: Set<String>,
)

/** Native animal pose operations supported by the configured entity type. */
internal enum class OriginScenePose {
    STAND,
    SIT,
    CAT_LIE,
    HORSE_GRAZE,
}

/** Item-model display contexts retained by portable scene ItemDisplays. */
internal enum class OriginSceneItemDisplayContext {
    NONE,
    GROUND,
    FIXED,
    HEAD,
}

internal sealed interface OriginSceneStep {
    val actorId: Int?

    sealed interface Display : OriginSceneStep {
        val key: String
        val anchor: String?
        val followActorId: Int?

        override val actorId: Int? get() = null
    }

    data class Move(
        override val actorId: Int,
        val anchor: String,
        val routeProfile: String,
        val timeoutTicks: Long,
    ) : OriginSceneStep

    /** Starts all routes at once and advances only after every actor arrives. */
    data class MoveGroup(
        val actorIds: List<Int>,
        val targetAnchors: List<String>,
        val routeProfile: String,
        val timeoutTicks: Long,
    ) : OriginSceneStep {
        override val actorId: Int? = null
    }

    data class Wait(val ticks: Long) : OriginSceneStep {
        override val actorId: Int? = null
    }

    data class LookAtAnchor(override val actorId: Int, val anchor: String) : OriginSceneStep

    data class LookAtSurface(override val actorId: Int, val surface: String) : OriginSceneStep

    data class LookAtActor(override val actorId: Int, val targetActorId: Int) : OriginSceneStep

    data class Equip(override val actorId: Int, val material: String) : OriginSceneStep

    data class Swing(
        override val actorId: Int,
        val repetitions: Int,
        val periodTicks: Long,
        val feedbackAnchor: String? = null,
        val feedbackSurface: String? = null,
        val damageTargetNpcId: Int? = null,
        val damageAmount: Double = 0.0,
        val particle: String? = null,
        val particleCount: Int = 3,
        val particleEvery: Int = 1,
        val sound: String? = null,
        val soundEvery: Int = 1,
        val soundVolume: Float = 0.35f,
        val soundPitch: Float = 1.0f,
    ) : OriginSceneStep

    data class BlockDisplay(
        override val key: String,
        val surface: String?,
        override val anchor: String?,
        val material: String,
        val origin: OriginScenePropOrigin,
        val offset: OriginSceneVector,
        val scale: OriginSceneVector,
        val rotationYDegrees: Float,
        val interpolationTicks: Int,
        override val followActorId: Int? = null,
        val followOffset: OriginSceneVector = OriginSceneVector.ZERO,
    ) : Display

    data class ItemDisplay(
        override val key: String,
        override val anchor: String?,
        val itemId: String,
        val context: OriginSceneItemDisplayContext,
        val offset: OriginSceneVector,
        val scale: OriginSceneVector,
        val yawOffsetDegrees: Float,
        val interpolationTicks: Int,
        override val followActorId: Int? = null,
        val followOffset: OriginSceneVector = OriginSceneVector.ZERO,
    ) : Display

    data class RemoveDisplay(val key: String) : OriginSceneStep {
        override val actorId: Int? = null
    }

    data class Sound(
        override val actorId: Int?,
        val anchor: String?,
        val sound: String,
        val volume: Float,
        val pitch: Float,
    ) : OriginSceneStep

    data class Particle(
        override val actorId: Int?,
        val anchor: String?,
        val particle: String,
        val count: Int,
    ) : OriginSceneStep

    data class Speech(override val actorId: Int, val text: String) : OriginSceneStep

    data class ContainerLid(
        override val actorId: Int,
        val anchor: String,
        val open: Boolean,
    ) : OriginSceneStep

    data class Mount(override val actorId: Int, val vehicleActorId: Int) : OriginSceneStep

    data class Dismount(override val actorId: Int) : OriginSceneStep

    data class Pose(override val actorId: Int, val pose: OriginScenePose) : OriginSceneStep
}

internal data class OriginSceneCycle(
    val id: String,
    val actorIds: Set<Int>,
    val cooldownMillis: LongRange,
    val initialDelayMillis: Long,
    val yieldAnchor: String?,
    val yieldRange: Double,
    val steps: List<OriginSceneStep>,
    val stepIds: List<String> = steps.indices.map { "step-$it" },
    val maxDurationTicks: Long = defaultMaxDurationTicks(steps),
)

private const val MAX_CYCLE_DURATION_TICKS = 1_728_000L // 24 hours at 20 TPS.
private const val RETURN_AND_SAFETY_MARGIN_TICKS = 1_200L

private fun authoredAsyncDurationTicks(step: OriginSceneStep): Long = when (step) {
    is OriginSceneStep.Move -> step.timeoutTicks
    is OriginSceneStep.MoveGroup -> step.timeoutTicks
    is OriginSceneStep.Wait -> step.ticks
    is OriginSceneStep.Swing -> (step.repetitions - 1L) * step.periodTicks
    else -> 0L
}

private fun defaultMaxDurationTicks(steps: List<OriginSceneStep>): Long {
    val authored = steps.sumOf(::authoredAsyncDurationTicks)
    return authored * 2L + RETURN_AND_SAFETY_MARGIN_TICKS
}

internal data class OriginSceneDefinition(
    val id: String,
    val anchor: OriginScenePoint,
    val audienceRange: Double,
    val retryMillis: Long,
    val maxConcurrentCycles: Int,
    val actors: Map<Int, OriginSceneActor>,
    val anchors: Map<String, OriginScenePoint>,
    val propSurfaces: Map<String, OriginScenePropSurface>,
    val routeProfiles: Map<String, NpcRouteProfile>,
    val cycles: List<OriginSceneCycle>,
) {
    fun validate() {
        require(id.isNotBlank()) { "scene id must not be blank" }
        require(audienceRange.isFinite() && audienceRange in 4.0..128.0) {
            "scene $id audience-range must be finite and within 4..128"
        }
        require(retryMillis in 1_000L..120_000L) { "scene $id retry-seconds must be within 1..120" }
        require(maxConcurrentCycles in 1..8) { "scene $id max-concurrent-cycles must be within 1..8" }
        require(actors.isNotEmpty() && cycles.isNotEmpty()) { "scene $id must declare actors and cycles" }
        require(cycles.map(OriginSceneCycle::id).distinct().size == cycles.size) {
            "scene $id has duplicate cycle ids"
        }
        actors.forEach { (actorId, actor) ->
            require(actorId >= 0 && actor.id == actorId) { "scene $id has invalid actor id $actorId" }
        }
        require(anchors.keys.all(String::isNotBlank)) { "scene $id has a blank anchor id" }
        require(propSurfaces.keys.all(String::isNotBlank)) { "scene $id has a blank prop surface id" }
        require(routeProfiles.keys.all(String::isNotBlank)) { "scene $id has a blank route profile id" }
        cycles.forEach { cycle ->
            val cycleContext = "scene $id cycle ${cycle.id}"
            require(cycle.id.isNotBlank()) { "$cycleContext id must not be blank" }
            require(cycle.actorIds.isNotEmpty() && cycle.actorIds.all(actors::containsKey)) {
                "$cycleContext references an undeclared actor"
            }
            require(cycle.cooldownMillis.first in 1_000L..3_600_000L && cycle.cooldownMillis.last in 1_000L..3_600_000L) {
                "$cycleContext cooldown must be within 1..3600 seconds"
            }
            require(cycle.initialDelayMillis in 0L..600_000L) {
                "$cycleContext initial-delay-seconds must be within 0..600"
            }
            require(cycle.yieldAnchor == null || cycle.yieldAnchor in anchors) {
                "$cycleContext references yield anchor ${cycle.yieldAnchor}"
            }
            require(cycle.yieldRange.isFinite() && cycle.yieldRange in 1.0..12.0) {
                "$cycleContext yield-range must be finite and within 1..12"
            }
            require(cycle.steps.isNotEmpty()) { "$cycleContext has no steps" }
            require(cycle.stepIds.size == cycle.steps.size) {
                "$cycleContext step id count ${cycle.stepIds.size} does not match step count ${cycle.steps.size}"
            }
            require(cycle.stepIds.all(String::isNotBlank)) { "$cycleContext has a blank step id" }
            require(cycle.stepIds.distinct().size == cycle.stepIds.size) { "$cycleContext has duplicate step ids" }
            require(cycle.maxDurationTicks in 1..MAX_CYCLE_DURATION_TICKS) {
                "$cycleContext max-duration must be within 1..$MAX_CYCLE_DURATION_TICKS ticks"
            }
            OriginScenePropContract.validateLifecycle(cycle.steps) { index ->
                "$cycleContext step ${cycle.stepIds[index]}"
            }
            cycle.steps.forEachIndexed { index, step ->
                val stepContext = "$cycleContext step ${cycle.stepIds[index]}"
                step.actorId?.let { actorId ->
                    require(actorId in cycle.actorIds) { "$stepContext actor $actorId is not leased" }
                }
                when (step) {
                    is OriginSceneStep.Move -> {
                        require(step.timeoutTicks in 20L..1_200L) { "$stepContext timeout-ticks must be within 20..1200" }
                        require(step.anchor in anchors) { "$stepContext references anchor ${step.anchor}" }
                        require(step.routeProfile in routeProfiles) { "$stepContext references route ${step.routeProfile}" }
                    }
                    is OriginSceneStep.MoveGroup -> {
                        require(step.actorIds.isNotEmpty()) { "$stepContext has no actors" }
                        require(step.actorIds.distinct().size == step.actorIds.size) {
                            "$stepContext contains duplicate actors"
                        }
                        require(step.actorIds.all(cycle.actorIds::contains)) {
                            "$stepContext references an actor outside the cycle lease"
                        }
                        require(step.targetAnchors.size == step.actorIds.size) {
                            "$stepContext target count ${step.targetAnchors.size} does not match actor count ${step.actorIds.size}"
                        }
                        require(step.targetAnchors.all(anchors::containsKey)) {
                            "$stepContext references an unknown target anchor"
                        }
                        require(step.routeProfile in routeProfiles) {
                            "$stepContext references route ${step.routeProfile}"
                        }
                        require(step.timeoutTicks in 20L..1_200L) {
                            "$stepContext timeout-ticks must be within 20..1200"
                        }
                    }
                    is OriginSceneStep.Wait -> require(step.ticks in 1L..1_200L) {
                        "$stepContext ticks must be within 1..1200"
                    }
                    is OriginSceneStep.LookAtAnchor -> require(step.anchor in anchors) {
                        "$stepContext references anchor ${step.anchor}"
                    }
                    is OriginSceneStep.LookAtSurface -> require(step.surface in propSurfaces) {
                        "$stepContext references prop surface ${step.surface}"
                    }
                    is OriginSceneStep.LookAtActor -> require(step.targetActorId in actors) {
                        "$stepContext references actor ${step.targetActorId}"
                    }
                    is OriginSceneStep.Equip -> {
                        val material = Material.matchMaterial(step.material)
                        require(material != null) {
                            "$stepContext has unknown material ${step.material}"
                        }
                        require(material.isItem || material.isAir) {
                            "$stepContext material ${step.material} is not an item (AIR is allowed to clear the hand)"
                        }
                    }
                    is OriginSceneStep.Swing -> {
                        require(step.repetitions in 1..20) { "$stepContext repetitions must be within 1..20" }
                        require(step.periodTicks in 1L..100L) { "$stepContext period-ticks must be within 1..100" }
                        require(step.feedbackAnchor == null || step.feedbackAnchor in anchors) {
                            "$stepContext references anchor ${step.feedbackAnchor}"
                        }
                        require(step.feedbackSurface == null || step.feedbackSurface in propSurfaces) {
                            "$stepContext references prop surface ${step.feedbackSurface}"
                        }
                        require(step.feedbackAnchor == null || step.feedbackSurface == null) {
                            "$stepContext must reference at most one feedback anchor or surface"
                        }
                        require((step.damageTargetNpcId == null) == (step.damageAmount == 0.0)) {
                            "$stepContext damage target and amount must be configured together"
                        }
                        require(step.damageTargetNpcId == null || step.damageTargetNpcId >= 0) {
                            "$stepContext damage target must be non-negative"
                        }
                        require(step.damageAmount.isFinite() && (step.damageAmount == 0.0 || step.damageAmount in 0.1..20.0)) {
                            "$stepContext damage amount must be 0 or within 0.1..20"
                        }
                        require(step.particleCount in 1..50) { "$stepContext particle-count must be within 1..50" }
                        require(step.particleEvery in 1..20) { "$stepContext particle-every must be within 1..20" }
                        require(step.soundEvery in 1..20) { "$stepContext sound-every must be within 1..20" }
                        require(step.soundVolume.isFinite() && step.soundVolume in 0f..4f) {
                            "$stepContext sound-volume must be finite and within 0..4"
                        }
                        require(step.soundPitch.isFinite() && step.soundPitch in 0.5f..2f) {
                            "$stepContext sound-pitch must be finite and within 0.5..2"
                        }
                        step.particle?.let { particleName ->
                            val particle = runCatching { Particle.valueOf(particleName) }.getOrNull()
                            require(particle != null) { "$stepContext has unknown particle $particleName" }
                            require(particle.dataType == Void::class.java) {
                                "$stepContext particle $particleName requires data ${particle.dataType.simpleName}, but runtime supplies no particle data"
                            }
                        }
                        step.sound?.let { sound ->
                            require(SoundUtils.getSound(sound) != null) { "$stepContext has unknown sound $sound" }
                        }
                    }
                    is OriginSceneStep.BlockDisplay -> {
                        require(listOf(step.surface, step.anchor, step.followActorId).count { it != null } == 1) {
                            "$stepContext prop ${step.key} must reference exactly one surface, anchor or follow actor"
                        }
                        step.surface?.let { surface ->
                            require(surface in propSurfaces) { "$stepContext references prop surface $surface" }
                        }
                        step.anchor?.let { anchor ->
                            require(anchor in anchors) { "$stepContext references prop anchor $anchor" }
                        }
                        step.followActorId?.let { actorId ->
                            require(actorId in cycle.actorIds) {
                                "$stepContext follow actor $actorId is not leased"
                            }
                        }
                        require(Material.matchMaterial(step.material)?.takeIf(Material::isBlock) != null) {
                            "$stepContext has invalid block material ${step.material}"
                        }
                        OriginScenePropContract.resolve(
                            step.surface?.let { propSurfaces.getValue(it).near }
                                ?: step.anchor?.let { anchors.getValue(it) }
                                ?: OriginScenePoint(0.0, 0.0, 0.0),
                            step.origin,
                            step.offset,
                            step.scale,
                            step.rotationYDegrees,
                        )
                        step.followOffset.requireBounded("$stepContext follow-offset", 16.0)
                    }
                    is OriginSceneStep.ItemDisplay -> {
                        require(listOf(step.anchor, step.followActorId).count { it != null } == 1) {
                            "$stepContext item display ${step.key} must reference exactly one anchor or follow actor"
                        }
                        step.anchor?.let { anchor ->
                            require(anchor in anchors) { "$stepContext references item display anchor $anchor" }
                        }
                        step.followActorId?.let { actorId ->
                            require(actorId in cycle.actorIds) {
                                "$stepContext item display follow actor $actorId is not leased"
                            }
                        }
                        require(step.itemId.contains(':') && NamespacedKey.fromString(step.itemId) != null) {
                            "$stepContext has invalid namespaced ItemsAdder item id ${step.itemId}"
                        }
                        step.scale.requirePositive("$stepContext item display scale")
                        step.scale.requireBounded("$stepContext item display scale", 4.0)
                        require(step.scale.x >= 0.01 && step.scale.y >= 0.01 && step.scale.z >= 0.01) {
                            "$stepContext item display scale values must be at least 0.01"
                        }
                        require(step.yawOffsetDegrees.isFinite() && step.yawOffsetDegrees in -360f..360f) {
                            "$stepContext item display yaw-offset-degrees must be finite and within -360..360"
                        }
                        require(step.interpolationTicks in 0..59) {
                            "$stepContext item display interpolation-ticks must be within 0..59"
                        }
                        step.offset.requireBounded("$stepContext item display offset", 16.0)
                        step.followOffset.requireBounded("$stepContext item display follow-offset", 16.0)
                    }
                    is OriginSceneStep.RemoveDisplay -> require(step.key.isNotBlank()) {
                        "$stepContext prop key must not be blank"
                    }
                    is OriginSceneStep.Sound -> {
                        require(step.actorId != null || step.anchor != null) { "$stepContext has no location" }
                        require(step.anchor == null || step.anchor in anchors) { "$stepContext references anchor ${step.anchor}" }
                        require(step.volume.isFinite() && step.volume in 0f..4f) {
                            "$stepContext volume must be finite and within 0..4"
                        }
                        require(step.pitch.isFinite() && step.pitch in 0.5f..2f) {
                            "$stepContext pitch must be finite and within 0.5..2"
                        }
                        require(SoundUtils.getSound(step.sound) != null) { "$stepContext has unknown sound ${step.sound}" }
                    }
                    is OriginSceneStep.Particle -> {
                        require(step.actorId != null || step.anchor != null) { "$stepContext has no location" }
                        require(step.anchor == null || step.anchor in anchors) { "$stepContext references anchor ${step.anchor}" }
                        require(step.count in 1..50) { "$stepContext count must be within 1..50" }
                        val particle = runCatching { Particle.valueOf(step.particle) }.getOrNull()
                        require(particle != null) {
                            "$stepContext has unknown particle ${step.particle}"
                        }
                        require(particle.dataType == Void::class.java) {
                            "$stepContext particle ${step.particle} requires data ${particle.dataType.simpleName}, but runtime supplies no particle data"
                        }
                    }
                    is OriginSceneStep.Speech -> require(step.text.isNotBlank()) { "$stepContext text must not be blank" }
                    is OriginSceneStep.ContainerLid -> require(step.anchor in anchors) {
                        "$stepContext references anchor ${step.anchor}"
                    }
                    is OriginSceneStep.Mount -> require(step.vehicleActorId in cycle.actorIds) {
                        "$stepContext vehicle ${step.vehicleActorId} is not leased"
                    }
                    is OriginSceneStep.Dismount -> Unit
                    is OriginSceneStep.Pose -> Unit
                }
            }
        }
    }
}

internal data class OriginScenePlan(
    val world: String,
    val tickTicks: Long,
    val speechDurationTicks: Long,
    val speechHeight: Double,
    val speechViewRange: Float,
    val speechScale: Float,
    val scenes: List<OriginSceneDefinition>,
) {
    init {
        require(world.isNotBlank()) { "world must not be blank" }
        require(tickTicks in 5L..100L) { "tick-ticks must be within 5..100" }
        require(speechDurationTicks in 20L..300L) { "speech.duration-ticks must be within 20..300" }
        require(speechHeight.isFinite() && speechHeight in 1.8..4.0) {
            "speech.height must be finite and within 1.8..4"
        }
        require(speechViewRange.isFinite() && speechViewRange in 0.5f..4f) {
            "speech.view-range must be finite and within 0.5..4"
        }
        require(speechScale.isFinite() && speechScale in 0.5f..2f) {
            "speech.scale must be finite and within 0.5..2"
        }
        require(scenes.isNotEmpty()) { "scene-ids must not be empty" }
        require(scenes.map(OriginSceneDefinition::id).distinct().size == scenes.size) {
            "scene-ids contains duplicate ids"
        }
    }

    private val scenesById = scenes.associateBy(OriginSceneDefinition::id)

    fun scene(id: String): OriginSceneDefinition = requireNotNull(scenesById[id]) { "Unknown Origin scene: $id" }

    companion object {
        fun load(dataPath: Path, mergeDefaults: Boolean = true): OriginScenePlan {
            val source = ConfigManager.ofModule(dataPath, "origin-scenes.yml")
            if (mergeDefaults) source.mergeMissingFromBundled("modules/origin-scenes.yml")
            val sceneIds = distinctIds(source.stringList("scene-ids"), "scene-ids")
            val plan = OriginScenePlan(
                world = source.string("world", "rc_origin_spawn"),
                tickTicks = boundedInteger(source, "tick-ticks", 20, 5..100).toLong(),
                speechDurationTicks = boundedInteger(source, "speech.duration-ticks", 100, 20..300).toLong(),
                speechHeight = boundedReal(source, "speech.height", 2.65, 1.8..4.0),
                speechViewRange = boundedReal(source, "speech.view-range", 1.0, 0.5..4.0).toFloat(),
                speechScale = boundedReal(source, "speech.scale", 0.95, 0.5..2.0).toFloat(),
                scenes = sceneIds.map { parseScene(source, it) },
            )
            plan.scenes.forEach { it.validate() }
            return plan
        }

        private fun parseScene(source: Config, id: String): OriginSceneDefinition {
            require(id.isNotBlank()) { "scene-ids contains a blank id" }
            val root = "scenes.$id"
            val actorIds = distinctActorIds(source.stringList("$root.actor-ids"), "$root.actor-ids")
            val actors = actorIds.associateWith { actorId ->
                OriginSceneActor(
                    id = actorId,
                    home = point(source.string("$root.actors.$actorId.home"), "$root.actors.$actorId.home"),
                    deniedDenizenFlags = source.stringList("$root.actors.$actorId.denied-denizen-flags").toSet(),
                )
            }
            // Separate additive work anchors merge into existing installations without replacing authored lists.
            val anchors = distinctIds(source.stringList("$root.anchor-ids") + source.stringList("$root.work-anchor-ids"), "$root.anchor-ids/work-anchor-ids").associateWith { anchorId ->
                point(source.string("$root.anchors.$anchorId"), "$root.anchors.$anchorId")
            }
            val propSurfaces = distinctIds(source.stringList("$root.prop-surface-ids"), "$root.prop-surface-ids").associateWith { surfaceId ->
                val surfaceRoot = "$root.prop-surfaces.$surfaceId"
                OriginScenePropSurface(
                    near = point(source.string("$surfaceRoot.near"), "$surfaceRoot.near"),
                    materials = source.stringList("$surfaceRoot.material-ids").mapIndexed { index, materialName ->
                        requireNotNull(Material.matchMaterial(materialName)) {
                            "Unknown material $materialName at $surfaceRoot.material-ids[$index]"
                        }
                    }.toSet(),
                    searchRadius = boundedInteger(source, "$surfaceRoot.search-radius", 2, 0..4),
                    topOffset = boundedReal(source, "$surfaceRoot.top-offset", 1.0, 0.0..2.0),
                    lookTargetOffsetY = boundedReal(source, "$surfaceRoot.look-target-offset-y", -1.15, -4.0..2.0),
                )
            }
            val routeProfiles = distinctIds(source.stringList("$root.route-profile-ids"), "$root.route-profile-ids").associateWith { profileId ->
                routeProfile(source, "$root.route-profiles.$profileId", "$id-$profileId")
            }
            val cycleIds = distinctIds(source.stringList("$root.cycle-ids"), "$root.cycle-ids")
            return OriginSceneDefinition(
                id = id,
                anchor = point(source.string("$root.anchor"), "$root.anchor"),
                audienceRange = boundedReal(source, "$root.audience-range", 48.0, 4.0..128.0),
                retryMillis = boundedInteger(source, "$root.retry-seconds", 8, 1..120).toLong() * 1_000L,
                maxConcurrentCycles = boundedInteger(source, "$root.max-concurrent-cycles", 1, 1..8),
                actors = actors,
                anchors = anchors,
                propSurfaces = propSurfaces,
                routeProfiles = routeProfiles,
                cycles = cycleIds.map { cycleId -> parseCycle(source, root, cycleId) },
            )
        }

        private fun parseCycle(source: Config, sceneRoot: String, id: String): OriginSceneCycle {
            require(id.isNotBlank()) { "$sceneRoot.cycle-ids contains a blank id" }
            val root = "$sceneRoot.cycles.$id"
            val minimum = boundedInteger(source, "$root.cooldown-min-seconds", 30, 1..3_600).toLong() * 1_000L
            val maximum = boundedInteger(source, "$root.cooldown-max-seconds", 60, 1..3_600).toLong() * 1_000L
            val actorIds = distinctActorIds(source.stringList("$root.actor-ids"), "$root.actor-ids")
            val stepIds = distinctIds(source.stringList("$root.step-ids"), "$root.step-ids")
            val steps = stepIds.map { stepId -> parseStep(source, "$root.steps.$stepId") }
            val derivedMaxDurationTicks = defaultMaxDurationTicks(steps)
            val configuredMaxDurationRaw = source.string("$root.max-duration-seconds", "").trim()
            val maxDurationTicks = if (configuredMaxDurationRaw.isBlank()) {
                derivedMaxDurationTicks
            } else {
                val configuredMaxDurationSeconds = configuredMaxDurationRaw.toIntOrNull()
                    ?: error("$root.max-duration-seconds must be an integer (was '$configuredMaxDurationRaw')")
                require(configuredMaxDurationSeconds in 1..86_400) {
                    "$root.max-duration-seconds must be within 1..86400 seconds (or be omitted)"
                }
                configuredMaxDurationSeconds.toLong() * 20L
            }
            return OriginSceneCycle(
                id = id,
                actorIds = actorIds.toSet(),
                cooldownMillis = minOf(minimum, maximum)..maxOf(minimum, maximum),
                initialDelayMillis = boundedInteger(source, "$root.initial-delay-seconds", 10, 0..600).toLong() * 1_000L,
                yieldAnchor = source.string("$root.yield-anchor", "").takeIf(String::isNotBlank),
                yieldRange = boundedReal(source, "$root.yield-range", 2.5, 1.0..12.0),
                steps = steps,
                stepIds = stepIds,
                maxDurationTicks = maxDurationTicks,
            )
        }

        private fun parseStep(source: Config, root: String): OriginSceneStep = when (source.string("$root.type").uppercase(Locale.ROOT)) {
            "MOVE" -> OriginSceneStep.Move(
                actorId = source.integer("$root.actor-id"),
                anchor = source.string("$root.anchor"),
                routeProfile = source.string("$root.route-profile"),
                timeoutTicks = boundedInteger(source, "$root.timeout-ticks", 240, 20..1_200).toLong(),
            )
            "MOVE_GROUP" -> {
                val actorIds = distinctActorIds(source.stringList("$root.actor-ids"), "$root.actor-ids")
                val commonAnchor = source.string("$root.anchor", "").trim()
                val explicitAnchors = source.stringListOrNull("$root.anchors").orEmpty().map(String::trim)
                val targetAnchors = if (explicitAnchors.isEmpty()) {
                    require(commonAnchor.isNotBlank()) { "$root requires anchor or anchors" }
                    List(actorIds.size) { commonAnchor }
                } else {
                    explicitAnchors
                }
                OriginSceneStep.MoveGroup(
                    actorIds = actorIds,
                    targetAnchors = targetAnchors,
                    routeProfile = source.string("$root.route-profile"),
                    timeoutTicks = boundedInteger(source, "$root.timeout-ticks", 240, 20..1_200).toLong(),
                )
            }
            "WAIT" -> OriginSceneStep.Wait(boundedInteger(source, "$root.ticks", 20, 1..1_200).toLong())
            "LOOK_AT_ANCHOR" -> OriginSceneStep.LookAtAnchor(source.integer("$root.actor-id"), source.string("$root.anchor"))
            "LOOK_AT_SURFACE" -> OriginSceneStep.LookAtSurface(source.integer("$root.actor-id"), source.string("$root.surface"))
            "LOOK_AT_ACTOR" -> OriginSceneStep.LookAtActor(source.integer("$root.actor-id"), source.integer("$root.target-actor-id"))
            "EQUIP" -> OriginSceneStep.Equip(source.integer("$root.actor-id"), source.string("$root.material"))
            "SWING" -> OriginSceneStep.Swing(
                actorId = source.integer("$root.actor-id"),
                repetitions = boundedInteger(source, "$root.repetitions", 1, 1..20),
                periodTicks = boundedInteger(source, "$root.period-ticks", 10, 1..100).toLong(),
                feedbackAnchor = source.string("$root.feedback-anchor", "").takeIf(String::isNotBlank),
                feedbackSurface = source.string("$root.feedback-surface", "").takeIf(String::isNotBlank),
                damageTargetNpcId = optionalActorInteger(source, "$root.damage-target-npc-id", -1),
                damageAmount = boundedReal(source, "$root.damage-amount", 0.0, 0.0..20.0),
                particle = source.string("$root.particle", "").takeIf(String::isNotBlank),
                particleCount = boundedInteger(source, "$root.particle-count", 3, 1..50),
                particleEvery = boundedInteger(source, "$root.particle-every", 1, 1..20),
                sound = source.string("$root.sound", "").takeIf(String::isNotBlank),
                soundEvery = boundedInteger(source, "$root.sound-every", 1, 1..20),
                soundVolume = boundedReal(source, "$root.sound-volume", 0.35, 0.0..4.0).toFloat(),
                soundPitch = boundedReal(source, "$root.sound-pitch", 1.0, 0.5..2.0).toFloat(),
            )
            "BLOCK_DISPLAY" -> {
                val scale = vector(source.string("$root.scale", "0.5,0.1,0.3"), "$root.scale")
                OriginSceneStep.BlockDisplay(
                    key = source.string("$root.key"),
                    surface = source.string("$root.surface", "").takeIf(String::isNotBlank),
                    anchor = source.string("$root.anchor", "").takeIf(String::isNotBlank),
                    material = source.string("$root.material"),
                    origin = runCatching { OriginScenePropOrigin.valueOf(source.string("$root.origin").uppercase(Locale.ROOT)) }
                        .getOrElse { error("$root.origin must be BOTTOM_CENTER or CENTER") },
                    offset = vector(source.string("$root.offset", "0,0,0"), "$root.offset"),
                    scale = scale.requirePositive("$root.scale"),
                    rotationYDegrees = boundedReal(source, "$root.rotation-y-degrees", 0.0, -360.0..360.0).toFloat(),
                    interpolationTicks = boundedInteger(source, "$root.interpolation-ticks", 0, 0..59),
                    followActorId = optionalActorInteger(source, "$root.follow-actor-id", -1),
                    followOffset = vector(source.string("$root.follow-offset", "0,0,0"), "$root.follow-offset"),
                )
            }
            "ITEM_DISPLAY" -> OriginSceneStep.ItemDisplay(
                key = source.string("$root.key"),
                anchor = source.string("$root.anchor", "").trim().takeIf(String::isNotBlank),
                itemId = source.string("$root.item-id").trim(),
                context = runCatching {
                    OriginSceneItemDisplayContext.valueOf(source.string("$root.context", "GROUND").uppercase(Locale.ROOT))
                }.getOrElse { error("$root.context must be NONE, GROUND, FIXED or HEAD") },
                offset = vector(source.string("$root.offset", "0,0,0"), "$root.offset"),
                scale = vector(source.string("$root.scale", "1,1,1"), "$root.scale"),
                yawOffsetDegrees = boundedReal(source, "$root.yaw-offset-degrees", 0.0, -360.0..360.0).toFloat(),
                interpolationTicks = boundedInteger(source, "$root.interpolation-ticks", 4, 0..59),
                followActorId = optionalActorInteger(source, "$root.follow-actor-id", -1),
                followOffset = vector(source.string("$root.follow-offset", "0,0,0"), "$root.follow-offset"),
            )
            "REMOVE_DISPLAY" -> OriginSceneStep.RemoveDisplay(source.string("$root.key"))
            "SOUND" -> OriginSceneStep.Sound(
                actorId = optionalActorInteger(source, "$root.actor-id", -1),
                anchor = source.string("$root.anchor", "").takeIf(String::isNotBlank),
                sound = source.string("$root.sound"),
                volume = boundedReal(source, "$root.volume", 0.5, 0.0..4.0).toFloat(),
                pitch = boundedReal(source, "$root.pitch", 1.0, 0.5..2.0).toFloat(),
            )
            "PARTICLE" -> OriginSceneStep.Particle(
                actorId = optionalActorInteger(source, "$root.actor-id", -1),
                anchor = source.string("$root.anchor", "").takeIf(String::isNotBlank),
                particle = source.string("$root.particle"),
                count = boundedInteger(source, "$root.count", 3, 1..50),
            )
            "SPEECH" -> OriginSceneStep.Speech(source.integer("$root.actor-id"), source.string("$root.text"))
            "CONTAINER_LID" -> OriginSceneStep.ContainerLid(
                actorId = source.integer("$root.actor-id"),
                anchor = source.string("$root.anchor"),
                open = when (val state = source.string("$root.state").uppercase(Locale.ROOT)) {
                    "OPEN" -> true
                    "CLOSE" -> false
                    else -> error("Unknown container state $state at $root")
                },
            )
            "MOUNT" -> OriginSceneStep.Mount(source.integer("$root.rider-id"), source.integer("$root.vehicle-id"))
            "DISMOUNT" -> OriginSceneStep.Dismount(source.integer("$root.actor-id"))
            "POSE" -> OriginSceneStep.Pose(
                actorId = source.integer("$root.actor-id"),
                pose = runCatching { OriginScenePose.valueOf(source.string("$root.state").uppercase(Locale.ROOT)) }
                    .getOrElse { error("$root.state must be STAND, SIT, CAT_LIE or HORSE_GRAZE") },
            )
            else -> error("Unknown Origin scene step type at $root")
        }

        private fun routeProfile(source: Config, root: String, id: String): NpcRouteProfile {
            val minimum = block(source.string("$root.min-block"), "$root.min-block")
            val maximum = block(source.string("$root.max-block"), "$root.max-block")
            return NpcRouteProfile(
                id = id,
                floorY = source.integer("$root.floor-y"),
                bounds = NpcRouteBounds(minOf(minimum.first, maximum.first), maxOf(minimum.first, maximum.first), minOf(minimum.second, maximum.second), maxOf(minimum.second, maximum.second)),
                forbidden = source.stringList("$root.forbidden-areas").mapIndexed { index, raw -> bounds(raw, "$root.forbidden-areas[$index]") },
                preferred = source.stringList("$root.preferred-areas").mapIndexed { index, raw -> bounds(raw, "$root.preferred-areas[$index]") },
                maxVisited = boundedInteger(source, "$root.max-visited", 1_024, 64..4_096),
                snapRadius = boundedInteger(source, "$root.snap-radius", 3, 1..6),
                pollTicks = boundedInteger(source, "$root.poll-ticks", 2, 1..10).toLong(),
                stallPolls = boundedInteger(source, "$root.stall-polls", 24, 5..100),
                offFloorTolerance = boundedReal(source, "$root.off-floor-tolerance", 0.45, 0.1..1.0),
                distanceMargin = boundedReal(source, "$root.distance-margin", 0.35, 0.1..2.0),
                pathDistanceMargin = boundedReal(source, "$root.path-distance-margin", 0.35, 0.1..2.0),
                speedModifier = boundedReal(source, "$root.speed-modifier", 0.68, 0.1..2.0).toFloat(),
                entityObstaclePadding = boundedReal(source, "$root.entity-obstacle-padding", 0.25, 0.0..1.0),
                obstacleRefreshPolls = boundedInteger(source, "$root.obstacle-refresh-polls", 10, 1..100),
                headingLookAheadCells = boundedInteger(source, "$root.heading-look-ahead-cells", 2, 1..8),
                headingUpdateTicks = boundedInteger(source, "$root.heading-update-ticks", 1, 1..10).toLong(),
                headingMaxTurnDegreesPerTick = boundedReal(source, "$root.heading-max-turn-degrees-per-tick", 18.0, 1.0..90.0).toFloat(),
                cornerSmoothingDistance = boundedReal(source, "$root.corner-smoothing-distance", 0.75, 0.0..1.5),
                cornerSmoothingLead = boundedReal(source, "$root.corner-smoothing-lead", 0.30, 0.0..0.75),
                pathShortcutMaximumDeviation = boundedReal(source, "$root.path-shortcut-max-deviation", 1.0, 0.0..2.0),
                obstacleClearance = boundedReal(source, "$root.obstacle-clearance", 0.25, 0.0..0.5),
                maximumStepHeight = boundedReal(source, "$root.maximum-step-height", 0.125, 0.0..1.0),
                maximumSurfaceDrop = boundedReal(source, "$root.maximum-surface-drop", 0.0, 0.0..0.5),
                surfaceSearchRange = boundedInteger(source, "$root.surface-search-range", 0, 0..2),
                allowedSupportMaterials = source.stringList("$root.allowed-support-materials").mapIndexed { index, raw ->
                    val materialName = raw.trim().uppercase(Locale.ROOT)
                    val material = requireNotNull(Material.matchMaterial(materialName)) {
                        "Unknown material $raw at $root.allowed-support-materials[$index]"
                    }
                    require(material.isBlock) {
                        "Support material $raw at $root.allowed-support-materials[$index] must be a block"
                    }
                    material
                }.toSet(),
            )
        }

        private fun distinctIds(raw: List<String>, path: String): List<String> {
            require(raw.all(String::isNotBlank)) { "$path must not contain blank ids" }
            require(raw.distinct().size == raw.size) { "$path contains duplicate ids" }
            return raw
        }

        private fun distinctActorIds(raw: List<String>, path: String): List<Int> {
            val ids = raw.mapIndexed { index, value -> parseInt(value, "$path[$index]") }
            require(ids.all { it >= 0 }) { "$path must contain non-negative actor ids" }
            require(ids.distinct().size == ids.size) { "$path contains duplicate actor ids" }
            return ids
        }

        private fun boundedInteger(source: Config, path: String, default: Int, range: IntRange): Int {
            val value = parseInt(source.string(path, default.toString()).trim(), path)
            require(value in range) { "$path must be within ${range.first}..${range.last} (was $value)" }
            return value
        }

        private fun boundedReal(source: Config, path: String, default: Double, range: ClosedFloatingPointRange<Double>): Double {
            val value = parseDouble(source.string(path, default.toString()).trim(), path)
            require(value.isFinite() && value in range) {
                "$path must be finite and within ${range.start}..${range.endInclusive} (was $value)"
            }
            return value
        }

        private fun optionalActorInteger(source: Config, path: String, default: Int): Int? {
            val value = parseInt(source.string(path, default.toString()).trim(), path)
            require(value == -1 || value >= 0) { "$path must be -1 or a non-negative integer (was $value)" }
            return value.takeUnless { it == -1 }
        }

        private fun parseInt(raw: String, path: String): Int = raw.toIntOrNull()
            ?: error("$path must be an integer (was '$raw')")

        private fun parseDouble(raw: String, path: String): Double = raw.toDoubleOrNull()?.also {
            require(it.isFinite()) { "$path must be finite (was '$raw')" }
        } ?: error("$path must be a number (was '$raw')")

        private fun parseFloat(raw: String, path: String): Float = raw.toFloatOrNull()?.also {
            require(it.isFinite()) { "$path must be finite (was '$raw')" }
        } ?: error("$path must be a number (was '$raw')")

        private fun point(raw: String, path: String): OriginScenePoint {
            val values = raw.split(',').map(String::trim)
            require(values.size in 3..5) { "$path must be x,y,z[,yaw[,pitch]]" }
            return OriginScenePoint(
                x = parseDouble(values[0], "$path.x"),
                y = parseDouble(values[1], "$path.y"),
                z = parseDouble(values[2], "$path.z"),
                yaw = values.getOrNull(3)?.let { parseFloat(it, "$path.yaw") } ?: 0f,
                pitch = values.getOrNull(4)?.let { parseFloat(it, "$path.pitch") } ?: 0f,
                explicitPose = values.size >= 4,
            )
        }

        private fun vector(raw: String, path: String): OriginSceneVector {
            val values = raw.split(',').map(String::trim)
            require(values.size == 3) { "$path must be x,y,z" }
            return OriginSceneVector(
                parseDouble(values[0], "$path.x"),
                parseDouble(values[1], "$path.y"),
                parseDouble(values[2], "$path.z"),
            )
        }

        private fun block(raw: String, path: String): Pair<Int, Int> {
            val values = raw.split(',').map(String::trim)
            require(values.size == 2) { "$path must be x,z" }
            return parseInt(values[0], "$path.x") to parseInt(values[1], "$path.z")
        }

        private fun bounds(raw: String, path: String): NpcRouteBounds {
            val values = raw.split(',').map(String::trim)
            require(values.size == 4) { "$path must be min-x,min-z,max-x,max-z" }
            return NpcRouteBounds(
                minOf(parseInt(values[0], "$path.min-x"), parseInt(values[2], "$path.max-x")),
                maxOf(parseInt(values[0], "$path.min-x"), parseInt(values[2], "$path.max-x")),
                minOf(parseInt(values[1], "$path.min-z"), parseInt(values[3], "$path.max-z")),
                maxOf(parseInt(values[1], "$path.min-z"), parseInt(values[3], "$path.max-z")),
            )
        }
    }
}
