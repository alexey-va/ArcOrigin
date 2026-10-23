package ru.arc.origin.scene

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import net.citizensnpcs.api.npc.NPC
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.entity.Wolf
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector
import ru.arc.npc.NpcRouteBounds
import ru.arc.npc.NpcRouteCell
import ru.arc.npc.NpcRouteProfile
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files
import java.util.UUID
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class OriginDozorGreetingsTest : StringSpec({
    lateinit var f: DozorFixture
    beforeEach { f = DozorFixture() }
    afterEach { f.close() }

    "only the exact native Dozor can borrow the unoccupied scene actor" {
        f.still()
        f.greetings.matches(f.actor) shouldBe true
        f.npcUuid = UUID.randomUUID()
        f.start() shouldBe null
        f.npcUuid = OriginDozorGreetings.NPC_UUID
        f.entityType = EntityType.FOX
        f.start() shouldBe null
        f.entityType = EntityType.WOLF
        val work = f.coordinator.tryAcquire(f.scene.id, "player-work", setOf(444), f.now)!!
        f.start() shouldBe null
        f.coordinator.owns(work) shouldBe true
        f.coordinator.release(work, f.now, 0)
        val visit = requireNotNull(f.start())
        f.coordinator.owns(visit.lease) shouldBe true
        f.coordinator.tryAcquire(f.scene.id, "dog-round", setOf(444), f.now) shouldBe null
        visit.scene.actors.getValue(444).home shouldBe f.scene.actors.getValue(444).home
        visit.scene.copy(cycles = listOf(visit.cycle)).validate()
        val move = visit.cycle.steps.filterIsInstance<OriginSceneStep.Move>().single()
        val profile = visit.scene.routeProfiles.getValue(move.routeProfile)
        profile.bounds shouldBe f.scene.routeProfiles.getValue("yard").bounds
        profile.maximumStepHeight shouldBe f.scene.routeProfiles.getValue("yard").maximumStepHeight
        profile.snapRadius shouldBe 0
    }

    "stationary sampling excludes passers by and both interaction buttons share one quiet interval" {
        f.now = 90_000
        f.greetings.observe(listOf(f.player), f.now)
        f.start() shouldBe null
        f.at.x += 0.5
        f.now += 2_000
        f.greetings.observe(listOf(f.player), f.now)
        f.start() shouldBe null
        f.now += 2_000
        f.greetings.observe(listOf(f.player), f.now)
        f.greetings.interacted(444, f.now)
        f.greetings.interacted(444, f.now) // Two event sources never reserve or start a second encounter.
        f.now += 29_999
        f.start() shouldBe null
        f.now++
        (f.start() != null) shouldBe true
    }

    "native missing support or blocked side cells skip the encounter without acquiring an actor" {
        f.still()
        f.support = false
        f.start() shouldBe null
        f.coordinator.busyActors() shouldBe emptySet()
        f.support = true
        f.blocked += originDozorApproachCells(f.at, f.scene.routeProfiles.getValue("yard"))
        f.start() shouldBe null
        f.coordinator.busyActors() shouldBe emptySet()
        f.blocked.clear()
        (f.start() != null) shouldBe true
    }

    "one snapshot is retained and leaving quitting or mounting ends the visit without a new target" {
        f.still()
        val visit = requireNotNull(f.start())
        val endpoint = visit.scene.anchors.getValue("dozor-visitor-side")
        visit.contains(f.player) shouldBe true
        f.at.x += 1.1
        visit.contains(f.player) shouldBe false
        visit.scene.anchors.getValue("dozor-visitor-side") shouldBe endpoint
        f.at.x -= 1.1
        f.online = false
        visit.contains(f.player) shouldBe false
        f.online = true; f.mounted = true
        visit.contains(f.player) shouldBe false
        f.mounted = false; f.at.y += 0.5
        visit.contains(f.player) shouldBe false
    }

    "global and visitor cooldowns survive an early end and lines vary without adjacent repeats" {
        f.still()
        val spoken = mutableListOf<String>()
        repeat(f.config.lines.size * 2) {
            val visit = requireNotNull(f.start())
            spoken += visit.cycle.steps.filterIsInstance<OriginSceneStep.Speech>().single().text
            f.coordinator.release(visit.lease, f.now, 0)
            f.now += 90_000
            f.greetings.observe(listOf(f.player), f.now)
            f.start() shouldBe null // This visitor remains on cooldown even if the scene ended early.
            f.now += 390_000
            f.greetings.observe(listOf(f.player), f.now)
        }
        spoken.take(f.config.lines.size).toSet() shouldBe f.config.lines.toSet()
        spoken.zipWithNext().all { (a, b) -> a != b } shouldBe true
    }

    "side goals remain inside the yard and leave a clear forward lane at every view angle" {
        val profile = NpcRouteProfile("yard", 70, NpcRouteBounds(-3, 3, -3, 3))
        for (yaw in 0 until 360 step 5) {
            val at = Location(f.world, 0.35, 70.0, 0.75, yaw.toFloat(), 0f)
            val radians = Math.toRadians(yaw.toDouble())
            val cells = originDozorApproachCells(at, profile)
            cells.isNotEmpty() shouldBe true
            (cells.size <= 4) shouldBe true
            cells.forEach { cell ->
                profile.allows(cell) shouldBe true
                val dx = cell.x + 0.5 - at.x; val dz = cell.z + 0.5 - at.z
                (abs(dx * cos(radians) + dz * sin(radians)) >= 1.35) shouldBe true
                (-dx * sin(radians) + dz * cos(radians) >= 0.7) shouldBe true
            }
        }
    }
})

private class DozorFixture : AutoCloseable {
    private val paper = MockBukkitTestRuntime.open()
    private val path = Files.createTempDirectory("origin-dozor-test")
    val config = OriginDozorConfig.load(path).copy(cooldownMillis = 90_000L..90_000L)
    val scene = OriginScenePlan.load(path).scene("mount-yard")
    val greetings = OriginDozorGreetings(config, 0, Random(44))
    val coordinator = OriginSceneCoordinator()
    var now = 90_000L
    var support = true
    val blocked = mutableSetOf<NpcRouteCell>()
    var npcUuid = OriginDozorGreetings.NPC_UUID
    var entityType = EntityType.WOLF
    var online = true
    var mounted = false
    val world = mockk<World> {
        every { uid } returns UUID.randomUUID()
        every { name } returns "rc_origin_spawn"
        every { isChunkLoaded(any<Int>(), any<Int>()) } returns true
        every { getBlockAt(any<Int>(), any<Int>(), any<Int>()) } answers {
            val x = firstArg<Int>(); val y = secondArg<Int>(); val z = thirdArg<Int>()
            val solid = (y == 69 && support) || (y == 70 && NpcRouteCell(x, z) in blocked)
            mockk<Block> {
                every { type } returns if (solid) Material.STONE else Material.AIR
                every { isPassable } returns !solid
                every { isLiquid } returns false
                every { collisionShape } returns mockk {
                    every { boundingBoxes } returns if (solid) listOf(BoundingBox(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)) else emptyList()
                }
            }
        }
    }
    val at = Location(world, 61.5, 70.0, -125.5, 0f, 0f)
    private val wolf = mockk<Wolf> {
        every { type } answers { entityType }
        every { world } returns this@DozorFixture.world
        every { location } answers { Location(this@DozorFixture.world, 57.5, 70.0, -125.5) }
        every { boundingBox } returns BoundingBox(57.2, 70.0, -125.8, 57.8, 70.85, -125.2)
    }
    val actor = mockk<NPC> {
        every { id } returns 444
        every { uniqueId } answers { npcUuid }
        every { isSpawned } returns true
        every { entity } returns wolf
    }
    val player = mockk<Player> {
        every { uniqueId } returns UUID.randomUUID()
        every { location } answers { at.clone() }
        every { world } answers { at.world }
        every { isOnline } answers { online }
        every { isInsideVehicle } answers { mounted }
        every { isDead } returns false
        every { isFlying } returns false
        every { isSprinting } returns false
        every { isSwimming } returns false
        every { gameMode } returns GameMode.SURVIVAL
        every { velocity } answers { Vector() }
        every { hasLineOfSight(wolf) } returns true
    }

    fun still() { greetings.observe(listOf(player), now - 2_000); greetings.observe(listOf(player), now) }
    fun start(): OriginDozorVisit? = greetings.tryStart(scene, actor, listOf(player), coordinator, now)
    override fun close() = paper.close()
}
