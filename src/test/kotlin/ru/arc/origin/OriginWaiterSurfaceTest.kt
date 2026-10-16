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
    "both ordinary bartender entrances keep a compact optional shift journal" {
        val config = waiterTestConfig()
        config.venues shouldBe listOf(OriginWaiterVenue("brewery", 405, false), OriginWaiterVenue("restaurant", 439, false))
        config.lifetimeMillis shouldBe 180_000
        config.preparationMillis shouldBe 10_000
        config.shiftMillis shouldBe 600_000
        config.graceMillis shouldBe 120_000
        val screen = waiterScreen(config, config.venues.last(), null, emptyList(), 20, SideJobRewardStatus(true, 20, 0, false), 0)
        screen.title.color() shouldBe TextColor.color(0xFFB277)
        screen.title.decoration(TextDecoration.ITALIC) shouldBe TextDecoration.State.FALSE
        screen.buttons.map { it.id.value } shouldBe listOf("waiter_start")
        screen.buttons.single().closeDialogBeforeAction shouldBe true
        val text = screen.body.joinToString("\n") { plain(it.text) }
        text shouldContain "10 минут"
        text shouldContain "20 💰"
        text shouldContain "до двух заказов"
        val coins = screen.body.flatMap { components(it.text) }.filter { plain(it) == "💰" }
        coins.size shouldBe 2
        coins.forEach { it.color() shouldBe NamedTextColor.WHITE }
    }

    "two-ticket board exposes readiness course sequence and exact independent actions" {
        val config = waiterTestConfig()
        val shift = OriginWorkShift.begin(0)
        val first = OriginWaiterJob(waiterTarget(), 0, 180_000, OriginWaiterStage.PICKUP, 10_000)
        val second = OriginWaiterJob(waiterTarget(guest = 434), 0, 180_000, OriginWaiterStage.PICKUP, 30_000,
            family = OriginWaiterFamily.DRINK_FIRST)
        val screen = waiterScreen(config, config.venues.last(), shift, listOf(first, second), 20, null, 20_000)
        screen.id shouldBe "dining.waiter.restaurant"
        val text = screen.body.joinToString("\n") { plain(it.text) }
        text shouldContain "Гость 433"
        text shouldContain "Гость 434"
        text shouldContain "Готово"
        text shouldContain "После напитка"
        text shouldContain "Ещё 10 с"
        screen.buttons.map { it.id.value } shouldBe listOf("waiter_focus_0", "waiter_pickup_0_0", "waiter_focus_1", "waiter_refresh", "waiter_finish")
        screen.buttons.single { it.id.value == "waiter_refresh" }.closeDialogBeforeAction shouldBe false
        screen.buttons.filter { it.id.value != "waiter_refresh" }.all { it.closeDialogBeforeAction } shouldBe true
    }

    "carrying hides unavailable pickups and revision presents original and replacement choices" {
        val config = waiterTestConfig()
        val shift = OriginWorkShift.begin(0)
        val revised = OriginWaiterJob(waiterTarget(), 0, 180_000, OriginWaiterStage.PICKUP, 10_000,
            family = OriginWaiterFamily.REVISED, clarified = true)
        val screen = waiterScreen(config, config.venues.last(), shift, listOf(revised), 20, null, 20_000)
        screen.buttons.map { it.id.value } shouldBe listOf("waiter_focus_0", "waiter_pickup_0_0", "waiter_pickup_0_1", "waiter_refresh", "waiter_finish")
        screen.body.joinToString("\n") { plain(it.text) } shouldContain "Уточнение гостя: вместо прежнего блюда подай «Запечённая рыба»"
        val carrying = revised.copy(target = waiterTarget(guest = 434), stage = OriginWaiterStage.DELIVER)
        val handsFull = waiterScreen(config, config.venues.last(), shift, listOf(revised, carrying), 20, null, 20_000)
        handsFull.buttons.any { it.id.value.startsWith("waiter_pickup") } shouldBe false
        val rework = waiterScreen(config, config.venues.last(), shift, listOf(revised.copy(stage = OriginWaiterStage.REWORK)), 20, null, 60_000)
        rework.buttons.any { it.id.value == "waiter_remake_0" } shouldBe true
    }

    "receipt names confirmed orders coins quality duration and separately unpaid work" {
        val config = waiterTestConfig()
        val shift = OriginWorkShift.begin(0).record(java.util.UUID.randomUUID(), 20, 85)
        val receipt = OriginWaiterReceipt(shift, 600_000, 1)
        val lines = waiterReceiptLines(config, receipt).joinToString("\n")
        lines shouldContain "10:00"
        lines shouldContain "заказов: 1"
        lines shouldContain "20 💰"
        lines shouldContain "85%"
        lines shouldContain "не подтверждена"
    }

    "quota cooldown and unresolved payment explain different states and hide start" {
        val config = waiterTestConfig()
        for ((status, text) in listOf(
            SideJobRewardStatus(true, 0, 0, false) to "Дневной лимит в 100 поручений",
            SideJobRewardStatus(true, 5, 20_000, false) to "через 20 с",
            SideJobRewardStatus(false, 5, 0, true) to "Оплату проверяет касса",
            SideJobRewardStatus(false, 20, 0, false) to "Касса временно недоступна",
        )) {
            val screen = waiterScreen(config, config.venues.last(), null, emptyList(), 20, status, 0)
            screen.buttons shouldBe emptyList()
            screen.body.joinToString("\n") { plain(it.text) } shouldContain text
        }
    }

    "legacy configuration gains shift keys without overwriting operator values and merges once" {
        val directory = Files.createTempDirectory("waiter-legacy")
        try {
            val file = directory.resolve("origin-dining.yml")
            Files.writeString(file, "waiter-game:\n  timeout-seconds: 240\n  preparation-seconds: 12\n  text:\n    title: Своя смена\noperator-key: preserved\n")
            val source = Config(directory, "origin-dining.yml")
            source.mergeMissingFromBundled("modules/origin-dining.yml") shouldBe true
            val config = OriginWaiterConfig.load(source)
            config.lifetimeMillis shouldBe 240_000
            config.preparationMillis shouldBe 12_000
            config.shiftMillis shouldBe 600_000
            config.line("title") shouldBe "Своя смена"
            source.string("operator-key") shouldBe "preserved"
            val merged = Files.readString(file)
            Config(directory, "origin-dining.yml").mergeMissingFromBundled("modules/origin-dining.yml") shouldBe false
            Files.readString(file) shouldBe merged
            source.setInt("waiter-game.shift-seconds", 100_000)
            source.setInt("waiter-game.finish-grace-seconds", -1)
            val bounded = OriginWaiterConfig.load(source)
            bounded.shiftMillis shouldBe 600_000
            bounded.graceMillis shouldBe 0
        } finally { directory.toFile().deleteRecursively() }
    }

    "NPC legacy colors never leak into literal names" {
        waiterNpcName("&6&lБармен Реми") shouldBe "Бармен Реми"
        waiterNpcName("§fЛев") shouldBe "Лев"
        waiterNpcName("<red>Лев") shouldBe "<red>Лев"
    }
})

private fun plain(component: Component): String = PlainTextComponentSerializer.plainText().serialize(component)
private fun components(component: Component): List<Component> = listOf(component) + component.children().flatMap(::components)
