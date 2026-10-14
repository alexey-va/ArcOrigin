package ru.arc.origin.scene

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeExactly
import io.kotest.matchers.floats.shouldBeExactly
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import org.bukkit.Location

class OriginScenePropContractTest : StringSpec({
    "surface props rest on the anchor instead of straddling it" {
        val resolved = OriginScenePropContract.resolve(
            anchor = OriginScenePoint(78.5, 71.04, 67.5),
            origin = OriginScenePropOrigin.BOTTOM_CENTER,
            offset = OriginSceneVector(0.0, 0.0, 0.0),
            scale = OriginSceneVector(0.46, 0.11, 0.30),
        )

        resolved.x.shouldBeExactly(78.5)
        resolved.y.shouldBeExactly(71.04)
        resolved.z.shouldBeExactly(67.5)
        resolved.translationX.shouldBeExactly(-0.23f)
        resolved.translationY.shouldBeExactly(0.0f)
        resolved.translationZ.shouldBeExactly(-0.15f)
    }

    "block surfaces derive the support plane from the live block" {
        val surface = OriginScenePropSurface(
            near = OriginScenePoint(79.1, 70.65, 67.8),
            materials = setOf(Material.ANVIL, Material.CHIPPED_ANVIL, Material.DAMAGED_ANVIL),
            searchRadius = 2,
            topOffset = 1.04,
        )

        val resolved = surface.pointFor(blockX = 78, blockY = 70, blockZ = 67)
        resolved.x.shouldBeExactly(78.5)
        resolved.y.shouldBeExactly(71.04)
        resolved.z.shouldBeExactly(67.5)
    }

    "work surfaces expose a lower look target than their display support plane" {
        val surface = OriginScenePropSurface(
            near = OriginScenePoint(79.1, 70.65, 67.8),
            materials = setOf(Material.ANVIL),
            searchRadius = 2,
            topOffset = 1.04,
            lookTargetOffsetY = -1.15,
        )

        val target = surface.lookTarget(OriginScenePoint(78.5, 71.04, 67.5))
        target.y.shouldBeExactly(69.89)
    }

    "rotated strokes keep their geometric center pinned to the configured offset" {
        val resolved = OriginScenePropContract.resolve(
            anchor = OriginScenePoint(78.5, 71.04, 67.5),
            origin = OriginScenePropOrigin.BOTTOM_CENTER,
            offset = OriginSceneVector(-0.2, 0.0, 0.0),
            scale = OriginSceneVector(0.1, 0.05, 0.2),
            rotationYDegrees = 90f,
        )

        resolved.x.shouldBeExactly(78.3)
        resolved.y.shouldBeExactly(71.04)
        resolved.z.shouldBeExactly(67.5)
        resolved.translationX.shouldBeExactly(-0.1f)
        resolved.translationY.shouldBeExactly(0.0f)
        resolved.translationZ.shouldBeExactly(0.05f)
    }

    "actor cargo offset follows the actor yaw while preserving its pose" {
        val anchor = OriginScenePropContract.actorAnchor(
            Location(null, 10.0, 64.0, -4.0, 90f, 12f),
            OriginSceneVector(1.0, 0.5, 0.0),
        )

        anchor.x.shouldBeExactly(10.0)
        anchor.y.shouldBeExactly(64.5)
        anchor.z.shouldBeExactly(-3.0)
        anchor.yaw.shouldBeExactly(90f)
        anchor.pitch.shouldBeExactly(12f)
        anchor.explicitPose shouldBe true
    }

    "portable item yaw adds its correction to the anchor or actor yaw" {
        OriginScenePropContract.itemDisplayYaw(0f, 180f).shouldBeExactly(180f)
        OriginScenePropContract.itemDisplayYaw(90f, 180f).shouldBeExactly(270f)
        OriginScenePropContract.itemDisplayYaw(270f, 180f).shouldBeExactly(90f)
    }

    "multi-part cargo stays rigid and uses actor yaw for the display pivot" {
        val actor = Location(null, 10.0, 64.0, -4.0, 450f, 0f)
        val left = OriginScenePropContract.actorAnchor(actor, OriginSceneVector(-0.5, 1.0, 0.0))
        val right = OriginScenePropContract.actorAnchor(actor, OriginSceneVector(0.5, 1.0, 0.0))
        val resolved = OriginScenePropContract.resolve(
            anchor = left,
            origin = OriginScenePropOrigin.BOTTOM_CENTER,
            offset = OriginSceneVector.ZERO,
            scale = OriginSceneVector(0.4, 0.2, 0.4),
            rotationYDegrees = OriginScenePropContract.actorRelativeRotationY(0f, actor.yaw),
        )

        left.z.shouldBeExactly(-4.5)
        right.z.shouldBeExactly(-3.5)
        resolved.translationX.shouldBeExactly(0.2f)
        resolved.translationZ.shouldBeExactly(-0.2f)
    }

    "following orientation normalizes wrapped yaw against an asymmetric part basis" {
        val actor = Location(null, 10.0, 64.0, -4.0, 450f, 0f)
        val anchor = OriginScenePropContract.actorAnchor(actor, OriginSceneVector(1.0, 1.0, 0.25))
        val rotation = OriginScenePropContract.actorRelativeRotationY(45f, actor.yaw)
        val resolved = OriginScenePropContract.resolve(
            anchor = anchor,
            origin = OriginScenePropOrigin.BOTTOM_CENTER,
            offset = OriginSceneVector.ZERO,
            scale = OriginSceneVector(0.2, 0.2, 0.8),
            rotationYDegrees = rotation,
        )

        rotation.shouldBeExactly(-45f)
        anchor.x.shouldBeExactly(9.75)
        anchor.z.shouldBeExactly(-3.0)
        (kotlin.math.abs(resolved.translationX - 0.21213204f) < 0.00001f) shouldBe true
        (kotlin.math.abs(resolved.translationZ + 0.35355338f) < 0.00001f) shouldBe true
    }

    "multipart follow tracking refreshes both parts on movement and none while stationary" {
        val initial = mapOf(369 to OriginSceneFollowPose(10.0, 64.0, -4.0, 450f))
        val moved = mapOf(369 to OriginSceneFollowPose(10.0, 64.0, -3.0, 450f))
        val twoParts = listOf(369, 369)

        twoParts.count { it in OriginScenePropContract.changedFollowActors(initial, moved) } shouldBe 2
        twoParts.count { it in OriginScenePropContract.changedFollowActors(moved, moved) } shouldBe 0
    }

    "display lifecycle rejects removal before creation" {
        shouldThrow<IllegalArgumentException> {
            OriginScenePropContract.validateLifecycle(listOf(OriginSceneStep.RemoveDisplay("missing")))
        }
    }

    "item and block displays share one removable key lifecycle" {
        val item = OriginSceneStep.ItemDisplay(
            key = "wheelbarrow",
            anchor = null,
            itemId = "elitecreatures:farmer_decoration_v1_wheelbarrow",
            context = OriginSceneItemDisplayContext.GROUND,
            offset = OriginSceneVector.ZERO,
            scale = OriginSceneVector(1.0, 1.0, 1.0),
            yawOffsetDegrees = 180f,
            interpolationTicks = 4,
            followActorId = 7,
            followOffset = OriginSceneVector(0.0, 0.078125, 1.0),
        )
        val block = OriginSceneStep.BlockDisplay(
            key = "wheelbarrow",
            surface = null,
            anchor = "forge",
            material = "IRON_BLOCK",
            origin = OriginScenePropOrigin.BOTTOM_CENTER,
            offset = OriginSceneVector.ZERO,
            scale = OriginSceneVector(0.5, 0.5, 0.5),
            rotationYDegrees = 0f,
            interpolationTicks = 4,
        )

        OriginScenePropContract.validateLifecycle(
            listOf(item, OriginSceneStep.RemoveDisplay(item.key), block, OriginSceneStep.RemoveDisplay(block.key)),
        )
        shouldThrow<IllegalArgumentException> {
            OriginScenePropContract.validateLifecycle(listOf(item, block, OriginSceneStep.RemoveDisplay(item.key)))
        }
    }

    "display lifecycle rejects a prop left visible at the end" {
        shouldThrow<IllegalArgumentException> {
            OriginScenePropContract.validateLifecycle(
                listOf(
                    OriginSceneStep.BlockDisplay(
                        key = "unfinished",
                        surface = "surface",
                        anchor = null,
                        material = "IRON_BLOCK",
                        origin = OriginScenePropOrigin.BOTTOM_CENTER,
                        offset = OriginSceneVector.ZERO,
                        scale = OriginSceneVector(0.5, 0.1, 0.3),
                        rotationYDegrees = 0f,
                        interpolationTicks = 0,
                    ),
                ),
            )
        }
    }
})
