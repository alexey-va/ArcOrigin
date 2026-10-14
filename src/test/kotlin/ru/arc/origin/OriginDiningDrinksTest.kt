package ru.arc.origin

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.entity.ItemDisplay
import org.bukkit.inventory.ItemStack
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.function.Consumer

class OriginDiningDrinksTest : StringSpec({
    lateinit var paper: MockBukkitTestRuntime
    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    "mug persists at counter, is picked once, placed at table and becomes empty" {
        val displays = mutableListOf<ItemDisplay>()
        val world = drinkWorld(displays)
        val config = OriginDiningLifeConfig(drinkFullTicks = 40, drinkEmptyTicks = 20)
        val bar = DiningBar(439, OriginDiningPoint(0.0, 73.0, 0.0), setOf(431))
        val drinks = OriginDiningDrinks(config) { ItemStack(Material.PAPER) }
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
        verify(exactly = 2) { displays.last().setItemStack(any()) }
        drinks.tick(60)
        drinks.needsDrink("table") shouldBe true
        verify(exactly = 1) { displays.last().remove() }
        drinks.close()
    }

    "cancel after pickup recreates the counter mug and releases the guest reservation" {
        val displays = mutableListOf<ItemDisplay>()
        val world = drinkWorld(displays)
        val bar = DiningBar(439, OriginDiningPoint(0.0, 73.0, 0.0), setOf(431))
        val drinks = OriginDiningDrinks(OriginDiningLifeConfig()) { ItemStack(Material.PAPER) }
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

private fun drinkWorld(displays: MutableList<ItemDisplay>): World = mockk<World>(relaxed = true) {
    every { isChunkLoaded(any<Int>(), any<Int>()) } returns false
    every { spawn<ItemDisplay>(any<Location>(), ItemDisplay::class.java, any<Consumer<ItemDisplay>>()) } answers {
        val display = mockk<ItemDisplay>(relaxed = true) {
            every { isValid } returns true
            every { teleport(any<Location>()) } returns true
        }
        displays += display
        thirdArg<Consumer<ItemDisplay>>().accept(display)
        display
    }
}
