package ru.arc.origin

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import ru.arc.config.Config
import java.nio.file.Files

internal fun waiterTestConfig(): OriginWaiterConfig {
    val path = Files.createTempFile("waiter-config", ".yml")
    OriginWaiterConfig::class.java.getResourceAsStream("/modules/origin-dining.yml")!!.use { Files.write(path, it.readBytes()) }
    return OriginWaiterConfig.load(Config(path.parent, path.fileName.toString()))
}

class OriginWaiterSurfaceTest : StringSpec({
    "both bartender entrances preserve the configured trade gesture" {
        val config = waiterTestConfig()
        config.venues shouldBe listOf(OriginWaiterVenue("brewery", 405, true), OriginWaiterVenue("restaurant", 439, false))
        config.lifetimeMillis shouldBe 180_000
        config.preparationMillis shouldBe 10_000
        val screen = waiterScreen(config, config.venues.last(), null, 20, SideJobRewardStatus(true, 20, 0, false), 0)
        screen.title.color() shouldBe TextColor.color(0xFFB277)
        screen.title.decoration(TextDecoration.ITALIC) shouldBe TextDecoration.State.FALSE
        screen.buttons.map { it.id.value } shouldBe listOf("waiter_start")
        screen.buttons.single().closeDialogBeforeAction shouldBe true
        screen.body.map { plain(it.text) }.joinToString("\n") shouldContain "20 💰"
        val coins = screen.body.flatMap { components(it.text) }.filter { plain(it) == "💰" }
        coins.size shouldBe 2
        coins.forEach { it.color() shouldBe NamedTextColor.WHITE }
    }

    "recipient kitchen and delivery states have explicit next actions and cancellation" {
        val config = waiterTestConfig()
        val venue = config.venues.last()
        val target = waiterTarget()
        for (stage in OriginWaiterStage.entries) {
            val job = OriginWaiterJob(target, 0, 180_000, stage, 10_000)
            val screen = waiterScreen(config, venue, job, 20, null, 20_000)
            screen.id shouldBe "dining.waiter.restaurant"
            val text = screen.body.joinToString("\n") { plain(it.text) }
            text shouldContain target.guestName
            if (stage != OriginWaiterStage.TAKE_ORDER) text shouldContain target.dishName
            screen.buttons.map { it.id.value } shouldBe when (stage) {
                OriginWaiterStage.PICKUP -> listOf("waiter_pickup", "waiter_cancel")
                OriginWaiterStage.PAYOUT -> emptyList()
                else -> listOf("waiter_cancel")
            }
        }
    }

    "quota cooldown and unresolved payment explain different states" {
        val config = waiterTestConfig()
        val venue = config.venues.last()
        for ((status, text) in listOf(
            SideJobRewardStatus(true, 0, 0, false) to "На сегодня",
            SideJobRewardStatus(true, 5, 20_000, false) to "через 20 с",
            SideJobRewardStatus(false, 5, 0, true) to "Оплату проверяет касса",
            SideJobRewardStatus(false, 20, 0, false) to "Касса временно недоступна",
        )) {
            val screen = waiterScreen(config, venue, null, 20, status, 0)
            screen.buttons shouldBe emptyList()
            screen.body.joinToString("\n") { plain(it.text) } shouldContain text
        }
    }

    "NPC legacy colors never leak into literal names" {
        waiterNpcName("&6&lБармен Реми") shouldBe "Бармен Реми"
        waiterNpcName("§fЛев") shouldBe "Лев"
        waiterNpcName("<red>Лев") shouldBe "<red>Лев"
    }
})

private fun plain(component: Component): String = PlainTextComponentSerializer.plainText().serialize(component)
private fun components(component: Component): List<Component> = listOf(component) + component.children().flatMap(::components)
