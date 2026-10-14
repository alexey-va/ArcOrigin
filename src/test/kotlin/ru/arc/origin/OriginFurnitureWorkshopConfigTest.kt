package ru.arc.origin

import io.kotest.core.spec.style.FreeSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.doubles.plusOrMinus
import ru.arc.config.ConfigManager
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import kotlin.math.floor

class OriginFurnitureWorkshopConfigTest :
    FreeSpec({
        lateinit var paper: MockBukkitTestRuntime
        beforeEach { paper = MockBukkitTestRuntime.open() }
        afterEach {
            ConfigManager.clear()
            paper.close()
        }

        "machine feedback meets the contact pose for fractional tick durations" {
            listOf(10L, 16L, 20L, 24L, 28L).forEach { gap ->
                workshopMachineStrokeProgress(0L, gap) shouldBe 0.0
                workshopMachineStrokeProgress(workshopMachineContactTick(gap), gap) shouldBe (0.65 plusOrMinus 1e-12)
                workshopMachineStrokeProgress(gap, gap) shouldBe 1.0
                (0L..gap).map { workshopMachineStrokeProgress(it, gap) }
                    .zipWithNext().all { (before, after) -> before <= after } shouldBe true
            }
        }

        "negative endpoint search radius rejects an operator override before runtime replacement" {
            val dataPath = Files.createTempDirectory("origin-furniture-workshop-invalid")
            try {
                val modulePath = dataPath.resolve("modules/origin-furniture-workshop.yml")
                Files.createDirectories(modulePath.parent)
                val bundled = OriginFurnitureWorkshopConfigTest::class.java.classLoader
                    .getResourceAsStream("modules/origin-furniture-workshop.yml")!!
                    .bufferedReader().use { it.readText() }
                Files.writeString(modulePath, bundled.replace("snap-radius: 3", "snap-radius: -1"))

                shouldThrow<IllegalArgumentException> {
                    OriginFurnitureWorkshopSettings.load(dataPath)
                }.message shouldBe "workshop route snap-radius must be within 0..8 cells"
            } finally {
                dataPath.toFile().deleteRecursively()
            }
        }

        "bundled workshop config keeps four workers on safe centered routes" {
            val dataPath = Files.createTempDirectory("origin-furniture-workshop-test")
            try {
                val settings = OriginFurnitureWorkshopSettings.load(dataPath)

                settings.enabled shouldBe false
                settings.world shouldBe "rc_origin_spawn"
                settings.workers.map { it.role.npcId } shouldBe listOf(430, 458, 459, 460)
                settings.workers.map { it.tableId } shouldBe listOf("carpenter", "upholsterer", "assembler", "finisher")
                val allDue = settings.workers.associate { it.role to 100L }
                workshopWorkersDue(settings.workers, allDue, emptySet(), 100L) shouldBe settings.workers
                workshopWorkersDue(settings.workers, allDue, setOf(OriginFurnitureWorkshopRole.CARPENTER), 100L)
                    .map { it.role } shouldBe settings.workers.drop(1).map { it.role }
                workshopWorkersDue(settings.workers, allDue, emptySet(), 99L) shouldBe emptyList()
                workshopWorkersDue(settings.workers, allDue, allDue.keys, 100L) shouldBe emptyList()
                settings.sleepDurationTicks shouldBe 2400L
                settings.sleepAt.y shouldBe 71.0
                settings.routeProfile.allows(settings.sleepApproach.cell()) shouldBe true
                shouldThrow<IllegalArgumentException> { settings.copy(sleepDurationTicks = 0) }
                shouldThrow<IllegalArgumentException> { settings.copy(sleepApproach = settings.sleepAt) }
                settings.workers.last().deliverOutput shouldBe false
                settings.workers.flatMap { it.beats }.filter { it.mechanism != OriginWorkshopMechanism.NONE }
                    .associate { it.phase to it.mechanism } shouldBe mapOf(
                        "saw" to OriginWorkshopMechanism.SAW,
                        "stretch" to OriginWorkshopMechanism.PRESS,
                        "brace" to OriginWorkshopMechanism.VISE,
                        "tap" to OriginWorkshopMechanism.ANVIL,
                        "coat" to OriginWorkshopMechanism.FINISH,
                    )
                val carpenter = settings.workers.first()
                val unsafeBeats = carpenter.beats.map { beat ->
                    if (beat.mechanism == OriginWorkshopMechanism.SAW) {
                        beat.copy(approach = beat.approach!!.copy(x = -100.5))
                    } else beat
                }
                shouldThrow<IllegalArgumentException> {
                    settings.copy(workers = listOf(carpenter.copy(beats = unsafeBeats)) + settings.workers.drop(1))
                }
                settings.workers.filter { it.deliverOutput }.map { it.productId } shouldBe listOf(
                    "furnituresplus:white_wooden_chair",
                    "furnituresplus:red_wooden_sofa_single",
                    "furnituresplus:white_wooden_diningtable",
                )
                settings.workers.filter { it.deliverOutput }.map { Triple(it.output.x, it.output.y, it.output.z) } shouldBe listOf(
                    Triple(-36.5, 71.0, -70.0),
                    Triple(-36.5, 71.18, -64.0),
                    Triple(-44.5, 71.18, -46.5),
                )

                // Concurrent workers stay in separate front-of-table lanes, including their stock trip.
                val lanes = settings.workers.map { worker ->
                    listOf(worker.home, worker.pickup, worker.workApproach, worker.outputApproach) +
                        worker.beats.mapNotNull { it.approach } +
                        (worker.pickupRoute + worker.workReturnRoute + worker.outputRoute + worker.homeReturnRoute).map { it.point }
                }
                lanes.forEachIndexed { index, lane ->
                    lanes.drop(index + 1).forEach { other ->
                        val separatedX = lane.maxOf { it.x } + 1.0 < other.minOf { it.x } || other.maxOf { it.x } + 1.0 < lane.minOf { it.x }
                        val separatedZ = lane.maxOf { it.z } + 1.0 < other.minOf { it.z } || other.maxOf { it.z } + 1.0 < lane.minOf { it.z }
                        (separatedX || separatedZ) shouldBe true
                    }
                }

                settings.workers.forEach { worker ->
                    worker.parts.size shouldBe when (worker.role) {
                        OriginFurnitureWorkshopRole.ASSEMBLER -> 3
                        else -> 2
                    }
                    worker.beats.isNotEmpty() shouldBe true
                    worker.beats.mapNotNull { it.approach }.forEach { approach ->
                        isWorkshopCellCenter(approach.x) shouldBe true
                        isWorkshopCellCenter(approach.z) shouldBe true
                        floor(approach.y).toInt() shouldBe settings.routeProfile.floorY
                        settings.routeProfile.allows(approach.cell()) shouldBe true
                    }
                    listOf(
                        worker.home to (worker.pickupRoute to worker.pickup),
                        worker.pickup to (worker.workReturnRoute to worker.workApproach),
                        worker.workApproach to (worker.outputRoute to worker.outputApproach),
                        worker.outputApproach to (worker.homeReturnRoute to worker.home),
                    ).forEach { (route, target) ->
                        val legs = target.first
                        val endpoint = target.second
                        legs.last().point.sameCell(endpoint) shouldBe true
                        val points = listOf(route) + legs.map { it.point }
                        points.zipWithNext().forEach { (from, to) ->
                            (from.x == to.x || from.z == to.z) shouldBe true
                        }
                        legs.forEach { leg ->
                            isWorkshopCellCenter(leg.point.x) shouldBe true
                            isWorkshopCellCenter(leg.point.z) shouldBe true
                            floor(leg.point.y).toInt() shouldBe settings.routeProfile.floorY
                            settings.routeProfile.allows(leg.point.cell()) shouldBe true
                        }
                    }
                }
            } finally {
                dataPath.toFile().deleteRecursively()
            }
        }
    })
