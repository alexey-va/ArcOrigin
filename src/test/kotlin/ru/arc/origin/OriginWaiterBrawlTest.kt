package ru.arc.origin

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkStatic
import io.mockk.verify
import net.citizensnpcs.api.CitizensAPI
import net.citizensnpcs.api.ai.Navigator
import net.citizensnpcs.api.npc.NPC
import net.citizensnpcs.api.npc.NPCRegistry
import net.citizensnpcs.trait.LookClose
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Entity
import org.bukkit.entity.LivingEntity
import ru.arc.npc.NpcRouteRotation
import ru.arc.npc.smoothNpcRotation
import ru.arc.origin.scene.OriginSceneLease
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Path
import java.util.UUID

class OriginWaiterBrawlTest : StringSpec({
    lateinit var f: WaiterBrawlFixture
    beforeEach { f = WaiterBrawlFixture() }
    afterEach { f.close() }

    "the existing guests approach and both swing, react and move before intervention is ready" {
        f.start() shouldBe true
        f.brawl.isReady(f.playerId) shouldBe false
        f.brawl.beginIntervention(f.playerId, OriginWaiterDisputeStage.FIRST_SIDE, f.now) shouldBe false
        f.awaitReady()

        f.events.filter { it.action == "swing" }.map { it.actor } shouldBe listOf(1, 2)
        f.events.filter { it.action == "hurt" }.map { it.actor } shouldBe listOf(2, 1)
        f.events.count { it.action == "move" } shouldBe 6
        f.claims shouldBe 1
        f.invulnerable.values.toList() shouldBe listOf(true, true)
        f.entities.values.forEach { entity ->
            verify(exactly = 0) { entity.damage(any<Double>()) }
            verify(exactly = 0) { entity.damage(any<Double>(), any<Entity>()) }
            verify(exactly = 0) { entity.setHealth(any()) }
            verify(exactly = 0) { entity.teleport(any<Location>()) }
            verify(exactly = 0) { entity.equipment }
        }
    }

    "intervention separates each guest, has real duration and cannot skip or spam stages" {
        f.start() shouldBe true
        f.awaitReady()
        f.awaitReady(minimumStrikes = 3)
        f.positions.getValue(2).x shouldBe 1.5
        val strikes = f.events.count { it.action == "swing" }
        f.brawl.beginIntervention(f.playerId, OriginWaiterDisputeStage.SECOND_SIDE, f.now) shouldBe false
        for (stage in OriginWaiterDisputeStage.entries) {
            f.brawl.beginIntervention(f.playerId, stage, f.now) shouldBe true
            f.brawl.beginIntervention(f.playerId, stage, f.now) shouldBe false
            val duration = if (stage == OriginWaiterDisputeStage.BRING_WATER) f.config.waterReactionMillis else f.config.interventionMillis
            f.advance(duration - 50)
            f.brawl.isReady(f.playerId) shouldBe false
            f.advance(50)
            f.brawl.isReady(f.playerId) shouldBe true
            if (stage == OriginWaiterDisputeStage.FIRST_SIDE) {
                f.positions.getValue(1).x shouldBe -1.5
                f.positions.getValue(2).x shouldBe 0.5
            }
            if (stage == OriginWaiterDisputeStage.SECOND_SIDE) f.positions.getValue(2).x shouldBe 1.5
        }
        f.events.count { it.action == "swing" } shouldBe strikes
        f.brawl.stop(f.playerId)
        f.brawl.stop(f.playerId)
        f.releases shouldBe 1
        f.invulnerable.values.toList() shouldBe listOf(false, true)
        f.lookClose.values.forEach { verify(exactly = 1) { it.lookClose(true) } }
    }

    "a refused route or vanished actor returns the original pair once and restores protection" {
        f.refusedMove = 2
        f.start() shouldBe false
        f.releases shouldBe 1
        f.brawl.isActive(f.playerId) shouldBe false
        f.invulnerable.values.toList() shouldBe listOf(false, true)
        f.refusedMove = null
        f.start() shouldBe true
        f.spawned[1] = false
        f.advance(50)
        f.releases shouldBe 2
        f.brawl.close()
        f.releases shouldBe 2
    }

    "distant guests are rejected before claim and a stalled approach never starts a fight" {
        f.positions[2] = Location(f.world, 30.0, 70.0, 0.0)
        f.start() shouldBe false
        f.claims shouldBe 0
        f.positions[2] = Location(f.world, 2.5, 70.0, 0.0)
        f.stalled = true
        f.start() shouldBe true
        f.advance(f.config.routeTimeoutMillis)
        f.brawl.isActive(f.playerId) shouldBe false
        f.events.none { it.action == "swing" || it.action == "hurt" } shouldBe true
        f.releases shouldBe 1
    }

    "export the production choreography commands with explicitly simulated walking" {
        f.start() shouldBe true
        f.awaitReady()
        f.advance(3_000)
        f.awaitReady()
        for (stage in OriginWaiterDisputeStage.entries) {
            f.phase = stage.name
            f.brawl.beginIntervention(f.playerId, stage, f.now) shouldBe true
            f.awaitReady()
            f.advance(350)
        }
        WaiterBrawlPreviewExport.write(Path.of("build/waiter-brawl-preview"), f.samples, f.events)
    }
})

/** Direct Paper/Citizens mocks check calls; the deliberately simple walker is not native physics. */
private class WaiterBrawlFixture : AutoCloseable {
    private val paper = MockBukkitTestRuntime.open()
    val world = mockk<World>(relaxed = true)
    val config = OriginWaiterBrawlConfig()
    val playerId: UUID = UUID.randomUUID()
    val entities = mutableMapOf<Int, LivingEntity>()
    val actors = mutableMapOf<Int, NPC>()
    val lookClose = mutableMapOf<Int, LookClose>()
    val spawned = mutableMapOf(1 to true, 2 to true)
    val positions = mutableMapOf(1 to Location(world, -2.5, 70.0, 0.0), 2 to Location(world, 2.5, 70.0, 0.0))
    val invulnerable = mutableMapOf(1 to false, 2 to true)
    val destinations = mutableMapOf<Int, Location>()
    val events = mutableListOf<WaiterBrawlPreviewEvent>()
    val samples = mutableListOf<WaiterBrawlPreviewSample>()
    var now = 0L
    var phase = "BRAWL"
    var claims = 0
    var releases = 0
    var refusedMove: Int? = null
    var stalled = false
    val brawl: OriginWaiterBrawl

    init {
        mockkStatic(CitizensAPI::class)
        mockkStatic("ru.arc.npc.NpcRouteRotationKt")
        val registry = mockk<NPCRegistry>()
        every { CitizensAPI.getNPCRegistry() } returns registry
        every { registry.getById(any()) } answers { actors[firstArg()] }
        every { world.name } returns OriginDiningLayout.WORLD
        every { smoothNpcRotation(any(), any(), any()) } answers {
            val id = firstArg<NPC>().id
            positions.getValue(id).yaw = secondArg()
            positions.getValue(id).pitch = thirdArg()
            mockk<NpcRouteRotation> { every { isLatestOwner } returns true }
        }
        for (id in 1..2) {
            val living = mockk<LivingEntity>(relaxed = true)
            every { living.world } returns world
            every { living.location } answers { positions.getValue(id).clone() }
            every { living.eyeLocation } answers { positions.getValue(id).clone().add(0.0, 1.62, 0.0) }
            every { living.isValid } returns true
            every { living.isDead } returns false
            every { living.isInvulnerable } answers { invulnerable.getValue(id) }
            every { living.isInvulnerable = any() } answers { invulnerable[id] = firstArg() }
            every { living.yaw } answers { positions.getValue(id).yaw }
            every { living.pitch } answers { positions.getValue(id).pitch }
            every { living.bodyYaw } answers { positions.getValue(id).yaw }
            every { living.swingMainHand() } answers { event("swing", id) }
            every { living.playHurtAnimation(any()) } answers { event("hurt", id) }
            val navigator = mockk<Navigator> { every { isNavigating } returns false }
            val look = mockk<LookClose> {
                every { isEnabled } returns true
                every { lookClose(any()) } just runs
            }
            lookClose[id] = look
            entities[id] = living
            actors[id] = mockk<NPC> {
                every { this@mockk.id } returns id
                every { isSpawned } answers { spawned.getValue(id) }
                every { entity } returns living
                every { this@mockk.navigator } returns navigator
                every { hasTrait(LookClose::class.java) } returns true
                every { getTraitNullable(LookClose::class.java) } returns look
            }
        }
        brawl = OriginWaiterBrawl(config,
            claim = { _, _ ->
                claims++
                Location(world, -0.5, 70.0, 0.0) to Location(world, 0.5, 70.0, 0.0)
            },
            release = { first, second ->
                setOf(first.guestId, second.guestId) shouldBe setOf(1, 2)
                releases++
            },
            move = { id, destination ->
                event("move", id, destination)
                if (refusedMove == id) false else { destinations[id] = destination; true }
            },
            stopMoving = { destinations.remove(it) },
            isMoving = { it in destinations },
        )
    }

    fun start(): Boolean = brawl.start(playerId, target(1), target(2), now)

    fun advance(millis: Long) {
        require(millis % 50L == 0L)
        repeat((millis / 50L).toInt()) {
            now += 50
            if (!stalled) destinations.toMap().forEach { (id, target) ->
                val current = positions.getValue(id)
                val delta = target.toVector().subtract(current.toVector())
                if (delta.lengthSquared() <= 0.12 * 0.12) {
                    positions[id] = target.clone()
                    destinations.remove(id)
                } else positions[id] = current.clone().add(delta.normalize().multiply(0.12))
            }
            brawl.tick(now)
            samples += WaiterBrawlPreviewSample(now, phase, (1..2).map { id ->
                val at = positions.getValue(id)
                WaiterBrawlPreviewActor(id, at.x, at.z, at.yaw, at.pitch)
            })
        }
    }

    fun awaitReady(minimumStrikes: Int = 0) {
        val until = now + 20_000
        while (brawl.isActive(playerId) && now < until &&
            (!brawl.isReady(playerId) || events.count { it.action == "swing" } < minimumStrikes)) advance(50)
        brawl.isReady(playerId) shouldBe true
        (events.count { it.action == "swing" } >= minimumStrikes) shouldBe true
    }

    private fun event(action: String, id: Int, at: Location = positions.getValue(id)) {
        events += WaiterBrawlPreviewEvent(now, action, id, at.x, at.z)
    }

    private fun target(id: Int) = OriginWaiterTarget(
        OriginSceneLease(UUID.randomUUID(), "player-waiter", "table-$id", setOf(id)),
        "restaurant", 439, id, "Guest $id", "table-$id", "dish", "Dish",
    )

    override fun close() {
        brawl.close()
        unmockkStatic(CitizensAPI::class)
        unmockkStatic("ru.arc.npc.NpcRouteRotationKt")
        paper.close()
    }
}
