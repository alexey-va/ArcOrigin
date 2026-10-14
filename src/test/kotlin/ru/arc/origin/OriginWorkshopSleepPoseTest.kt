package ru.arc.origin

import io.kotest.core.spec.style.FreeSpec
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import io.mockk.verifyOrder
import net.citizensnpcs.api.npc.NPC
import net.citizensnpcs.trait.SleepTrait
import org.bukkit.entity.HumanEntity

class OriginWorkshopSleepPoseTest : FreeSpec({
    "clears the Citizens sleep trait before waking a natively sleeping human" {
        val actor = mockk<NPC>()
        val sleepTrait = mockk<SleepTrait>()
        val human = mockk<HumanEntity>()
        every { actor.getTraitNullable(SleepTrait::class.java) } returns sleepTrait
        every { actor.isSpawned } returns true
        every { actor.entity } returns human
        every { sleepTrait.setSleeping(null) } just runs
        every { human.isSleeping } returns true
        every { human.wakeup(false) } just runs

        wakeOriginWorkshopNpc(actor)

        verifyOrder {
            sleepTrait.setSleeping(null)
            human.wakeup(false)
        }
    }

    "clears the trait but does not wake an already awake human" {
        val actor = mockk<NPC>()
        val sleepTrait = mockk<SleepTrait>()
        val human = mockk<HumanEntity>()
        every { actor.getTraitNullable(SleepTrait::class.java) } returns sleepTrait
        every { actor.isSpawned } returns true
        every { actor.entity } returns human
        every { sleepTrait.setSleeping(null) } just runs
        every { human.isSleeping } returns false

        wakeOriginWorkshopNpc(actor)

        verify(exactly = 1) { sleepTrait.setSleeping(null) }
        verify(exactly = 0) { human.wakeup(any()) }
    }

    "clears the trait and avoids native entity access when unspawned" {
        val actor = mockk<NPC>()
        val sleepTrait = mockk<SleepTrait>()
        every { actor.getTraitNullable(SleepTrait::class.java) } returns sleepTrait
        every { actor.isSpawned } returns false
        every { sleepTrait.setSleeping(null) } just runs

        wakeOriginWorkshopNpc(actor)

        verify(exactly = 1) { sleepTrait.setSleeping(null) }
        verify(exactly = 0) { actor.entity }
    }
})
