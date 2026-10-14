package ru.arc.origin

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import org.bukkit.Material
import org.joml.Quaternionf
import org.joml.Vector3f

class OriginWorkshopTurnTest : FreeSpec({
    "assembler first planed edge turns around the live vise center" {
        val dimensions = OriginWorkshopTableDimensions.DEFAULT
        val tuning = OriginWorkshopMachineTuning()
        val target = OriginWorkshopPoint(tuning.viseCenterX, dimensions.height + 0.10, tuning.viseCenterZ)
        val band = originWorkshopJoinedTopPieces(edges = 1).single { it.material == Material.BIRCH_PLANKS }
        val start = originWorkshopTurnPose(target, target, Quaternionf(), flip = false, lift = 0.18, progress = 0.0)
        val middle = originWorkshopTurnPose(target, target, Quaternionf(), flip = false, lift = 0.18, progress = 0.5)
        val end = originWorkshopTurnPose(target, target, Quaternionf(), flip = false, lift = 0.18, progress = 1.0)

        start.center.near(target) shouldBe true
        middle.center.near(target.copy(y = target.y + 0.18)) shouldBe true
        end.center.near(target) shouldBe true

        val bandStart = rotated(start.rotation, band.center)
        val bandMiddle = rotated(middle.rotation, band.center)
        val bandEnd = rotated(end.rotation, band.center)
        (start.center.x + bandStart.x).near(target.x + band.center.x) shouldBe true
        (middle.center.z + bandMiddle.z).near(target.z - band.center.x) shouldBe true
        (end.center.x + bandEnd.x).near(target.x - band.center.x) shouldBe true
        rotated(end.rotation, Vector3f(1.0f, 0.0f, 0.0f)).x.toDouble().near(-1.0) shouldBe true
    }

    "finisher front-side workpiece flips to the back without passing through the bench" {
        val dimensions = OriginWorkshopTableDimensions.DEFAULT
        val target = OriginWorkshopPoint(0.0, dimensions.height + 0.04, -0.45)
        val panel = originWorkshopFinishingPanelPieces(sandedFront = 3)
        val processedBand = panel.first { it.material == Material.BIRCH_PLANKS && it.center.y > 0.0 }
        val start = originWorkshopTurnPose(target, target, Quaternionf(), flip = true, lift = 0.36, progress = 0.0)
        val middle = originWorkshopTurnPose(target, target, Quaternionf(), flip = true, lift = 0.36, progress = 0.5)
        val end = originWorkshopTurnPose(target, target, Quaternionf(), flip = true, lift = 0.36, progress = 1.0)

        start.center.near(target) shouldBe true
        middle.center.near(target.copy(y = target.y + 0.36)) shouldBe true
        end.center.near(target) shouldBe true
        (processedBand.center.y > 0.0) shouldBe true
        rotated(end.rotation, Vector3f(0.0f, 1.0f, 0.0f)).y.toDouble().near(-1.0) shouldBe true
        (end.center.y + rotated(end.rotation, processedBand.center).y).near(
            target.y - processedBand.center.y,
        ) shouldBe true

        for (tick in 0..ORIGIN_WORKSHOP_TURN_TICKS.toInt()) {
            val progress = tick.toDouble() / ORIGIN_WORKSHOP_TURN_TICKS
            val pose = originWorkshopTurnPose(target, target, Quaternionf(), flip = true, lift = 0.36, progress = progress)
            for (piece in panel) {
                (minimumY(piece, pose) >= dimensions.height - CLEARANCE_EPSILON) shouldBe true
            }
        }
    }

    "seam one moves to the far edge while the opposite edge feeds under the needle" {
        val dimensions = OriginWorkshopTableDimensions.DEFAULT
        val tuning = OriginWorkshopMachineTuning()
        val from = originWorkshopSewingClothPoint(dimensions, progress = 1.0)
        val to = originWorkshopSewingClothPoint(dimensions, progress = 0.0)
        val seam = originWorkshopUpholsteryPieces(stretchedEdges = 2, seams = 1)
            .single { it.material == Material.WHITE_WOOL }
        val start = originWorkshopTurnPose(from, to, Quaternionf(), flip = false, lift = 0.025, progress = 0.0)
        val middle = originWorkshopTurnPose(from, to, Quaternionf(), flip = false, lift = 0.025, progress = 0.5)
        val end = originWorkshopTurnPose(from, to, Quaternionf(), flip = false, lift = 0.025, progress = 1.0)

        start.center.near(from) shouldBe true
        middle.center.near(OriginWorkshopPoint(0.42, from.y + 0.025, from.z)) shouldBe true
        end.center.near(to) shouldBe true
        (start.center.z + seam.center.z).near(-0.58) shouldBe true
        val seamAtEnd = rotated(end.rotation, seam.center)
        (end.center.z + seamAtEnd.z).near(-0.20) shouldBe true
        val secondPassCenter = originWorkshopSewingClothPoint(dimensions, progress = 0.5)
        val nextSeamUnderNeedle = rotated(end.rotation, Vector3f(0.0f, seam.center.y.toFloat(), 0.19f))
        (secondPassCenter.x + nextSeamUnderNeedle.x).near(0.42) shouldBe true
        (secondPassCenter.z + nextSeamUnderNeedle.z).near(-0.58) shouldBe true

        val footPose = originWorkshopCraftMachinePose("sewing-foot", 0.0, dimensions, tuning)
        footPose.pieces.getValue("upholsterer-sewing-foot-toe-left").centerOffset.y shouldBe 0.0
        footPose.pieces.getValue("upholsterer-sewing-foot-lifter").rotationDegrees shouldBe 0.0
        val table = originWorkshopTablePieces(OriginWorkshopTableRole.UPHOLSTERER, 0, dimensions, tuning)
        val toe = table.single { it.key == "upholsterer-sewing-foot-toe-left" }
        val cloth = table.single { it.key == "upholsterer-sewing-fabric" }
        (toe.y - toe.height / 2.0 > cloth.y + cloth.height / 2.0) shouldBe true
    }

    "turn calculations leave the caller rotation unchanged" {
        val rotation = Quaternionf().rotateY(0.37f).rotateX(-0.19f)
        val original = Quaternionf(rotation)
        val from = OriginWorkshopPoint(-0.4, 1.2, -0.6)
        val to = OriginWorkshopPoint(0.7, 1.4, 0.2)

        repeat(3) { index ->
            originWorkshopTurnPose(from, to, rotation, flip = index == 1, lift = 0.18, progress = index / 2.0)
        }

        rotation.components() shouldBe original.components()
    }
})

private const val CLEARANCE_EPSILON = 1.0e-6

private fun OriginWorkshopPoint.near(expected: OriginWorkshopPoint): Boolean =
    x.near(expected.x) && y.near(expected.y) && z.near(expected.z)

private fun Double.near(expected: Double): Boolean = abs(this - expected) <= CLEARANCE_EPSILON

private fun Quaternionf.components(): List<Float> = listOf(x, y, z, w)

private fun rotated(rotation: Quaternionf, point: OriginWorkshopPoint): Vector3f =
    rotated(rotation, Vector3f(point.x.toFloat(), point.y.toFloat(), point.z.toFloat()))

private fun rotated(rotation: Quaternionf, vector: Vector3f): Vector3f =
    Vector3f(vector).also { rotation.transform(it) }

private fun minimumY(piece: OriginWorkshopWorkpiecePiece, pose: OriginWorkshopTurnPose): Double {
    val center = rotated(pose.rotation, piece.center)
    val axisX = rotated(pose.rotation, Vector3f(1.0f, 0.0f, 0.0f))
    val axisY = rotated(pose.rotation, Vector3f(0.0f, 1.0f, 0.0f))
    val axisZ = rotated(pose.rotation, Vector3f(0.0f, 0.0f, 1.0f))
    val halfHeight = (
        abs(axisX.y) * piece.size.x + abs(axisY.y) * piece.size.y + abs(axisZ.y) * piece.size.z
    ) / 2.0
    return pose.center.y + center.y - halfHeight
}
