package ru.arc.origin.scene

import net.citizensnpcs.api.npc.NPC
import net.citizensnpcs.trait.LookClose
import net.citizensnpcs.trait.RotationTrait
import net.citizensnpcs.api.trait.trait.Equipment as CitizensEquipment
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Lidded
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Display
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.AbstractHorse
import org.bukkit.entity.Cat
import org.bukkit.entity.Entity
import org.bukkit.entity.Pose as BukkitPose
import org.bukkit.entity.Sittable
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Transformation
import org.joml.AxisAngle4f
import org.joml.Vector3f
import ru.arc.npc.npcRouteYaw

/** A failure retained by [OriginSceneResources] for an explicit cleanup retry. */
internal data class OriginSceneCleanupFailure(
    val resource: String,
    val id: String,
    val failure: Exception,
)

/**
 * Owns the temporary native resources used by one ambient scene cycle.
 *
 * The owner deliberately keeps the Citizens NPC object and the exact hand
 * snapshot rather than depending on an entity being spawned during cleanup.
 * Every resource is registered before its fallible mutation where the API
 * permits it, so a partial operation remains retryable instead of becoming a
 * leaked world side effect.
 */
internal class OriginSceneResources {
    private data class HandSnapshot(
        val id: Int,
        val npc: NPC,
        val original: ItemStack?,
    )

    private data class PoseSnapshot(
        val id: Int,
        val entity: Entity,
        val pose: BukkitPose,
        val fixedPose: Boolean,
        val sitting: Boolean?,
        val catLyingDown: Boolean?,
        val catHeadUp: Boolean?,
        val horseEatingGrass: Boolean?,
    )

    private val hands = linkedMapOf<Int, HandSnapshot>()
    private val poses = linkedMapOf<Int, PoseSnapshot>()
    private val containers = linkedSetOf<Location>()
    private val displays = linkedMapOf<String, BlockDisplay>()
    private val items = linkedMapOf<String, ItemDisplay>()
    private val rotations = linkedMapOf<Int, Pair<NPC, Location>>()
    private val explicitRotations = mutableSetOf<Int>()
    private val lookClose = linkedMapOf<Int, Pair<LookClose, Boolean>>()
    private val usingItems = linkedMapOf<Int, LivingEntity>()

    val displayCount: Int
        get() = displays.size + items.size

    /** Equips the scene item while preserving the first observed hand state. */
    fun equip(actor: NPC, material: Material) {
        equip(actor, ItemStack(material))
    }

    /** The same equipment lease also supports configured custom items. */
    fun equip(actor: NPC, item: ItemStack) {
        val actorId = actor.id
        val equipment = actor.getOrAddTrait(CitizensEquipment::class.java)
        if (actorId !in hands) {
            hands[actorId] = HandSnapshot(
                id = actorId,
                npc = actor,
                original = equipment.get(CitizensEquipment.EquipmentSlot.HAND)?.clone(),
            )
        }
        equipment.set(CitizensEquipment.EquipmentSlot.HAND, item.clone())
    }

    /** Owns the original facing for a seated gesture or workstation action. */
    fun face(actor: NPC, target: Location) {
        check(actor.isSpawned && actor.entity.world == target.world)
        captureFacing(actor)
        actor.faceLocation(target)
    }

    /** Conversation yaw is independent of mounted eye offsets and target-height compensation. */
    fun faceHorizontal(actor: NPC, target: Location, pitch: Float = 0f) {
        check(actor.isSpawned && actor.entity.world == target.world)
        val origin = actor.entity.location
        val yaw = npcRouteYaw(origin.x, origin.z, target.x, target.z)
        faceRotation(actor, yaw, pitch)
    }

    /** Absolute work point, measured from eyes, never from Citizens' feet-based target. */
    fun facePoint(actor: NPC, target: Location) {
        captureFacing(actor)
        explicitRotations += actor.id
        faceOriginScenePoint(actor, target)
    }

    private fun faceRotation(actor: NPC, yaw: Float, pitch: Float) {
        captureFacing(actor)
        explicitRotations += actor.id
        actor.getOrAddTrait(RotationTrait::class.java).physicalSession.rotateToHave(yaw, pitch)
        actor.entity.setRotation(yaw, pitch)
    }

    private fun captureFacing(actor: NPC) {
        rotations.putIfAbsent(actor.id, actor to actor.entity.location.clone())
        if (actor.id !in lookClose && actor.hasTrait(LookClose::class.java)) {
            val trait = actor.getTraitNullable(LookClose::class.java)
            if (trait != null) {
                lookClose[actor.id] = trait to trait.isEnabled
                trait.lookClose(false)
            }
        }
    }

    /** Starts the native eating/drinking pose without consuming an item or running its effects. */
    fun useItem(actor: NPC) {
        val entity = actor.entity as? LivingEntity ?: return
        if (actor.id !in usingItems && entity.hasActiveItem()) return
        usingItems[actor.id] = entity
        entity.startUsingItem(org.bukkit.inventory.EquipmentSlot.HAND)
    }

    /**
     * A bounded native prop for short, small scenes. Durable/interactive meals
     * stay with their domain owner; this lease removes only its own props.
     * Model support lift must come from the configured model/context bounds.
     */
    fun item(
        key: String,
        location: Location,
        stack: ItemStack,
        scale: Float,
        supportLift: Float,
        interpolationTicks: Int = 4,
    ): ItemDisplay {
        require(scale.isFinite() && scale in 0.01f..4f)
        require(supportLift.isFinite())
        require(interpolationTicks in 0..59)
        val display = items[key]?.takeIf { it.isValid } ?: run {
            check(displayCount < 4) { "Small scene prop budget exceeded" }
            location.world.spawn(location, ItemDisplay::class.java) { candidate ->
                items[key] = candidate
                candidate.isPersistent = false
                candidate.setGravity(false)
                candidate.isInvulnerable = true
                candidate.addScoreboardTag("arc_origin_scene_item")
            }.also { items[key] = it }
        }
        display.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.GROUND
        display.billboard = Display.Billboard.FIXED
        display.viewRange = 0.5f
        display.displayWidth = 2f
        display.displayHeight = 2f
        display.shadowRadius = 0f
        display.setItemStack(stack)
        display.teleportDuration = interpolationTicks
        check(display.teleport(location)) { "Scene item $key rejected movement" }
        display.interpolationDelay = 0
        display.interpolationDuration = interpolationTicks
        display.transformation = Transformation(
            Vector3f(0f, supportLift, 0f), AxisAngle4f(), Vector3f(scale, scale, scale), AxisAngle4f(),
        )
        return display
    }

    fun removeItem(key: String) {
        val item = items[key] ?: return
        if (item.isValid) item.remove()
        items.remove(key)
    }

    /** Creates or updates one ItemsAdder-backed portable prop under the scene display key. */
    fun updateItemDisplay(
        key: String,
        location: Location,
        stack: ItemStack,
        context: OriginSceneItemDisplayContext,
        scale: OriginSceneVector,
        interpolationTicks: Int,
        tags: Set<String>,
    ): Boolean {
        scale.requirePositive("origin scene item display scale")
        scale.requireBounded("origin scene item display scale", 4.0)
        require(scale.x >= 0.01 && scale.y >= 0.01 && scale.z >= 0.01) {
            "origin scene item display scale values must be at least 0.01"
        }
        require(interpolationTicks in 0..59)
        require(!stack.type.isAir) { "origin scene item display $key cannot use AIR" }
        var created = false
        val display = items[key]?.takeIf(ItemDisplay::isValid)
            ?: run {
                items.remove(key)
                check(displayCount < 4) { "Small scene prop budget exceeded" }
                val spawned = location.world.spawn(location, ItemDisplay::class.java) { candidate ->
                    // Register before any consumer mutation so failed setup is still retryable on cleanup.
                    items[key] = candidate
                    candidate.isPersistent = false
                    candidate.setGravity(false)
                    candidate.isInvulnerable = true
                    tags.forEach(candidate::addScoreboardTag)
                }
                if (items[key] !== spawned) items[key] = spawned
                created = true
                spawned
            }

        display.itemDisplayTransform = context.bukkitTransform()
        display.billboard = Display.Billboard.FIXED
        display.viewRange = 0.5f
        display.displayWidth = 2f
        display.displayHeight = 2f
        display.shadowRadius = 0f
        display.setItemStack(stack.clone())
        display.interpolationDelay = -1
        display.interpolationDuration = interpolationTicks
        display.teleportDuration = interpolationTicks
        check(display.teleport(location)) { "Scene item display $key rejected teleport to $location" }
        // Keep model-context placement separate from the level entity yaw in the location.
        display.transformation = Transformation(
            Vector3f(),
            AxisAngle4f(),
            Vector3f(scale.x.toFloat(), scale.y.toFloat(), scale.z.toFloat()),
            AxisAngle4f(),
        )
        return created
    }

    private fun OriginSceneItemDisplayContext.bukkitTransform(): ItemDisplay.ItemDisplayTransform = when (this) {
        OriginSceneItemDisplayContext.NONE -> ItemDisplay.ItemDisplayTransform.NONE
        OriginSceneItemDisplayContext.GROUND -> ItemDisplay.ItemDisplayTransform.GROUND
        OriginSceneItemDisplayContext.FIXED -> ItemDisplay.ItemDisplayTransform.FIXED
        OriginSceneItemDisplayContext.HEAD -> ItemDisplay.ItemDisplayTransform.HEAD
    }

    /** A small geometric workstation prop, with an explicit lower-corner anchor. */
    fun solid(key: String, location: Location, material: Material, scale: Vector3f): BlockDisplay {
        require(material.isBlock && scale.x > 0 && scale.y > 0 && scale.z > 0)
        check(displays[key]?.isValid == true || displayCount < 4) { "Small scene prop budget exceeded" }
        val display = displays[key]?.takeIf { it.isValid } ?: location.world.spawn(location, BlockDisplay::class.java) {
            displays[key] = it
            it.isPersistent = false
            it.addScoreboardTag("arc_origin_scene_prop")
        }.also { displays[key] = it }
        display.block = material.createBlockData()
        display.viewRange = 0.5f
        display.displayWidth = 2f
        display.displayHeight = 2f
        display.teleportDuration = 4
        check(display.teleport(location)) { "Scene block prop $key rejected movement" }
        display.transformation = Transformation(Vector3f(), AxisAngle4f(), Vector3f(scale), AxisAngle4f())
        return display
    }

    /** Captures the original native pose once, then applies a configured pose. */
    fun setPose(actor: NPC, pose: OriginScenePose) {
        val entity = actor.entity
        capturePose(actor.id, entity)
        when (pose) {
            OriginScenePose.STAND -> clearMovementPose(entity)
            OriginScenePose.SIT -> {
                clearMovementPose(entity)
                (entity as? Sittable)?.setSitting(true) ?: entity.setPose(BukkitPose.SITTING, true)
            }
            OriginScenePose.CAT_LIE -> {
                require(entity is Cat) {
                    "origin scene CAT_LIE requires a Cat entity, got ${entity.type}"
                }
                clearMovementPose(entity)
                entity.setLyingDown(true)
            }
            OriginScenePose.HORSE_GRAZE -> {
                require(entity is AbstractHorse) {
                    "origin scene HORSE_GRAZE requires an AbstractHorse entity, got ${entity.type}"
                }
                clearMovementPose(entity)
                entity.setEatingGrass(true)
            }
        }
    }

    /** Clears movement-blocking pose state while retaining the original snapshot. */
    fun clearMovementPose(actor: NPC) {
        val entity = actor.entity
        capturePose(actor.id, entity)
        clearMovementPose(entity)
    }

    /**
     * Changes a block's lid state and owns an opening until it is explicitly
     * closed. A non-container is a contract violation, not a silent no-op.
     */
    fun setContainer(location: Location, open: Boolean) {
        val lidded = requireLidded(location)
        val ownedLocation = location.clone()
        if (open) {
            // Keep ownership before open(): failed opening must be retryable in
            // cleanup, even when the block state is only partially applied.
            containers += ownedLocation
            lidded.open()
        } else {
            lidded.close()
            containers.remove(ownedLocation)
        }
    }

    /**
     * Creates or updates one non-persistent display using the same transform
     * semantics as the scene runtime. The return value reports creation, not
     * whether the display was updated.
     */
    fun updateDisplay(
        key: String,
        world: World,
        resolved: OriginSceneResolvedProp,
        step: OriginSceneStep.BlockDisplay,
        tags: Set<String>,
        rotationYDegrees: Float = step.rotationYDegrees,
    ): Boolean {
        val material = requireNotNull(Material.matchMaterial(step.material)?.takeIf(Material::isBlock)) {
            "origin scene display $key requires a block material: ${step.material}"
        }
        val location = Location(world, resolved.x, resolved.y, resolved.z)
        var created = false
        val display = displays[key]?.takeIf(BlockDisplay::isValid)
            ?: run {
                // An invalid old entity is no longer useful, but the key must
                // be replaced by the newly owned entity if spawning succeeds.
                displays.remove(key)
                val spawned = world.spawn(location, BlockDisplay::class.java) { candidate ->
                    // Register before any consumer mutation. If a tag or
                    // persistence call throws, cleanup still owns this entity.
                    displays[key] = candidate
                    candidate.isPersistent = false
                    tags.forEach(candidate::addScoreboardTag)
                }
                if (displays[key] !== spawned) displays[key] = spawned
                created = true
                spawned
            }

        display.block = material.createBlockData()
        check(display.teleport(location)) {
            "origin scene display $key rejected teleport to $location"
        }
        display.interpolationDelay = -1
        display.interpolationDuration = step.interpolationTicks
        display.teleportDuration = step.interpolationTicks.coerceAtMost(59)
        display.transformation = Transformation(
            Vector3f(resolved.translationX, resolved.translationY, resolved.translationZ),
            AxisAngle4f(Math.toRadians(rotationYDegrees.toDouble()).toFloat(), 0f, 1f, 0f),
            Vector3f(step.scale.x.toFloat(), step.scale.y.toFloat(), step.scale.z.toFloat()),
            AxisAngle4f(),
        )
        return created
    }

    fun hasDisplay(key: String): Boolean = displays[key]?.isValid == true || items[key]?.isValid == true

    /** Removes one owned display; a failed removal remains owned for cleanup. */
    fun removeDisplay(key: String): Boolean {
        val blockDisplay = displays[key]
        val itemDisplay = items[key]
        if (blockDisplay == null && itemDisplay == null) return false
        var failure: Exception? = null
        try {
            if (blockDisplay?.isValid == true) blockDisplay.remove()
            if (blockDisplay != null) displays.remove(key)
        } catch (problem: Exception) {
            failure = problem
        }
        try {
            if (itemDisplay?.isValid == true) itemDisplay.remove()
            if (itemDisplay != null) items.remove(key)
        } catch (problem: Exception) {
            val current = failure
            if (current == null) failure = problem else current.addSuppressed(problem)
        }
        failure?.let { throw it }
        return true
    }

    /**
     * Attempts every currently owned resource. Successful resources are
     * removed from ownership; failures stay registered for a later retry.
     */
    fun cleanup(): List<OriginSceneCleanupFailure> {
        val failures = mutableListOf<OriginSceneCleanupFailure>()

        usingItems.toMap().forEach { (id, entity) ->
            try {
                if (entity.isValid) entity.clearActiveItem()
                usingItems.remove(id)
            } catch (failure: Exception) {
                failures += OriginSceneCleanupFailure("using-item", id.toString(), failure)
            }
        }

        hands.values.toList().forEach { snapshot ->
            try {
                val equipment = snapshot.npc.getOrAddTrait(CitizensEquipment::class.java)
                equipment.set(
                    CitizensEquipment.EquipmentSlot.HAND,
                    snapshot.original?.clone(),
                )
                hands.remove(snapshot.id)
            } catch (failure: Exception) {
                failures += OriginSceneCleanupFailure("hand", snapshot.id.toString(), failure)
            }
        }

        containers.toList().forEach { location ->
            try {
                requireLidded(location).close()
                containers.remove(location)
            } catch (failure: Exception) {
                failures += OriginSceneCleanupFailure("container", location.toString(), failure)
            }
        }

        poses.values.toList().forEach { snapshot ->
            try {
                restorePose(snapshot)
            } catch (failure: Exception) {
                failures += OriginSceneCleanupFailure("pose", snapshot.id.toString(), failure)
            }
        }

        displays.toMap().forEach { (key, display) ->
            try {
                if (display.isValid) display.remove()
                displays.remove(key)
            } catch (failure: Exception) {
                failures += OriginSceneCleanupFailure("display", key, failure)
            }
        }

        items.toMap().forEach { (key, item) ->
            try {
                if (item.isValid) item.remove()
                items.remove(key)
            } catch (failure: Exception) {
                failures += OriginSceneCleanupFailure("item", key, failure)
            }
        }
        rotations.toMap().forEach { (id, snapshot) ->
            try {
                val (actor, original) = snapshot
                if (actor.isSpawned && actor.entity.world == original.world) {
                    if (id in explicitRotations) actor.getOrAddTrait(RotationTrait::class.java).physicalSession
                        .rotateToHave(original.yaw, original.pitch)
                    actor.entity.setRotation(original.yaw, original.pitch)
                }
                explicitRotations.remove(id)
                rotations.remove(id)
            } catch (failure: Exception) {
                failures += OriginSceneCleanupFailure("rotation", id.toString(), failure)
            }
        }
        lookClose.toMap().forEach { (id, snapshot) ->
            try {
                snapshot.first.lookClose(snapshot.second)
                lookClose.remove(id)
            } catch (failure: Exception) {
                failures += OriginSceneCleanupFailure("look-close", id.toString(), failure)
            }
        }

        return failures
    }

    /** Restores pose snapshots after a return route has stopped moving actors. */
    fun restorePoses() {
        val failures = mutableListOf<OriginSceneCleanupFailure>()
        poses.values.forEach { snapshot ->
            try {
                restorePose(snapshot)
            } catch (failure: Exception) {
                failures += OriginSceneCleanupFailure("pose", snapshot.id.toString(), failure)
            }
        }
        if (failures.isNotEmpty()) {
            throw IllegalStateException("Origin scene pose restoration failed").apply {
                failures.forEach { addSuppressed(it.failure) }
            }
        }
    }

    private fun requireLidded(location: Location): Lidded =
        requireNotNull(location.block.state as? Lidded) {
            "origin scene resource at $location is not a lidded block"
        }

    private fun capturePose(id: Int, entity: Entity) {
        if (id in poses) return
        val cat = entity as? Cat
        val horse = entity as? AbstractHorse
        poses[id] = PoseSnapshot(
            id = id,
            entity = entity,
            pose = entity.pose,
            fixedPose = entity.hasFixedPose(),
            sitting = (entity as? Sittable)?.isSitting,
            catLyingDown = cat?.isLyingDown,
            catHeadUp = cat?.isHeadUp,
            horseEatingGrass = horse?.isEatingGrass,
        )
    }

    private fun clearMovementPose(entity: Entity) {
        entity.setPose(BukkitPose.STANDING, false)
        (entity as? Sittable)?.setSitting(false)
        (entity as? Cat)?.setLyingDown(false)
        (entity as? AbstractHorse)?.setEatingGrass(false)
    }

    private fun restorePose(snapshot: PoseSnapshot) {
        snapshot.entity.setPose(snapshot.pose, snapshot.fixedPose)
        snapshot.sitting?.let { (snapshot.entity as Sittable).setSitting(it) }
        val cat = snapshot.entity as? Cat
        snapshot.catLyingDown?.let { cat?.setLyingDown(it) }
        snapshot.catHeadUp?.let { cat?.setHeadUp(it) }
        snapshot.horseEatingGrass?.let { (snapshot.entity as AbstractHorse).setEatingGrass(it) }
    }
}

/** Caller owns the actor's lifecycle; the target is an absolute world point. */
internal fun faceOriginScenePoint(actor: NPC, target: Location) {
    check(actor.isSpawned && actor.entity.world == target.world)
    val eye = (actor.entity as? LivingEntity)?.eyeLocation ?: actor.entity.location
    val direction = target.toVector().subtract(eye.toVector())
    if (direction.lengthSquared() < 1e-8) return
    val look = eye.clone().setDirection(direction)
    actor.getOrAddTrait(RotationTrait::class.java).physicalSession.rotateToHave(look.yaw, look.pitch)
    actor.entity.setRotation(look.yaw, look.pitch)
}
