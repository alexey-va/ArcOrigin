package ru.arc.origin

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.floats.shouldBeLessThan
import kotlin.math.abs
import kotlin.math.max
import org.bukkit.Material
import org.joml.Quaternionf
import org.joml.Vector3f

class OriginWorkshopWorkpieceTest : FreeSpec({
    "workpiece renderer accepts the two config values and coarse board states match the cube preview" {
        OriginWorkshopWorkpieceRenderer.parse(" model ") shouldBe OriginWorkshopWorkpieceRenderer.MODEL
        OriginWorkshopWorkpieceRenderer.parse("CUBES") shouldBe OriginWorkshopWorkpieceRenderer.CUBES
        shouldThrow<IllegalArgumentException> { OriginWorkshopWorkpieceRenderer.parse("display") }

        val expected = linkedMapOf(
            OriginWorkshopBoardModel.RAW to (39 to 1.04),
            OriginWorkshopBoardModel.CUT_ONCE to (33 to 0.88),
            OriginWorkshopBoardModel.CUT to (27 to 0.72),
            OriginWorkshopBoardModel.DRILLED_1 to (26 to 0.72),
            OriginWorkshopBoardModel.DRILLED_2 to (25 to 0.72),
            OriginWorkshopBoardModel.DRILLED_3 to (24 to 0.72),
            OriginWorkshopBoardModel.OFFCUT to (6 to 0.16),
        )
        expected.forEach { (model, dimensions) ->
            val pieces = originWorkshopCoarseBoardPieces(model)
            pieces.size shouldBe dimensions.first
            pieces.all { it.material == Material.OAK_PLANKS } shouldBe true
            pieces.all { it.size == OriginWorkshopGamePartSize(0.08f, 0.08f, 0.08f) } shouldBe true
            pieces.map { it.center }.toSet().size shouldBe pieces.size
            (pieces.maxOf { it.center.x + it.size.x / 2.0 } - pieces.minOf { it.center.x - it.size.x / 2.0 })
                .near(dimensions.second) shouldBe true
            (pieces.maxOf { it.center.z + it.size.z / 2.0 } - pieces.minOf { it.center.z - it.size.z / 2.0 })
                .near(0.24) shouldBe true
            (pieces.maxOf { it.center.y + it.size.y / 2.0 } - pieces.minOf { it.center.y - it.size.y / 2.0 })
                .near(0.08) shouldBe true
        }

        val drilled = listOf(
            OriginWorkshopBoardModel.DRILLED_1 to 1,
            OriginWorkshopBoardModel.DRILLED_2 to 2,
            OriginWorkshopBoardModel.DRILLED_3 to 3,
        )
        drilled.forEach { (model, holes) ->
            val pieces = originWorkshopCoarseBoardPieces(model)
            listOf(-0.24, 0.0, 0.24).take(holes).forEach { holeX ->
                pieces.any { it.center.x.near(holeX) && it.center.y.near(0.0) && it.center.z.near(0.0) } shouldBe false
                for (sideZ in listOf(-0.08, 0.08)) {
                    pieces.any { it.center.x.near(holeX) && it.center.z.near(sideZ) } shouldBe true
                }
            }
        }
    }

    "the sawn board stays centered in one cuboid before drilling" {
        originWorkshopBoardPieces(0).single().size shouldBe OriginWorkshopGamePartSize(0.72f, 0.08f, 0.22f)
        originWorkshopBoardPieces(0).single().center shouldBe OriginWorkshopPoint(0.0, 0.0, 0.0)
        originWorkshopBoardPieces(0).single().material shouldBe Material.OAK_PLANKS
    }

    "fixed ItemDisplay compensation cancels the native half turn for every workpiece pose" {
        for (angle in listOf(-Math.PI, -Math.PI / 2, 0.0, Math.PI / 3, Math.PI)) {
            val desired = Quaternionf().rotationY(angle.toFloat())
            val entity = originWorkshopBoardItemDisplayRotation(desired)
            val rendered = Quaternionf(entity).rotateY(Math.PI.toFloat())
            for (point in listOf(Vector3f(1f, 0f, 0f), Vector3f(0f, 0f, 1f))) {
                val expected = Vector3f(point).rotate(desired)
                val actual = Vector3f(point).rotate(rendered)
                (actual.distance(expected) < 1.0e-5f) shouldBe true
            }
        }
    }

    "pure board cuboid hitboxes stay centered and match rotated piece dimensions" {
        val center = OriginWorkshopPoint(5.25, 72.0, -8.5)
        val piece = OriginWorkshopWorkpiecePiece(
            OriginWorkshopPoint(0.18, 0.03, -0.02),
            OriginWorkshopGamePartSize(0.24f, 0.08f, 0.075f),
            Material.OAK_PLANKS,
        )
        for (angle in listOf(0.0, Math.PI / 2, Math.PI, -Math.PI / 2)) {
            val rotation = Quaternionf().rotationY(angle.toFloat())
            val matrix = originWorkshopWorkpieceHitboxMatrix(center, rotation, piece)
            val corners = buildList {
                for (x in listOf(0f, 1f)) for (y in listOf(0f, 1f)) for (z in listOf(0f, 1f)) {
                    add(matrix.transformPosition(Vector3f(x, y, z)))
                }
            }
            val localCenter = rotation.transform(Vector3f(piece.center.x.toFloat(), piece.center.y.toFloat(), piece.center.z.toFloat()))
            val half = rotation.transform(Vector3f(piece.size.x / 2f, piece.size.y / 2f, piece.size.z / 2f))
            val expected = Vector3f(center.x.toFloat(), center.y.toFloat(), center.z.toFloat()).add(localCenter)
            val actualCenter = Vector3f(
                (corners.minOf { it.x } + corners.maxOf { it.x }) / 2f,
                (corners.minOf { it.y } + corners.maxOf { it.y }) / 2f,
                (corners.minOf { it.z } + corners.maxOf { it.z }) / 2f,
            )
            actualCenter.distance(expected) shouldBeLessThan 1.0e-5f
            (corners.maxOf { it.x } - corners.minOf { it.x }).near(2f * abs(half.x)) shouldBe true
            (corners.maxOf { it.y } - corners.minOf { it.y }).near(2f * abs(half.y)) shouldBe true
            (corners.maxOf { it.z } - corners.minOf { it.z }).near(2f * abs(half.z)) shouldBe true
        }
    }

    "progressive drilling makes one through-hole at a time without overlapping wood pieces" {
        val holePositions = listOf(-0.23, 0.0, 0.23)
        for (holes in 0..3) {
            val pieces = originWorkshopBoardPieces(holes)
            val removedVolume = holes * 0.075f.toDouble() * 0.08f.toDouble() * 0.075f.toDouble()
            val boardVolume = 0.72f.toDouble() * 0.08f.toDouble() * 0.22f.toDouble()

            pieces.sumOf(::volume).near(boardVolume - removedVolume) shouldBe true
            pieces.all { it.material == Material.OAK_PLANKS } shouldBe true
            pieces.all { it.center.x - it.size.x / 2.0 >= -0.36 - EPSILON && it.center.x + it.size.x / 2.0 <= 0.36 + EPSILON } shouldBe true
            pieces.all { it.center.z - it.size.z / 2.0 >= -0.11 - EPSILON && it.center.z + it.size.z / 2.0 <= 0.11 + EPSILON } shouldBe true
            pieces.indices.forEach { first ->
                for (second in first + 1 until pieces.size) cuboidsOverlap(pieces[first], pieces[second]) shouldBe false
            }

            holePositions.take(holes).forEach { holeX -> contains(pieces, holeX, 0.0, 0.0) shouldBe false }
            holePositions.drop(holes).forEach { uncutX -> contains(pieces, uncutX, 0.0, 0.0) shouldBe true }
            holePositions.take(holes).forEach { holeX -> contains(pieces, holeX, 0.0, 0.09) shouldBe true }
        }
        originWorkshopBoardPieces(1).size shouldBe 4
        originWorkshopBoardPieces(2).size shouldBe 7
        originWorkshopBoardPieces(3).size shouldBe 10
        shouldThrow<IllegalArgumentException> { originWorkshopBoardPieces(-1) }
        shouldThrow<IllegalArgumentException> { originWorkshopBoardPieces(4) }
    }

    "all visible corners of a placement marker stay inside the click radius" {
        for (action in OriginWorkshopGameAction.entries) for (piece in originWorkshopPlacementMarker(action)) {
            val x = abs(piece.center.x) + piece.size.x / 2
            val y = abs(piece.center.y) + piece.size.y / 2
            val z = abs(piece.center.z) + piece.size.z / 2
            (x * x + y * y + z * z <= 0.36 * 0.36) shouldBe true
        }

        val pinDepth = originWorkshopAssemblerLegPieces(fastened = true).last().size.z.toDouble()
        val fastenerMarker = originWorkshopPlacementMarker(OriginWorkshopGameAction.TIGHTEN_LEFT, pinDepth).single()
        val x = abs(fastenerMarker.center.x) + fastenerMarker.size.x / 2
        val y = abs(fastenerMarker.center.y) + fastenerMarker.size.y / 2
        val z = abs(fastenerMarker.center.z) + fastenerMarker.size.z / 2
        (x * x + y * y + z * z <= 0.36 * 0.36) shouldBe true
        shouldThrow<IllegalArgumentException> {
            originWorkshopPlacementMarker(OriginWorkshopGameAction.TIGHTEN_VISE, pinDepth)
        }
    }

    "sewing feed maps finite progress to the needle span and clamps its endpoints" {
        val dimensions = OriginWorkshopTableDimensions.DEFAULT
        for ((progress, x) in listOf(0.0 to 0.72, 0.5 to 0.42, 1.0 to 0.12)) {
            val feed = originWorkshopSewingClothPoint(dimensions, progress)
            feed.x shouldBe x
            feed.y shouldBe dimensions.height + 0.14
            feed.z.near(-0.39) shouldBe true
        }
        originWorkshopSewingClothPoint(dimensions, -1.0) shouldBe originWorkshopSewingClothPoint(dimensions, 0.0)
        originWorkshopSewingClothPoint(dimensions, 2.0) shouldBe originWorkshopSewingClothPoint(dimensions, 1.0)
        shouldThrow<IllegalArgumentException> { originWorkshopSewingClothPoint(dimensions, Double.NaN) }
    }

    "carried board follows the shoulder, clears the head, and stays slightly behind at cardinal yaws" {
        val board = originWorkshopBoardPieces(0)
        for (yawDegrees in listOf(0.0, 90.0, 180.0, 270.0)) {
            val pose = originWorkshopCarryPose(yawDegrees, sneaking = false, geometry = board)
            val bounds = carryEnvelope(yawDegrees, board, pose)
            val forward = bodyForward(yawDegrees)
            val longAxis = pose.rotation.transform(Vector3f(1f, 0f, 0f))
            val thicknessAxis = pose.rotation.transform(Vector3f(0f, 1f, 0f))

            (dot(longAxis, forward) > 0.999) shouldBe true
            (abs(thicknessAxis.y) > 0.999) shouldBe true
            (bounds.minRight >= headHalfWidth(boardHeadFixture().size) + CARRY_TEST_CLEARANCE - 1.0e-4) shouldBe true
            (bounds.centerForward in -0.25..-0.05) shouldBe true
            assertCarryMeasurement(
                actual = bounds.minY,
                expected = torsoTop(bodyTorsoFixture()),
                label = "standing board bottom at yaw=$yawDegrees should rest on the shoulder",
            )
            (bounds.maxY > headBottom(boardHeadFixture())) shouldBe true

            val crouched = carryEnvelope(
                yawDegrees,
                board,
                originWorkshopCarryPose(yawDegrees, sneaking = true, geometry = board),
            )
            assertCarryMeasurement(
                actual = crouched.minY,
                expected = torsoTop(bodyTorsoFixture()) - 0.30,
                label = "sneaking board bottom at yaw=$yawDegrees should follow the lowered shoulder",
            )
        }
    }

    "wide cloth and tabletop turn upright beside the torso below the face" {
        val wideItems = listOf(
            originWorkshopUpholsteryPieces(stretchedEdges = 2),
            originWorkshopJoinedTopPieces(edges = 2, joints = 2),
        )
        for (yawDegrees in listOf(0.0, 90.0, 180.0, 270.0)) for (geometry in wideItems) {
            val pose = originWorkshopCarryPose(yawDegrees, sneaking = false, geometry = geometry)
            val bounds = carryEnvelope(yawDegrees, geometry, pose)
            val forward = bodyForward(yawDegrees)
            val longAxis = pose.rotation.transform(Vector3f(1f, 0f, 0f))
            val panelHeightAxis = pose.rotation.transform(Vector3f(0f, 0f, 1f))

            (dot(longAxis, forward) > 0.999) shouldBe true
            (abs(panelHeightAxis.y) > 0.999) shouldBe true
            (bounds.minRight >= bodySideExtent(bodyTorsoFixture(), bodyRightArmFixture()) + CARRY_TEST_CLEARANCE - 1.0e-4) shouldBe true
            (bounds.maxY < headBottom(boardHeadFixture())) shouldBe true
        }
    }

    "legs and padding stay upright, clear the torso, and remain below the face" {
        val recipe = originWorkshopGameRecipe(
            OriginWorkshopTableRole.UPHOLSTERER,
            "preview:carry-test",
            OriginWorkshopTableDimensions.DEFAULT,
            OriginWorkshopMachineTuning(),
            OriginWorkshopPoint(0.0, 0.0, 0.0),
            OriginWorkshopGameRules(),
        )
        val leg = originWorkshopAssemblerLegPieces(fastened = true)
        val padding = listOf(
            OriginWorkshopWorkpiecePiece(
                OriginWorkshopPoint(0.0, 0.0, 0.0),
                recipe.partSizes.getValue(OriginWorkshopGameAction.PICK_PADDING),
                Material.WHITE_WOOL,
            ),
        )
        for (yawDegrees in listOf(0.0, 90.0, 180.0, 270.0)) for (geometry in listOf(leg, padding)) {
            val pose = originWorkshopCarryPose(yawDegrees, sneaking = false, geometry = geometry)
            val bounds = carryEnvelope(yawDegrees, geometry, pose)
            val localUp = pose.rotation.transform(Vector3f(0f, 1f, 0f))

            (abs(localUp.y) > 0.999) shouldBe true
            (bounds.minRight >= bodySideExtent(bodyTorsoFixture(), bodyRightArmFixture()) + CARRY_TEST_CLEARANCE - 1.0e-4) shouldBe true
            (bounds.maxY < headBottom(boardHeadFixture())) shouldBe true
        }
    }

    "sneaking lowers the actual carried bounds without changing their orientation or side clearance" {
        val geometries = listOf(
            originWorkshopBoardPieces(0),
            originWorkshopUpholsteryPieces(stretchedEdges = 2),
            originWorkshopJoinedTopPieces(edges = 2, joints = 2),
            originWorkshopAssemblerLegPieces(fastened = true),
        )
        for (yawDegrees in listOf(0.0, 90.0, 180.0, 270.0)) for (geometry in geometries) {
            val standingPose = originWorkshopCarryPose(yawDegrees, sneaking = false, geometry = geometry)
            val sneakingPose = originWorkshopCarryPose(yawDegrees, sneaking = true, geometry = geometry)
            val standing = carryEnvelope(yawDegrees, geometry, standingPose)
            val sneaking = carryEnvelope(yawDegrees, geometry, sneakingPose)

            (standingPose.rotation.x.near(sneakingPose.rotation.x) &&
                standingPose.rotation.y.near(sneakingPose.rotation.y) &&
                standingPose.rotation.z.near(sneakingPose.rotation.z) &&
                standingPose.rotation.w.near(sneakingPose.rotation.w)) shouldBe true
            assertCarryMeasurement(
                actual = standing.minY - sneaking.minY,
                expected = 0.30,
                label = "sneaking lower-bound drop at yaw=$yawDegrees",
            )
            assertCarryMeasurement(
                actual = standing.maxY - sneaking.maxY,
                expected = 0.30,
                label = "sneaking upper-bound drop at yaw=$yawDegrees",
            )
            (standing.minRight - sneaking.minRight).near(0.0) shouldBe true
        }
    }

    "carry pose rejects malformed geometry and non-finite body yaw" {
        val board = originWorkshopBoardPieces(0)
        shouldThrow<IllegalArgumentException> { originWorkshopCarryPose(Double.NaN, false, board) }
        shouldThrow<IllegalArgumentException> { originWorkshopCarryPose(Double.POSITIVE_INFINITY, false, board) }
        shouldThrow<IllegalArgumentException> { originWorkshopCarryPose(0.0, false, emptyList()) }
        val finiteExtremeYaw = originWorkshopCarryPose(Double.MAX_VALUE, false, board)
        listOf(
            finiteExtremeYaw.center.x.toFloat(), finiteExtremeYaw.center.y.toFloat(), finiteExtremeYaw.center.z.toFloat(),
            finiteExtremeYaw.rotation.x, finiteExtremeYaw.rotation.y, finiteExtremeYaw.rotation.z, finiteExtremeYaw.rotation.w,
        ).all { it.isFinite() } shouldBe true
        shouldThrow<IllegalArgumentException> {
            originWorkshopCarryPose(0.0, false, listOf(board.single().copy(center = OriginWorkshopPoint(Double.NaN, 0.0, 0.0))))
        }
    }
})

private data class CarryEnvelope(
    val minRight: Double,
    val centerForward: Double,
    val minY: Double,
    val maxY: Double,
)

private fun carryEnvelope(
    yawDegrees: Double,
    geometry: List<OriginWorkshopWorkpiecePiece>,
    pose: OriginWorkshopCarryPose,
): CarryEnvelope {
    val forward = bodyForward(yawDegrees)
    val right = bodyRight(yawDegrees)
    val axisX = pose.rotation.transform(Vector3f(1f, 0f, 0f))
    val axisY = pose.rotation.transform(Vector3f(0f, 1f, 0f))
    val axisZ = pose.rotation.transform(Vector3f(0f, 0f, 1f))
    val base = Vector3f(pose.center.x.toFloat(), pose.center.y.toFloat(), pose.center.z.toFloat())
    var minRight = Double.POSITIVE_INFINITY
    var minForward = Double.POSITIVE_INFINITY
    var maxForward = Double.NEGATIVE_INFINITY
    var minY = Double.POSITIVE_INFINITY
    var maxY = Double.NEGATIVE_INFINITY

    geometry.forEach { piece ->
        val center = pose.rotation.transform(Vector3f(
            piece.center.x.toFloat(), piece.center.y.toFloat(), piece.center.z.toFloat(),
        )).add(base)
        val halfRight = abs(dot(axisX, right)) * piece.size.x / 2.0 +
            abs(dot(axisY, right)) * piece.size.y / 2.0 +
            abs(dot(axisZ, right)) * piece.size.z / 2.0
        val halfForward = abs(dot(axisX, forward)) * piece.size.x / 2.0 +
            abs(dot(axisY, forward)) * piece.size.y / 2.0 +
            abs(dot(axisZ, forward)) * piece.size.z / 2.0
        val halfUp = abs(axisX.y) * piece.size.x / 2.0 +
            abs(axisY.y) * piece.size.y / 2.0 +
            abs(axisZ.y) * piece.size.z / 2.0
        val centerRight = dot(center, right)
        val centerForward = dot(center, forward)
        minRight = minOf(minRight, centerRight - halfRight)
        minForward = minOf(minForward, centerForward - halfForward)
        maxForward = maxOf(maxForward, centerForward + halfForward)
        minY = minOf(minY, center.y - halfUp)
        maxY = maxOf(maxY, center.y + halfUp)
    }
    return CarryEnvelope(minRight, (minForward + maxForward) / 2.0, minY, maxY)
}

private fun bodyForward(yawDegrees: Double): Vector3f {
    val yaw = Math.toRadians(yawDegrees)
    return Vector3f(-kotlin.math.sin(yaw).toFloat(), 0f, kotlin.math.cos(yaw).toFloat())
}

private fun bodyRight(yawDegrees: Double): Vector3f {
    val yaw = Math.toRadians(yawDegrees)
    return Vector3f(-kotlin.math.cos(yaw).toFloat(), 0f, -kotlin.math.sin(yaw).toFloat())
}

private fun dot(first: Vector3f, second: Vector3f): Double =
    first.x.toDouble() * second.x + first.y.toDouble() * second.y + first.z.toDouble() * second.z

private fun boardHeadFixture() = OriginWorkshopWorkpiecePiece(
    OriginWorkshopPoint(0.0, 1.65, 0.0),
    OriginWorkshopGamePartSize(0.50f, 0.50f, 0.50f),
    Material.TERRACOTTA,
)

private fun bodyTorsoFixture() = OriginWorkshopWorkpiecePiece(
    OriginWorkshopPoint(0.0, 1.04, 0.0),
    OriginWorkshopGamePartSize(0.50f, 0.72f, 0.25f),
    Material.BLUE_TERRACOTTA,
)

private fun bodyRightArmFixture() = OriginWorkshopWorkpiecePiece(
    OriginWorkshopPoint(-0.36, 1.0275, 0.0),
    OriginWorkshopGamePartSize(0.22f, 0.72f, 0.25f),
    Material.BLUE_TERRACOTTA,
)

private fun headHalfWidth(size: OriginWorkshopGamePartSize): Double = max(size.x, size.z) / 2.0
private fun torsoTop(piece: OriginWorkshopWorkpiecePiece): Double = piece.center.y + piece.size.y / 2.0
private fun headBottom(piece: OriginWorkshopWorkpiecePiece): Double = piece.center.y - piece.size.y / 2.0
private fun bodySideExtent(torso: OriginWorkshopWorkpiecePiece, arm: OriginWorkshopWorkpiecePiece): Double =
    max(torso.size.x / 2.0, abs(arm.center.x) + arm.size.x / 2.0)

private const val CARRY_TEST_CLEARANCE = 0.04
// Carry poses are converted through JOML Vector3f before reaching the ItemDisplay API.
private const val CARRY_FLOAT_ROUNDING_TOLERANCE = 1.0e-6

private fun assertCarryMeasurement(actual: Double, expected: Double, label: String) {
    val delta = actual - expected
    if (abs(delta) > CARRY_FLOAT_ROUNDING_TOLERANCE) {
        throw AssertionError(
            "$label: expected $expected blocks, got $actual (delta=$delta, " +
                "float-rounding tolerance=$CARRY_FLOAT_ROUNDING_TOLERANCE)",
        )
    }
}

private const val EPSILON = 1.0e-7

private fun volume(piece: OriginWorkshopWorkpiecePiece): Double =
    piece.size.x.toDouble() * piece.size.y.toDouble() * piece.size.z.toDouble()

private fun Double.near(expected: Double): Boolean = abs(this - expected) <= 1.0e-8

private fun Float.near(expected: Float): Boolean = abs(this - expected) <= 1.0e-5f

private fun contains(pieces: List<OriginWorkshopWorkpiecePiece>, x: Double, y: Double, z: Double): Boolean =
    pieces.any { piece ->
        abs(x - piece.center.x) < piece.size.x / 2.0 - EPSILON &&
            abs(y - piece.center.y) < piece.size.y / 2.0 - EPSILON &&
            abs(z - piece.center.z) < piece.size.z / 2.0 - EPSILON
    }

private fun cuboidsOverlap(first: OriginWorkshopWorkpiecePiece, second: OriginWorkshopWorkpiecePiece): Boolean =
    overlap(first.center.x, first.size.x, second.center.x, second.size.x) &&
        overlap(first.center.y, first.size.y, second.center.y, second.size.y) &&
        overlap(first.center.z, first.size.z, second.center.z, second.size.z)

private fun overlap(firstCenter: Double, firstSize: Float, secondCenter: Double, secondSize: Float): Boolean =
    minOf(firstCenter + firstSize / 2.0, secondCenter + secondSize / 2.0) -
        maxOf(firstCenter - firstSize / 2.0, secondCenter - secondSize / 2.0) > EPSILON
