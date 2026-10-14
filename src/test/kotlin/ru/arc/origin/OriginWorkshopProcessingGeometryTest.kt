package ru.arc.origin

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import kotlin.math.abs
import org.bukkit.Material

class OriginWorkshopProcessingGeometryTest : FreeSpec({
    "upholstery stretching keeps its underside fixed and seams survive the turn symmetrically" {
        val raw = originWorkshopUpholsteryPieces()
        raw.single().size shouldBe OriginWorkshopGamePartSize(0.64f, 0.04f, 0.46f)
        raw.single().center.x shouldBe 0.0
        raw.single().center.y.near(0.0) shouldBe true
        raw.single().center.z shouldBe 0.0

        val stretched = originWorkshopUpholsteryPieces(stretchedEdges = 2).first()
        stretched.size.x.toDouble().near(0.664) shouldBe true
        stretched.size.y.toDouble().near(0.032) shouldBe true
        stretched.size.z.toDouble().near(0.46) shouldBe true
        (stretched.center.y - stretched.size.y / 2.0).near(-0.02) shouldBe true
        (stretched.center.y + stretched.size.y / 2.0).near(0.012) shouldBe true

        val sewn = originWorkshopUpholsteryPieces(seams = 2)
        val seams = sewn.filter { it.material == Material.WHITE_WOOL }
        seams.size shouldBe 2
        seams.map { it.center.z }.sorted().map { it.near(-0.19) || it.near(0.19) }.all { it } shouldBe true

        seams.all { first -> seams.any { second ->
            (first.center.x + second.center.x).near(0.0) && (first.center.z + second.center.z).near(0.0)
        } } shouldBe true
        listOf(raw, originWorkshopUpholsteryPieces(stretchedEdges = 2, seams = 2, fasteners = 2)).forEach(::assertNoOverlaps)
    }

    "padding stays full thickness while near and far tucks replace its visible white sections" {
        val padded = originWorkshopUpholsteryPieces(padded = true)
        val whitePad = padded.single { it.material == Material.WHITE_WOOL }
        whitePad.size shouldBe OriginWorkshopGamePartSize(0.58f, 0.08f, 0.40f)
        (whitePad.center.y - whitePad.size.y / 2.0).near(0.02) shouldBe true
        (whitePad.center.y + whitePad.size.y / 2.0).near(0.10) shouldBe true

        val nearTucked = originWorkshopUpholsteryPieces(padded = true, tuckedEdges = 1)
        val remainingWhite = nearTucked.single { it.material == Material.WHITE_WOOL }
        remainingWhite.size shouldBe OriginWorkshopGamePartSize(0.58f, 0.08f, 0.30f)
        remainingWhite.center.z.near(0.05) shouldBe true
        val coveredNearPad = nearTucked.single { it.material == Material.RED_WOOL && it.size.y == 0.08f }
        coveredNearPad.center.z.near(-0.15) shouldBe true
        nearTucked.count { it.material == Material.RED_TERRACOTTA } shouldBe 1

        val bothTucked = originWorkshopUpholsteryPieces(padded = true, tuckedEdges = 2, seams = 2)
        bothTucked.any { it.material == Material.WHITE_WOOL && it.size.y == 0.08f } shouldBe false
        bothTucked.count { it.material == Material.RED_WOOL && it.size.y == 0.08f } shouldBe 1
        bothTucked.count { it.material == Material.RED_TERRACOTTA } shouldBe 2
        bothTucked.count { it.material == Material.WHITE_WOOL } shouldBe 2 // contrasting near/far seam lines

        for (pieces in listOf(padded, nearTucked, bothTucked)) {
            assertNoOverlaps(pieces)
            (pieces.minOf { it.center.y - it.size.y / 2.0 }).near(-0.02) shouldBe true
        }
    }

    "joined tabletop adds planed end bands and plates without changing its body or bounds" {
        for (edges in 0..2) for (joints in 0..2) {
            val pieces = originWorkshopJoinedTopPieces(edges, joints)
            pieces.size shouldBe 1 + edges + joints
            val top = pieces.first()
            top.size shouldBe OriginWorkshopGamePartSize(0.68f, 0.06f, 0.30f)
            top.center shouldBe OriginWorkshopPoint(0.0, 0.0, 0.0)
            pieces.count { it.material == Material.BIRCH_PLANKS } shouldBe edges
            pieces.count { it.material == Material.IRON_BLOCK } shouldBe joints
            pieces.filter { it.material == Material.BIRCH_PLANKS }.all { it.size == OriginWorkshopGamePartSize(0.04f, 0.005f, 0.30f) } shouldBe true
            pieces.filter { it.material == Material.IRON_BLOCK }.all { it.size == OriginWorkshopGamePartSize(0.08f, 0.012f, 0.06f) } shouldBe true
            pieces.minOf { it.center.x - it.size.x / 2.0 }.near(-0.34) shouldBe true
            pieces.maxOf { it.center.x + it.size.x / 2.0 }.near(0.34) shouldBe true
            pieces.minOf { it.center.z - it.size.z / 2.0 }.near(-0.15) shouldBe true
            pieces.maxOf { it.center.z + it.size.z / 2.0 }.near(0.15) shouldBe true
            assertNoOverlaps(pieces)
        }
    }

    "finishing bands change material in work order and back progress flips to the near world edge" {
        val raw = originWorkshopFinishingPanelPieces()
        raw.size shouldBe 7 // one solid core plus three skins on each face
        val rawFront = faceSkins(raw, front = true)
        rawFront.map { it.material } shouldBe listOf(Material.OAK_PLANKS, Material.OAK_PLANKS, Material.OAK_PLANKS)

        for (count in 1..3) {
            val sanded = originWorkshopFinishingPanelPieces(sandedFront = count)
            faceSkins(sanded, front = true).map { it.material } shouldBe
                List(count) { Material.BIRCH_PLANKS } + List(3 - count) { Material.OAK_PLANKS }
            val coated = originWorkshopFinishingPanelPieces(sandedFront = count, coatedFront = count)
            faceSkins(coated, front = true).map { it.material } shouldBe
                List(count) { Material.STRIPPED_DARK_OAK_WOOD } + List(3 - count) { Material.OAK_PLANKS }
            samePanelBounds(raw, sanded)
            samePanelBounds(raw, coated)
            assertNoOverlaps(sanded)
            assertNoOverlaps(coated)
        }

        val firstBackBand = originWorkshopFinishingPanelPieces(sandedBack = 1)
        val backBands = faceSkins(firstBackBand, front = false)
        backBands.map { it.material } shouldBe listOf(Material.OAK_PLANKS, Material.OAK_PLANKS, Material.BIRCH_PLANKS)
        val backNearWorldZ = -backBands.last().center.z // X-π runtime flip reverses local Z.
        backNearWorldZ.near(-FINISH_PANEL_TEST_BAND_CENTER) shouldBe true

        val mixed = originWorkshopFinishingPanelPieces(sandedFront = 2, sandedBack = 2, coatedFront = 1, coatedBack = 2)
        faceSkins(mixed, front = true).map { it.material } shouldBe
            listOf(Material.STRIPPED_DARK_OAK_WOOD, Material.BIRCH_PLANKS, Material.OAK_PLANKS)
        faceSkins(mixed, front = false).map { it.material } shouldBe
            listOf(Material.OAK_PLANKS, Material.STRIPPED_DARK_OAK_WOOD, Material.STRIPPED_DARK_OAK_WOOD)
        samePanelBounds(raw, mixed)
        assertNoOverlaps(mixed)
    }

    "processing geometry rejects impossible progress states" {
        shouldThrow<IllegalArgumentException> { originWorkshopUpholsteryPieces(stretchedEdges = 3) }
        shouldThrow<IllegalArgumentException> { originWorkshopUpholsteryPieces(padded = false, tuckedEdges = 1) }
        shouldThrow<IllegalArgumentException> { originWorkshopUpholsteryPieces(fasteners = -1) }
        shouldThrow<IllegalArgumentException> { originWorkshopJoinedTopPieces(edges = 3) }
        shouldThrow<IllegalArgumentException> { originWorkshopJoinedTopPieces(joints = -1) }
        shouldThrow<IllegalArgumentException> { originWorkshopFinishingPanelPieces(sandedFront = 4) }
        shouldThrow<IllegalArgumentException> { originWorkshopFinishingPanelPieces(sandedBack = 1, coatedBack = 2) }
    }
})

private const val FINISH_PANEL_TEST_BAND_CENTER = 0.68 / 3.0
private const val OVERLAP_EPSILON = 1.0e-7
private const val BOUNDS_EPSILON = 1.0e-6

private fun faceSkins(pieces: List<OriginWorkshopWorkpiecePiece>, front: Boolean) =
    pieces.filter { if (front) it.center.y > 0.02 else it.center.y < -0.02 }.sortedBy { it.center.z }

private fun samePanelBounds(expected: List<OriginWorkshopWorkpiecePiece>, actual: List<OriginWorkshopWorkpiecePiece>) {
    for (axis in listOf('x', 'y', 'z')) {
        bounds(expected, axis).first.near(bounds(actual, axis).first) shouldBe true
        bounds(expected, axis).second.near(bounds(actual, axis).second) shouldBe true
    }
}

private fun bounds(pieces: List<OriginWorkshopWorkpiecePiece>, axis: Char): Pair<Double, Double> {
    val centers = pieces.map {
        when (axis) {
            'x' -> it.center.x to it.size.x.toDouble()
            'y' -> it.center.y to it.size.y.toDouble()
            else -> it.center.z to it.size.z.toDouble()
        }
    }
    return centers.minOf { it.first - it.second / 2.0 } to centers.maxOf { it.first + it.second / 2.0 }
}

private fun assertNoOverlaps(pieces: List<OriginWorkshopWorkpiecePiece>) {
    for (first in pieces.indices) for (second in first + 1 until pieces.size) {
        positiveOverlap(pieces[first], pieces[second]) shouldBe false
    }
}

private fun positiveOverlap(first: OriginWorkshopWorkpiecePiece, second: OriginWorkshopWorkpiecePiece): Boolean =
    overlap(first.center.x, first.size.x.toDouble(), second.center.x, second.size.x.toDouble()) &&
        overlap(first.center.y, first.size.y.toDouble(), second.center.y, second.size.y.toDouble()) &&
        overlap(first.center.z, first.size.z.toDouble(), second.center.z, second.size.z.toDouble())

private fun overlap(firstCenter: Double, firstSize: Double, secondCenter: Double, secondSize: Double): Boolean =
    minOf(firstCenter + firstSize / 2.0, secondCenter + secondSize / 2.0) -
        maxOf(firstCenter - firstSize / 2.0, secondCenter - secondSize / 2.0) > OVERLAP_EPSILON

private fun Double.near(expected: Double): Boolean = abs(this - expected) <= BOUNDS_EPSILON
