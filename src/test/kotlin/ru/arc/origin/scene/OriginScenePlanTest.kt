package ru.arc.origin.scene

import io.kotest.core.spec.style.FreeSpec
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import ru.arc.npc.NpcRouteCell
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.nio.file.Files

class OriginScenePlanTest : FreeSpec({
    lateinit var paper: MockBukkitTestRuntime

    beforeEach { paper = MockBukkitTestRuntime.open() }
    afterEach { paper.close() }

    "bundled plan exposes independent forge and mount-yard scenes" {
        val plan = OriginScenePlan.load(Files.createTempDirectory("origin-scenes-plan-test"))

        plan.world shouldBe "rc_origin_spawn"
        plan.scenes.map(OriginSceneDefinition::id) shouldContainAll listOf("forge", "mount-yard")
        plan.scene("forge").cycles.map(OriginSceneCycle::id) shouldContainAll
            listOf(
                "master-anvil",
                "master-spear",
                "master-shield",
                "master-horseshoe",
                "ledger-orders",
                "apprentice-engraving-jopa",
                "ore-inspection",
                "blade-practice",
                "furnace-check",
            )
        plan.scene("mount-yard").cycles.map(OriginSceneCycle::id) shouldContainAll
            listOf("groom-wind", "inspect-buran", "inspect-ryzhik", "wind-paddock", "buran-paddock", "ryzhik-paddock")
    }

    "every cycle references declared actors anchors and route profiles" {
        val plan = OriginScenePlan.load(Files.createTempDirectory("origin-scenes-plan-validation-test"))

        plan.scenes.forEach { scene ->
            scene.validate()
            scene.cycles.forEach { cycle ->
                cycle.actorIds.all(scene.actors::containsKey) shouldBe true
                cycle.steps.filterIsInstance<OriginSceneStep.Move>().all { it.anchor in scene.anchors } shouldBe true
                cycle.steps.filterIsInstance<OriginSceneStep.Move>().all { it.routeProfile in scene.routeProfiles } shouldBe true
            }
        }
    }

    "bundled cycles retain authored step ids and derive a watchdog budget" {
        val cycle = OriginScenePlan.load(Files.createTempDirectory("origin-scenes-duration-test"))
            .scene("forge")
            .cycles
            .single { it.id == "master-forging-showcase" }

        cycle.stepIds.first() shouldBe "call"
        cycle.stepIds.size shouldBe cycle.steps.size
        val authoredTicks = cycle.steps.sumOf {
            when (it) {
                is OriginSceneStep.Move -> it.timeoutTicks
                is OriginSceneStep.Wait -> it.ticks
                is OriginSceneStep.Swing -> (it.repetitions - 1L) * it.periodTicks
                else -> 0L
            }
        }
        cycle.maxDurationTicks shouldBe authoredTicks * 2L + 1_200L
    }

    "plan validation rejects duplicate cycle and step identities with context" {
        val scene = OriginScenePlan.load(Files.createTempDirectory("origin-scenes-duplicate-test")).scene("forge")
        val cycle = scene.cycles.single { it.id == "ledger-orders" }

        shouldThrow<IllegalArgumentException> {
            scene.copy(cycles = scene.cycles + cycle).validate()
        }
        shouldThrow<IllegalArgumentException> {
            val duplicateStepIds = cycle.stepIds.dropLast(1) + cycle.stepIds.first()
            scene.copy(
                cycles = scene.cycles.map { if (it.id == cycle.id) it.copy(stepIds = duplicateStepIds) else it },
            ).validate()
        }
    }

    "plan validation rejects unknown equipment material" {
        val scene = OriginScenePlan.load(Files.createTempDirectory("origin-scenes-material-test")).scene("forge")
        val cycle = scene.cycles.single { it.id == "ledger-orders" }
        val steps = cycle.steps.map {
            if (it is OriginSceneStep.Equip) it.copy(material = "NOT_A_MATERIAL") else it
        }

        shouldThrow<IllegalArgumentException> {
            scene.copy(cycles = scene.cycles.map { if (it.id == cycle.id) it.copy(steps = steps) else it }).validate()
        }
    }

    "plan validation rejects itemless equipment and particles requiring data" {
        val scene = OriginScenePlan.load(Files.createTempDirectory("origin-scenes-runtime-contract-test")).scene("forge")
        val cycle = scene.cycles.single { it.id == "ledger-orders" }

        shouldThrow<IllegalArgumentException> {
            val equip = OriginSceneStep.Equip(cycle.actorIds.first(), "WATER")
            scene.copy(
                cycles = scene.cycles.map {
                    if (it.id == cycle.id) it.copy(steps = it.steps + equip, stepIds = it.stepIds + "bad-equip") else it
                },
            ).validate()
        }
        shouldThrow<IllegalArgumentException> {
            val particle = OriginSceneStep.Particle(cycle.actorIds.first(), scene.anchors.keys.first(), "DUST", 1)
            scene.copy(
                cycles = scene.cycles.map {
                    if (it.id == cycle.id) it.copy(steps = it.steps + particle, stepIds = it.stepIds + "bad-particle") else it
                },
            ).validate()
        }
        shouldThrow<IllegalArgumentException> {
            val swingCycle = scene.cycles.single { it.id == "blade-practice" }
            val swingSteps = swingCycle.steps.map { step ->
                if (step is OriginSceneStep.Swing) step.copy(particle = "DUST") else step
            }
            scene.copy(
                cycles = scene.cycles.map {
                    if (it.id == swingCycle.id) it.copy(steps = swingSteps) else it
                },
            ).validate()
        }
    }

    "plan validation accepts NPC registry id zero in actor and optional references" {
        val scene = OriginScenePlan.load(Files.createTempDirectory("origin-scenes-zero-id-test")).scene("forge")
        val cycle = scene.cycles.single { it.id == "ledger-orders" }
        val zeroActor = scene.actors.getValue(350).copy(id = 0)
        val zeroReferences = listOf(
            OriginSceneStep.Sound(actorId = 0, anchor = null, sound = "ITEM_BOOK_PAGE_TURN", volume = 0.5f, pitch = 1.0f),
            OriginSceneStep.Particle(actorId = 0, anchor = null, particle = "FLAME", count = 1),
            OriginSceneStep.Swing(
                actorId = cycle.actorIds.first(),
                repetitions = 1,
                periodTicks = 1,
                damageTargetNpcId = 0,
                damageAmount = 1.0,
            ),
        )

        scene.copy(
            actors = scene.actors + (0 to zeroActor),
            cycles = scene.cycles.map {
                if (it.id == cycle.id) it.copy(
                    actorIds = it.actorIds + 0,
                    steps = it.steps + zeroReferences,
                    stepIds = it.stepIds + listOf("zero-sound", "zero-particle", "zero-damage"),
                ) else it
            },
        ).validate()
    }

    "plan loading rejects malformed numeric scalars instead of applying defaults" {
        listOf(
            "tick-ticks: 1.5\n",
            "speech:\n  height: not-a-number\n",
        ).forEachIndexed { index, patch ->
            val root = Files.createTempDirectory("origin-scenes-invalid-number-$index")
            Files.createDirectories(root.resolve("modules"))
            Files.writeString(root.resolve("modules/origin-scenes.yml"), patch)

            shouldThrow<IllegalStateException> { OriginScenePlan.load(root) }
        }
    }

    "route profile rejects unknown allowed support material" {
        val root = Files.createTempDirectory("origin-scenes-invalid-support-material-")
        Files.createDirectories(root.resolve("modules"))
        Files.writeString(
            root.resolve("modules/origin-scenes.yml"),
            """
            scenes:
              forge:
                route-profiles:
                  floor:
                    allowed-support-materials: [NOT_A_MATERIAL]
            """.trimIndent(),
        )

        shouldThrow<IllegalArgumentException> { OriginScenePlan.load(root) }
    }

    "route profile rejects item-only support material" {
        val root = Files.createTempDirectory("origin-scenes-invalid-item-support-material-")
        Files.createDirectories(root.resolve("modules"))
        Files.writeString(
            root.resolve("modules/origin-scenes.yml"),
            """
            scenes:
              forge:
                route-profiles:
                  floor:
                    allowed-support-materials: [POTION]
            """.trimIndent(),
        )

        shouldThrow<IllegalArgumentException> { OriginScenePlan.load(root) }
    }

    "route profile parses bounded surface drop and support materials" {
        val root = Files.createTempDirectory("origin-scenes-support-material-")
        Files.createDirectories(root.resolve("modules"))
        Files.writeString(
            root.resolve("modules/origin-scenes.yml"),
            """
            scenes:
              forge:
                route-profiles:
                  floor:
                    maximum-surface-drop: 0.0625
                    maximum-step-height: 1.0
                    surface-search-range: 2
                    allowed-support-materials: [FARMLAND]
            """.trimIndent(),
        )

        val profile = OriginScenePlan.load(root).scene("forge").routeProfiles.getValue("floor")
        profile.maximumSurfaceDrop shouldBe 0.0625
        profile.maximumStepHeight shouldBe 1.0
        profile.surfaceSearchRange shouldBe 2
        profile.allowedSupportMaterials shouldBe setOf(org.bukkit.Material.FARMLAND)
    }

    "route profile rejects nonzero surface search without support allowlist" {
        val root = Files.createTempDirectory("origin-scenes-surface-search-gate-")
        Files.createDirectories(root.resolve("modules"))
        Files.writeString(
            root.resolve("modules/origin-scenes.yml"),
            """
            scenes:
              forge:
                route-profiles:
                  floor:
                    surface-search-range: 1
            """.trimIndent(),
        )

        shouldThrow<IllegalArgumentException> { OriginScenePlan.load(root) }
    }

    "scene points reject non-finite coordinates and poses" {
        shouldThrow<IllegalArgumentException> { OriginScenePoint(Double.NaN, 0.0, 0.0) }
        shouldThrow<IllegalArgumentException> { OriginScenePoint(0.0, 0.0, 0.0, Float.POSITIVE_INFINITY) }
    }

    "living scene steps validate leased actors and bounded group routes" {
        val scene = OriginScenePlan.load(Files.createTempDirectory("origin-scenes-living-contract-test")).scene("forge")
        val cycle = scene.cycles.single { it.id == "ledger-orders" }
        val actorId = cycle.actorIds.first()
        val routeProfile = scene.routeProfiles.keys.first()
        val steps = listOf(
            OriginSceneStep.BlockDisplay(
                key = "cargo",
                surface = null,
                anchor = null,
                material = "HAY_BLOCK",
                origin = OriginScenePropOrigin.BOTTOM_CENTER,
                offset = OriginSceneVector.ZERO,
                scale = OriginSceneVector(0.5, 0.5, 0.5),
                rotationYDegrees = 0f,
                interpolationTicks = 2,
                followActorId = actorId,
                followOffset = OriginSceneVector(0.0, 1.0, -0.5),
            ),
            OriginSceneStep.RemoveDisplay("cargo"),
            OriginSceneStep.Pose(actorId, OriginScenePose.STAND),
            OriginSceneStep.Dismount(actorId),
            OriginSceneStep.MoveGroup(listOf(actorId), listOf(scene.anchors.keys.first()), routeProfile, 20),
        )

        scene.copy(
            cycles = scene.cycles.map {
                if (it.id == cycle.id) it.copy(steps = steps, stepIds = steps.indices.map { index -> "living-$index" }) else it
            },
        ).validate()

        shouldThrow<IllegalArgumentException> {
            scene.copy(
                cycles = scene.cycles.map {
                    if (it.id == cycle.id) it.copy(
                        steps = listOf(OriginSceneStep.MoveGroup(listOf(actorId), emptyList(), routeProfile, 20)),
                        stepIds = listOf("bad-group"),
                    ) else it
                },
            ).validate()
        }
    }

    "ItemsAdder display steps support a followed prop restaged at an anchor with the same key" {
        val scene = OriginScenePlan.load(Files.createTempDirectory("origin-scenes-item-display-contract-test"))
            .scene("forge")
        val cycle = scene.cycles.single { it.id == "ledger-orders" }
        val actorId = cycle.actorIds.first()
        val item = OriginSceneStep.ItemDisplay(
            key = "wheelbarrow",
            anchor = null,
            itemId = "elitecreatures:farmer_decoration_v1_wheelbarrow",
            context = OriginSceneItemDisplayContext.GROUND,
            offset = OriginSceneVector.ZERO,
            scale = OriginSceneVector(1.0, 1.0, 1.0),
            yawOffsetDegrees = 180f,
            interpolationTicks = 4,
            followActorId = actorId,
            followOffset = OriginSceneVector(0.0, 0.078125, 1.0),
        )
        val restaged = item.copy(
            anchor = scene.anchors.keys.first(),
            followActorId = null,
            followOffset = OriginSceneVector.ZERO,
        )
        val steps = listOf(item, restaged, OriginSceneStep.RemoveDisplay(item.key))

        scene.copy(
            cycles = scene.cycles.map {
                if (it.id == cycle.id) it.copy(steps = steps, stepIds = listOf("follow", "park", "remove")) else it
            },
        ).validate()

        shouldThrow<IllegalArgumentException> {
            val invalid = item.copy(itemId = "wheelbarrow")
            val invalidSteps = listOf(invalid, OriginSceneStep.RemoveDisplay(invalid.key))
            scene.copy(
                cycles = scene.cycles.map {
                    if (it.id == cycle.id) it.copy(
                        steps = invalidSteps,
                        stepIds = listOf("invalid-item", "remove"),
                    ) else it
                },
            ).validate()
        }
    }

    "ore inspection pushes and parks the cart on the protected haul route" {
        val scene = OriginScenePlan.load(Files.createTempDirectory("origin-scenes-ore-haul-test")).scene("forge")
        val cycle = scene.cycles.single { it.id == "ore-inspection" }
        val cartSteps = cycle.steps.filterIsInstance<OriginSceneStep.ItemDisplay>().filter { it.key == "savva-cart" }

        cartSteps.any { it.followActorId == 351 } shouldBe true
        cartSteps.map(OriginSceneStep.ItemDisplay::itemId).toSet() shouldBe
            setOf("elitecreatures:medieval_market_decoration_v1_cart_2")
        cartSteps.all { it.context == OriginSceneItemDisplayContext.GROUND } shouldBe true
        cartSteps.mapNotNull(OriginSceneStep.ItemDisplay::anchor).toSet() shouldBe setOf("ore-load-cart", "ore-park-cart")
        cartSteps.none {
            it.anchor != null && it.anchor in setOf("ore-chest", "ore-chest-stand", "ore-cart", "ore-cart-stand")
        } shouldBe true
        cycle.steps.filterIsInstance<OriginSceneStep.Move>().all { it.routeProfile == "ore-haul" } shouldBe true

        val haul = scene.routeProfiles.getValue("ore-haul")
        listOf(
            NpcRouteCell(66, 73), // Bran
            NpcRouteCell(71, 72), // training dummy
            NpcRouteCell(76, 70), // Edgar
            NpcRouteCell(79, 66), // Luka
        ).forEach { cell -> haul.allows(cell) shouldBe false }
    }

    "mount yard keeps multiple animals active without sharing one actor across simultaneous lanes" {
        val scene = OriginScenePlan.load(Files.createTempDirectory("origin-scenes-mount-test")).scene("mount-yard")
        val animalCycles = scene.cycles.filter { it.id.endsWith("-paddock") }

        animalCycles.size shouldBe 3
        animalCycles.flatMap(OriginSceneCycle::actorIds).toSet() shouldBe setOf(371, 422, 423)
        scene.maxConcurrentCycles shouldBe 7
        scene.actors.keys.containsAll((440..446).toList()) shouldBe true
        scene.cycles.map(OriginSceneCycle::id).toSet().containsAll(
            setOf("saddler-work", "caravan-preparation", "helper-hay", "helper-water", "cat-and-dog"),
        ) shouldBe true
    }

    "forge workstations separate walk stands from gaze targets" {
        val scene = OriginScenePlan.load(Files.createTempDirectory("origin-scenes-facing-test")).scene("forge")

        scene.anchors.getValue("stock-stand").explicitPose shouldBe false
        scene.actors.getValue(354).home.explicitPose shouldBe true
        (scene.anchors.getValue("apprentice-stand").x == scene.anchors.getValue("apprentice-anvil").x &&
            scene.anchors.getValue("apprentice-stand").z == scene.anchors.getValue("apprentice-anvil").z) shouldBe false
        scene.propSurfaces.getValue("apprentice-work-surface").lookTargetOffsetY shouldBe -1.15
        scene.propSurfaces.getValue("master-work-surface").lookTargetOffsetY shouldBe -1.15
    }

    "forge production cycles expose readable material transformations and container handoffs" {
        val scene = OriginScenePlan.load(Files.createTempDirectory("origin-scenes-production-test")).scene("forge")
        val luka = scene.cycles.single { it.id == "apprentice-engraving" }
        val savva = scene.cycles.single { it.id == "ore-inspection" }
        val bran = scene.cycles.single { it.id == "blade-practice" }
        val yar = scene.cycles.single { it.id == "furnace-check" }

        luka.steps.filterIsInstance<OriginSceneStep.ContainerLid>().map(OriginSceneStep.ContainerLid::open) shouldContainExactly
            listOf(true, false, true, false)
        luka.steps.filterIsInstance<OriginSceneStep.Swing>().filter { it.feedbackSurface == "apprentice-work-surface" }
            .sumOf(OriginSceneStep.Swing::repetitions) shouldBe 22
        luka.steps.filterIsInstance<OriginSceneStep.BlockDisplay>().map { it.key }.toSet() shouldBe
            setOf(
                "luka-blank",
                "luka-letter-x-left",
                "luka-letter-x-right",
                "luka-letter-u-left",
                "luka-letter-u-right",
                "luka-letter-short-i-left",
                "luka-letter-short-i-right",
                "luka-letter-short-i-diagonal",
                "luka-letter-short-i-breve",
            )
        luka.steps.filterIsInstance<OriginSceneStep.BlockDisplay>().all {
            it.surface == "apprentice-work-surface" && it.origin == OriginScenePropOrigin.BOTTOM_CENTER
        } shouldBe true
        luka.steps.filterIsInstance<OriginSceneStep.BlockDisplay>().count { it.rotationYDegrees != 0f } shouldBe 5
        val uStrokes = luka.steps.filterIsInstance<OriginSceneStep.BlockDisplay>()
            .filter { it.key.startsWith("luka-letter-u-") }
        uStrokes.size shouldBe 2
        uStrokes.single { it.key.endsWith("left") }.rotationYDegrees shouldBe 41f
        uStrokes.single { it.key.endsWith("right") }.rotationYDegrees shouldBe -32f
        luka.steps.filterIsInstance<OriginSceneStep.RemoveDisplay>().map { it.key }.toSet() shouldBe
            luka.steps.filterIsInstance<OriginSceneStep.BlockDisplay>().map { it.key }.toSet()
        val alternate = scene.cycles.single { it.id == "apprentice-engraving-jopa" }
        alternate.steps.filterIsInstance<OriginSceneStep.BlockDisplay>().map { it.key }.toSet().size shouldBe 14
        alternate.steps.filterIsInstance<OriginSceneStep.RemoveDisplay>().map { it.key }.toSet() shouldBe
            alternate.steps.filterIsInstance<OriginSceneStep.BlockDisplay>().map { it.key }.toSet()
        val alternateSteps = alternate.steps
        val alternateSwingIndices = alternateSteps.indices.filter { alternateSteps[it] is OriginSceneStep.Swing }
        alternateSwingIndices.size shouldBe 5
        val alternateDisplayIndex = { key: String ->
            alternateSteps.indexOfFirst { it is OriginSceneStep.BlockDisplay && it.key == key }
        }
        (alternateSwingIndices[1] < alternateDisplayIndex("luka-jopa-zh-left")) shouldBe true
        (alternateDisplayIndex("luka-jopa-zh-left") < alternateSwingIndices[2]) shouldBe true
        (alternateSwingIndices[2] < alternateDisplayIndex("luka-jopa-o-left")) shouldBe true
        (alternateDisplayIndex("luka-jopa-o-left") < alternateSwingIndices[3]) shouldBe true
        (alternateSwingIndices[3] < alternateDisplayIndex("luka-jopa-p-left")) shouldBe true
        (alternateDisplayIndex("luka-jopa-p-left") < alternateSwingIndices[4]) shouldBe true
        (alternateSwingIndices[4] < alternateDisplayIndex("luka-jopa-a-left")) shouldBe true
        alternate.steps.filterIsInstance<OriginSceneStep.BlockDisplay>()
            .filter { it.key == "luka-jopa-plate" }
            .map { it.scale.x } shouldContainExactly listOf(0.88, 0.82, 0.62, 0.43, 0.21)
        val aStrokes = alternate.steps.filterIsInstance<OriginSceneStep.BlockDisplay>()
            .filter { it.key.startsWith("luka-jopa-a-") }
        aStrokes.single { it.key.endsWith("left") }.rotationYDegrees shouldBe -17f
        aStrokes.single { it.key.endsWith("right") }.rotationYDegrees shouldBe 17f
        savva.steps.filterIsInstance<OriginSceneStep.ContainerLid>().map(OriginSceneStep.ContainerLid::anchor).toSet() shouldBe
            setOf("ore-cart", "stock-chest")
        scene.actors.getValue(351).home.x shouldBe scene.anchors.getValue("ore-cart-stand").x
        scene.actors.getValue(351).home.z shouldBe scene.anchors.getValue("ore-cart-stand").z
        bran.yieldAnchor shouldBe "dummy"
        bran.yieldRange shouldBe 2.0
        bran.steps.filterIsInstance<OriginSceneStep.Swing>().sumOf(OriginSceneStep.Swing::repetitions) shouldBe 24
        bran.steps.filterIsInstance<OriginSceneStep.Swing>().all {
            it.damageTargetNpcId == 364 && it.damageAmount == 1.0
        } shouldBe true
        scene.anchors.getValue("dummy-stage").z shouldBe 71.4
        yar.steps.filterIsInstance<OriginSceneStep.Equip>().map(OriginSceneStep.Equip::material) shouldContainExactly
            listOf("RAW_IRON", "MAGMA_BLOCK", "AIR", "IRON_INGOT", "AIR")
        yar.steps.filterIsInstance<OriginSceneStep.ContainerLid>().map(OriginSceneStep.ContainerLid::open) shouldContainExactly
            listOf(true, false, true, false)
        yar.steps.filterIsInstance<OriginSceneStep.BlockDisplay>().map { it.key }.toSet() shouldBe
            setOf(
                "yar-mould",
                "yar-crucible",
                "yar-molten-cap",
                "yar-stream-a",
                "yar-stream-b",
                "yar-stream-c",
                "yar-casting",
            )
        yar.steps.filterIsInstance<OriginSceneStep.BlockDisplay>().all {
            it.anchor == "quench-cauldron" && it.surface == null
        } shouldBe true
        yar.steps.filterIsInstance<OriginSceneStep.RemoveDisplay>().map { it.key }.toSet() shouldBe
            yar.steps.filterIsInstance<OriginSceneStep.BlockDisplay>().map { it.key }.toSet()
    }

    "Edgar showcase cycle crosses the forge with an assistant and a scoped workpiece" {
        val scene = OriginScenePlan.load(Files.createTempDirectory("origin-scenes-edgar-showcase-test")).scene("forge")
        val cycle = scene.cycles.single { it.id == "master-forging-showcase" }
        val axe = scene.cycles.single { it.id == "master-anvil" }
        val spear = scene.cycles.single { it.id == "master-spear" }
        val shield = scene.cycles.single { it.id == "master-shield" }
        val horseshoe = scene.cycles.single { it.id == "master-horseshoe" }

        cycle.actorIds shouldBe setOf(349, 358)
        cycle.steps.filterIsInstance<OriginSceneStep.Move>().map(OriginSceneStep.Move::anchor).toSet() shouldContainAll
            setOf("stock-stand", "master-anvil-stand", "quench-stand")
        cycle.steps.filterIsInstance<OriginSceneStep.BlockDisplay>().map { it.key }.toSet() shouldBe
            setOf("edgar-blade", "edgar-guard", "edgar-grip", "edgar-pommel")
        cycle.steps.filterIsInstance<OriginSceneStep.BlockDisplay>().all {
            it.surface == "master-work-surface" && it.anchor == null && it.origin == OriginScenePropOrigin.BOTTOM_CENTER
        } shouldBe true
        cycle.steps.filterIsInstance<OriginSceneStep.Swing>().filter { it.feedbackSurface == "master-work-surface" }
            .sumOf(OriginSceneStep.Swing::repetitions) shouldBe 24
        cycle.steps.filterIsInstance<OriginSceneStep.RemoveDisplay>().map(OriginSceneStep.RemoveDisplay::key).toSet() shouldBe
            setOf("edgar-blade", "edgar-guard", "edgar-grip", "edgar-pommel")
        cycle.steps.filterIsInstance<OriginSceneStep.LookAtSurface>().map(OriginSceneStep.LookAtSurface::surface).toSet() shouldBe
            setOf("master-work-surface")
        listOf(axe, spear, shield, horseshoe).forEach { figure ->
            figure.actorIds shouldBe setOf(349)
            val displays = figure.steps.filterIsInstance<OriginSceneStep.BlockDisplay>()
            val removals = figure.steps.filterIsInstance<OriginSceneStep.RemoveDisplay>()
            displays.all {
                it.surface == "master-work-surface" && it.anchor == null && it.origin == OriginScenePropOrigin.BOTTOM_CENTER
            } shouldBe true
            removals.map { it.key }.toSet() shouldBe displays.map { it.key }.toSet()
            val swings = figure.steps.filterIsInstance<OriginSceneStep.Swing>()
            swings.all { it.feedbackSurface == "master-work-surface" } shouldBe true
            (swings.sumOf { it.repetitions * it.periodTicks } in 1_200L..2_400L) shouldBe true
            (figure.cooldownMillis.last <= 2_000L) shouldBe true
            val finishIndex = figure.steps.indexOfLast { it is OriginSceneStep.Swing }
            val consumeIndex = figure.steps.indexOfFirst { it is OriginSceneStep.RemoveDisplay && it.key.endsWith("-blank") }
            (consumeIndex in 0 until finishIndex) shouldBe true
        }
        shield.steps.filterIsInstance<OriginSceneStep.Equip>().last().material shouldBe "SHIELD"
    }
})
