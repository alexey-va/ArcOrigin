package ru.arc.worldcontent

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import ru.arc.paper.menu.PaperDialogClickContext

class BreweryTableDialogsTest : FreeSpec({
    val player = mockk<Player>(relaxed = true)
    val context = mockk<PaperDialogClickContext>()
    val location = mockk<Location>(relaxed = true)
    val world = mockk<World>(relaxed = true)
    var x = BreweryTableDialogs.BREWERY_X
    var y = BreweryTableDialogs.BREWERY_Y
    var z = BreweryTableDialogs.BREWERY_Z

    beforeEach {
        x = BreweryTableDialogs.BREWERY_X
        y = BreweryTableDialogs.BREWERY_Y
        z = BreweryTableDialogs.BREWERY_Z
        clearMocks(player, context, location, world)
        every { player.isOnline } returns true
        every { player.location } returns location
        every { location.world } returns world
        every { world.name } returns BreweryTableDialogs.ORIGIN_WORLD
        every { location.x } answers { x }
        every { location.y } answers { y }
        every { location.z } answers { z }
        every { player.performCommand(any()) } returns true
        every { context.player } returns player
    }

    "food screen is native, priced, and has no close button" {
        val screen = BreweryTableDialogs.orderScreen(player, BreweryTableDialogs.Menu.FOOD)
        val body = screen.body.joinToString("\n") { PlainTextComponentSerializer.plainText().serialize(it.text) }
        screen.id shouldBe "dining.food.order"
        body shouldContain "блюдо"
        screen.buttons.map { it.id.value } shouldContainExactly listOf("food_egg", "food_fish", "food_steak")
        screen.buttons.map { PlainTextComponentSerializer.plainText().serialize(it.label) } shouldContainExactly
            listOf("Яичница с травами · 300 💰", "Запечённая рыба · 450 💰", "Стейк с перцем · 650 💰")
        screen.buttons.map { it.label.children().single().color() }.all { it == TextColor.color(0xFFFFFF) } shouldBe true
        screen.buttons.all { it.closeDialogBeforeAction } shouldBe true
    }

    "campfire screen offers three drinks" {
        val screen = BreweryTableDialogs.orderScreen(player, BreweryTableDialogs.Menu.DRINKS)
        screen.id shouldBe "dining.drinks.order"
        screen.buttons.map { it.id.value } shouldContainExactly
            listOf("drinks_herbal_tea", "drinks_berry_kvass", "drinks_spiced_mead")
        screen.buttons.map { PlainTextComponentSerializer.plainText().serialize(it.label) } shouldContainExactly
            listOf("Пиво · 250 💰", "Красное вино · 400 💰", "Медовуха · 600 💰")
    }

    "restaurant includes meals and one drink" {
        x = BreweryTableDialogs.RESTAURANT_X
        y = BreweryTableDialogs.RESTAURANT_Y
        z = BreweryTableDialogs.RESTAURANT_Z
        val screen = BreweryTableDialogs.orderScreen(player, BreweryTableDialogs.Menu.RESTAURANT)
        screen.buttons.map { it.id.value } shouldContainExactly
            listOf("restaurant_egg", "restaurant_fish", "restaurant_steak", "restaurant_herbal_tea")
    }

    "courtyard combines all fixed food and drink choices" {
        val screen = BreweryTableDialogs.orderScreen(player, BreweryTableDialogs.Menu.COURTYARD)
        screen.id shouldBe "dining.courtyard.order"
        screen.buttons.map { it.id.value } shouldContainExactly
            listOf(
                "courtyard_egg",
                "courtyard_fish",
                "courtyard_steak",
                "courtyard_herbal_tea",
                "courtyard_berry_kvass",
                "courtyard_spiced_mead",
            )
    }

    "callbacks use fixed commands and recheck the venue" {
        BreweryTableDialogs.orderScreen(player, BreweryTableDialogs.Menu.FOOD).buttons.forEach { it.onClick.handle(context) }
        verify(exactly = 1) { player.performCommand("rcbreweryorder egg") }
        verify(exactly = 1) { player.performCommand("rcbreweryorder fish") }
        verify(exactly = 1) { player.performCommand("rcbreweryorder steak") }
        x = BreweryTableDialogs.BREWERY_X + BreweryTableDialogs.BREWERY_RADIUS + 0.01
        BreweryTableDialogs.orderScreen(player, BreweryTableDialogs.Menu.FOOD).buttons.first().onClick.handle(context)
        verify(exactly = 3) { player.performCommand(any()) }
    }
})
