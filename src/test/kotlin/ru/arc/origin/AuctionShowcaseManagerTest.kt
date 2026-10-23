package ru.arc.origin

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.Interaction
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.TextDisplay
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.inventory.EquipmentSlot
import org.joml.Vector3f
import ru.arc.core.Tasks
import ru.arc.core.TestTaskScheduler
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PacketItemDisplay
import ru.arc.paper.display.PacketTextDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID
import java.util.concurrent.CompletableFuture

class AuctionShowcaseManagerTest : FreeSpec({
    "packet visuals preserve pedestal style while native Interaction owns clicks and legacy cleanup" {
        MockBukkitTestRuntime.open().use { paper ->
            val scheduler = TestTaskScheduler()
            Tasks.withScheduler(scheduler) {
                val base = mockk<PacketBlockDisplay>(relaxed = true)
                val item = mockk<PacketItemDisplay>(relaxed = true)
                val text = mockk<PacketTextDisplay>(relaxed = true)
                val displays = mockk<PaperPacketDisplays>(relaxed = true)
                val positions = mutableListOf<Location>()
                every { displays.spawnBlock(any(), any()) } answers { positions += firstArg<Location>().clone(); base }
                every { displays.spawnItem(any(), any()) } answers { positions += firstArg<Location>().clone(); item }
                every { displays.spawnText(any(), any()) } answers { positions += firstArg<Location>().clone(); text }
                val interaction = mockk<Interaction>(relaxed = true) {
                    every { uniqueId } returns UUID.randomUUID()
                    every { isValid } returns true
                }
                val legacy = mockk<Entity>(relaxed = true) {
                    every { scoreboardTags } returns setOf(AuctionShowcaseManager.ENTITY_TAG)
                }
                val chunk = mockk<Chunk> { every { entities } returns arrayOf(legacy) }
                val world = mockk<World>(relaxed = true) {
                    every { getChunkAtAsync(any<Int>(), any<Int>(), false) } returns CompletableFuture.completedFuture(chunk)
                    every { isChunkLoaded(any<Int>(), any<Int>()) } returns true
                    every { getChunkAt(any<Int>(), any<Int>()) } returns chunk
                    every { spawn(any<Location>(), Interaction::class.java) } returns interaction
                }
                val store = mockk<AuctionPedestalStore> {
                    every { load() } returns CompletableFuture.completedFuture(null)
                    every { closeAsync() } returns CompletableFuture.completedFuture(Unit)
                }
                val config = mockk<OriginSpawnConfig>(relaxed = true) {
                    every { enabled } returns true
                    every { showcaseEnabled } returns true
                    every { worldName } returns "showcase"
                    every { pedestals } returns listOf(AuctionPedestalSpec("test", 2.0, 64.0, 3.0, 45F))
                    every { cycleTicks } returns 20L
                    every { pageTicks } returns 60L
                    every { rotationTicks } returns 2L
                }
                mockkStatic(Bukkit::class)
                try {
                    every { Bukkit.getWorld("showcase") } returns world
                    val manager = AuctionShowcaseManager(displays, store)
                    try {
                        manager.apply(config)
                        scheduler.tick(2)
                        positions.map { it.y } shouldBe listOf(64.0, 65.22, 67.62)
                        positions.all { it.x == 2.0 && it.z == 3.0 && it.yaw == 45F } shouldBe true
                        verify { base.transformation = match {
                            it.translation == Vector3f(-0.45F, 0F, -0.45F) && it.scale == Vector3f(0.9F, 0.24F, 0.9F)
                        } }
                        verify { item.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.GROUND }
                        verify { item.transformation = match { it.scale == Vector3f(AuctionShowcaseManager.ITEM_SCALE) } }
                        verify { item.interpolationDuration = 2 }
                        verify { text.billboard = Display.Billboard.CENTER }
                        verify { text.alignment = TextDisplay.TextAlignment.CENTER }
                        verify { text.backgroundColor = Color.fromARGB(96, 0, 0, 0) }
                        verify { text.lineWidth = 220 }
                        verify { text.isShadowed = true }
                        verify { text.isSeeThrough = false }
                        verify { base.viewRange = 1.5F; item.viewRange = 1.5F; text.viewRange = 1.5F }
                        verify(exactly = 1) { legacy.remove() }
                        verify { interaction.interactionWidth = 2.8F; interaction.interactionHeight = 4.15F }
                        verify { interaction.addScoreboardTag(AuctionShowcaseManager.ENTITY_TAG) }

                        val player = paper.addPlayer("Viewer").apply { isOp = false }
                        val event = PlayerInteractEntityEvent(player, interaction, EquipmentSlot.HAND)
                        manager.handle(event) shouldBe true
                        event.isCancelled shouldBe true
                        manager.handle(PlayerInteractEntityEvent(player, legacy, EquipmentSlot.HAND)) shouldBe false
                    } finally { manager.shutdown() }
                    verify(exactly = 1) { base.remove(); item.remove(); text.remove(); interaction.remove() }
                    verify(exactly = 0) { displays.close(); displays.closeAsync() }
                    scheduler.pendingCount() shouldBe 0
                } finally { unmockkStatic(Bukkit::class) }
            }
        }
    }
})
