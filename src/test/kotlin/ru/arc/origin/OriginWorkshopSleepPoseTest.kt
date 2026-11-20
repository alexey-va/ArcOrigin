package ru.arc.origin

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import net.citizensnpcs.api.npc.NPC
import net.citizensnpcs.trait.EntityPoseTrait
import net.citizensnpcs.trait.EntityPoseTrait.EntityPose
import net.citizensnpcs.trait.RotationTrait
import net.citizensnpcs.trait.SleepTrait
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.attribute.Attribute
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.data.type.Bed
import org.bukkit.entity.HumanEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Pose

class OriginWorkshopSleepPoseTest : FreeSpec({
    "rests on the west-facing workshop bed without entering player sleep and releases the pose on wake" {
        val actor = mockk<NPC>()
        val player = mockk<Player>(relaxed = true)
        val legacy = mockk<SleepTrait>()
        val pose = mockk<EntityPoseTrait>(relaxUnitFun = true)
        val rotation = mockk<RotationTrait>(relaxed = true)
        val world = mockk<World>()
        val block = mockk<Block>()
        val bed = mockk<Bed>()
        val destination = slot<Location>()
        every { world.getBlockAt(any<Location>()) } returns block
        every { block.blockData } returns bed
        every { bed.facing } returns BlockFace.WEST
        every { actor.id } returns 430
        every { actor.entity } returns player
        every { actor.isSpawned } returns true
        every { actor.getOrAddTrait(EntityPoseTrait::class.java) } returns pose
        every { actor.getOrAddTrait(RotationTrait::class.java) } returns rotation
        every { actor.getTraitNullable(EntityPoseTrait::class.java) } returns pose
        every { actor.getTraitNullable(SleepTrait::class.java) } returns legacy
        var legacyCleared = false
        every { legacy.setSleeping(null) } answers { legacyCleared = true }
        every { pose.pose } answers { if (legacyCleared) null else EntityPose.SLEEPING }
        every { player.getAttribute(Attribute.SCALE) } returns null
        every { player.teleport(capture(destination)) } returns true
        every { player.hasGravity() } returns true
        every { player.isValid } returns true
        every { player.pose } returns Pose.SLEEPING
        val sleep = OriginWorkshopSleepPose()
        val anchor = Location(world, -56.5, 71.0, -46.5, 90f, 0f)

        sleep.seat(actor, anchor)
        sleep.seat(actor, anchor)
        sleep.isSeated(actor) shouldBe true
        val respawnedPlayer = mockk<Player>()
        every { respawnedPlayer.pose } returns Pose.SLEEPING
        every { actor.entity } returns respawnedPlayer
        sleep.isSeated(actor) shouldBe false
        every { actor.entity } returns player
        sleep.wake(actor)
        sleep.isSeated(actor) shouldBe false

        destination.captured.x shouldBe (-54.98 plusOrMinus 1e-8)
        destination.captured.y shouldBe 71.6875
        destination.captured.z shouldBe -46.5
        destination.captured.yaw shouldBe 0f
        verify { player.bodyYaw = 0f }
        verify { rotation.physicalSession.rotateToHave(0f, 0f) }
        verifyOrder {
            pose.pose = EntityPose.SLEEPING
            pose.run()
            player.setGravity(false)
            player.setGravity(true)
            legacy.setSleeping(null)
            pose.pose = EntityPose.STANDING
            pose.run()
            pose.pose = null
        }
        verify(exactly = 0) { actor.getOrAddTrait(SleepTrait::class.java) }
        verify(exactly = 0) { player.sleep(any(), any()) }
        verify(exactly = 0) { player.wakeup(any()) }
    }

    "clears the Citizens sleep trait before waking a natively sleeping human" {
        val actor = mockk<NPC>()
        val sleepTrait = mockk<SleepTrait>()
        val human = mockk<HumanEntity>()
        every { actor.id } returns 430
        every { actor.getTraitNullable(EntityPoseTrait::class.java) } returns null
        every { actor.getTraitNullable(SleepTrait::class.java) } returns sleepTrait
        every { actor.isSpawned } returns true
        every { actor.entity } returns human
        every { sleepTrait.setSleeping(null) } just runs
        every { human.isSleeping } returns true
        every { human.wakeup(false) } just runs

        OriginWorkshopSleepPose().wake(actor)

        verifyOrder {
            sleepTrait.setSleeping(null)
            human.wakeup(false)
        }
    }

    "clears the trait but does not wake an already awake human" {
        val actor = mockk<NPC>()
        val sleepTrait = mockk<SleepTrait>()
        val human = mockk<HumanEntity>()
        every { actor.id } returns 430
        every { actor.getTraitNullable(EntityPoseTrait::class.java) } returns null
        every { actor.getTraitNullable(SleepTrait::class.java) } returns sleepTrait
        every { actor.isSpawned } returns true
        every { actor.entity } returns human
        every { sleepTrait.setSleeping(null) } just runs
        every { human.isSleeping } returns false

        OriginWorkshopSleepPose().wake(actor)

        verify(exactly = 1) { sleepTrait.setSleeping(null) }
        verify(exactly = 0) { human.wakeup(any()) }
    }

    "clears the trait and avoids native entity access when unspawned" {
        val actor = mockk<NPC>()
        val sleepTrait = mockk<SleepTrait>()
        every { actor.id } returns 430
        every { actor.getTraitNullable(EntityPoseTrait::class.java) } returns null
        every { actor.getTraitNullable(SleepTrait::class.java) } returns sleepTrait
        every { actor.isSpawned } returns false
        every { sleepTrait.setSleeping(null) } just runs

        OriginWorkshopSleepPose().wake(actor)

        verify(exactly = 1) { sleepTrait.setSleeping(null) }
        verify(exactly = 0) { actor.entity }
    }
})
