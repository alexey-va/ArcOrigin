package ru.arc.origin

import dev.lone.itemsadder.api.CustomStack
import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.event.CitizensEnableEvent
import net.citizensnpcs.api.npc.NPC
import net.citizensnpcs.trait.LookClose
import net.citizensnpcs.trait.SleepTrait
import net.citizensnpcs.trait.EntityPoseTrait
import net.citizensnpcs.api.trait.trait.Equipment as CitizensEquipment
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.World
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Pose
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Vector3f
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.PluginModule
import ru.arc.origin.OriginNpcHolograms
import ru.arc.npc.CitizensNpcRouteController
import ru.arc.npc.NpcRouteBounds
import ru.arc.npc.NpcRouteCell
import ru.arc.npc.NpcRouteEvent
import ru.arc.npc.NpcRouteObstacleSource
import ru.arc.npc.NpcRouteProfile
import ru.arc.origin.scene.faceOriginScenePoint
import ru.arc.origin.scene.originFurnitureObstacleCells
import ru.arc.paper.display.PacketItemDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.origin.util.SoundUtils
import java.nio.file.Path
import java.util.Locale
import java.util.UUID
import java.util.logging.Level
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** A world-space workshop anchor. Route anchors are validated as cell-centered floor points. */
internal data class OriginFurnitureWorkshopPoint(
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float = 0f,
    val pitch: Float = 0f,
) {
    init {
        require(x.isFinite() && x in -30_000_000.0..30_000_000.0) { "workshop point x is invalid" }
        require(y.isFinite() && y in -64.0..320.0) { "workshop point y is invalid" }
        require(z.isFinite() && z in -30_000_000.0..30_000_000.0) { "workshop point z is invalid" }
        require(yaw.isFinite() && pitch.isFinite() && pitch in -90f..90f) { "workshop point pose is invalid" }
    }

    fun inWorld(world: World): Location = Location(world, x, y, z, yaw, pitch)

    fun cell(): NpcRouteCell = NpcRouteCell(floor(x).toInt(), floor(z).toInt())

    fun sameCell(other: OriginFurnitureWorkshopPoint): Boolean = cell() == other.cell()
}

internal data class OriginFurnitureWorkshopCarryAnchor(
    val x: Double,
    val z: Double,
    val yaw: Float,
)

/** Places a carried display in front of the actor while keeping its configured facing offset. */
internal fun originFurnitureWorkshopCarryAnchor(
    x: Double,
    z: Double,
    actorYaw: Float,
    forwardDistance: Double,
    yawOffset: Float,
): OriginFurnitureWorkshopCarryAnchor {
    require(x.isFinite() && z.isFinite() && actorYaw.isFinite() && yawOffset.isFinite()) {
        "workshop carry pose must be finite"
    }
    require(forwardDistance.isFinite() && forwardDistance >= 0.0) {
        "workshop carry forward-distance must be finite and non-negative"
    }
    val radians = Math.toRadians(actorYaw.toDouble())
    return OriginFurnitureWorkshopCarryAnchor(
        x = x - sin(radians) * forwardDistance,
        z = z + cos(radians) * forwardDistance,
        yaw = actorYaw + yawOffset,
    )
}

internal data class OriginFurnitureWorkshopLeg(
    val point: OriginFurnitureWorkshopPoint,
    val timeoutTicks: Long,
) {
    init {
        require(timeoutTicks in 20L..1_200L) { "workshop route timeout must be within 20..1200 ticks" }
    }
}

internal data class OriginFurnitureWorkshopPart(
    val material: Material,
    val scale: Double,
    val source: OriginFurnitureWorkshopPoint,
    val join: OriginFurnitureWorkshopPoint,
    val convergeDelayTicks: Long,
) {
    init {
        require(material.isItem && !material.isAir) { "workshop part material must be an item" }
        require(scale.isFinite() && scale in 0.01..4.0) { "workshop part scale must be within 0.01..4" }
        require(convergeDelayTicks in 0L..120L) { "workshop part converge delay must be within 0..120 ticks" }
    }
}

internal data class OriginFurnitureWorkshopBeat(
    val phase: String,
    val mechanism: OriginWorkshopMechanism,
    val approach: OriginFurnitureWorkshopPoint?,
    val hand: Material,
    val offhand: Material,
    val focus: OriginFurnitureWorkshopPoint,
    val rootAt: OriginFurnitureWorkshopPoint,
    val rootScale: Double,
    val strokes: Int,
    val strokeGapTicks: Long,
    val durationTicks: Long,
    val converge: Boolean,
    val particleMaterial: Material,
    val particleQuantity: Int,
    val sound: Sound,
    val soundPitch: Float,
) {
    init {
        require(phase.matches(Regex("[a-z0-9_-]{1,32}"))) { "invalid workshop phase '$phase'" }
        require(hand.isItem && !hand.isAir && offhand.isItem && !offhand.isAir) {
            "workshop beat equipment must be item materials"
        }
        require(rootScale.isFinite() && rootScale in 0.01..4.0) { "workshop beat root scale must be within 0.01..4" }
        require(strokes in 1..20 && strokeGapTicks in 1L..100L) { "workshop beat strokes are invalid" }
        require(mechanism == OriginWorkshopMechanism.NONE || strokeGapTicks >= 10L) {
            "workshop machinery strokes need at least 10 ticks"
        }
        require(durationTicks in 1L..1_200L) { "workshop beat duration must be within 1..1200 ticks" }
        require(particleMaterial.isItem && !particleMaterial.isAir) { "workshop particle must be an item material" }
        require(particleQuantity in 1..50) { "workshop particle quantity must be within 1..50" }
        require(soundPitch.isFinite() && soundPitch in 0.5f..2f) { "workshop sound pitch must be within 0.5..2" }
    }
}

internal enum class OriginFurnitureWorkshopRole(val key: String, val npcId: Int) {
    CARPENTER("carpenter", 430),
    UPHOLSTERER("upholsterer", 458),
    ASSEMBLER("assembler", 459),
    FINISHER("finisher", 460),
}

internal enum class OriginFurnitureWorkshopSleepRequestResult {
    QUEUED,
    ALREADY_SLEEPING,
    UNKNOWN_NPC,
    NPC_UNAVAILABLE,
    SHIFT_UNAVAILABLE,
    WORKSHOP_UNAVAILABLE,
}

internal fun originWorkshopSleepNpcIds(): List<Int> =
    OriginFurnitureWorkshopRole.entries.map(OriginFurnitureWorkshopRole::npcId)

internal data class OriginFurnitureWorkshopWorker(
    val role: OriginFurnitureWorkshopRole,
    val tableId: String,
    val home: OriginFurnitureWorkshopPoint,
    val pickup: OriginFurnitureWorkshopPoint,
    val pickupSource: OriginFurnitureWorkshopPoint,
    val workApproach: OriginFurnitureWorkshopPoint,
    val bench: OriginFurnitureWorkshopPoint,
    val restFocus: OriginFurnitureWorkshopPoint,
    val outputApproach: OriginFurnitureWorkshopPoint,
    val output: OriginFurnitureWorkshopPoint,
    val pickupRoute: List<OriginFurnitureWorkshopLeg>,
    val workReturnRoute: List<OriginFurnitureWorkshopLeg>,
    val outputRoute: List<OriginFurnitureWorkshopLeg>,
    val homeReturnRoute: List<OriginFurnitureWorkshopLeg>,
    val initialDelayTicks: Long,
    val cycleDelayTicks: Long,
    val finishedHoldTicks: Long,
    val rawHand: Material,
    val rawRootMaterial: Material,
    val rawRootScale: Double,
    val deliverOutput: Boolean,
    val productId: String,
    val finishedScale: Double,
    val finishedTranslationY: Double,
    val parts: List<OriginFurnitureWorkshopPart>,
    val beats: List<OriginFurnitureWorkshopBeat>,
) {
    init {
        require(tableId.matches(Regex("[a-z0-9_-]{1,48}"))) { "invalid workshop table id '$tableId'" }
        require(initialDelayTicks in 0L..12_000L) { "workshop initial delay must be within 0..12000 ticks" }
        require(cycleDelayTicks in 200L..72_000L) { "workshop cycle delay must be within 200..72000 ticks" }
        require(finishedHoldTicks in 1L..1_200L) { "workshop finished hold must be within 1..1200 ticks" }
        require(rawHand.isItem && !rawHand.isAir && rawRootMaterial.isItem && !rawRootMaterial.isAir) {
            "workshop raw materials must be items"
        }
        require(rawRootScale.isFinite() && rawRootScale in 0.01..4.0) { "workshop raw root scale must be within 0.01..4" }
        require(productId.contains(':') && productId.matches(Regex("[a-z0-9_.-]+:[a-z0-9_./-]+"))) {
            "workshop product id must be namespaced"
        }
        require(finishedScale.isFinite() && finishedScale in 0.01..4.0) { "workshop finished scale must be within 0.01..4" }
        require(finishedTranslationY.isFinite() && finishedTranslationY in -4.0..4.0) {
            "workshop finished translation-y must be within -4..4"
        }
        require(parts.size in 1..4 && beats.size in 1..8) { "workshop role needs 1..4 parts and 1..8 beats" }
    }
}

internal data class OriginFurnitureWorkshopSettings(
    val enabled: Boolean,
    val world: String,
    val dispatcherIntervalTicks: Long,
    val viewerRadius: Double,
    val activeTimeoutTicks: Long,
    val pickupDurationTicks: Long,
    val pickupSound: Sound,
    val soundVolume: Float,
    val propInterpolationTicks: Int,
    val machineUpdateTicks: Long,
    val workstationRouteTimeoutTicks: Long,
    val carryScale: Double,
    val carryOffsetY: Double,
    val carryTranslationY: Double,
    val carryForwardDistance: Double,
    val carryYawOffset: Float,
    val carryPitch: Float,
    val routeProfile: NpcRouteProfile,
    val workers: List<OriginFurnitureWorkshopWorker>,
    val sleepDurationTicks: Long,
    val sleepApproach: OriginFurnitureWorkshopPoint,
    val sleepAt: OriginFurnitureWorkshopPoint,
) {
    init {
        require(world == WORKSHOP_WORLD) { "workshop world must be '$WORKSHOP_WORLD'" }
        require(dispatcherIntervalTicks in 1L..200L) { "workshop dispatcher interval must be within 1..200 ticks" }
        require(viewerRadius.isFinite() && viewerRadius in 4.0..64.0) { "workshop viewer radius must be within 4..64" }
        require(activeTimeoutTicks in 100L..12_000L) { "workshop active timeout must be within 100..12000 ticks" }
        require(pickupDurationTicks in 1L..100L) { "workshop pickup duration must be within 1..100 ticks" }
        require(soundVolume.isFinite() && soundVolume in 0f..4f) { "workshop sound volume must be within 0..4" }
        require(propInterpolationTicks in 0..59) { "workshop interpolation must be within 0..59 ticks" }
        require(machineUpdateTicks in 1L..5L) { "workshop machinery update must be within 1..5 ticks" }
        require(workstationRouteTimeoutTicks in 20L..400L) { "workstation route timeout must be within 20..400 ticks" }
        require(carryScale.isFinite() && carryScale in 0.01..4.0) { "workshop carry scale must be within 0.01..4" }
        require(carryOffsetY.isFinite() && carryOffsetY in -4.0..4.0) { "workshop carry offset-y must be within -4..4" }
        require(carryTranslationY.isFinite() && carryTranslationY in -4.0..4.0) {
            "workshop carry translation-y must be within -4..4"
        }
        require(carryForwardDistance.isFinite() && carryForwardDistance in 0.0..2.0) {
            "workshop carry forward-distance must be within 0..2"
        }
        require(carryYawOffset.isFinite() && carryYawOffset in -360f..360f && carryPitch.isFinite() && carryPitch in -90f..90f) {
            "workshop carry pose is invalid"
        }
        require(workers.map { it.role }.toSet() == OriginFurnitureWorkshopRole.entries.toSet()) {
            "workshop must configure all four worker roles"
        }
        require(workers.map { it.role.npcId }.distinct().size == workers.size) { "workshop NPC ids must be unique" }
        require(workers.map { it.tableId }.distinct().size == workers.size) { "parallel workshop workers need distinct tables" }
        require(routeProfile.snapRadius in 0..8) { "workshop route snap-radius must be within 0..8 cells" }
        require(sleepDurationTicks in 200L..72_000L) { "workshop sleep duration must be within 200..72000 ticks" }
        require(isWorkshopCellCenter(sleepApproach.x) && isWorkshopCellCenter(sleepApproach.z) &&
            floor(sleepApproach.y).toInt() == routeProfile.floorY && routeProfile.allows(sleepApproach.cell())) {
            "workshop sleep approach must be a safe centered route point"
        }
        val sleepDx = sleepApproach.x - sleepAt.x
        val sleepDz = sleepApproach.z - sleepAt.z
        require(sleepDx * sleepDx + sleepDz * sleepDz <= 16.0 && abs(sleepAt.y - sleepApproach.y) <= 1.0) {
            "workshop sleep seat must be near its approach"
        }
        validateOriginFurnitureWorkshopRoutes(workers, routeProfile)
    }

    companion object {
        private const val RESOURCE = "origin-furniture-workshop.yml"
        private const val ROOT = "origin-furniture-workshop"

        fun load(dataPath: Path, mergeDefaults: Boolean = true): OriginFurnitureWorkshopSettings {
            val source = ConfigManager.ofModule(dataPath, RESOURCE)
            if (mergeDefaults) source.mergeMissingFromBundled("modules/$RESOURCE")
            val root = ROOT
            val routeRoot = "$root.route"
            val routeProfile = NpcRouteProfile(
                id = "${root}-workshop",
                floorY = source.int("$routeRoot.floor-y", 71),
                bounds = NpcRouteBounds(
                    source.int("$routeRoot.bounds.min-x", -55),
                    source.int("$routeRoot.bounds.max-x", -38),
                    source.int("$routeRoot.bounds.min-z", -76),
                    source.int("$routeRoot.bounds.max-z", -49),
                ),
                forbidden = readBounds(source, "$routeRoot.forbidden"),
                preferred = readBounds(source, "$routeRoot.preferred"),
                maxVisited = source.int("$routeRoot.max-visited", 1_024),
                snapRadius = source.int("$routeRoot.snap-radius", 3),
                pollTicks = source.long("$routeRoot.poll-ticks", 2L),
                stallPolls = source.int("$routeRoot.stall-polls", 20),
                offFloorTolerance = source.real("$routeRoot.off-floor-tolerance", 0.45),
                distanceMargin = source.real("$routeRoot.distance-margin", 0.35),
                pathDistanceMargin = source.real("$routeRoot.path-distance-margin", 0.35),
                speedModifier = source.real("$routeRoot.speed", 0.65).toFloat(),
                entityObstaclePadding = source.real("$routeRoot.entity-obstacle-padding", 0.25),
                obstacleRefreshPolls = source.int("$routeRoot.obstacle-refresh-polls", 10),
                headingLookAheadCells = source.int("$routeRoot.heading-look-ahead-cells", 2),
                headingUpdateTicks = source.long("$routeRoot.heading-update-ticks", 1L),
                headingMaxTurnDegreesPerTick = source.real("$routeRoot.heading-max-turn-degrees-per-tick", 18.0).toFloat(),
                cornerSmoothingDistance = source.real("$routeRoot.corner-smoothing-distance", 0.75),
                cornerSmoothingLead = source.real("$routeRoot.corner-smoothing-lead", 0.30),
                pathShortcutMaximumDeviation = source.real("$routeRoot.path-shortcut-max-deviation", 1.0),
                obstacleClearance = source.real("$routeRoot.obstacle-clearance", 0.25),
                maximumStepHeight = source.real("$routeRoot.maximum-step-height", 0.125),
            )
            val workers = OriginFurnitureWorkshopRole.entries.map { role -> parseWorker(source, "$root.actors.${role.key}", role) }
            return OriginFurnitureWorkshopSettings(
                enabled = source.bool("$root.enabled", false),
                world = source.string("$root.world", WORKSHOP_WORLD).trim(),
                dispatcherIntervalTicks = source.long("$root.dispatcher-interval-ticks", 60L),
                viewerRadius = source.real("$root.viewer-radius", 24.0),
                activeTimeoutTicks = source.long("$root.active-timeout-ticks", 1_800L),
                pickupDurationTicks = source.long("$root.pickup-duration-ticks", 8L),
                pickupSound = readSound(source.string("$root.pickup-sound", "BLOCK_WOOD_PLACE"), "$root.pickup-sound"),
                soundVolume = source.real("$root.sound-volume", 0.22).toFloat(),
                propInterpolationTicks = source.int("$root.prop-interpolation-ticks", 6),
                machineUpdateTicks = source.long("$root.machine-update-ticks", 2L),
                workstationRouteTimeoutTicks = source.long("$root.workstation-route-timeout-ticks", 120L),
                carryScale = source.real("$root.carry.scale", 0.34),
                carryOffsetY = source.real("$root.carry.offset-y", 0.7),
                carryTranslationY = source.real("$root.carry.translation-y", 0.18),
                carryForwardDistance = source.real("$root.carry.forward-distance", 0.4),
                carryYawOffset = source.real(
                    "$root.carry.yaw-offset",
                    source.real("$root.carry.yaw", 90.0),
                ).toFloat(),
                carryPitch = source.real("$root.carry.pitch", 0.0).toFloat(),
                routeProfile = routeProfile,
                workers = workers,
                sleepDurationTicks = source.long("$root.sleep-rotation.duration-ticks", 2_400L),
                sleepApproach = readPoint(source, "$root.sleep-rotation.approach"),
                sleepAt = readPoint(source, "$root.sleep-rotation.at"),
            )
        }

        private fun parseWorker(
            config: Config,
            path: String,
            role: OriginFurnitureWorkshopRole,
        ): OriginFurnitureWorkshopWorker {
            val configuredId = config.int("$path.npc-id", role.npcId)
            require(configuredId == role.npcId) { "$path.npc-id must remain the existing NPC ${role.npcId}" }
            val routes = "$path.routes"
            return OriginFurnitureWorkshopWorker(
                role = role,
                tableId = config.string("$path.table-id", role.key),
                home = readPoint(config, "$path.home"),
                pickup = readPoint(config, "$path.pickup"),
                pickupSource = readPoint(config, "$path.pickup-source"),
                workApproach = readPoint(config, "$path.work-approach"),
                bench = readPoint(config, "$path.bench"),
                restFocus = readPoint(config, "$path.rest-focus"),
                outputApproach = readPoint(config, "$path.output-approach"),
                output = readPoint(config, "$path.output"),
                pickupRoute = readLegs(config, "$routes.pickup"),
                workReturnRoute = readLegs(config, "$routes.work-return"),
                outputRoute = readLegs(config, "$routes.output"),
                homeReturnRoute = readLegs(config, "$routes.home-return"),
                initialDelayTicks = config.long("$path.initial-delay-ticks", 0L),
                cycleDelayTicks = config.long("$path.cycle-delay-ticks", 700L),
                finishedHoldTicks = config.long("$path.finished-hold-ticks", 60L),
                rawHand = readMaterial(config.string("$path.raw-hand"), "$path.raw-hand"),
                rawRootMaterial = readMaterial(config.string("$path.raw-root-material"), "$path.raw-root-material"),
                rawRootScale = config.real("$path.raw-root-scale", 0.32),
                deliverOutput = config.bool("$path.deliver-output", true),
                productId = config.string("$path.product-id"),
                finishedScale = config.real("$path.finished-scale", 1.0),
                finishedTranslationY = config.real("$path.finished-translation-y", 0.5),
                parts = readParts(config, "$path.parts"),
                beats = readBeats(config, "$path.beats"),
            )
        }

        private fun readPoint(config: Config, path: String): OriginFurnitureWorkshopPoint {
            val map = readMap(config, path)
            return OriginFurnitureWorkshopPoint(
                x = requiredDouble(map, "x", path),
                y = requiredDouble(map, "y", path),
                z = requiredDouble(map, "z", path),
                yaw = optionalDouble(map, "yaw", 0.0).toFloat(),
                pitch = optionalDouble(map, "pitch", 0.0).toFloat(),
            )
        }

        private fun readLegs(config: Config, path: String): List<OriginFurnitureWorkshopLeg> = readList(config, path).mapIndexed { index, value ->
            val map = requireMap(value, "$path[$index]")
            val pointMap = requireMap(map["point"], "$path[$index].point")
            OriginFurnitureWorkshopLeg(
                point = OriginFurnitureWorkshopPoint(
                    requiredDouble(pointMap, "x", "$path[$index].point"),
                    requiredDouble(pointMap, "y", "$path[$index].point"),
                    requiredDouble(pointMap, "z", "$path[$index].point"),
                    optionalDouble(pointMap, "yaw", 0.0).toFloat(),
                    optionalDouble(pointMap, "pitch", 0.0).toFloat(),
                ),
                timeoutTicks = requiredLong(map, "timeout-ticks", "$path[$index]"),
            )
        }

        private fun readParts(config: Config, path: String): List<OriginFurnitureWorkshopPart> = readList(config, path).mapIndexed { index, value ->
            val map = requireMap(value, "$path[$index]")
            OriginFurnitureWorkshopPart(
                material = readMaterial(requiredString(map, "material", "$path[$index]"), "$path[$index].material"),
                scale = requiredDouble(map, "scale", "$path[$index]"),
                source = pointFromMap(requireMap(map["source"], "$path[$index].source"), "$path[$index].source"),
                join = pointFromMap(requireMap(map["join"], "$path[$index].join"), "$path[$index].join"),
                convergeDelayTicks = requiredLong(map, "converge-delay-ticks", "$path[$index]"),
            )
        }

        private fun readBeats(config: Config, path: String): List<OriginFurnitureWorkshopBeat> = readList(config, path).mapIndexed { index, value ->
            val map = requireMap(value, "$path[$index]")
            val beatPath = "$path[$index]"
            val gesture = requiredString(map, "gesture", beatPath).uppercase(Locale.ROOT)
            require(gesture == "ARM_SWING") { "$beatPath.gesture currently supports ARM_SWING only" }
            OriginFurnitureWorkshopBeat(
                phase = requiredString(map, "phase", beatPath).lowercase(Locale.ROOT),
                mechanism = OriginWorkshopMechanism.valueOf(
                    (if (map.containsKey("mechanism")) requiredString(map, "mechanism", beatPath) else "none").uppercase(Locale.ROOT),
                ),
                approach = map["approach"]?.let { pointFromMap(requireMap(it, "$beatPath.approach"), "$beatPath.approach") },
                hand = readMaterial(requiredString(map, "hand", beatPath), "$beatPath.hand"),
                offhand = readMaterial(requiredString(map, "offhand", beatPath), "$beatPath.offhand"),
                focus = pointFromMap(requireMap(map["focus"], "$beatPath.focus"), "$beatPath.focus"),
                rootAt = pointFromMap(requireMap(map["root-at"], "$beatPath.root-at"), "$beatPath.root-at"),
                rootScale = requiredDouble(map, "root-scale", beatPath),
                strokes = requiredLong(map, "strokes", beatPath).toInt(),
                strokeGapTicks = requiredLong(map, "stroke-gap-ticks", beatPath),
                durationTicks = requiredLong(map, "duration-ticks", beatPath),
                converge = map["converge"] as? Boolean ?: false,
                particleMaterial = readMaterial(requiredString(map, "particle-material", beatPath), "$beatPath.particle-material"),
                particleQuantity = requiredLong(map, "particle-quantity", beatPath).toInt(),
                sound = readSound(requiredString(map, "sound", beatPath), "$beatPath.sound"),
                soundPitch = requiredDouble(map, "sound-pitch", beatPath).toFloat(),
            )
        }

        private fun pointFromMap(map: Map<*, *>, path: String) = OriginFurnitureWorkshopPoint(
            requiredDouble(map, "x", path),
            requiredDouble(map, "y", path),
            requiredDouble(map, "z", path),
            optionalDouble(map, "yaw", 0.0).toFloat(),
            optionalDouble(map, "pitch", 0.0).toFloat(),
        )

        private fun readBounds(config: Config, path: String): List<NpcRouteBounds> = readList(config, path).mapIndexed { index, value ->
            val map = requireMap(value, "$path[$index]")
            NpcRouteBounds(
                requiredLong(map, "min-x", "$path[$index]").toInt(),
                requiredLong(map, "max-x", "$path[$index]").toInt(),
                requiredLong(map, "min-z", "$path[$index]").toInt(),
                requiredLong(map, "max-z", "$path[$index]").toInt(),
            )
        }

        private fun readList(config: Config, path: String): List<Any?> = config.list<Any?>(path)

        private fun readMap(config: Config, path: String): Map<String, Any?> =
            requireMap(config.map<Any?>(path), path)

        private fun requireMap(value: Any?, path: String): Map<String, Any?> = requireMap(value as? Map<*, *>, path)

        private fun requireMap(value: Map<*, *>?, path: String): Map<String, Any?> {
            require(value != null) { "$path must be a mapping" }
            return value.entries.associate { (key, item) ->
                require(key is String) { "$path contains a non-string key" }
                key to item
            }
        }

        private fun requiredString(map: Map<*, *>, key: String, path: String): String =
            map[key] as? String ?: error("$path.$key is required")

        private fun requiredDouble(map: Map<*, *>, key: String, path: String): Double =
            (map[key] as? Number)?.toDouble() ?: (map[key] as? String)?.toDoubleOrNull()
            ?: error("$path.$key must be numeric")

        private fun optionalDouble(map: Map<*, *>, key: String, default: Double): Double =
            (map[key] as? Number)?.toDouble() ?: (map[key] as? String)?.toDoubleOrNull() ?: default

        private fun requiredLong(map: Map<*, *>, key: String, path: String): Long =
            (map[key] as? Number)?.toLong() ?: (map[key] as? String)?.toLongOrNull()
            ?: error("$path.$key must be an integer")

        private fun readMaterial(value: String, path: String): Material =
            Material.matchMaterial(value.trim().uppercase(Locale.ROOT))
                ?: error("$path has unknown material '$value'")

        private fun readSound(value: String, path: String): Sound =
            SoundUtils.getSound(value.trim()) ?: error("$path has unknown sound '$value'")
    }
}

/** Checks the authored route endpoints and hard walls without touching Bukkit world state. */
internal fun validateOriginFurnitureWorkshopRoutes(
    workers: List<OriginFurnitureWorkshopWorker>,
    profile: NpcRouteProfile,
) {
    workers.forEach { worker ->
        worker.beats.forEach { beat ->
            beat.approach?.let { point ->
                require(profile.allows(point.cell()) && floor(point.y).toInt() == profile.floorY &&
                    isWorkshopCellCenter(point.x) && isWorkshopCellCenter(point.z)) {
                    "${worker.role.key} beat ${beat.phase} approach must be a safe centered floor cell"
                }
            }
        }
        val routes = listOf(
            "pickup" to (worker.pickupRoute to worker.pickup),
            "work-return" to (worker.workReturnRoute to worker.workApproach),
            "output" to (worker.outputRoute to worker.outputApproach),
            "home-return" to (worker.homeReturnRoute to worker.home),
        )
        routes.forEach { (name, routeAndTarget) ->
            val (legs, target) = routeAndTarget
            require(legs.isNotEmpty()) { "${worker.role.key} route $name must contain at least one leg" }
            require(legs.last().point.sameCell(target)) {
                "${worker.role.key} route $name must end at its authored endpoint"
            }
            legs.forEachIndexed { index, leg ->
                val cell = leg.point.cell()
                require(profile.allows(cell)) { "${worker.role.key} route $name leg ${index + 1} enters a forbidden or out-of-bounds cell $cell" }
                require(floor(leg.point.y).toInt() == profile.floorY) {
                    "${worker.role.key} route $name leg ${index + 1} must stay on floor ${profile.floorY}"
                }
                require(isWorkshopCellCenter(leg.point.x) && isWorkshopCellCenter(leg.point.z)) {
                    "${worker.role.key} route $name leg ${index + 1} must be centered in its floor cell"
                }
            }
        }
    }
}

internal fun isWorkshopCellCenter(value: Double): Boolean = abs(value - (floor(value) + 0.5)) <= 1.0e-6

private const val WORKSHOP_SLEEP_NAME_OWNER = "origin-workshop-sleep"

internal object OriginFurnitureWorkshopModule : PluginModule, Listener {
    override val name = "OriginFurnitureWorkshop"
    override val priority = 26

    private var runtime: OriginFurnitureWorkshopRuntime? = null

    internal fun availablePlayerTable(): String? = runtime?.availablePlayerTable()
    internal fun productIdFor(tableId: String): String? = runtime?.productIdFor(tableId)
    internal fun acquirePlayerTable(tableId: String, playerId: UUID): Boolean =
        runtime?.acquirePlayerTable(tableId, playerId) == true
    internal fun ownsPlayerTable(tableId: String, playerId: UUID): Boolean =
        runtime?.ownsPlayerTable(tableId, playerId) == true
    internal fun releasePlayerTable(tableId: String, playerId: UUID) {
        runtime?.releasePlayerTable(tableId, playerId)
    }
    internal fun requestSleep(npcId: Int): OriginFurnitureWorkshopSleepRequestResult =
        runtime?.requestSleep(npcId) ?: OriginFurnitureWorkshopSleepRequestResult.WORKSHOP_UNAVAILABLE
    private var startupTasks: LifecycleTaskScope? = null
    private var pendingSettings: OriginFurnitureWorkshopSettings? = null
    private var startupGeneration = 0L
    private var startupChecks = 0
    private var listenerRegistered = false

    override fun init() {
        registerCitizensListenerIfReady()
        reload()
    }

    override fun reload() {
        cancelPendingStartup()
        val settings = try {
            OriginFurnitureWorkshopSettings.load(ArcOriginPlugin.instance.legacyDataPath, mergeDefaults = false)
        } catch (failure: Exception) {
            ArcOriginPlugin.instance.logger.log(Level.WARNING, "Origin furniture workshop config rejected; keeping current runtime", failure)
            return
        }
        if (!settings.enabled) {
            runtime?.close()
            runtime = null
            ArcOriginPlugin.instance.logger.info("ORIGIN_WORKSHOP phase=DISABLED reason=config")
            return
        }

        when (prepareAndStart(settings)) {
            WorkshopStartupAttempt.STARTED, WorkshopStartupAttempt.FAILED -> Unit
            WorkshopStartupAttempt.WAITING_FOR_CITIZENS -> deferUntilCitizensReady(settings)
        }
    }

    @EventHandler
    fun onCitizensEnable(event: CitizensEnableEvent) {
        val settings = pendingSettings ?: return
        when (prepareAndStart(settings)) {
            WorkshopStartupAttempt.STARTED -> {
                finishPendingStartup()
                ArcOriginPlugin.instance.logger.info("ORIGIN_WORKSHOP phase=STARTED source=citizens-enable-event")
            }
            WorkshopStartupAttempt.FAILED -> finishPendingStartup()
            WorkshopStartupAttempt.WAITING_FOR_CITIZENS -> Unit
        }
    }

    private fun prepareAndStart(settings: OriginFurnitureWorkshopSettings): WorkshopStartupAttempt {
        if (!citizensRegistryContainsWorkers(settings)) return WorkshopStartupAttempt.WAITING_FOR_CITIZENS
        val prepared = try {
            OriginFurnitureWorkshopRuntime.prepare(settings)
        } catch (failure: Exception) {
            ArcOriginPlugin.instance.logger.log(Level.WARNING, "Origin furniture workshop could not prepare; keeping current runtime", failure)
            return WorkshopStartupAttempt.FAILED
        }
        runtime?.close()
        runtime = try {
            prepared.also(OriginFurnitureWorkshopRuntime::start)
        } catch (failure: Exception) {
            prepared.close()
            ArcOriginPlugin.instance.logger.log(Level.WARNING, "Origin furniture workshop failed to start", failure)
            null
        }
        return if (runtime === prepared) WorkshopStartupAttempt.STARTED else WorkshopStartupAttempt.FAILED
    }

    private fun citizensRegistryContainsWorkers(settings: OriginFurnitureWorkshopSettings): Boolean {
        if (!Bukkit.getPluginManager().isPluginEnabled("Citizens") || !CitizensAPI.hasImplementation()) return false
        return runCatching {
            val registry = CitizensAPI.getNPCRegistry()
            settings.workers.all { registry.getById(it.role.npcId) != null }
        }.getOrDefault(false)
    }

    private fun deferUntilCitizensReady(settings: OriginFurnitureWorkshopSettings) {
        pendingSettings = settings
        val generation = startupGeneration
        startupChecks = 0
        startupTasks = LifecycleTaskScope().also { tasks ->
            ArcOriginPlugin.instance.logger.info(
                "ORIGIN_WORKSHOP phase=WAITING_FOR_CITIZENS check_interval_ticks=$STARTUP_CHECK_INTERVAL_TICKS max_checks=$MAX_STARTUP_CHECKS",
            )
            tasks.runTimer(1L, STARTUP_CHECK_INTERVAL_TICKS) {
                if (generation != startupGeneration || pendingSettings !== settings) return@runTimer
                registerCitizensListenerIfReady()
                startupChecks++
                when (prepareAndStart(settings)) {
                    WorkshopStartupAttempt.STARTED -> {
                        val checks = startupChecks
                        finishPendingStartup()
                        ArcOriginPlugin.instance.logger.info("ORIGIN_WORKSHOP phase=STARTED source=registry-readiness-check checks=$checks")
                    }
                    WorkshopStartupAttempt.FAILED -> finishPendingStartup()
                    WorkshopStartupAttempt.WAITING_FOR_CITIZENS -> if (startupChecks >= MAX_STARTUP_CHECKS) {
                        finishPendingStartup()
                        ArcOriginPlugin.instance.logger.warning(
                            "ORIGIN_WORKSHOP phase=STARTUP_EXHAUSTED checks=$MAX_STARTUP_CHECKS reason=required-npcs-not-in-registry",
                        )
                    }
                }
            }
        }
    }

    private fun registerCitizensListenerIfReady() {
        if (listenerRegistered || !Bukkit.getPluginManager().isPluginEnabled("Citizens")) return
        Bukkit.getPluginManager().registerEvents(this, ArcOriginPlugin.instance)
        listenerRegistered = true
    }

    private fun finishPendingStartup() {
        startupTasks?.close()
        startupTasks = null
        pendingSettings = null
        startupChecks = 0
    }

    private fun cancelPendingStartup() {
        startupGeneration++
        finishPendingStartup()
    }

    override fun shutdown() {
        cancelPendingStartup()
        runtime?.close()
        runtime = null
        if (listenerRegistered) HandlerList.unregisterAll(this)
        listenerRegistered = false
    }

    private enum class WorkshopStartupAttempt { STARTED, WAITING_FOR_CITIZENS, FAILED }

    private const val STARTUP_CHECK_INTERVAL_TICKS = 20L
    private const val MAX_STARTUP_CHECKS = 30
}

/** A busy or delayed worker must not hold up the other, spatially separate stations. */
internal fun workshopWorkersDue(
    workers: List<OriginFurnitureWorkshopWorker>,
    nextDueTick: Map<OriginFurnitureWorkshopRole, Long>,
    activeRoles: Set<OriginFurnitureWorkshopRole>,
    tick: Long,
): List<OriginFurnitureWorkshopWorker> = workers.filter { worker ->
    worker.role !in activeRoles && tick >= nextDueTick.getValue(worker.role)
}

private class WorkshopSleepJourney(val workerIndex: Int, val leaveBed: Boolean = true) {
    var started = false
    var arrived = false
    var deadline = 0L
    var retryAt = 0L
}

private enum class WorkshopAfterRoute { PICKUP, WORK_RETURN, WORKSTATION, OUTPUT, HOME }

private enum class WorkshopStage { ROUTING, PICKUP_HOLD, ASSEMBLY, BENCH_HOLD, STOCK_HOLD }

private data class WorkshopPropKey(val role: OriginFurnitureWorkshopRole, val token: UUID, val key: String)

private class ActiveWorkshopCycle(
    val worker: OriginFurnitureWorkshopWorker,
    val actor: NPC,
    val token: UUID,
    val deadlineTick: Long,
    val mainHand: ItemStack?,
    val offHand: ItemStack?,
) {
    var stage = WorkshopStage.ROUTING
    var afterRoute = WorkshopAfterRoute.PICKUP
    var route: List<OriginFurnitureWorkshopLeg> = emptyList()
    var routeIndex = 0
    var legDeadlineTick = 0L
    var legMaxPitchDegrees = 0.0
    var dueTick = 0L
    var beatIndex = 0
    var beatPhase = 0
    var beatStroke = 0
    var convergeIndex = 0
    var routeStarted = false
    var workStartedTick = -1L
    var machineFocus: Location? = null
    val props = linkedMapOf<String, PacketItemDisplay>()
    var carriedProduct: PacketItemDisplay? = null
}

/** Independent bounded cycles, one per workstation. All visuals die with this owner. */
private class OriginFurnitureWorkshopRuntime private constructor(
    private val settings: OriginFurnitureWorkshopSettings,
    private val world: World,
    private val actors: Map<OriginFurnitureWorkshopRole, NPC>,
    private val products: Map<OriginFurnitureWorkshopRole, ItemStack>,
) : AutoCloseable {
    private val tasks = LifecycleTaskScope()
    private val routeController = CitizensNpcRouteController(::logRoute, NpcRouteObstacleSource(::originFurnitureObstacleCells))
    private val displays = PaperPacketDisplays(ArcOriginPlugin.instance, "workshop-workers")
    private val stock = linkedMapOf<OriginFurnitureWorkshopRole, PacketItemDisplay>()
    private val lookCloseSnapshots = linkedMapOf<Int, Pair<LookClose, Boolean>>()
    private val navigationPauseSnapshots = linkedMapOf<Int, Boolean>()
    private val sleepingEquipmentSnapshots = linkedMapOf<Int, Pair<ItemStack?, ItemStack?>>()
    private val sleepingNameplateSnapshots = linkedMapOf<Int, Pair<Boolean, Boolean>>()
    private val nextDueTick = settings.workers.associate { it.role to it.initialDelayTicks }.toMutableMap()
    private val active = linkedMapOf<OriginFurnitureWorkshopRole, ActiveWorkshopCycle>()
    private val sleepShift = OriginWorkshopSleepShift(settings.workers.map { it.tableId }, settings.sleepDurationTicks)
    private var incomingSleeper: WorkshopSleepJourney? = null
    private val returningWorkers = linkedMapOf<Int, WorkshopSleepJourney>()
    private var sleepRetryAt = 0L
    private var tick = 0L
    private var closed = false

    companion object {
        fun prepare(settings: OriginFurnitureWorkshopSettings): OriginFurnitureWorkshopRuntime {
            check(Bukkit.getPluginManager().isPluginEnabled("Citizens")) { "Citizens is not enabled" }
            check(Bukkit.getPluginManager().isPluginEnabled("ItemsAdder")) { "ItemsAdder is not enabled" }
            check(Bukkit.getPluginManager().isPluginEnabled("packetevents")) { "PacketEvents is not enabled" }
            val world = requireNotNull(Bukkit.getWorld(settings.world)) { "Workshop world '${settings.world}' is not loaded" }
            val actors = settings.workers.associate { worker ->
                val actor = requireNotNull(CitizensAPI.getNPCRegistry().getById(worker.role.npcId)) {
                    "Workshop NPC ${worker.role.npcId} for ${worker.role.key} is missing"
                }
                worker.role to actor
            }
            val products = settings.workers.filter { it.deliverOutput }.associate { worker ->
                val product = CustomStack.getInstance(worker.productId)?.itemStack?.clone()
                    ?: error("Workshop product '${worker.productId}' is unavailable from ItemsAdder")
                worker.role to product
            }
            return OriginFurnitureWorkshopRuntime(settings, world, actors, products)
        }
    }

    fun start() {
        check(!closed) { "Workshop runtime is closed" }
        settings.workers.forEach { worker ->
            val actor = actors.getValue(worker.role)
            actor.getTraitNullable(LookClose::class.java)?.let { trait ->
                lookCloseSnapshots[actor.id] = trait to trait.isEnabled
                trait.lookClose(false)
            }
            if (actor.isSpawned && actor.entity.world == world) {
                check(!actor.navigator.isNavigating) { "Workshop NPC ${actor.id} is navigating outside this runtime" }
                resumeWorkshopNavigation(actor)
                wakeOriginWorkshopNpc(actor)
                check(actor.entity.teleport(worker.home.inWorld(world))) { "Workshop NPC ${actor.id} rejected home reset" }
                faceOriginScenePoint(actor, worker.restFocus.inWorld(world))
            }
            if (worker.deliverOutput) stock[worker.role] = createItemDisplay(
                item = products.getValue(worker.role),
                location = worker.output.inWorld(world),
                displayTransform = ItemDisplay.ItemDisplayTransform.NONE,
                scale = worker.finishedScale,
                translationY = worker.finishedTranslationY,
                key = WorkshopPropKey(worker.role, UUID(0L, 0L), "stock"),
            )
        }
        // Establish rest before production starts, including when nobody is watching.
        tickSleep()
        tasks.runTimer(1L, 1L, ::tick)
        log("READY", detail = "actors=${settings.workers.joinToString(",") { "${it.role.key}:${it.role.npcId}" }}")
    }

    private fun tick() {
        if (closed) return
        try {
            tickOnce()
        } catch (failure: Exception) {
            ArcOriginPlugin.instance.logger.log(Level.WARNING, "ORIGIN_WORKSHOP phase=DISPATCH_ERROR", failure)
        }
    }

    private fun tickOnce() {
        tick++
        try {
            tickSleep()
        } catch (failure: Exception) {
            sleepRetryAt = tick + 100L
            ArcOriginPlugin.instance.logger.log(Level.WARNING,
                "ORIGIN_WORKSHOP phase=SLEEP_ERROR retry_tick=$sleepRetryAt", failure)
        }
        val restingRoles = buildSet {
            add(settings.workers[sleepShift.index].role)
            incomingSleeper?.let { add(settings.workers[it.workerIndex].role) }
            returningWorkers.keys.forEach { add(settings.workers[it].role) }
        }
        if (tick % settings.machineUpdateTicks == 0L) {
            settings.workers.filter { it.role !in restingRoles || it.role in active }.forEach { worker ->
                val actor = actors.getValue(worker.role)
                OriginWorkshopTablesModule.animateDrive(worker.tableId, tick + WORKSHOP_MACHINE_INTERPOLATION_TICKS,
                    actor.isSpawned && actor.entity.world == world && hasViewer(actor.entity.location))
            }
        }
        active.values.toList().forEach { cycle ->
            try {
                tickCycle(cycle)
            } catch (failure: Exception) {
                runCatching { abort(cycle, "tick-exception-${failure.javaClass.simpleName}") }
                ArcOriginPlugin.instance.logger.log(Level.WARNING, "ORIGIN_WORKSHOP phase=RUNTIME_ERROR actor=${cycle.actor.id} role=${cycle.worker.role.key}", failure)
            }
        }
        if (tick % settings.dispatcherIntervalTicks != 0L) return
        workshopWorkersDue(settings.workers, nextDueTick, active.keys + restingRoles, tick)
            .filter { worker ->
                val actor = actors.getValue(worker.role)
                actor.isSpawned && actor.entity.world == world && !routeController.isNavigating(actor) && hasViewer(actor.entity.location)
            }
            .forEach { worker ->
                try {
                    startCycle(worker)
                } catch (failure: Exception) {
                    active[worker.role]?.let { runCatching { abort(it, "start-exception-${failure.javaClass.simpleName}") } }
                    ArcOriginPlugin.instance.logger.log(Level.WARNING, "ORIGIN_WORKSHOP phase=START_ERROR role=${worker.role.key}", failure)
                }
            }
    }

    fun availablePlayerTable(): String? {
        val actor = actors.getValue(settings.workers[sleepShift.index].role)
        return sleepShift.table.takeIf { !closed && sleepShift.ready && isSleeping(actor) }
    }

    fun productIdFor(tableId: String): String? = settings.workers.firstOrNull { it.tableId == tableId }?.productId

    fun acquirePlayerTable(tableId: String, playerId: UUID): Boolean =
        availablePlayerTable() == tableId && sleepShift.acquire(tableId, playerId)

    fun ownsPlayerTable(tableId: String, playerId: UUID): Boolean =
        availablePlayerTable() == tableId && sleepShift.owns(tableId, playerId)

    fun releasePlayerTable(tableId: String, playerId: UUID) = sleepShift.release(tableId, playerId)

    fun requestSleep(npcId: Int): OriginFurnitureWorkshopSleepRequestResult {
        if (closed) return OriginFurnitureWorkshopSleepRequestResult.WORKSHOP_UNAVAILABLE
        val workerIndex = settings.workers.indexOfFirst { it.role.npcId == npcId }
        if (workerIndex < 0) return OriginFurnitureWorkshopSleepRequestResult.UNKNOWN_NPC
        val worker = settings.workers[workerIndex]
        val actor = actors.getValue(worker.role)
        if (!isAvailable(actor)) return OriginFurnitureWorkshopSleepRequestResult.NPC_UNAVAILABLE
        if (isSleeping(actor)) return OriginFurnitureWorkshopSleepRequestResult.ALREADY_SLEEPING
        if (!sleepShift.ready || workerIndex == sleepShift.index) {
            return OriginFurnitureWorkshopSleepRequestResult.SHIFT_UNAVAILABLE
        }

        sleepShift.forceNext(workerIndex, tick)
        sleepRetryAt = tick
        val displaced = incomingSleeper
        if (displaced != null && displaced.workerIndex != workerIndex) {
            incomingSleeper = null
            val displacedWorker = settings.workers[displaced.workerIndex]
            if (displacedWorker.role !in active) {
                routeController.stop(actors.getValue(displacedWorker.role))
                returningWorkers[displaced.workerIndex] = WorkshopSleepJourney(displaced.workerIndex, leaveBed = false)
            }
        }
        log("SLEEP_QUEUED", detail = "actor=$npcId index=$workerIndex")
        return OriginFurnitureWorkshopSleepRequestResult.QUEUED
    }

    private fun tickSleep() {
        if (tick < sleepRetryAt) return
        val current = settings.workers[sleepShift.index]
        val currentActor = actors.getValue(current.role)
        if (sleepShift.ready && !isSleeping(currentActor)) {
            // Citizens may respawn an NPC while refreshing its skin. Restore its pose first.
            if (isAvailable(currentActor) && seatSleepingWorker(current)) return
            sleepShift.invalidate()
            restoreSleepingWorker(current)
            log("SLEEP_INTERRUPTED", detail = "actor=${currentActor.id} reason=actor-or-pose-unavailable")
        }
        if (!sleepShift.ready) {
            // Initial seating and recovery must not depend on viewers or a long approach route.
            val available = settings.workers.indices
                .map { (sleepShift.index + it) % settings.workers.size }
                .filter { isAvailable(actors.getValue(settings.workers[it].role)) }
            val idle = available.filter { settings.workers[it].role !in active }
            val index = idle.firstOrNull { it != sleepShift.forcedNextIndex }
                ?: idle.firstOrNull()
                ?: available.firstOrNull()
                ?: return
            val worker = settings.workers[index]
            active[worker.role]?.let { abort(it, "sleep-coverage-recovery") }
            val displaced = incomingSleeper
            incomingSleeper = null
            if (displaced != null && displaced.workerIndex != index) {
                val displacedWorker = settings.workers[displaced.workerIndex]
                if (displacedWorker.role !in active) {
                    routeController.stop(actors.getValue(displacedWorker.role))
                    returningWorkers[displaced.workerIndex] = WorkshopSleepJourney(displaced.workerIndex, leaveBed = false)
                }
            }
            returningWorkers.remove(index)
            routeController.stop(actors.getValue(worker.role))
            OriginWorkshopTablesModule.resetWork(worker.tableId)
            OriginWorkshopTablesModule.animateDrive(worker.tableId, tick, false)
            if (!seatSleepingWorker(worker)) {
                sleepRetryAt = tick + 100L
                return
            }
            sleepShift.resting(tick, index)
            log("SLEEP_STARTED", detail = "actor=${worker.role.npcId} table=${worker.tableId} initial=true")
        }
        returningWorkers.values.toList().forEach { journey ->
            val worker = settings.workers[journey.workerIndex]
            if (advanceSleepJourney(journey, worker.home, "HOME")) {
                returningWorkers.remove(journey.workerIndex)
                faceOriginScenePoint(actors.getValue(worker.role), worker.restFocus.inWorld(world))
                nextDueTick[worker.role] = tick + worker.initialDelayTicks
            }
        }
        if (incomingSleeper == null && sleepShift.rotationDue(tick)) {
            val available = settings.workers.indices
                .filter { isAvailable(actors.getValue(settings.workers[it].role)) }
                .toSet()
            val next = sleepShift.nextWorkerIndex(available, returningWorkers.keys)
            incomingSleeper = next?.let(::WorkshopSleepJourney)
        }
        val incoming = incomingSleeper ?: return
        val worker = settings.workers[incoming.workerIndex]
        val actor = actors.getValue(worker.role)
        if (!isAvailable(actor)) {
            routeController.stop(actor)
            incomingSleeper = null
            return
        }
        if (worker.role in active) return // Reserve the next turn, but let its existing cycle finish.
        if (!advanceSleepJourney(incoming, settings.sleepApproach, "TO_REST")) return
        if (!sleepShift.rotationDue(tick)) return // A game can acquire the old station while the next NPC walks.
        if (!seatSleepingWorker(worker)) {
            sleepRetryAt = tick + 100L
            return
        }
        val previous = sleepShift.index
        // The new NPC is visibly asleep before either the old lease or old pose is retired.
        sleepShift.replaceWith(incoming.workerIndex, tick)
        incomingSleeper = null
        returningWorkers[previous] = WorkshopSleepJourney(previous)
        val previousWorker = settings.workers[previous]
        restoreSleepingWorker(previousWorker)
        log("SLEEP_STARTED", detail = "actor=${actor.id} table=${worker.tableId} previous=${previousWorker.role.npcId}")
    }

    private fun advanceSleepJourney(
        journey: WorkshopSleepJourney,
        destination: OriginFurnitureWorkshopPoint,
        phase: String,
    ): Boolean {
        if (journey.arrived) return true
        val worker = settings.workers[journey.workerIndex]
        val actor = actors.getValue(worker.role)
        if (!isAvailable(actor) || tick < journey.retryAt) return false
        var failureReason = "route-unavailable"
        if (!journey.started) {
            // Leave the sofa by its front anchor; floor travel remains normal Citizens navigation.
            if (phase == "HOME" && journey.retryAt == 0L && journey.leaveBed) {
                wakeOriginWorkshopNpc(actor)
                check(actor.entity.teleport(settings.sleepApproach.inWorld(world))) { "Workshop NPC ${actor.id} rejected sleep exit" }
            }
            OriginWorkshopTablesModule.resetWork(worker.tableId)
            OriginWorkshopTablesModule.animateDrive(worker.tableId, tick, false)
            resumeWorkshopNavigation(actor)
            journey.started = routeController.navigate(actor, destination.inWorld(world), settings.routeProfile)
            journey.deadline = tick + settings.activeTimeoutTicks
            if (journey.started) return false
        } else {
            if (routeController.isNavigating(actor) && tick < journey.deadline) return false
            val outcome = routeController.consumeOutcome(actor)
            if (outcome?.successful == true) {
                journey.arrived = true
                return true
            }
            failureReason = outcome?.reason ?: "timeout-or-missing-outcome"
        }
        routeController.stop(actor)
        journey.started = false
        journey.retryAt = tick + worker.cycleDelayTicks
        ArcOriginPlugin.instance.logger.warning("ORIGIN_WORKSHOP phase=SLEEP_ROUTE_FAILED actor=${actor.id} target=$phase reason=$failureReason retry_tick=${journey.retryAt}")
        return false
    }

    private fun isAvailable(actor: NPC): Boolean = actor.isSpawned && actor.entity.world == world

    private fun isSleeping(actor: NPC): Boolean = isAvailable(actor) && actor.entity.pose == Pose.SLEEPING

    private fun resumeWorkshopNavigation(actor: NPC) {
        navigationPauseSnapshots.putIfAbsent(actor.id, actor.navigator.isPaused)
        if (actor.navigator.isPaused) {
            actor.navigator.isPaused = false
            log("SLEEP_NAV_RESUMED", detail = "actor=${actor.id}")
        }
    }

    private fun seatSleepingWorker(worker: OriginFurnitureWorkshopWorker): Boolean {
        val actor = actors.getValue(worker.role)
        val npcData = actor.data()
        sleepingEquipmentSnapshots.getOrPut(actor.id) { snapshotEquipment(actor) }
        sleepingNameplateSnapshots.getOrPut(actor.id) {
            npcData.has(NPC.Metadata.NAMEPLATE_VISIBLE) to npcData.get(NPC.Metadata.NAMEPLATE_VISIBLE, true)
        }
        check(actor.entity.teleport(settings.sleepAt.inWorld(world))) { "Workshop NPC ${actor.id} rejected sleep seat" }
        OriginNpcHolograms.setNameHiddenTemporarily(actor.id, WORKSHOP_SLEEP_NAME_OWNER, true)
        npcData.set(NPC.Metadata.NAMEPLATE_VISIBLE, false)
        setEquipment(actor, null, null)
        actor.getOrAddTrait(SleepTrait::class.java).apply {
            setSleeping(settings.sleepAt.inWorld(world))
            run()
        }
        actor.getOrAddTrait(EntityPoseTrait::class.java).run()
        if (isSleeping(actor)) return true
        restoreSleepingWorker(worker)
        ArcOriginPlugin.instance.logger.warning("ORIGIN_WORKSHOP phase=SLEEP_POSE_FAILED actor=${actor.id}")
        return false
    }

    private fun restoreSleepingWorker(worker: OriginFurnitureWorkshopWorker) {
        val actor = actors.getValue(worker.role)
        runCatching {
            OriginNpcHolograms.setNameHiddenTemporarily(actor.id, WORKSHOP_SLEEP_NAME_OWNER, false)
        }.onFailure { failure ->
            ArcOriginPlugin.instance.logger.log(
                Level.WARNING,
                "ORIGIN_WORKSHOP phase=SLEEP_RESTORE_FAILED actor=${actor.id} role=${worker.role.key} part=arc-name",
                failure,
            )
        }
        sleepingNameplateSnapshots.remove(actor.id)?.let { (hadOverride, wasVisible) ->
            runCatching {
                if (hadOverride) actor.data().set(NPC.Metadata.NAMEPLATE_VISIBLE, wasVisible)
                else actor.data().remove(NPC.Metadata.NAMEPLATE_VISIBLE)
            }.onFailure { failure ->
                ArcOriginPlugin.instance.logger.log(
                    Level.WARNING,
                    "ORIGIN_WORKSHOP phase=SLEEP_RESTORE_FAILED actor=${actor.id} role=${worker.role.key} part=nameplate",
                    failure,
                )
            }
        }
        sleepingEquipmentSnapshots.remove(actor.id)?.let { (hand, offHand) ->
            runCatching {
                setEquipment(actor, CitizensEquipment.EquipmentSlot.HAND, hand ?: ItemStack(Material.AIR))
            }.onFailure { failure ->
                ArcOriginPlugin.instance.logger.log(
                    Level.WARNING,
                    "ORIGIN_WORKSHOP phase=SLEEP_RESTORE_FAILED actor=${actor.id} role=${worker.role.key} part=main-hand",
                    failure,
                )
            }
            runCatching {
                setEquipment(actor, CitizensEquipment.EquipmentSlot.OFF_HAND, offHand ?: ItemStack(Material.AIR))
            }.onFailure { failure ->
                ArcOriginPlugin.instance.logger.log(
                    Level.WARNING,
                    "ORIGIN_WORKSHOP phase=SLEEP_RESTORE_FAILED actor=${actor.id} role=${worker.role.key} part=off-hand",
                    failure,
                )
            }
        }
        runCatching { wakeOriginWorkshopNpc(actor) }.onFailure { failure ->
            ArcOriginPlugin.instance.logger.log(Level.WARNING,
                "ORIGIN_WORKSHOP phase=SLEEP_RESTORE_FAILED actor=${actor.id} role=${worker.role.key} part=sleep-trait", failure)
        }
    }

    private fun startCycle(worker: OriginFurnitureWorkshopWorker) {
        val actor = actors.getValue(worker.role)
        val equipment = actor.getOrAddTrait(CitizensEquipment::class.java)
        val cycle = ActiveWorkshopCycle(
            worker = worker,
            actor = actor,
            token = UUID.randomUUID(),
            deadlineTick = tick + settings.activeTimeoutTicks,
            mainHand = equipment.get(CitizensEquipment.EquipmentSlot.HAND)?.clone(),
            offHand = equipment.get(CitizensEquipment.EquipmentSlot.OFF_HAND)?.clone(),
        )
        check(active.putIfAbsent(worker.role, cycle) == null) { "Workshop ${worker.role.key} already has an active cycle" }
        nextDueTick[worker.role] = tick + worker.cycleDelayTicks
        log("CYCLE_STARTED", cycle, "deadline_ticks=${settings.activeTimeoutTicks}")
        beginRoute(cycle, worker.pickupRoute, WorkshopAfterRoute.PICKUP)
    }

    private fun beginRoute(cycle: ActiveWorkshopCycle, legs: List<OriginFurnitureWorkshopLeg>, after: WorkshopAfterRoute) {
        if (legs.isEmpty()) {
            abort(cycle, "empty-route-${after.name.lowercase(Locale.ROOT)}")
            return
        }
        cycle.route = legs
        cycle.routeIndex = 0
        cycle.afterRoute = after
        cycle.stage = WorkshopStage.ROUTING
        startRouteLeg(cycle)
    }

    private fun startRouteLeg(cycle: ActiveWorkshopCycle) {
        val remainingLegs = cycle.route.drop(cycle.routeIndex)
        val leg = remainingLegs.last()
        val arrivalLookAt = when (cycle.afterRoute) {
            WorkshopAfterRoute.PICKUP -> cycle.worker.pickupSource
            WorkshopAfterRoute.WORK_RETURN -> cycle.worker.beats.getOrNull(cycle.beatIndex)?.focus ?: cycle.worker.restFocus
            WorkshopAfterRoute.WORKSTATION -> cycle.worker.beats[cycle.beatIndex].focus
            WorkshopAfterRoute.OUTPUT -> cycle.worker.output
            WorkshopAfterRoute.HOME -> cycle.worker.restFocus
        }
        if (!cycle.actor.isSpawned || cycle.actor.entity.world != world) {
            abort(cycle, "actor-unavailable-before-route")
            return
        }
        if (!routeController.navigate(
                cycle.actor, leg.point.inWorld(world), settings.routeProfile,
                via = remainingLegs.dropLast(1).map { it.point.inWorld(world) },
                arrivalLookAt = arrivalLookAt.inWorld(world),
            )) {
            abort(cycle, "route-rejected-${cycle.afterRoute.name.lowercase(Locale.ROOT)}-${cycle.routeIndex + 1}")
            return
        }
        cycle.routeStarted = true
        val timeoutTicks = remainingLegs.sumOf { it.timeoutTicks }
        cycle.legDeadlineTick = tick + timeoutTicks
        cycle.legMaxPitchDegrees = 0.0
        log(
            "ROUTE_START",
            cycle,
            "legs=${remainingLegs.size} purpose=${cycle.afterRoute.name.lowercase(Locale.ROOT)} target=${pointText(leg.point)} timeout_ticks=$timeoutTicks",
        )
    }

    private fun tickCycle(cycle: ActiveWorkshopCycle) {
        if (cycle !== active[cycle.worker.role]) return
        if (tick >= cycle.deadlineTick) {
            abort(cycle, "active-timeout")
            return
        }
        if (!cycle.actor.isSpawned || cycle.actor.entity.world != world) {
            abort(cycle, "actor-left-workshop-world")
            return
        }
        if (!hasViewer(cycle.actor.entity.location)) {
            abort(cycle, "viewer-left-radius")
            return
        }
        when (cycle.stage) {
            WorkshopStage.ROUTING -> tickRoute(cycle)
            WorkshopStage.PICKUP_HOLD -> if (tick >= cycle.dueTick) {
                setEquipment(cycle.actor, CitizensEquipment.EquipmentSlot.HAND, ItemStack(cycle.worker.rawHand))
                beginRoute(cycle, cycle.worker.workReturnRoute, WorkshopAfterRoute.WORK_RETURN)
            }
            WorkshopStage.ASSEMBLY -> tickAssembly(cycle)
            WorkshopStage.BENCH_HOLD -> if (tick >= cycle.dueTick) {
                if (cycle.worker.deliverOutput) {
                    prepareCarry(cycle)
                    beginRoute(cycle, cycle.worker.outputRoute, WorkshopAfterRoute.OUTPUT)
                } else {
                    beginRoute(cycle, cycle.worker.homeReturnRoute, WorkshopAfterRoute.HOME)
                }
            }
            WorkshopStage.STOCK_HOLD -> if (tick >= cycle.dueTick) {
                beginRoute(cycle, cycle.worker.homeReturnRoute, WorkshopAfterRoute.HOME)
            }
        }
    }

    private fun tickRoute(cycle: ActiveWorkshopCycle) {
        val pitch = abs(cycle.actor.entity.location.pitch.toDouble())
        cycle.legMaxPitchDegrees = maxOf(cycle.legMaxPitchDegrees, pitch)
        cycle.carriedProduct?.takeIf { it.isValid }?.let { carry ->
            carry.teleport(carryLocation(cycle.actor, world))
        }
        if (tick >= cycle.legDeadlineTick && routeController.isNavigating(cycle.actor)) {
            routeController.stop(cycle.actor)
            cycle.routeStarted = false
            abort(cycle, "route-timeout-${cycle.afterRoute.name.lowercase(Locale.ROOT)}-${cycle.routeIndex + 1}")
            return
        }
        if (routeController.isNavigating(cycle.actor)) return
        val outcome = routeController.consumeOutcome(cycle.actor)
        cycle.routeStarted = false
        if (outcome?.successful != true) {
            abort(cycle, "route-failed-${cycle.afterRoute.name.lowercase(Locale.ROOT)}-${cycle.routeIndex + 1}-${outcome?.reason ?: "missing-outcome"}")
            return
        }
        log(
            "ROUTE_FINISHED",
            cycle,
            "legs=${cycle.route.size} purpose=${cycle.afterRoute.name.lowercase(Locale.ROOT)} target=${pointText(cycle.route.last().point)} max_abs_pitch_degrees=${"%.3f".format(Locale.ROOT, cycle.legMaxPitchDegrees)}",
        )
        cycle.routeIndex = cycle.route.size
        completeRoute(cycle)
    }

    private fun completeRoute(cycle: ActiveWorkshopCycle) {
        when (cycle.afterRoute) {
            WorkshopAfterRoute.PICKUP -> {
                face(cycle.actor, cycle.worker.pickupSource)
                swing(cycle.actor)
                playSound(cycle.worker.pickupSource.inWorld(world), settings.pickupSound, settings.soundVolume, 0.95f)
                cycle.stage = WorkshopStage.PICKUP_HOLD
                cycle.dueTick = tick + settings.pickupDurationTicks
                log("PICKUP", cycle, "duration_ticks=${settings.pickupDurationTicks}")
            }
            WorkshopAfterRoute.WORK_RETURN -> beginAssembly(cycle)
            WorkshopAfterRoute.WORKSTATION -> activateBeat(cycle)
            WorkshopAfterRoute.OUTPUT -> storeProduct(cycle)
            WorkshopAfterRoute.HOME -> completeCycle(cycle)
        }
    }

    private fun beginAssembly(cycle: ActiveWorkshopCycle) {
        val worker = cycle.worker
        val rawRoot = createItemDisplay(
            ItemStack(if (worker.deliverOutput) worker.rawRootMaterial else Material.AIR),
            worker.bench.inWorld(world),
            ItemDisplay.ItemDisplayTransform.FIXED,
            worker.rawRootScale,
            0.0,
            WorkshopPropKey(worker.role, cycle.token, "root"),
        )
        cycle.props["root"] = rawRoot
        worker.parts.forEachIndexed { index, part ->
            cycle.props["part-$index"] = createItemDisplay(
                ItemStack(if (worker.deliverOutput) part.material else Material.AIR),
                part.source.inWorld(world),
                ItemDisplay.ItemDisplayTransform.FIXED,
                part.scale,
                0.0,
                WorkshopPropKey(worker.role, cycle.token, "part-$index"),
            )
        }
        cycle.stage = WorkshopStage.ASSEMBLY
        cycle.beatIndex = 0
        cycle.beatPhase = 0
        startBeat(cycle)
        log("ASSEMBLY_STARTED", cycle, "parts=${worker.parts.size} beats=${worker.beats.size}")
    }

    private fun startBeat(cycle: ActiveWorkshopCycle) {
        OriginWorkshopTablesModule.resetWork(cycle.worker.tableId)
        if (cycle.beatIndex >= cycle.worker.beats.size) {
            finishAssembly(cycle)
            return
        }
        val beat = cycle.worker.beats[cycle.beatIndex]
        val approach = beat.approach ?: cycle.worker.workApproach
        if (cycle.actor.entity.location.distanceSquared(approach.inWorld(world)) > settings.routeProfile.distanceMargin * settings.routeProfile.distanceMargin) {
            beginRoute(cycle, listOf(OriginFurnitureWorkshopLeg(approach, settings.workstationRouteTimeoutTicks)), WorkshopAfterRoute.WORKSTATION)
            return
        }
        activateBeat(cycle)
    }

    private fun activateBeat(cycle: ActiveWorkshopCycle) {
        val beat = cycle.worker.beats[cycle.beatIndex]
        val root = cycle.props["root"] ?: run {
            abort(cycle, "assembly-root-missing")
            return
        }
        setEquipment(cycle.actor, CitizensEquipment.EquipmentSlot.HAND, ItemStack(beat.hand))
        setEquipment(cycle.actor, CitizensEquipment.EquipmentSlot.OFF_HAND, ItemStack(beat.offhand))
        face(cycle.actor, beat.focus)
        root.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.FIXED
        root.itemStack = ItemStack(
            if (cycle.worker.deliverOutput && beat.mechanism == OriginWorkshopMechanism.NONE) cycle.worker.rawRootMaterial else Material.AIR,
        )
        root.teleport(beat.rootAt.inWorld(world))
        setTransformation(root, beat.rootScale, 0.0)
        cycle.beatPhase = if (beat.converge) 0 else 1
        cycle.convergeIndex = 0
        cycle.beatStroke = 0
        cycle.workStartedTick = -1L
        cycle.machineFocus = null
        cycle.stage = WorkshopStage.ASSEMBLY
        cycle.dueTick = tick
        log("BEAT_STARTED", cycle, "beat=${beat.phase} mechanism=${beat.mechanism.name.lowercase(Locale.ROOT)} table=${cycle.worker.tableId} index=${cycle.beatIndex + 1}/${cycle.worker.beats.size}")
    }

    private fun tickAssembly(cycle: ActiveWorkshopCycle) {
        val beat = cycle.worker.beats.getOrNull(cycle.beatIndex) ?: return
        if (cycle.beatPhase == 0) {
            if (cycle.convergeIndex >= cycle.worker.parts.size) {
                cycle.beatPhase = 1
                cycle.dueTick = tick
            } else if (tick >= cycle.dueTick) {
                val partIndex = cycle.convergeIndex
                val part = cycle.worker.parts[partIndex]
                val display = cycle.props["part-$partIndex"] ?: run {
                    abort(cycle, "assembly-part-missing-$partIndex")
                    return
                }
                display.teleport(part.join.inWorld(world))
                cycle.convergeIndex++
                cycle.dueTick = tick + part.convergeDelayTicks
            }
            return
        }
        if (beat.mechanism != OriginWorkshopMechanism.NONE) {
            tickMachinery(cycle, beat)
            return
        }
        if (cycle.beatPhase == 1) {
            if (cycle.beatStroke < beat.strokes && tick >= cycle.dueTick) {
                swing(cycle.actor)
                cycle.beatStroke++
                cycle.dueTick = tick + beat.strokeGapTicks
            } else if (cycle.beatStroke >= beat.strokes) {
                cycle.beatPhase = 2
                cycle.dueTick = tick + beat.durationTicks
            }
            return
        }
        if (tick < cycle.dueTick) return
        val feedback = beat.focus.inWorld(world)
        playItemParticles(feedback, ItemStack(beat.particleMaterial), beat.particleQuantity)
        playSound(feedback, beat.sound, settings.soundVolume, beat.soundPitch)
        finishBeat(cycle, beat)
    }

    private fun tickMachinery(cycle: ActiveWorkshopCycle, beat: OriginFurnitureWorkshopBeat) {
        if (cycle.workStartedTick < 0L) cycle.workStartedTick = tick
        val elapsed = tick - cycle.workStartedTick
        val strokeDuration = beat.strokes * beat.strokeGapTicks
        val duration = strokeDuration + beat.durationTicks
        if (beat.mechanism == OriginWorkshopMechanism.FINISH) {
            if (elapsed == 0L) setEquipment(cycle.actor, CitizensEquipment.EquipmentSlot.HAND, ItemStack(Material.PAPER))
            if (elapsed == duration / 2L) setEquipment(cycle.actor, CitizensEquipment.EquipmentSlot.HAND, ItemStack(beat.hand))
        }
        if (elapsed >= duration) {
            finishBeat(cycle, beat)
            return
        }
        val strokeOffset = elapsed % beat.strokeGapTicks
        val contactOffset = workshopMachineContactTick(beat.strokeGapTicks)
        // Display interpolation reaches this pose two ticks later, with the contact sound.
        val poseElapsed = (elapsed + WORKSHOP_MACHINE_INTERPOLATION_TICKS).coerceAtMost(duration)
        val poseOffset = poseElapsed % beat.strokeGapTicks
        if (elapsed % settings.machineUpdateTicks == 0L || poseOffset == contactOffset) {
            val focus = OriginWorkshopTablesModule.animateWork(
                cycle.worker.tableId,
                beat.mechanism,
                poseElapsed.toDouble() / duration,
                if (poseElapsed < strokeDuration) workshopMachineStrokeProgress(poseOffset, beat.strokeGapTicks) else 0.0,
            ) ?: run {
                abort(cycle, "workstation-unavailable-${cycle.worker.tableId}-${beat.mechanism.name.lowercase(Locale.ROOT)}")
                return
            }
            if (cycle.machineFocus == null || cycle.machineFocus!!.distanceSquared(focus) > 0.0001) {
                faceOriginScenePoint(cycle.actor, focus)
            }
            cycle.machineFocus = focus
        }
        if (elapsed >= strokeDuration) return
        if (beat.mechanism == OriginWorkshopMechanism.FINISH && elapsed.toDouble() / duration !in 0.25..0.75) return
        if (strokeOffset == (contactOffset - 3L).coerceAtLeast(0L)) swing(cycle.actor)
        if (strokeOffset == contactOffset) {
            val focus = cycle.machineFocus ?: return
            playItemParticles(focus, ItemStack(beat.particleMaterial), beat.particleQuantity)
            playSound(focus, beat.sound, settings.soundVolume, beat.soundPitch)
            log("WORK_CONTACT", cycle, "beat=${beat.phase} mechanism=${beat.mechanism.name.lowercase(Locale.ROOT)} stroke=${elapsed / beat.strokeGapTicks + 1}/${beat.strokes}")
        }
    }

    private fun finishBeat(cycle: ActiveWorkshopCycle, beat: OriginFurnitureWorkshopBeat) {
        OriginWorkshopTablesModule.resetWork(cycle.worker.tableId)
        log("BEAT_FINISHED", cycle, "beat=${beat.phase} index=${cycle.beatIndex + 1}/${cycle.worker.beats.size}")
        cycle.beatIndex++
        startBeat(cycle)
    }

    private fun finishAssembly(cycle: ActiveWorkshopCycle) {
        val root = cycle.props["root"] ?: run {
            abort(cycle, "assembly-root-missing-at-finish")
            return
        }
        if (!cycle.worker.deliverOutput) {
            cycle.stage = WorkshopStage.BENCH_HOLD
            cycle.dueTick = tick + cycle.worker.finishedHoldTicks
            log("FINISHING_READY", cycle, "hold_ticks=${cycle.worker.finishedHoldTicks}")
            return
        }
        root.itemStack = products.getValue(cycle.worker.role).clone()
        root.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.NONE
        root.teleport(cycle.worker.bench.inWorld(world))
        setTransformation(root, settings.carryScale, settings.carryTranslationY)
        cycle.worker.parts.indices.forEach { index ->
            cycle.props.remove("part-$index")?.remove()
        }
        face(cycle.actor, cycle.worker.bench)
        cycle.carriedProduct = root
        cycle.stage = WorkshopStage.BENCH_HOLD
        cycle.dueTick = tick + cycle.worker.finishedHoldTicks
        log("PRODUCT_READY", cycle, "hold_ticks=${cycle.worker.finishedHoldTicks}")
    }

    private fun prepareCarry(cycle: ActiveWorkshopCycle) {
        val carried = cycle.carriedProduct ?: run {
            abort(cycle, "carry-product-missing")
            return
        }
        setTransformation(carried, settings.carryScale, settings.carryTranslationY)
        // The carry target is refreshed every runtime tick; long prop interpolation trails each update.
        carried.teleportDuration = 1
        carried.teleport(carryLocation(cycle.actor, world))
    }

    private fun storeProduct(cycle: ActiveWorkshopCycle) {
        val worker = cycle.worker
        val carried = cycle.carriedProduct ?: run {
            abort(cycle, "stock-product-missing")
            return
        }
        val previousStock = stock[worker.role] ?: run {
            abort(cycle, "stock-display-missing")
            return
        }
        previousStock.remove()
        carried.teleport(worker.output.inWorld(world))
        setTransformation(carried, worker.finishedScale, worker.finishedTranslationY)
        stock[worker.role] = carried
        cycle.props.remove("root")
        cycle.carriedProduct = null
        playItemParticles(worker.output.inWorld(world), products.getValue(worker.role), 8)
        face(cycle.actor, worker.output)
        cycle.stage = WorkshopStage.STOCK_HOLD
        cycle.dueTick = tick + worker.finishedHoldTicks
        log("STOCK_REPLACED", cycle, "item=${worker.productId} hold_ticks=${worker.finishedHoldTicks}")
    }

    private fun completeCycle(cycle: ActiveWorkshopCycle) {
        face(cycle.actor, cycle.worker.restFocus)
        restoreEquipment(cycle)
        removeCycleProps(cycle)
        active.remove(cycle.worker.role)
        log("CYCLE_COMPLETED", cycle, "product=${cycle.worker.productId}")
    }

    private fun abort(cycle: ActiveWorkshopCycle, reason: String) {
        if (cycle !== active[cycle.worker.role]) return
        if (cycle.routeStarted) runCatching { routeController.stop(cycle.actor) }
        cycle.routeStarted = false
        restoreEquipment(cycle)
        removeCycleProps(cycle)
        if (cycle.actor.isSpawned && cycle.actor.entity.world == world) {
            runCatching { cycle.actor.entity.teleport(cycle.worker.home.inWorld(world)) }
            runCatching { face(cycle.actor, cycle.worker.restFocus) }
        }
        active.remove(cycle.worker.role)
        log("CYCLE_CANCELLED", cycle, "reason=$reason")
    }

    private fun snapshotEquipment(actor: NPC): Pair<ItemStack?, ItemStack?> {
        val equipment = actor.getOrAddTrait(CitizensEquipment::class.java)
        return equipment.get(CitizensEquipment.EquipmentSlot.HAND)?.clone() to
            equipment.get(CitizensEquipment.EquipmentSlot.OFF_HAND)?.clone()
    }

    private fun setEquipment(actor: NPC, hand: ItemStack?, offHand: ItemStack?) {
        val equipment = actor.getOrAddTrait(CitizensEquipment::class.java)
        equipment.set(CitizensEquipment.EquipmentSlot.HAND, hand?.clone() ?: ItemStack(Material.AIR))
        equipment.set(CitizensEquipment.EquipmentSlot.OFF_HAND, offHand?.clone() ?: ItemStack(Material.AIR))
    }

    private fun restoreEquipment(cycle: ActiveWorkshopCycle) {
        runCatching {
            setEquipment(cycle.actor, CitizensEquipment.EquipmentSlot.HAND, cycle.mainHand ?: ItemStack(Material.AIR))
        }
        runCatching {
            setEquipment(cycle.actor, CitizensEquipment.EquipmentSlot.OFF_HAND, cycle.offHand ?: ItemStack(Material.AIR))
        }
    }

    private fun removeCycleProps(cycle: ActiveWorkshopCycle) {
        OriginWorkshopTablesModule.resetWork(cycle.worker.tableId)
        cycle.props.values.toList().forEach { runCatching { it.remove() } }
        cycle.props.clear()
        cycle.carriedProduct = null
    }

    private fun createItemDisplay(
        item: ItemStack,
        location: Location,
        displayTransform: ItemDisplay.ItemDisplayTransform,
        scale: Double,
        translationY: Double,
        key: WorkshopPropKey,
    ): PacketItemDisplay = displays.spawnItem(location, item.clone()).apply {
        isVisibleByDefault = true
        billboard = Display.Billboard.FIXED
        viewRange = 0.5f
        displayWidth = 2f
        displayHeight = 2f
        shadowRadius = 0f
        interpolationDelay = -1
        interpolationDuration = settings.propInterpolationTicks
        teleportDuration = settings.propInterpolationTicks
        itemDisplayTransform = displayTransform
        setTransformation(this, scale, translationY)
        log("PROP_CREATED", detail = "role=${key.role.key} kind=${key.key} token=${key.token}")
    }

    private fun setTransformation(display: PacketItemDisplay, scale: Double, translationY: Double) {
        display.transformation = Transformation(
            Vector3f(0f, translationY.toFloat(), 0f),
            AxisAngle4f(),
            Vector3f(scale.toFloat(), scale.toFloat(), scale.toFloat()),
            AxisAngle4f(),
        )
        display.interpolationDuration = settings.propInterpolationTicks
        display.teleportDuration = settings.propInterpolationTicks
    }

    private fun carryLocation(actor: NPC, world: World): Location {
        val location = actor.entity.location
        val anchor = originFurnitureWorkshopCarryAnchor(
            x = location.x,
            z = location.z,
            actorYaw = location.yaw,
            forwardDistance = settings.carryForwardDistance,
            yawOffset = settings.carryYawOffset,
        )
        return Location(
            world,
            anchor.x,
            location.y + settings.carryOffsetY,
            anchor.z,
            anchor.yaw,
            settings.carryPitch,
        )
    }

    private fun setEquipment(actor: NPC, slot: CitizensEquipment.EquipmentSlot, item: ItemStack) {
        actor.getOrAddTrait(CitizensEquipment::class.java).set(slot, item.clone())
    }

    private fun face(actor: NPC, point: OriginFurnitureWorkshopPoint) {
        faceOriginScenePoint(actor, point.inWorld(world))
    }

    private fun swing(actor: NPC) {
        (actor.entity as? LivingEntity)?.swingMainHand()
    }

    private fun playSound(location: Location, sound: Sound, volume: Float, pitch: Float) {
        playersNear(location).forEach { it.playSound(location, sound, volume, pitch) }
    }

    private fun playItemParticles(location: Location, item: ItemStack, quantity: Int) {
        world.spawnParticle(Particle.ITEM, location, quantity, 0.18, 0.12, 0.18, 0.015, item.clone())
    }

    private fun playersNear(location: Location) = Bukkit.getOnlinePlayers().filter { player ->
        player.world == world && player.location.distanceSquared(location) <= settings.viewerRadius * settings.viewerRadius
    }

    private fun hasViewer(location: Location): Boolean = playersNear(location).isNotEmpty()

    private fun logRoute(event: NpcRouteEvent) {
        val reason = event.reason?.let { " reason=$it" }.orEmpty()
        log(
            "NAV_${event.phase}",
            detail = "actor=${event.npcId} profile=${event.profileId} cells=${event.cells} actual=${pointText(event.actual)} target=${pointText(event.target)}$reason",
        )
    }

    private fun log(phase: String, cycle: ActiveWorkshopCycle? = null, detail: String = "") {
        val worker = cycle?.worker
        val actorId = cycle?.actor?.id?.toString() ?: worker?.role?.npcId?.toString() ?: "none"
        val role = worker?.role?.key ?: "none"
        val token = cycle?.token?.toString()?.take(8) ?: "none"
        ArcOriginPlugin.instance.logger.info("ORIGIN_WORKSHOP phase=$phase actor=$actorId role=$role token=$token $detail")
    }

    private fun closeActor(cycle: ActiveWorkshopCycle) {
        if (cycle.routeStarted) routeController.stop(cycle.actor)
        restoreEquipment(cycle)
        removeCycleProps(cycle)
        if (cycle.actor.isSpawned && cycle.actor.entity.world == world) {
            runCatching { cycle.actor.entity.teleport(cycle.worker.home.inWorld(world)) }
            runCatching { face(cycle.actor, cycle.worker.restFocus) }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        fun cleanup(part: String, operation: () -> Unit) {
            runCatching(operation).onFailure { failure ->
                ArcOriginPlugin.instance.logger.log(Level.WARNING, "ORIGIN_WORKSHOP phase=CLOSE_FAILED part=$part", failure)
            }
        }
        cleanup("tasks") { tasks.close() }
        active.values.toList().forEach { cycle -> cleanup("actor-${cycle.actor.id}") { closeActor(cycle) } }
        active.clear()
        sleepShift.invalidate()
        settings.workers.forEach { worker ->
            cleanup("sleep-${worker.role.key}") { restoreSleepingWorker(worker) }
            val actor = actors.getValue(worker.role)
            if (actor.isSpawned && actor.entity.world == world) {
                runCatching { actor.entity.teleport(worker.home.inWorld(world)) }
            }
        }
        settings.workers.forEach { worker -> cleanup("drive-${worker.tableId}") {
            OriginWorkshopTablesModule.animateDrive(worker.tableId, 0L, false)
        } }
        cleanup("routes") { routeController.close() }
        navigationPauseSnapshots.forEach { (id, paused) ->
            cleanup("navigation-pause-$id") { actors.values.first { it.id == id }.navigator.isPaused = paused }
        }
        navigationPauseSnapshots.clear()
        incomingSleeper = null
        returningWorkers.clear()
        stock.values.toList().forEach { runCatching { it.remove() } }
        stock.clear()
        cleanup("displays") { displays.close() }
        lookCloseSnapshots.values.forEach { (trait, enabled) ->
            runCatching { trait.lookClose(enabled) }.onFailure { failure ->
                ArcOriginPlugin.instance.logger.log(Level.WARNING, "Could not restore workshop NPC LookClose", failure)
            }
        }
        lookCloseSnapshots.clear()
        log("CLOSED")
    }

    private fun pointText(point: OriginFurnitureWorkshopPoint): String =
        "${"%.2f".format(Locale.ROOT, point.x)},${"%.2f".format(Locale.ROOT, point.y)},${"%.2f".format(Locale.ROOT, point.z)}"

    private fun pointText(location: Location): String =
        "${"%.2f".format(Locale.ROOT, location.x)},${"%.2f".format(Locale.ROOT, location.y)},${"%.2f".format(Locale.ROOT, location.z)}"
}

private const val WORKSHOP_WORLD = "rc_origin_spawn"

/** Matches the contact pose of the hinged hammer and upholstery press. */
internal fun workshopMachineContactTick(strokeGapTicks: Long): Long = (strokeGapTicks * 0.65).toLong()

/** Keep the exact contact pose even when 65% of a configured stroke is a fractional tick. */
internal fun workshopMachineStrokeProgress(offset: Long, gap: Long): Double {
    val contact = workshopMachineContactTick(gap)
    return if (offset <= contact) 0.65 * offset / contact
    else 0.65 + 0.35 * (offset - contact) / (gap - contact)
}
