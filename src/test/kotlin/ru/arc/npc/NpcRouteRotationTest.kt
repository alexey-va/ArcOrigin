package ru.arc.npc

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import net.citizensnpcs.api.npc.NPC
import net.citizensnpcs.trait.RotationTrait
import org.bukkit.entity.LivingEntity
import org.bukkit.World
import org.bukkit.Bukkit
import ru.arc.core.ScheduledTask
import ru.arc.core.TaskScheduler
import ru.arc.core.Tasks

class NpcRouteRotationTest : FreeSpec({
    afterEach { NpcRouteRotation.closeAll() }
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
        every { entity.world } returns mockk<World>()
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

        var tick = 0
        val rotation = NpcRouteRotation(npc, 5f, currentTick = { ++tick })
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

    "an action hands its actual body angle and the current tick budget to a new route" {
        val fixture = RotationFixture()
        var tick = 1
        var cancelled = 0
        val action = NpcRouteRotation(fixture.npc, 18f, { tick }, { cancelled++ })
        action.turnToward(130f) shouldBe false
        fixture.bodyYaw shouldBe 18f

        val route = NpcRouteRotation(fixture.npc, 18f, { tick })
        cancelled shouldBe 1
        action.isLatestOwner shouldBe false
        route.yaw shouldBe 18f
        route.turnToward(-90f) shouldBe false
        fixture.bodyYaw shouldBe 18f
        action.turnToward(130f) shouldBe false
        tick++
        route.turnToward(-90f) shouldBe false
        fixture.bodyYaw shouldBe 0f
        action.close()
        fixture.bodyYaw shouldBe 0f
        cancelled shouldBe 1
    }

    "a completed owner may restore facing until another action claims it" {
        val fixture = RotationFixture()
        val action = NpcRouteRotation(fixture.npc, 18f, { 1 })
        action.turnToward(10f) shouldBe true
        action.close()
        action.isLatestOwner shouldBe true
        val replacement = NpcRouteRotation(fixture.npc, 18f, { 2 })
        action.isLatestOwner shouldBe false
        replacement.yaw shouldBe 10f
    }

    "stationary turns schedule one tick at a time and cancel when a route takes ownership" {
        val fixture = RotationFixture()
        val scheduler = mockk<TaskScheduler>(relaxed = true)
        val handle = mockk<ScheduledTask>(relaxed = true)
        every { scheduler.runTimer(1L, 1L, any()) } returns handle
        mockkStatic(Bukkit::class)
        every { Bukkit.getCurrentTick() } returns 1
        try {
            Tasks.withScheduler(scheduler) {
                val action = smoothNpcRotation(fixture.npc, 140f)
                fixture.bodyYaw shouldBe 18f
                val route = NpcRouteRotation(fixture.npc, 18f, { 2 })
                action.canTurn shouldBe false
                verify(exactly = 1) { handle.cancel() }
                route.turnToward(-90f)
                fixture.bodyYaw shouldBe 0f
            }
        } finally {
            unmockkStatic(Bukkit::class)
        }
    }
})

private class RotationFixture {
    val npc = mockk<NPC>()
    val entity = mockk<LivingEntity>(relaxed = true)
    private val trait = mockk<RotationTrait>(relaxed = true)
    var bodyYaw = 0f

    init {
        every { npc.entity } returns entity
        every { npc.isSpawned } returns true
        every { npc.getOrAddTrait(RotationTrait::class.java) } returns trait
        every { entity.world } returns mockk<World>()
        every { entity.bodyYaw } answers { bodyYaw }
        every { entity.bodyYaw = any() } answers { bodyYaw = firstArg() }
        every { trait.createPacketSession(any()) } answers {
            mockk<RotationTrait.PacketRotationSession>(relaxed = true)
        }
    }
}
