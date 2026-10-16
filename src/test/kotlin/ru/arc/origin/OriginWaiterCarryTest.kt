package ru.arc.origin

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import io.mockk.verifyOrder
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.ItemDisplay
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Transformation
import org.joml.Vector3f
import org.mockbukkit.mockbukkit.entity.PlayerMock
import ru.arc.paper.display.PacketItemDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.testing.failOnUnsupportedMockBukkitOperation

class OriginWaiterCarryTest : StringSpec({
    lateinit var paper: MockBukkitTestRuntime
    beforeEach {
        paper = MockBukkitTestRuntime.open()
        mockkObject(OriginDiningLayout)
        every { OriginDiningLayout.lifeConfig } returns OriginDiningLifeConfig()
        every { OriginDiningLayout.displayScale(any()) } returns 0.65f
        every { OriginDiningLayout.displayLift(any()) } returns 0.24375f
    }
    afterEach { unmockkObject(OriginDiningLayout); paper.close() }

    "carried dish follows body yaw and keeps the verified support height while crouching" {
        val standing = waiterCarryTransform(0.0, 1.8, false, 0.65f, 0.24375f, 0.32, 0.45, 1.05)
        standing.translation.distance(Vector3f(0.32f, -0.50625f, 0.45f)) shouldBeLessThan 1e-6f
        standing.scale shouldBe Vector3f(0.65f)
        val turned = waiterCarryTransform(90.0, 1.8, false, 0.65f, 0.24375f, 0.32, 0.45, 1.05)
        turned.translation.distance(Vector3f(-0.45f, -0.50625f, 0.32f)) shouldBeLessThan 1e-6f
        val crouched = waiterCarryTransform(0.0, 1.5, true, 0.65f, 0.24375f, 0.32, 0.45, 1.05)
        crouched.translation.distance(Vector3f(0.32f, -0.45625f, 0.45f)) shouldBeLessThan 1e-6f
    }

    "one visible packet dish mounts once updates its pose and cleans up without an inventory item" {
        failOnUnsupportedMockBukkitOperation {
            val player = waiterCarrier(paper)
            player.teleport(Location(paper.addSimpleWorld(OriginDiningLayout.WORLD), 0.0, 70.0, 0.0))
            val before = player.inventory.contents.toList()
            val display = mockk<PacketItemDisplay>(relaxed = true)
            val owner = mockk<PaperPacketDisplays>(relaxed = true)
            every { owner.spawnItem(any(), any()) } returns display
            val carry = OriginWaiterCarry({ ItemStack(Material.PAPER) }, { owner })
            carry.pickup(player, "egg") shouldBe true
            carry.pickup(player, "egg") shouldBe false
            verifyOrder {
                display.isVisibleByDefault = true
                display.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.GROUND
                display.transformation = any()
                display.attachTo(player)
            }
            carry.tick()
            verify(exactly = 2) { display.transformation = any<Transformation>() }
            verify(exactly = 1) { display.attachTo(player) }
            verify(exactly = 0) { display.teleport(any()) }
            player.inventory.contents.toList() shouldBe before
            carry.remove(player.uniqueId)
            carry.remove(player.uniqueId)
            carry.close()
            carry.close()
            verify(exactly = 1) { display.remove() }
            verify(exactly = 1) { owner.close() }
        }
    }

    "missing model and failed attachment never leave a carried prop" {
        failOnUnsupportedMockBukkitOperation {
            val player = waiterCarrier(paper)
            val display = mockk<PacketItemDisplay>(relaxed = true)
            val owner = mockk<PaperPacketDisplays>(relaxed = true)
            every { owner.spawnItem(any(), any()) } returns display
            val missing = OriginWaiterCarry({ null }, { owner })
            missing.pickup(player, "egg") shouldBe false
            missing.close()
            verify(exactly = 0) { owner.spawnItem(any(), any()) }

            every { display.attachTo(player) } throws IllegalStateException("packet mount failed")
            val carry = OriginWaiterCarry({ ItemStack(Material.PAPER) }, { owner })
            shouldThrow<IllegalStateException> { carry.pickup(player, "egg") }
            carry.remove(player.uniqueId)
            carry.close()
            verify(exactly = 1) { display.remove() }
            verify(exactly = 1) { owner.close() }
        }
    }
})

/** MockBukkit does not implement Paper body yaw; the pose contract is tested above. */
private fun waiterCarrier(paper: MockBukkitTestRuntime): PlayerMock =
    object : PlayerMock(paper.server, "Carrier") {
        override fun getBodyYaw(): Float = 0f
        override fun getHeight(): Double = 1.8
    }.also(paper.server::addPlayer)
