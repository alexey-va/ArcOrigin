package ru.arc.origin

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Interaction
import ru.arc.config.ConfigManager
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PacketTextDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files

class OriginSlimefunTerminalTest : FreeSpec({
    "reload replaces terminal coordinates and copy while retaining the old snapshot" {
        withTerminalConfig { path ->
            val file = path.resolve("modules/origin-slimefun-terminal.yml")
            val before = SlimefunTerminalSettings.load(path)
            PlainTextComponentSerializer.plainText().serialize(before.line("screen")) shouldBe "СЛАЙМФАН\nПКМ"
            Files.writeString(file, Files.readString(file).replace("x: -17.25", "x: -16.25")
                .replace("Войти в Слаймфан", "Открыть Слаймфан"))
            ConfigManager.reloadAll()
            val after = SlimefunTerminalSettings.load(path)
            before.x shouldBe -17.25
            after.x shouldBe -16.25
            PlainTextComponentSerializer.plainText().serialize(after.line("enter")) shouldBe "Открыть Слаймфан"
        }
    }

    "menu enters only through its primary action and has a native close footer" {
        withTerminalConfig { path ->
            var calls = 0
            val screen = slimefunTerminalDialog(SlimefunTerminalSettings.load(path)) { calls++ }
            SlimefunTerminalPreviewExport.exportDialog(screen)
            screen.buttons.single().id.value shouldBe "enter_slimefun"
            screen.buttons.single().closeDialogBeforeAction shouldBe true
            screen.buttons.single().onClick.handle(mockk())
            calls shouldBe 1
            requireNotNull(screen.exitButton).onClick.handle(mockk())
            calls shouldBe 1
            screen.canCloseWithEscape shouldBe true
            screen.body.single().width shouldBe 310
        }
    }

    "the configured console rests on its platform and leaves the portal centerline clear" {
        withTerminalConfig { path ->
            val settings = SlimefunTerminalSettings.load(path)
            val world = mockk<World>(relaxed = true)
            val parts = slimefunTerminalParts()
            parts.minOf { it.y - it.height / 2 } shouldBe (0.0 plusOrMinus 1e-9)
            val corners = parts.flatMap { part ->
                listOf(-1, 1).flatMap { a -> listOf(-1, 1).map { b ->
                    settings.location(world, part.x + a * part.width / 2, part.y, part.z + b * part.depth / 2)
                } }
            }
            corners.minOf { it.z } shouldBe (-50.65 plusOrMinus 1e-9)
            corners.maxOf { it.z } shouldBe (-49.55 plusOrMinus 1e-9)
            (corners.minOf { it.z } - -52.5 > 1.8) shouldBe true
        }
    }

    "closing a console retires its one nonpersistent native input and every borrowed display once" {
        withTerminalConfig { path ->
            val settings = SlimefunTerminalSettings.load(path)
            val world = mockk<World>(relaxed = true)
            val owner = mockk<PaperPacketDisplays>()
            val hitbox = mockk<Interaction>(relaxed = true)
            val text = mockk<PacketTextDisplay>(relaxed = true)
            val blocks = mutableListOf<PacketBlockDisplay>()
            every { owner.spawnBlock(any(), any()) } answers { mockk<PacketBlockDisplay>(relaxed = true).also(blocks::add) }
            every { owner.spawnText(any(), any()) } returns text
            every { world.spawn(any<Location>(), Interaction::class.java) } returns hitbox
            val runtime = SlimefunTerminalRuntime(settings, world, owner)
            runtime.start()
            verify(exactly = 1) { hitbox.isPersistent = false }
            runtime.close()
            runtime.close()
            verify(exactly = 1) { hitbox.remove() }
            verify(exactly = 1) { text.remove() }
            blocks.size shouldBe 9
            blocks.forEach { block -> verify(exactly = 1) { block.remove() } }
            verify(exactly = 0) { owner.close() }
        }
    }
})

private fun withTerminalConfig(action: (java.nio.file.Path) -> Unit) {
    val paper = MockBukkitTestRuntime.open()
    val path = Files.createTempDirectory("slimefun-terminal-test")
    try {
        val file = path.resolve("modules/origin-slimefun-terminal.yml")
        Files.createDirectories(file.parent)
        val source = requireNotNull(OriginSlimefunTerminalTest::class.java.classLoader
            .getResourceAsStream("modules/origin-slimefun-terminal.yml")).bufferedReader().use { it.readText() }
        Files.writeString(file, source)
        action(path)
    } finally {
        ConfigManager.clear()
        paper.close()
        path.toFile().deleteRecursively()
    }
}
