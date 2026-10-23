package ru.arc.npc

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.npc.NPC
import net.citizensnpcs.trait.RotationTrait
import net.citizensnpcs.util.NMS
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.EntityType
import org.bukkit.World
import org.bukkit.Bukkit
import org.bukkit.Location
import ru.arc.core.ScheduledTask
import ru.arc.core.TaskScheduler
import ru.arc.core.Tasks

class NpcRouteRotationTest : FreeSpec({
    afterEach { NpcRouteRotation.closeAll() }
    "body turns use native tracking each tick through the yaw seam without extra rotation packets" {
        val npc = mockk<NPC>()
        val entity = mockk<LivingEntity>(relaxed = true)
        val trait = mockk<RotationTrait>()
        val physicalRotation = mockk<RotationTrait.RotationSession>(relaxed = true)
        every { npc.entity } returns entity
        every { npc.isSpawned } returns true
        every { npc.getOrAddTrait(RotationTrait::class.java) } returns trait
        every { entity.world } returns mockk<World>()
        every { entity.bodyYaw } returns 170f
        every { entity.yaw } returns 20f
        every { entity.pitch } returns 0f
        every { trait.physicalSession } returns physicalRotation

        var tick = 0
        val rotation = NpcRouteRotation(npc, 5f, currentTick = { ++tick })
        verify(exactly = 0) { entity.setRotation(any(), any()) }
        rotation.turnToward(-170f) shouldBe false
        rotation.yaw shouldBe 175f
        rotation.turnToward(-170f) shouldBe false
        rotation.yaw shouldBe -180f
        rotation.turnToward(-170f) shouldBe false
        rotation.turnToward(-170f) shouldBe true
        listOf(175f, -180f, -175f, -170f).forEach { yaw ->
            verify(exactly = 1) { entity.setRotation(yaw, 0f) }
            verify(exactly = 1) { entity.bodyYaw = yaw }
            verify(exactly = 1) { physicalRotation.rotateToHave(yaw, 0f) }
        }
        rotation.close()
        rotation.close()
        rotation.turnToward(0f) shouldBe false
        rotation.yaw shouldBe -170f
        verify(exactly = 5) { entity.setRotation(any(), any()) }
        verify(exactly = 0) { trait.createPacketSession(any()) }
        verify(exactly = 0) { physicalRotation.rotateToHave(0f, 0f) }
    }

    "the Citizens idle runner keeps the authored body head and pitch after every turn" {
        org.junit.jupiter.api.Assumptions.assumeTrue(
            RotationTrait::class.java.classLoader.getResource("com/mojang/authlib/GameProfileRepository.class") != null,
            "Citizens NMS method signatures require Mojang authlib, absent from MockBukkit's runtime; idle rotation is also checked against the exact live Citizens bytecode.")
        val npc = mockk<NPC>(relaxed = true)
        val entity = mockk<LivingEntity>(relaxed = true)
        val world = mockk<World>()
        mockkStatic(CitizensAPI::class)
        val trait = try {
            every { CitizensAPI.getTraitFactory() } returns mockk(relaxed = true)
            RotationTrait().also { it.linkToNPC(npc) }
        } finally {
            unmockkStatic(CitizensAPI::class)
        }
        var head = 170f
        var body = 170f
        var pitch = 0f
        every { npc.entity } returns entity
        every { npc.isSpawned } returns true
        every { npc.getOrAddTrait(RotationTrait::class.java) } returns trait
        every { npc.data().get(NPC.Metadata.RESET_PITCH_ON_TICK, false) } returns false
        every { npc.navigator.isNavigating } returns false
        every { entity.world } returns world
        every { entity.type } returns EntityType.HORSE
        every { entity.bodyYaw } answers { body }
        every { entity.bodyYaw = any() } answers { body = firstArg() }
        every { entity.pitch } answers { pitch }
        every { entity.location } answers { Location(world, 0.0, 70.0, 0.0, head, pitch) }
        every { entity.setRotation(any(), any()) } answers { head = firstArg(); pitch = secondArg() }
        mockkStatic(NMS::class)
        every { NMS.getYaw(entity) } answers { body }
        every { NMS.getHeadYaw(entity) } answers { head }
        every { NMS.setBodyYaw(entity, any()) } answers { body = secondArg() }
        every { NMS.setHeadYaw(entity, any()) } answers { head = secondArg() }
        every { NMS.setPitch(entity, any()) } answers { pitch = secondArg() }
        try {
            var tick = 0
            val rotation = NpcRouteRotation(npc, 5f, { ++tick })
            repeat(4) {
                rotation.turnToward(-170f, 10f)
                trait.run()
                listOf(body, head, pitch) shouldBe listOf(rotation.yaw, rotation.yaw, rotation.pitch)
            }
            rotation.close()
            trait.run()
            listOf(body, head, pitch) shouldBe listOf(-170f, -170f, 10f)
            verify(exactly = 0) { NMS.sendRotationPacketNearby(entity, any(), any(), any(), any()) }
        } finally {
            unmockkStatic(NMS::class)
        }
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
    }
}
