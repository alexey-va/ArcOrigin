package ru.arc.origin

import io.kotest.core.spec.style.FreeSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import ru.arc.config.ConfigManager
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files

class OriginSlimefunWorkshopConfigTest : FreeSpec({
    "the paired wall layout rejects unsupported quarter-turn tuning" {
        shouldThrow<IllegalArgumentException> {
            SlimefunWorkshopStation("press", SlimefunWorkshopMachine.PRESS, 462,
                -10.5, 71.0, -45.5, 90.0, 1.0, SlimefunWorkshopPoint(-.65, 0.0, -1.45), 20)
        }.message shouldBe "Slimefun workshop wall stations require yaw 0 or 180"
    }

    "normal config reload replaces timing and placement without rewriting the previous snapshot" {
        val paper = MockBukkitTestRuntime.open()
        val path = Files.createTempDirectory("slimefun-workshop-config")
        try {
            val file = path.resolve("modules/origin-slimefun-workshop.yml")
            Files.createDirectories(file.parent)
            val source = requireNotNull(OriginSlimefunWorkshopConfigTest::class.java.classLoader.getResourceAsStream("modules/origin-slimefun-workshop.yml"))
                .bufferedReader().use { it.readText() }
            Files.writeString(file, source)
            val before = SlimefunWorkshopSettings.load(path)
            before.enabled shouldBe true
            before.stations.map { it.npcId } shouldBe listOf(462, 463)
            before.stations.map { it.z } shouldBe listOf(-43.55, -60.65)
            Files.writeString(file, source.replace("load-ticks: 70", "load-ticks: 90").replace("x: -10.5", "x: -11.5"))
            ConfigManager.reloadAll()
            val after = SlimefunWorkshopSettings.load(path)
            before.timing.load shouldBe 70
            before.stations.first().x shouldBe -10.5
            after.timing.load shouldBe 90
            after.stations.first().x shouldBe -11.5
        } finally {
            ConfigManager.clear()
            paper.close()
            path.toFile().deleteRecursively()
        }
    }
})
