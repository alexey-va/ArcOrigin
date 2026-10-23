package ru.arc.origin

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.inventory.ItemStack
import ru.arc.paper.testing.MockBukkitTestRuntime
import ru.arc.paper.display.PacketItemDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.origin.scene.OriginSceneResources

class OriginDiningDrinksTest : StringSpec({
    lateinit var paper: MockBukkitTestRuntime
    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    "mug persists at counter, is picked once, placed at table and becomes empty" {
        val displays = mutableListOf<PacketItemDisplay>()
        val renderer = drinkRenderer(displays)
        val world = mockk<World>(relaxed = true)
        val config = OriginDiningLifeConfig(drinkFullTicks = 40, drinkEmptyTicks = 20)
        val bar = DiningBar(439, OriginDiningPoint(0.0, 73.0, 0.0), setOf(431))
        val drinks = OriginDiningDrinks(config, { ItemStack(Material.PAPER) },
            { OriginSceneResources(createDisplays = { renderer }) })
        drinks.ready(bar, Location(world, 0.0, 73.0, 0.0))
        drinks.ready(bar, Location(world, 0.0, 73.0, 0.0))
        displays.size shouldBe 1
        val order = drinks.reserve("table", 431)!!
        drinks.reserve("other", 431) shouldBe null
        verify(exactly = 0) { displays.first().remove() }
        drinks.pickup(order)!!.type shouldBe Material.PAPER
        drinks.pickup(order) shouldBe null
        verify(exactly = 1) { displays.first().remove() }
        drinks.deliver(order, Location(world, 5.0, 73.0, 0.0)) shouldBe displays.last()
        drinks.deliver(order, Location(world, 5.0, 73.0, 0.0)) shouldBe null
        drinks.needsDrink("table") shouldBe false
        drinks.hasPending(439) shouldBe false
        drinks.tick(40)
        verify(exactly = 2) { displays.last().itemStack = any() }
        drinks.tick(60)
        drinks.needsDrink("table") shouldBe true
        verify(exactly = 1) { displays.last().remove() }
        drinks.close()
        verify(exactly = 0) { renderer.close(); renderer.closeAsync() }
    }

    "cancel after pickup recreates the counter mug and releases the guest reservation" {
        val displays = mutableListOf<PacketItemDisplay>()
        val renderer = drinkRenderer(displays)
        val world = mockk<World>(relaxed = true)
        val bar = DiningBar(439, OriginDiningPoint(0.0, 73.0, 0.0), setOf(431))
        val drinks = OriginDiningDrinks(OriginDiningLifeConfig(), { ItemStack(Material.PAPER) },
            { OriginSceneResources(createDisplays = { renderer }) })
        drinks.ready(bar, Location(world, 0.0, 73.0, 0.0))
        val order = drinks.reserve("table", 431)!!
        drinks.pickup(order)
        drinks.cancel(order)
        drinks.needsDrink("table") shouldBe true
        drinks.hasPending(439) shouldBe true
        displays.size shouldBe 2
        drinks.reserve("next", 431) shouldBe order
        drinks.close()
        verify(exactly = 1) { displays.last().remove() }
    }
})

private fun drinkRenderer(displays: MutableList<PacketItemDisplay>): PaperPacketDisplays = mockk(relaxed = true) {
    every { spawnItem(any(), any()) } answers {
        val display = mockk<PacketItemDisplay>(relaxed = true) {
            every { isValid } returns true
        }
        displays += display
        display
    }
}
