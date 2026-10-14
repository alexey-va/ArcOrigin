package ru.arc.origin

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.World
import ru.arc.config.ConfigManager
import ru.arc.origin.commands.OriginPortalsSubCommand
import java.nio.file.Files
import java.util.UUID

class OriginPortalsModuleTest : FreeSpec({
    afterTest { ConfigManager.clear() }

    "bundled Origin portals keep the requested anchors and route ownership" {
        val directory = Files.createTempDirectory("arc-origin-portals")
        try {
            val config = OriginPortalsConfig.load(directory)
            config.enabled shouldBe true
            config.verticalOffset shouldBe 5.0
            config.anchors.map { it.id } shouldContainExactly OriginPortalId.entries

            val survival = config.anchors.first { it.id == OriginPortalId.SURVIVAL }
            survival.x shouldBe 9.5
            survival.y shouldBe 70.0
            survival.z shouldBe 0.5
            survival.yaw shouldBe 270f
            survival.width shouldBe 12.0
            survival.height shouldBe 16.8
            survival.style shouldBe OriginPortalStyle.ORIGIN
            survival.command shouldBe "arc rtp survival --only-if-first"
            survival.label shouldBe "Новые биомы"
            survival.verticalOffset shouldBe 5.0
            survival.labelFrontDistance shouldBe 3.0
            survival.labelHeightOffset shouldBe -0.5
            survival.labelScale shouldBe 2.5f
            survival.labelBackgroundGray shouldBe 48
            survival.labelBackgroundAlpha shouldBe 180
            config.gateSettings(survival).height shouldBe 16.8
            survival.particlesEnabled.shouldBeTrue()
            config.gateSettings(survival).particlesEnabled.shouldBeTrue()
            config.gateSettings(survival).itemIds shouldBe
                mapOf(
                    "origin" to "origin_gate_portals:origin_portal",
                    "astral" to "origin_gate_portals:astral_portal",
                    "void" to "origin_gate_portals:void_portal",
                )

            val mining = config.anchors.first { it.id == OriginPortalId.MINING }
            mining.style shouldBe OriginPortalStyle.ASTRAL
            mining.command shouldBe "arc rtp mining --only-if-first"
            mining.label shouldBe "Мир добычи"
            mining.labelScale shouldBe 3.0f
            val vanilla = config.anchors.first { it.id == OriginPortalId.VANILLA }
            vanilla.style shouldBe OriginPortalStyle.VOID
            vanilla.command shouldBe "arc rtp vanilla --only-if-first"
            vanilla.label shouldBe "Ванильные биомы"
            vanilla.labelScale shouldBe 2.0f
            val gallery = config.anchors.first { it.id == OriginPortalId.GALLERY_EXIT }
            gallery.worldName shouldBe "rc_atelier_furniture_gallery"
            gallery.width shouldBe 4.5
            gallery.height shouldBe 6.3
            gallery.command shouldBe "rcfurniturereturn"
            gallery.labelFrontDistance shouldBe 0.0
            gallery.labelScale shouldBe 0.9f
            gallery.labelBackgroundAlpha shouldBe 0
            gallery.labelLocations(mockk()).size shouldBe 1

            val slimefun = config.anchors.first { it.id == OriginPortalId.SLIMEFUN }
            slimefun.enabled.shouldBeFalse()
            slimefun.worldName shouldBe "rc_origin_spawn"
            slimefun.x shouldBe -17.5
            slimefun.y shouldBe 72.0
            slimefun.z shouldBe -52.5
            slimefun.yaw shouldBe 180f
            slimefun.width shouldBe 2.8
            slimefun.height shouldBe 2.8
            slimefun.entryDepth shouldBe 0.65
            slimefun.verticalOffset shouldBe 1.4
            (kotlin.math.abs(originPortalDisplayCenter(slimefun, mockk()).y - 73.4) < 1e-9).shouldBeTrue()
            slimefun.command shouldBe "arc originportals enter slimefun"
            slimefun.particleRadius shouldBe 1.25
            slimefun.particleHeight shouldBe 2.8
            slimefun.pulseAmplitude shouldBe 0f
            slimefun.particlesEnabled.shouldBeFalse()
            config.gateSettings(slimefun).particlesEnabled.shouldBeFalse()
            config.gateSettings(slimefun).radius shouldBe 1.25
            config.gateSettings(slimefun).suctionHeight shouldBe 2.8

            val furnitureEntry = config.anchors.first { it.id == OriginPortalId.FURNITURE_ENTRY }
            furnitureEntry.enabled.shouldBeFalse()
            furnitureEntry.worldName shouldBe "rc_origin_spawn"
            furnitureEntry.x shouldBe -46.5
            furnitureEntry.y shouldBe 71.9375
            furnitureEntry.z shouldBe -70.5
            furnitureEntry.yaw shouldBe 0f
            furnitureEntry.width shouldBe 3.0
            furnitureEntry.height shouldBe 4.2
            furnitureEntry.verticalOffset shouldBe 2.1
            furnitureEntry.visibleBottomInsetRatio shouldBe 10.0 / 116.0
            val furnitureCenter = originPortalDisplayCenter(furnitureEntry, mockk())
            (kotlin.math.abs(
                furnitureCenter.y -
                    (furnitureEntry.y + furnitureEntry.verticalOffset -
                        (furnitureEntry.height * furnitureEntry.visibleBottomInsetRatio)),
            ) < 1e-9).shouldBeTrue()
            (kotlin.math.abs(
                (furnitureCenter.y - furnitureEntry.height / 2.0 +
                    furnitureEntry.height * furnitureEntry.visibleBottomInsetRatio) - furnitureEntry.y,
            ) < 1e-9).shouldBeTrue()
            furnitureEntry.style shouldBe OriginPortalStyle.ORIGIN
            furnitureEntry.command shouldBe "rcfurniturevisit room_01"
            furnitureEntry.label shouldBe "Галерея мебели"
            furnitureEntry.particlesEnabled.shouldBeFalse()
            furnitureEntry.particleRadius shouldBe 1.25
            furnitureEntry.particleHeight shouldBe 4.2
            furnitureEntry.pulseAmplitude shouldBe 0f
            config.gateSettings(furnitureEntry).particlesEnabled.shouldBeFalse()
            config.gateSettings(furnitureEntry).radius shouldBe 1.25
            config.gateSettings(furnitureEntry).suctionHeight shouldBe 4.2
            OriginPortalsSubCommand.USAGE.contains("furniture_entry").shouldBeTrue()
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    "portal entry uses the configured yaw-aware plane and vertical bounds" {
        val world = mockk<World>()
        val anchor = OriginPortalAnchor(
            id = OriginPortalId.SURVIVAL,
            worldName = "origin",
            x = 0.0,
            y = 0.0,
            z = 0.0,
            yaw = 90f,
            width = 12.0,
            height = 16.8,
            entryDepth = 2.0,
            command = "test",
            label = "Тест",
            verticalOffset = 5.0,
            labelFrontDistance = 3.0,
            labelSideOffset = 1.0,
            labelHeightOffset = 0.75,
            labelScale = 4.6f,
            labelBackgroundGray = 48,
            labelBackgroundAlpha = 180,
            style = OriginPortalStyle.ORIGIN,
        )
        val center = Location(world, 0.0, 0.0, 0.0)
        everyWorldName(world, "origin")

        anchor.contains(center).shouldBeTrue()
        anchor.contains(Location(world, 2.1, 0.0, 0.0)).shouldBeFalse()
        anchor.contains(Location(world, 0.0, 0.0, 6.1)).shouldBeFalse()
        anchor.contains(Location(world, 0.0, 8.41, 0.0)).shouldBeFalse()
        val hologram = anchor.labelLocation(world)
        hologram.x shouldBe 3.0
        hologram.y shouldBe 5.75
        (kotlin.math.abs(hologram.z - 1.0) < 1e-9).shouldBeTrue()
        hologram.yaw shouldBe anchor.yaw
        val sides = anchor.labelLocations(world)
        sides.size shouldBe 2
        sides.map { it.x } shouldContainExactly listOf(3.01, 2.99)
        sides.all { kotlin.math.abs(it.z - 1.0) < 1e-9 }.shouldBeTrue()
        sides.map { it.yaw } shouldContainExactly listOf(270f, 90f)
    }

    "Slimefun entry volume stays at floor-level while the gate display is raised" {
        val directory = Files.createTempDirectory("arc-origin-portals-slimefun-entry")
        try {
            val anchor = OriginPortalsConfig.load(directory).anchors.first { it.id == OriginPortalId.SLIMEFUN }.copy(enabled = true)
            val world = mockk<World>()
            everyWorldName(world, "rc_origin_spawn")

            anchor.contains(Location(world, -17.5, 72.0, -52.5)).shouldBeTrue()
            anchor.contains(Location(world, -17.5, 70.5, -52.5)).shouldBeFalse()
            anchor.contains(Location(world, -17.5, 73.5, -52.5)).shouldBeFalse()
            anchor.contains(Location(world, -15.9, 72.0, -52.5)).shouldBeFalse()
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    "furniture entry volume is anchored from its moved floor coordinate" {
        val directory = Files.createTempDirectory("arc-origin-portals-furniture-entry")
        try {
            val anchor = OriginPortalsConfig.load(directory).anchors
                .first { it.id == OriginPortalId.FURNITURE_ENTRY }.copy(enabled = true)
            val world = mockk<World>()
            everyWorldName(world, "rc_origin_spawn")

            anchor.contains(Location(world, anchor.x, anchor.y, anchor.z)).shouldBeTrue()
            anchor.contains(Location(world, anchor.x, anchor.y - 0.01, anchor.z)).shouldBeFalse()
            anchor.contains(Location(world, anchor.x, anchor.y + anchor.height, anchor.z)).shouldBeTrue()
            anchor.contains(Location(world, anchor.x, anchor.y + anchor.height + 0.01, anchor.z)).shouldBeFalse()
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    "Slimefun anchor overrides stay within its small visual bounds" {
        val directory = Files.createTempDirectory("arc-origin-portals-slimefun-bounds")
        try {
            OriginPortalsConfig.load(directory)
            val source = ConfigManager.ofModule(directory, "origin-spawn.yml")
            source.setBoolean("origin-portals.anchors.slimefun.enabled", true)
            source.setDouble("origin-portals.anchors.slimefun.particles.radius", 6.0)
            source.setDouble("origin-portals.anchors.slimefun.particles.height", 12.0)
            source.setBoolean("origin-portals.anchors.slimefun.particles.enabled", true)
            source.setDouble("origin-portals.anchors.slimefun.pulse.amplitude", 0.08)
            source.saveStrict()
            ConfigManager.clear()

            val slimefun = OriginPortalsConfig.load(directory).anchors.first { it.id == OriginPortalId.SLIMEFUN }
            slimefun.enabled.shouldBeTrue()
            slimefun.particleRadius shouldBe 1.4
            slimefun.particleHeight shouldBe 2.8
            slimefun.particlesEnabled.shouldBeTrue()
            OriginPortalsConfig.load(directory).gateSettings(slimefun).particlesEnabled.shouldBeTrue()
            slimefun.pulseAmplitude shouldBe 0.08f
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    "portal visuals wait for every visual chunk and reset lost native handles" {
        val directory = Files.createTempDirectory("arc-origin-portal-visual-lifecycle")
        try {
            val gallery = OriginPortalsConfig.load(directory).anchors.first { it.id == OriginPortalId.GALLERY_EXIT }
            val edgeAnchor = gallery.copy(x = 16.1, yaw = 270f, labelFrontDistance = 3.0)
            val world = mockk<World>()
            var unloadedChunk = 0 to 3
            every { world.isChunkLoaded(any(), any()) } answers {
                (firstArg<Int>() to secondArg<Int>()) != unloadedChunk
            }

            originPortalVisualChunksLoaded(edgeAnchor, world).shouldBeFalse()

            unloadedChunk = 99 to 99
            originPortalVisualChunksLoaded(edgeAnchor, world).shouldBeTrue()

            shouldResetOriginPortalVisual(
                spawnAttempted = true,
                chunksLoaded = false,
                gateSpawned = true,
                gateActive = false,
            ).shouldBeTrue()
            shouldResetOriginPortalVisual(
                spawnAttempted = true,
                chunksLoaded = true,
                gateSpawned = true,
                gateActive = false,
            ).shouldBeTrue()
            shouldResetOriginPortalVisual(
                spawnAttempted = true,
                chunksLoaded = true,
                gateSpawned = false,
                gateActive = false,
            ).shouldBeFalse()
            shouldResetOriginPortalVisual(
                spawnAttempted = true,
                chunksLoaded = true,
                gateSpawned = true,
                gateActive = true,
            ).shouldBeFalse()
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    "central labels keep both faces at one configured position before every rotated portal" {
        val directory = Files.createTempDirectory("arc-origin-portal-label-faces")
        try {
            val world = mockk<World>()
            OriginPortalsConfig.load(directory).anchors.filter { it.id.central }.forEach { anchor ->
                val faces = anchor.labelLocations(world)
                faces.size shouldBe 2
                val expected = anchor.labelLocation(world)
                faces.forEach { face ->
                    (face.distance(expected) < 0.011).shouldBeTrue()
                    face.y shouldBe expected.y
                    val angle = anchor.yaw * kotlin.math.PI / 180.0
                    val frontDistance = (face.x - anchor.x) * kotlin.math.sin(angle) -
                        (face.z - anchor.z) * kotlin.math.cos(angle)
                    (frontDistance > 0.0).shouldBeTrue()
                }
                (kotlin.math.abs(faces[0].distance(faces[1]) - 0.02) < 1e-9).shouldBeTrue()
                faces.map { it.yaw } shouldContainExactly listOf(anchor.yaw + 180f, anchor.yaw)
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    "move persists feet coordinates and reloads the same anchor" {
        val directory = Files.createTempDirectory("arc-origin-portals-move")
        try {
            val config = OriginPortalsConfig.load(directory)
            val world = mockk<World>()
            everyWorldName(world, "rc_origin_spawn")
            config.persistFeet(OriginPortalId.SURVIVAL, Location(world, 13.25, 71.5, -2.75, 42.5f, 30f))
            config.persistFeet(OriginPortalId.FURNITURE_ENTRY, Location(world, -46.5, 71.0, -52.5, 0f, 30f))
            ConfigManager.clear()
            val reloaded = OriginPortalsConfig.load(directory)
            val moved = reloaded.anchors.first { it.id == OriginPortalId.SURVIVAL }
            moved.x shouldBe 13.25
            moved.y shouldBe 71.5
            moved.z shouldBe -2.75
            moved.yaw shouldBe 42.5f

            val furniture = reloaded.anchors.first { it.id == OriginPortalId.FURNITURE_ENTRY }
            furniture.x shouldBe -46.5
            furniture.y shouldBe 71.0
            furniture.z shouldBe -52.5
            furniture.yaw shouldBe 180f
            originPortalMoveYaw(OriginPortalId.FURNITURE_ENTRY, 170f) shouldBe -10f
            originPortalMoveYaw(OriginPortalId.SURVIVAL, 170f) shouldBe 170f
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    "hologram text, position, size and per-portal height can be tuned in config" {
        val directory = Files.createTempDirectory("arc-origin-portals-hologram")
        try {
            OriginPortalsConfig.load(directory)
            val source = ConfigManager.ofModule(directory, "origin-spawn.yml")
            source.setString("origin-portals.anchors.survival.hologram.text", "Новый мир")
            source.setDouble("origin-portals.anchors.survival.hologram.front-distance", 4.0)
            source.setDouble("origin-portals.anchors.survival.hologram.side-offset", -1.5)
            source.setDouble("origin-portals.anchors.survival.hologram.height-offset", 1.25)
            source.setDouble("origin-portals.anchors.survival.hologram.scale", 3.2)
            source.setInt("origin-portals.anchors.survival.hologram.background-gray", 72)
            source.setInt("origin-portals.anchors.survival.hologram.background-alpha", 210)
            source.setDouble("origin-portals.anchors.survival.vertical-offset", 4.5)
            source.setDouble("origin-portals.anchors.furniture_entry.y", 71.0)
            source.setDouble("origin-portals.anchors.furniture_entry.yaw", 37.5)
            source.setDouble("origin-portals.anchors.furniture_entry.width", 7.5)
            source.setDouble("origin-portals.anchors.furniture_entry.height", 10.5)
            source.setDouble("origin-portals.anchors.furniture_entry.vertical-offset", 3.25)
            source.setDouble("origin-portals.anchors.furniture_entry.hologram.height-offset", 2.75)
            source.setDouble("origin-portals.anchors.furniture_entry.visible-bottom-inset-ratio", 10.0 / 116.0)
            source.saveStrict()
            ConfigManager.clear()
            val survival = OriginPortalsConfig.load(directory).anchors.first { it.id == OriginPortalId.SURVIVAL }
            survival.label shouldBe "Новый мир"
            survival.labelFrontDistance shouldBe 4.0
            survival.labelSideOffset shouldBe -1.5
            survival.labelHeightOffset shouldBe 1.25
            survival.labelScale shouldBe 3.2f
            survival.labelBackgroundGray shouldBe 72
            survival.labelBackgroundAlpha shouldBe 210
            survival.verticalOffset shouldBe 4.5
            OriginPortalsConfig.load(directory).gateSettings(survival).verticalOffset shouldBe 4.5

            val furniture = OriginPortalsConfig.load(directory).anchors.first { it.id == OriginPortalId.FURNITURE_ENTRY }
            furniture.yaw shouldBe 37.5f
            furniture.width shouldBe 7.5
            furniture.height shouldBe 10.5
            furniture.verticalOffset shouldBe 3.25
            furniture.labelHeightOffset shouldBe 2.75
            furniture.visibleBottomInsetRatio shouldBe 10.0 / 116.0
            furniture.id.usesFixedTwoSidedLabels.shouldBeTrue()
            val world = mockk<World>()
            val center = originPortalDisplayCenter(furniture, world)
            val previousFurniture = furniture.copy(verticalOffset = 5.25)
            previousFurniture.labelHeightOffset shouldBe furniture.labelHeightOffset
            val previousCenter = originPortalDisplayCenter(previousFurniture, world)
            (kotlin.math.abs((center.y - previousCenter.y) + 2.0) < 1e-9).shouldBeTrue()
            val visibleBottom = center.y - furniture.height / 2.0 +
                furniture.height * furniture.visibleBottomInsetRatio
            val previousVisibleBottom = previousCenter.y - furniture.height / 2.0 +
                furniture.height * furniture.visibleBottomInsetRatio
            (kotlin.math.abs((visibleBottom - previousVisibleBottom) + 2.0) < 1e-9).shouldBeTrue()
            (kotlin.math.abs(visibleBottom - (furniture.y - 2.0)) < 1e-9).shouldBeTrue()
            val label = furniture.labelLocation(world)
            (kotlin.math.abs(label.y - (center.y + furniture.labelHeightOffset)) < 1e-9).shouldBeTrue()
            val previousLabel = previousFurniture.labelLocation(world)
            (kotlin.math.abs((label.y - previousLabel.y) + 2.0) < 1e-9).shouldBeTrue()
            val faces = furniture.labelLocations(world)
            faces.size shouldBe 2
            faces.forEach { face ->
                (face.distance(label) < 0.011).shouldBeTrue()
                face.y shouldBe label.y
            }
            (kotlin.math.abs(faces[0].distance(faces[1]) - 0.02) < 1e-9).shouldBeTrue()
            faces.map { it.yaw } shouldContainExactly listOf(furniture.yaw + 180f, furniture.yaw)
            OriginPortalId.GALLERY_EXIT.usesFixedTwoSidedLabels.shouldBeFalse()
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    "Origin portal bypass covers every configured portal and command" {
        OriginPortalId.entries.forEach { id ->
            shouldBypassOriginPortal(id, hasBypassPermission = true).shouldBeTrue()
            shouldBypassOriginPortal(id, hasBypassPermission = false).shouldBeFalse()
        }

        matchesPortalCommand(
            "/ARC RTP survival --only-if-first",
            "arc rtp survival --only-if-first",
        ).shouldBeTrue()
        matchesPortalCommand(
            "/arc rtp mining --only-if-first",
            "arc rtp survival --only-if-first",
        ).shouldBeFalse()
    }

    "public Slimefun entry is narrowly permission-bypassed and transfers are cooldown fenced" {
        OriginPortalsSubCommand.isPublicAction(arrayOf("enter", "slimefun")).shouldBeTrue()
        OriginPortalsSubCommand.isPublicAction(arrayOf("ENTER", "SLIMEFUN")).shouldBeTrue()
        OriginPortalsSubCommand.isPublicAction(arrayOf("move", "slimefun")).shouldBeFalse()
        OriginPortalsSubCommand.isPublicAction(arrayOf("enter", "slimefun", "extra")).shouldBeFalse()

        var now = 1_000L
        val tracker = OriginPortalTransferTracker(nowMillis = { now }, cooldownMillis = 10_000L)
        val playerId = UUID.randomUUID()
        val first = tracker.begin(playerId) as OriginPortalTransferAttempt.Started
        (tracker.begin(playerId) is OriginPortalTransferAttempt.AlreadyPending).shouldBeTrue()
        tracker.finish(playerId, first.token).shouldBeTrue()
        (tracker.begin(playerId) is OriginPortalTransferAttempt.CoolingDown).shouldBeTrue()
        now += 10_000L
        val second = tracker.begin(playerId) as OriginPortalTransferAttempt.Started
        tracker.finish(playerId, first.token).shouldBeFalse()
        tracker.finish(playerId, second.token).shouldBeTrue()
        tracker.clearPlayer(playerId)
        (tracker.begin(playerId) is OriginPortalTransferAttempt.Started).shouldBeTrue()
    }
})

private fun everyWorldName(world: World, name: String) {
    every { world.name } returns name
}
