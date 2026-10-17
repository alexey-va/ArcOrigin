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
    "both entrances explain the shift and require explicit acceptance with consistent native controls" {
        val config = waiterTestConfig()
        config.venues shouldBe listOf(OriginWaiterVenue("brewery", 405, false, 411, "brewery-kitchen"), OriginWaiterVenue("restaurant", 439, false))
        config.lifetimeMillis shouldBe 180_000
        config.preparationMillis shouldBe 6_000
        config.shiftMillis shouldBe 600_000
        config.graceMillis shouldBe 120_000
        val screen = waiterScreen(config, config.venues.last(), null, emptyList(), 20, SideJobRewardStatus(true, 20, 0, false), 0)
        plain(screen.title) shouldBe "Смена · Ресторан"
        screen.title.color() shouldBe TextColor.color(0xFFB277)
        screen.title.decoration(TextDecoration.ITALIC) shouldBe TextDecoration.State.FALSE
        screen.buttons.map { plain(it.label) } shouldBe listOf("Выйти на смену", "Не сейчас")
        screen.buttons.all { it.closeDialogBeforeAction } shouldBe true
        val text = screen.body.joinToString("\n") { plain(it.text) }
        text shouldContain "10 минут"
        text shouldContain "20 💰"
        text shouldContain "Принимай заказы"
        screen.body.flatMap { components(it.text) }.filter { plain(it) == "💰" }
            .forEach { it.color() shouldBe NamedTextColor.WHITE }
    }

    "an active shift has only continue and end with no order selection or dialog gameplay actions" {
        val config = waiterTestConfig()
        for (stage in OriginWaiterStage.entries) {
            val job = OriginWaiterJob(waiterTarget(), 0, 180_000, stage, 10_000)
            val screen = waiterScreen(config, config.venues.last(), OriginWorkShift.begin(0), listOf(job), 20, null, 20_000)
            screen.id shouldBe "dining.waiter.restaurant"
            screen.buttons.map { plain(it.label) } shouldBe listOf("Продолжить смену", "Завершить смену")
            screen.buttons.map { it.id.value } shouldBe listOf("waiter_continue", "waiter_finish")
            screen.buttons.last().label.color() shouldBe TextColor.color(0xFF6B61)
            screen.body.joinToString("\n") { plain(it.text) } shouldContain "Незаконченные заказы отменятся"
        }
    }

    "a receipt has two short colored rows and a white currency glyph" {
        val config = waiterTestConfig()
        val shift = OriginWorkShift.begin(0).record(java.util.UUID.randomUUID(), 20, 85)
        val receipt = OriginWaiterReceipt(shift, 600_000, 0)
        waiterReceiptLines(config, receipt) shouldBe listOf("Смена завершена · 10:00", "Заказов: 1 · Получено 20 💰")
        val message = waiterReceipt(config, receipt)
        plain(message) shouldBe "\n  Смена завершена · 10:00\n  Заказов: 1 · Получено 20 💰\n"
        components(message).filter { plain(it) == "💰" }.single().color() shouldBe NamedTextColor.WHITE
        components(message).any { it.color() == TextColor.color(0xFFB277) } shouldBe true
        components(message).any { it.color() == TextColor.color(0x9BD48D) } shouldBe true
        waiterReceiptLines(config, receipt.copy(unpaidOrders = 1)).last() shouldContain "Оплата ожидается"
    }

    "quota and unresolved payment explain why acceptance is unavailable" {
        val config = waiterTestConfig()
        for ((status, text) in listOf(
            SideJobRewardStatus(true, 0, 0, false) to "100 поручений",
            SideJobRewardStatus(false, 5, 0, true) to "Касса ещё проверяет",
            SideJobRewardStatus(false, 20, 0, false) to "Касса временно недоступна",
        )) {
            val screen = waiterScreen(config, config.venues.last(), null, emptyList(), 20, status, 0)
            screen.buttons.map { it.id.value } shouldBe listOf("waiter_cancel")
            screen.body.joinToString("\n") { plain(it.text) } shouldContain text
        }
    }

    "legacy payout deadlines do not delay admission and unconfirmed earnings are not a payment receipt" {
        val config = waiterTestConfig()
        val ready = waiterScreen(config, config.venues.last(), null, emptyList(), 20,
            SideJobRewardStatus(true, 5, 20_000, false), 0)
        ready.buttons.first().id.value shouldBe "waiter_start"
        ready.body.joinToString(" ") { plain(it.text) }.contains("через 20") shouldBe false
        val shift = OriginWorkShift.begin(0).record(java.util.UUID.randomUUID(), 20, 100)
        val receipt = waiterReceipt(config, OriginWaiterReceipt(shift, 1000, 0, paid = false))
        plain(receipt) shouldContain "Заработано 20 💰"
        plain(receipt).contains("Получено") shouldBe false
        components(receipt).filter { plain(it) == "💰" }.single().color() shouldBe NamedTextColor.WHITE
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
