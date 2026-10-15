package ru.arc.npc

import net.citizensnpcs.api.npc.NPC
import net.citizensnpcs.trait.RotationTrait
import org.bukkit.Bukkit
import org.bukkit.entity.LivingEntity
import ru.arc.core.LifecycleTaskScope
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.abs

/** One route owns both the physical orientation and Citizens' rendered body/head pose. */
internal class NpcRouteRotation(
    private val npc: NPC,
    private val maxDegreesPerTick: Float,
    private val currentTick: () -> Int = { Bukkit.getCurrentTick() },
    private val onClose: () -> Unit = {},
) : AutoCloseable {
    private class Ownership {
        var rotation: WeakReference<NpcRouteRotation>? = null
        var lastTurnTick: Int? = null
    }

    companion object {
        // Main-thread only. Weak values must not retain the weak NPC keys after despawn.
        private val owners = WeakHashMap<NPC, Ownership>()

        fun closeAll() {
            owners.values.mapNotNull { it.rotation?.get() }.forEach(NpcRouteRotation::close)
            owners.clear()
        }
    }

    private val ownership = owners.getOrPut(npc) { Ownership() }.also {
        require(maxDegreesPerTick.isFinite() && maxDegreesPerTick > 0f)
        it.rotation?.get()?.close()
    }
    private val entity = npc.entity
    private val world = entity.world
    private val rotation = npc.getOrAddTrait(RotationTrait::class.java)
    // Start from the body, which may still face elsewhere after an idle head-only look.
    var yaw = normalizedNpcYaw((entity as? LivingEntity)?.bodyYaw ?: entity.yaw)
        private set
    var pitch = entity.pitch
        private set
    private val parameters = RotationTrait.RotationParams().filter { true }.immediate(true).linkedBody(true)
    private val packets = rotation.createPacketSession(parameters)
    private var closed = false

    /** Completed actions may restore their pose, but never overwrite a newer route or action. */
    val isLatestOwner: Boolean get() = ownership.rotation?.get() === this
    val canTurn: Boolean get() = !closed && isLatestOwner && npc.isSpawned && npc.entity === entity && entity.world == world

    init {
        ownership.rotation = WeakReference(this)
        // createPacketSession runs immediately: do not activate its default zero-degree target.
        packets.session.rotateToHave(yaw, pitch)
        parameters.persist(true)
    }

    fun turnToward(targetYaw: Float, targetPitch: Float = 0f): Boolean {
        if (!canTurn) return false
        val tick = currentTick()
        // A route can replace a scene gesture in the same tick. Share the tick budget across owners.
        if (ownership.lastTurnTick == tick) return alignedWith(targetYaw, targetPitch)
        ownership.lastTurnTick = tick
        yaw = turnNpcYawToward(yaw, targetYaw, maxDegreesPerTick)
        pitch += (targetPitch - pitch).coerceIn(-maxDegreesPerTick, maxDegreesPerTick)
        applyPhysical()
        packets.session.rotateToHave(yaw, pitch)
        packets.run(entity)
        return alignedWith(targetYaw, targetPitch)
    }

    private fun alignedWith(targetYaw: Float, targetPitch: Float) =
        abs(normalizedNpcYaw(targetYaw - yaw)) < 0.01f && abs(targetPitch - pitch) < 0.01f

    private fun applyPhysical() {
        entity.setRotation(yaw, pitch)
        (entity as? LivingEntity)?.bodyYaw = yaw
        // The physical session is paused during navigation; replace any stale idle target.
        rotation.physicalSession.rotateToHave(yaw, pitch)
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            packets.end()
            if (isLatestOwner && npc.isSpawned && npc.entity === entity && entity.world == world) applyPhysical()
        } finally {
            onClose()
        }
    }
}

/** Idle actions use the same body/head owner and rate limit as a walking route. */
internal fun smoothNpcRotation(npc: NPC, yaw: Float, pitch: Float = 0f): NpcRouteRotation {
    require(yaw.isFinite() && pitch.isFinite())
    val tasks = LifecycleTaskScope()
    val rotation = try {
        NpcRouteRotation(npc, 18f, onClose = tasks::close)
    } catch (failure: Exception) {
        tasks.close()
        throw failure
    }
    try {
        // Moving workstation targets may update every tick. Advance once now so replacement
        // cannot perpetually postpone the first step; the shared tick budget prevents a double turn.
        if (rotation.turnToward(yaw, pitch)) rotation.close()
        else tasks.runTimer(1L, 1L) {
            if (!rotation.canTurn || rotation.turnToward(yaw, pitch)) rotation.close()
        }
    } catch (failure: Exception) {
        rotation.close()
        throw failure
    }
    return rotation
}
