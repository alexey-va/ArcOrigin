package ru.arc.npc

import net.citizensnpcs.api.npc.NPC
import net.citizensnpcs.trait.RotationTrait
import org.bukkit.entity.LivingEntity
import kotlin.math.abs

/** One route owns both the physical orientation and Citizens' rendered body/head pose. */
internal class NpcRouteRotation(private val npc: NPC, private val maxDegreesPerTick: Float) : AutoCloseable {
    private val entity = npc.entity
    private val rotation = npc.getOrAddTrait(RotationTrait::class.java)
    // Start from the body, which may still face elsewhere after an idle head-only look.
    var yaw = normalizedNpcYaw((entity as? LivingEntity)?.bodyYaw ?: entity.yaw)
        private set
    var pitch = entity.pitch
        private set
    private val parameters = RotationTrait.RotationParams().filter { true }.immediate(true).linkedBody(true)
    private val packets = rotation.createPacketSession(parameters)
    private var closed = false

    init {
        require(maxDegreesPerTick.isFinite() && maxDegreesPerTick > 0f)
        // createPacketSession runs immediately: do not activate its default zero-degree target.
        packets.session.rotateToHave(yaw, pitch)
        parameters.persist(true)
    }

    fun turnToward(targetYaw: Float, targetPitch: Float = 0f): Boolean {
        if (closed) return false
        yaw = turnNpcYawToward(yaw, targetYaw, maxDegreesPerTick)
        pitch += (targetPitch - pitch).coerceIn(-maxDegreesPerTick, maxDegreesPerTick)
        applyPhysical()
        packets.session.rotateToHave(yaw, pitch)
        packets.run(entity)
        return abs(normalizedNpcYaw(targetYaw - yaw)) < 0.01f && abs(targetPitch - pitch) < 0.01f
    }

    private fun applyPhysical() {
        entity.setRotation(yaw, pitch)
        (entity as? LivingEntity)?.bodyYaw = yaw
        // The physical session is paused during navigation; replace any stale idle target.
        rotation.physicalSession.rotateToHave(yaw, pitch)
    }

    override fun close() {
        if (closed) return
        closed = true
        packets.end()
        if (npc.isSpawned && npc.entity === entity) applyPhysical()
    }
}
