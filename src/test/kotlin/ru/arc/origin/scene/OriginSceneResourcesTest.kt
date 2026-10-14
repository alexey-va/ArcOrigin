package ru.arc.origin.scene

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkStatic
import io.mockk.verify
import net.citizensnpcs.api.npc.NPC
import net.citizensnpcs.api.trait.trait.Equipment as CitizensEquipment
import net.citizensnpcs.trait.LookClose
import net.citizensnpcs.trait.RotationTrait
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockState
import org.bukkit.block.Lidded
import org.bukkit.block.data.BlockData
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Cat
import org.bukkit.entity.Entity
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Pose as BukkitPose
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock as mockitoMock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify as mockitoVerify
import org.mockito.kotlin.whenever
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.function.Consumer

class OriginSceneResourcesTest : StringSpec({
    lateinit var paper: MockBukkitTestRuntime

    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    "empty hand is captured once, including null, and repeated equip restores it" {
        val npc = mockNpc(17)
        val equipment = mockitoMock<CitizensEquipment>()
        every { npc.getOrAddTrait(CitizensEquipment::class.java) } returns equipment
        whenever(equipment.get(CitizensEquipment.EquipmentSlot.HAND)).thenReturn(null)

        val resources = OriginSceneResources()
        resources.equip(npc, Material.IRON_SWORD)
        resources.equip(npc, Material.DIAMOND_SWORD)
        resources.cleanup() shouldBe emptyList()

        mockitoVerify(equipment, times(1)).get(CitizensEquipment.EquipmentSlot.HAND)
        mockitoVerify(equipment).set(CitizensEquipment.EquipmentSlot.HAND, null)
    }

    "normal hand state is restored exactly after cleanup" {
        val npc = mockNpc(18)
        val equipment = mockitoMock<CitizensEquipment>()
        val original = ItemStack(Material.STICK, 2)
        every { npc.getOrAddTrait(CitizensEquipment::class.java) } returns equipment
        whenever(equipment.get(CitizensEquipment.EquipmentSlot.HAND)).thenReturn(original)

        val resources = OriginSceneResources()
        resources.equip(npc, Material.IRON_SWORD)
        resources.cleanup() shouldBe emptyList()

        mockitoVerify(equipment).set(CitizensEquipment.EquipmentSlot.HAND, original)
    }

    "custom hand item is cloned before scene ownership" {
        val npc = mockNpc(22)
        val equipment = mockitoMock<CitizensEquipment>()
        val custom = ItemStack(Material.PAPER, 3)
        every { npc.getOrAddTrait(CitizensEquipment::class.java) } returns equipment
        whenever(equipment.get(CitizensEquipment.EquipmentSlot.HAND)).thenReturn(null)

        val resources = OriginSceneResources()
        resources.equip(npc, custom)
        custom.amount = 1

        val equipped = argumentCaptor<ItemStack>()
        mockitoVerify(equipment).set(eq(CitizensEquipment.EquipmentSlot.HAND), equipped.capture())
        equipped.firstValue.amount shouldBe 3
    }

    "face restores the original native rotation during cleanup" {
        val npc = mockk<NPC>()
        val entity = mockitoMock<Entity>()
        val world = mockk<World>(relaxed = true)
        val lookClose = mockk<LookClose>()
        val original = Location(world, 12.0, 64.0, -4.0, 37.0f, 11.0f)
        val target = Location(world, 20.0, 64.0, -4.0)
        every { npc.id } returns 23
        every { npc.isSpawned } returns true
        every { npc.entity } returns entity
        every { npc.hasTrait(LookClose::class.java) } returns true
        every { npc.getTraitNullable(LookClose::class.java) } returns lookClose
        every { npc.faceLocation(target) } just runs
        every { lookClose.isEnabled } returns true
        every { lookClose.lookClose(any()) } just runs
        whenever(entity.world).thenReturn(world)
        whenever(entity.location).thenReturn(original)

        val resources = OriginSceneResources()
        resources.face(npc, target)
        resources.cleanup() shouldBe emptyList()

        verify(exactly = 1) { npc.faceLocation(target) }
        verify(exactly = 1) { lookClose.lookClose(false) }
        verify(exactly = 1) { lookClose.lookClose(true) }
        mockitoVerify(entity).setRotation(37.0f, 11.0f)
    }

    "horizontal conversation ignores partner height and restores its rotation owner" {
        val npc = mockk<NPC>()
        val entity = mockitoMock<Entity>()
        val world = mockk<World>(relaxed = true)
        val rotation = mockk<RotationTrait>(relaxed = true)
        every { npc.id } returns 419
        every { npc.isSpawned } returns true
        every { npc.entity } returns entity
        every { npc.hasTrait(LookClose::class.java) } returns false
        every { npc.getOrAddTrait(RotationTrait::class.java) } returns rotation
        whenever(entity.world).thenReturn(world)
        whenever(entity.location).thenReturn(Location(world, 0.0, 70.0, 0.0, 37f, 0f))
        val resources = OriginSceneResources()
        resources.faceHorizontal(npc, Location(world, 2.0, 74.0, 0.0))
        resources.faceHorizontal(npc, Location(world, 2.0, 74.0, 0.0), pitch = 6f)
        resources.cleanup() shouldBe emptyList()
        verify { rotation.physicalSession.rotateToHave(-90f, 0f) }
        verify { rotation.physicalSession.rotateToHave(-90f, 6f) }
        verify { rotation.physicalSession.rotateToHave(37f, 0f) }
        verify(exactly = 0) { npc.faceLocation(any()) }
        mockitoVerify(entity).setRotation(-90f, 0f)
        mockitoVerify(entity).setRotation(37f, 0f)
    }

    "work point above feet but below eyes makes a seated guest look down" {
        val npc = mockk<NPC>()
        val entity = mockitoMock<LivingEntity>()
        val world = mockk<World>(relaxed = true)
        val rotation = mockk<RotationTrait>(relaxed = true)
        every { npc.id } returns 419
        every { npc.isSpawned } returns true
        every { npc.entity } returns entity
        every { npc.hasTrait(LookClose::class.java) } returns false
        every { npc.getOrAddTrait(RotationTrait::class.java) } returns rotation
        whenever(entity.world).thenReturn(world)
        whenever(entity.location).thenReturn(Location(world, 0.0, 70.0, 0.0, 0f, 0f))
        whenever(entity.eyeLocation).thenReturn(Location(world, 0.0, 71.5, 0.0))
        val resources = OriginSceneResources()
        resources.facePoint(npc, Location(world, 0.0, 71.0, 0.5))
        verify { rotation.physicalSession.rotateToHave(0f, 45f) }
        mockitoVerify(entity).setRotation(0f, 45f)
        verify(exactly = 0) { npc.faceLocation(any()) }
        resources.cleanup() shouldBe emptyList()
        verify { rotation.physicalSession.rotateToHave(0f, 0f) }
    }

    "use item is cleared during cleanup" {
        val npc = mockk<NPC>()
        every { npc.id } returns 439
        val entity = mockitoMock<LivingEntity>()
        every { npc.entity } returns entity
        whenever(entity.hasActiveItem()).thenReturn(false)
        whenever(entity.isValid).thenReturn(true)

        val resources = OriginSceneResources()
        resources.useItem(npc)
        resources.cleanup() shouldBe emptyList()

        mockitoVerify(entity).startUsingItem(EquipmentSlot.HAND)
        mockitoVerify(entity).clearActiveItem()
    }

    "container opening failure remains owned and is retried by cleanup" {
        val location = mockk<Location>(relaxed = true)
        val block = mockk<Block>(relaxed = true)
        val liddedState = mockk<BlockState>(moreInterfaces = arrayOf(Lidded::class), relaxed = true)
        val lidded = liddedState as Lidded
        every { location.clone() } returns location
        every { location.block } returns block
        every { block.state } returns liddedState
        every { lidded.open() } throws IllegalStateException("opening failed")
        every { lidded.close() } just runs

        val resources = OriginSceneResources()
        shouldThrow<IllegalStateException> { resources.setContainer(location, open = true) }

        resources.cleanup() shouldBe emptyList()
        verify(exactly = 1) { lidded.close() }
    }

    "non-lidded container is rejected instead of silently skipped" {
        val location = mockk<Location>(relaxed = true)
        val block = mockk<Block>(relaxed = true)
        every { location.block } returns block
        every { block.state } returns mockk<BlockState>()

        shouldThrow<IllegalArgumentException> {
            OriginSceneResources().setContainer(location, open = true)
        }
    }

    "display removal is idempotent" {
        val display = mockDisplay()
        val world = mockWorld(display)
        val resources = OriginSceneResources()
        withBukkitBlockData {
            resources.updateDisplay("hammer", world, resolvedProp(), displayStep(), setOf("scene-test")) shouldBe true
            resources.displayCount shouldBe 1

            resources.removeDisplay("hammer") shouldBe true
            resources.removeDisplay("hammer") shouldBe false
        }

        verify(exactly = 1) { display.remove() }
    }

    "invalid non-block material does not spawn or become owned" {
        val display = mockDisplay()
        val world = mockWorld(display)
        val resources = OriginSceneResources()

        shouldThrow<IllegalArgumentException> {
            resources.updateDisplay("invalid", world, resolvedProp(), displayStep("DIAMOND"), setOf("scene-test"))
        }

        resources.displayCount shouldBe 0
        verify(exactly = 0) {
            world.spawn<BlockDisplay>(any<Location>(), BlockDisplay::class.java, any<Consumer<BlockDisplay>>())
        }
    }

    "failed cleanup removes healthy resources and retries failed ones" {
        val npc = mockNpc(19)
        val equipment = mockitoMock<CitizensEquipment>()
        every { npc.getOrAddTrait(CitizensEquipment::class.java) } returns equipment
        whenever(equipment.get(CitizensEquipment.EquipmentSlot.HAND)).thenReturn(null)
        var setCalls = 0
        doAnswer {
            setCalls++
            if (setCalls == 2) throw IllegalStateException("restore failed")
            null
        }.whenever(equipment).set(eq(CitizensEquipment.EquipmentSlot.HAND), anyOrNull())

        val display = mockDisplay()
        val world = mockWorld(display)
        val resources = OriginSceneResources()
        resources.equip(npc, Material.IRON_SWORD)
        withBukkitBlockData {
            resources.updateDisplay("hammer", world, resolvedProp(), displayStep(), setOf("scene-test")) shouldBe true
        }

        val firstFailures = resources.cleanup()
        firstFailures.map { it.resource to it.id } shouldContainExactly listOf("hand" to "19")
        resources.displayCount shouldBe 0
        verify(exactly = 1) { display.remove() }

        resources.cleanup() shouldBe emptyList()
        mockitoVerify(equipment, times(3)).set(eq(CitizensEquipment.EquipmentSlot.HAND), anyOrNull())
    }

    "display mutation failure is owned and cleaned up" {
        val display = mockDisplay()
        val world = mockWorld(display)
        every { display.block = any() } throws IllegalStateException("display mutation failed")

        val resources = OriginSceneResources()
        withBukkitBlockData {
            shouldThrow<IllegalStateException> {
                resources.updateDisplay("broken", world, resolvedProp(), displayStep(), setOf("scene-test"))
            }
        }
        resources.displayCount shouldBe 1

        resources.cleanup() shouldBe emptyList()
        verify(exactly = 1) { display.remove() }
        resources.displayCount shouldBe 0
    }

    "item displays stay within the budget and cleanup removes every owned item" {
        val spawned = mutableListOf<ItemDisplay>()
        val world = mockk<World>(relaxed = true) {
            every {
                spawn<ItemDisplay>(any<Location>(), ItemDisplay::class.java, any<Consumer<ItemDisplay>>())
            } answers {
                val display = mockk<ItemDisplay>(relaxed = true) {
                    every { isValid } returns true
                    every { teleport(any<Location>()) } returns true
                }
                spawned += display
                thirdArg<Consumer<ItemDisplay>>().accept(display)
                display
            }
        }
        val resources = OriginSceneResources()
        val location = Location(world, 12.0, 64.0, -4.0)

        repeat(4) { index ->
            resources.item("item-$index", location, ItemStack(Material.PAPER), 0.5f, 0.1f)
        }
        resources.displayCount shouldBe 4
        shouldThrow<IllegalStateException> {
            resources.item("item-over-budget", location, ItemStack(Material.PAPER), 0.5f, 0.1f)
        }

        resources.cleanup() shouldBe emptyList()
        resources.displayCount shouldBe 0
        spawned.forEach { verify(exactly = 1) { it.remove() } }
    }

    "ItemsAdder scene display restaging reuses the same entity key and removal owns it" {
        val display = mockk<ItemDisplay>(relaxed = true) {
            every { isValid } returns true
            every { teleport(any<Location>()) } returns true
        }
        var spawnCount = 0
        val world = mockk<World>(relaxed = true) {
            every {
                spawn<ItemDisplay>(any<Location>(), ItemDisplay::class.java, any<Consumer<ItemDisplay>>())
            } answers {
                spawnCount++
                thirdArg<Consumer<ItemDisplay>>().accept(display)
                display
            }
        }
        val resources = OriginSceneResources()
        val stack = ItemStack(Material.PAPER)
        val scale = OriginSceneVector(1.0, 1.0, 1.0)

        resources.updateItemDisplay(
            "wheelbarrow",
            Location(world, 12.0, 64.0, -4.0, 180f, 0f),
            stack,
            OriginSceneItemDisplayContext.GROUND,
            scale,
            4,
            setOf("scene-test"),
        ) shouldBe true
        resources.updateItemDisplay(
            "wheelbarrow",
            Location(world, 18.0, 70.078125, 69.5, 90f, 0f),
            stack,
            OriginSceneItemDisplayContext.GROUND,
            scale,
            4,
            setOf("scene-test"),
        ) shouldBe false

        spawnCount shouldBe 1
        resources.displayCount shouldBe 1
        verify(exactly = 2) { display.setItemStack(any()) }
        verify(exactly = 2) { display.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.GROUND }
        resources.removeDisplay("wheelbarrow") shouldBe true
        resources.removeDisplay("wheelbarrow") shouldBe false
        resources.cleanup() shouldBe emptyList()
        verify(exactly = 1) { display.remove() }
    }

    "item display setup failure remains owned for cleanup" {
        val display = mockk<ItemDisplay>(relaxed = true) {
            every { isValid } returns true
            every { teleport(any<Location>()) } returns true
            every { setItemStack(any()) } throws IllegalStateException("item setup failed")
        }
        val world = mockk<World>(relaxed = true) {
            every {
                spawn<ItemDisplay>(any<Location>(), ItemDisplay::class.java, any<Consumer<ItemDisplay>>())
            } answers {
                thirdArg<Consumer<ItemDisplay>>().accept(display)
                display
            }
        }
        val resources = OriginSceneResources()

        shouldThrow<IllegalStateException> {
            resources.updateItemDisplay(
                "broken-cart",
                Location(world, 0.0, 64.0, 0.0),
                ItemStack(Material.PAPER),
                OriginSceneItemDisplayContext.GROUND,
                OriginSceneVector(1.0, 1.0, 1.0),
                4,
                setOf("scene-test"),
            )
        }

        resources.displayCount shouldBe 1
        resources.cleanup() shouldBe emptyList()
        resources.displayCount shouldBe 0
        verify(exactly = 1) { display.remove() }
    }

    "rejected display teleport fails with an owned resource for cleanup" {
        val display = mockDisplay()
        every { display.teleport(any<Location>()) } returns false
        val world = mockWorld(display)
        val resources = OriginSceneResources()

        withBukkitBlockData {
            shouldThrow<IllegalStateException> {
                resources.updateDisplay("teleport-failed", world, resolvedProp(), displayStep(), setOf("scene-test"))
            }
        }
        resources.displayCount shouldBe 1
        resources.cleanup() shouldBe emptyList()
        verify(exactly = 1) { display.remove() }
    }

    "animal pose changes clear for movement and restore the original state" {
        val npc = mockNpc(20)
        val cat = mockitoMock<Cat>()
        every { npc.entity } returns cat
        whenever(cat.pose).thenReturn(BukkitPose.STANDING)
        whenever(cat.hasFixedPose()).thenReturn(false)
        whenever(cat.isLyingDown).thenReturn(true)
        whenever(cat.isHeadUp).thenReturn(false)

        val resources = OriginSceneResources()
        resources.setPose(npc, OriginScenePose.CAT_LIE)
        mockitoVerify(cat).setLyingDown(true)
        resources.clearMovementPose(npc)
        mockitoVerify(cat, times(2)).setLyingDown(false)

        resources.cleanup() shouldBe emptyList()
        mockitoVerify(cat, times(3)).setPose(BukkitPose.STANDING, false)
        mockitoVerify(cat, times(2)).setLyingDown(true)
    }

    "cat sit and lie poses are mutually exclusive and restore the original state" {
        val npc = mockNpc(21)
        val cat = mockitoMock<Cat>()
        var sitting = false
        every { npc.entity } returns cat
        whenever(cat.pose).thenReturn(BukkitPose.STANDING)
        whenever(cat.hasFixedPose()).thenReturn(false)
        whenever(cat.isSitting).thenReturn(false)
        whenever(cat.isLyingDown).thenReturn(false)
        whenever(cat.isHeadUp).thenReturn(false)
        doAnswer {
            sitting = it.getArgument<Boolean>(0)
            null
        }.whenever(cat).setSitting(any())

        val resources = OriginSceneResources()
        resources.setPose(npc, OriginScenePose.SIT)
        resources.setPose(npc, OriginScenePose.CAT_LIE)
        sitting shouldBe false
        resources.cleanup() shouldBe emptyList()
        sitting shouldBe false
    }
})

private fun mockNpc(id: Int): NPC = mockk {
    every { this@mockk.id } returns id
}

private fun mockDisplay(): BlockDisplay = mockk(relaxed = true) {
    every { isValid } returns true
    every { teleport(any<Location>()) } returns true
}

private fun mockWorld(display: BlockDisplay): World = mockk(relaxed = true) {
    every {
        spawn<BlockDisplay>(any<Location>(), BlockDisplay::class.java, any<Consumer<BlockDisplay>>())
    } answers {
        thirdArg<Consumer<BlockDisplay>>().accept(display)
        display
    }
}

private fun resolvedProp() = OriginSceneResolvedProp(
    x = 12.0,
    y = 64.0,
    z = -4.0,
    translationX = -0.25f,
    translationY = 0f,
    translationZ = -0.15f,
)

private fun displayStep(material: String = "IRON_BLOCK") = OriginSceneStep.BlockDisplay(
    key = "hammer",
    surface = null,
    anchor = "forge",
    material = material,
    origin = OriginScenePropOrigin.BOTTOM_CENTER,
    offset = OriginSceneVector.ZERO,
    scale = OriginSceneVector(0.5, 0.5, 0.5),
    rotationYDegrees = 90f,
    interpolationTicks = 4,
)

private fun <T> withBukkitBlockData(block: () -> T): T {
    val blockData = mockk<BlockData>()
    mockkStatic(Bukkit::class)
    return try {
        every { Bukkit.createBlockData(Material.IRON_BLOCK) } returns blockData
        block()
    } finally {
        unmockkStatic(Bukkit::class)
    }
}
