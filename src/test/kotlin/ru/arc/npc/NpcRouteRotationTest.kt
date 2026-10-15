package ru.arc.npc

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.citizensnpcs.api.npc.NPC
import net.citizensnpcs.trait.RotationTrait
import org.bukkit.entity.LivingEntity

class NpcRouteRotationTest : FreeSpec({
    "body turns use the shortest angle from the real body and close without a stale target" {
        val npc = mockk<NPC>()
        val entity = mockk<LivingEntity>(relaxed = true)
        val trait = mockk<RotationTrait>()
        val packet = mockk<RotationTrait.PacketRotationSession>(relaxed = true)
        val packetRotation = mockk<RotationTrait.RotationSession>(relaxed = true)
        val physicalRotation = mockk<RotationTrait.RotationSession>(relaxed = true)
        every { npc.entity } returns entity
        every { npc.isSpawned } returns true
        every { npc.getOrAddTrait(RotationTrait::class.java) } returns trait
        every { entity.bodyYaw } returns 170f
        every { entity.yaw } returns 20f
        every { entity.pitch } returns 0f
        every { trait.createPacketSession(any()) } answers {
            // A session must be inactive while Citizens initializes it: its default target is zero.
            trait.RotationSession(firstArg()).isActive shouldBe false
            packet
        }
        every { trait.physicalSession } returns physicalRotation
        every { packet.session } returns packetRotation

        val rotation = NpcRouteRotation(npc, 5f)
        verify(exactly = 1) { packetRotation.rotateToHave(170f, 0f) }
        verify(exactly = 0) { entity.setRotation(any(), any()) }
        rotation.turnToward(-170f) shouldBe false
        rotation.yaw shouldBe 175f
        rotation.turnToward(-170f) shouldBe false
        rotation.yaw shouldBe -180f
        rotation.turnToward(-170f) shouldBe false
        rotation.turnToward(-170f) shouldBe true
        verify { entity.bodyYaw = -170f }
        verify { physicalRotation.rotateToHave(-170f, 0f) }
        rotation.close()
        rotation.close()
        rotation.turnToward(0f) shouldBe false
        rotation.yaw shouldBe -170f
        verify(exactly = 1) { packet.end() }
        verify(exactly = 4) { packet.run(entity) }
        verify(exactly = 0) { packetRotation.rotateToHave(0f, 0f) }
    }
})
