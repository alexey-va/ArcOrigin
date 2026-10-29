package ru.arc.origin

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.event.CitizensEnableEvent
import net.citizensnpcs.api.npc.NPCRegistry
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.event.HandlerList
import org.bukkit.plugin.PluginManager
import ru.arc.core.Tasks
import ru.arc.core.TestTaskScheduler
import java.nio.file.Path
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger

class OriginSlimefunWorkshopLifecycleTest : StringSpec({
    "an enabled Citizens plugin waits for its save load and then exposes a genuinely missing actor" {
        SlimefunStartupFixture().use { fixture ->
            OriginSlimefunWorkshopModule.init()
            fixture.scheduler.timerCount() shouldBe 1
            fixture.scheduler.tick(21)
            fixture.failures().size shouldBe 0

            OriginSlimefunWorkshopModule.onCitizensEnable(mockk<CitizensEnableEvent>())
            fixture.failures().single().thrown.message shouldBe "Station 'press' NPC 462 is missing"
            fixture.scheduler.timerCount() shouldBe 0
            OriginSlimefunWorkshopModule.onCitizensEnable(mockk<CitizensEnableEvent>())
            fixture.failures().size shouldBe 1
        }
    }

    "startup retry is bounded and reload or shutdown cancels the previous wait" {
        SlimefunStartupFixture().use { fixture ->
            OriginSlimefunWorkshopModule.init()
            fixture.scheduler.tick(600)
            fixture.failures().single().thrown.message shouldBe "Station 'press' NPC 462 is missing"
            fixture.scheduler.timerCount() shouldBe 0

            OriginSlimefunWorkshopModule.reload()
            OriginSlimefunWorkshopModule.reload()
            fixture.scheduler.timerCount() shouldBe 1
            OriginSlimefunWorkshopModule.shutdown()
            fixture.scheduler.timerCount() shouldBe 0
            fixture.scheduler.tick(600)
            OriginSlimefunWorkshopModule.onCitizensEnable(mockk<CitizensEnableEvent>())
            fixture.failures().size shouldBe 1
            verify(exactly = 1) { fixture.plugins.registerEvents(OriginSlimefunWorkshopModule, fixture.plugin) }
            verify(exactly = 1) { HandlerList.unregisterAll(OriginSlimefunWorkshopModule) }
        }
    }
})

private class SlimefunStartupFixture : AutoCloseable {
    val scheduler = TestTaskScheduler()
    val plugins = mockk<PluginManager>(relaxed = true)
    val plugin = mockk<ArcOriginPlugin>()
    private val records = mutableListOf<LogRecord>()
    private val logger = Logger.getAnonymousLogger().apply {
        useParentHandlers = false
        addHandler(object : Handler() {
            override fun publish(record: LogRecord) { records += record }
            override fun flush() = Unit
            override fun close() = Unit
        })
    }

    init {
        val settings = SlimefunWorkshopSettings(true, "rc_origin_spawn", 28.0, SlimefunWorkshopTiming(),
            SlimefunWorkshopMachine.entries.mapIndexed { index, machine ->
                SlimefunWorkshopStation(machine.name.lowercase(), machine, 462 + index,
                    -10.5, 71.0, -45.5, 0.0, 1.0, SlimefunWorkshopPoint(-.65, 0.0, -1.45), 20)
            })
        val registry = mockk<NPCRegistry> { every { getById(any()) } returns null }
        val world = mockk<World>()
        mockkObject(ArcOriginPlugin.Companion, SlimefunWorkshopSettings.Companion)
        mockkStatic(Bukkit::class, CitizensAPI::class, HandlerList::class)
        every { ArcOriginPlugin.instance } returns plugin
        every { plugin.legacyDataPath } returns Path.of("/unused/slimefun-startup-test")
        every { plugin.logger } returns logger
        every { SlimefunWorkshopSettings.load(any()) } returns settings
        every { Bukkit.getPluginManager() } returns plugins
        every { plugins.isPluginEnabled("Citizens") } returns true
        every { Bukkit.getWorld("rc_origin_spawn") } returns world
        every { CitizensAPI.hasImplementation() } returns true
        every { CitizensAPI.getNPCRegistry() } returns registry
        every { HandlerList.unregisterAll(OriginSlimefunWorkshopModule) } just Runs
        Tasks.install(scheduler)
    }

    fun failures() = records.filter { "phase=PREPARE_FAILED" in it.message }

    override fun close() {
        try {
            OriginSlimefunWorkshopModule.shutdown()
        } finally {
            Tasks.reset()
            unmockkStatic(Bukkit::class, CitizensAPI::class, HandlerList::class)
            unmockkObject(ArcOriginPlugin.Companion, SlimefunWorkshopSettings.Companion)
        }
    }
}
