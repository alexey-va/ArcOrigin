package ru.arc.origin

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material
import java.nio.file.Files
import kotlin.math.abs

class OriginWorkshopTablesGeometryTest : FreeSpec({
    "default table stays grounded and has the authored top height" {
        val dimensions = OriginWorkshopTableDimensions.DEFAULT
        val pieces = originWorkshopTablePieces(
            OriginWorkshopTableRole.CARPENTER,
            yaw = 0,
            dimensions = dimensions,
        )
        val blocks = pieces.filter { it.kind == OriginWorkshopTablePieceKind.BLOCK }
        val items = pieces.filter { it.kind == OriginWorkshopTablePieceKind.ITEM }

        // The tiled top replaces one stretched display with at most fifteen small planks.
        (blocks.size <= 110) shouldBe true
        items.size shouldBe 1
        blocks.minOf { it.y - it.height / 2.0 } shouldBe 0.0
        val topWidth = dimensions.width - 0.20
        val topDepth = dimensions.depth - 0.20
        val tabletopPlanks = blocks.filter { it.key.startsWith("tabletop-plank-") }
        (tabletopPlanks.size in 1..15) shouldBe true
        (tabletopPlanks.maxOf { maxOf(it.width, it.depth) } <= 1.0) shouldBe true
        tabletopPlanks.sumOf { it.width * it.depth }.let { abs(it - topWidth * topDepth) < 1e-9 } shouldBe true
        (abs(tabletopPlanks.minOf { it.x - it.width / 2.0 } + topWidth / 2.0) < 1e-9) shouldBe true
        (abs(tabletopPlanks.maxOf { it.x + it.width / 2.0 } - topWidth / 2.0) < 1e-9) shouldBe true
        (abs(tabletopPlanks.minOf { it.z - it.depth / 2.0 } + topDepth / 2.0) < 1e-9) shouldBe true
        (abs(tabletopPlanks.maxOf { it.z + it.depth / 2.0 } - topDepth / 2.0) < 1e-9) shouldBe true
        tabletopPlanks.all { abs(it.y + it.height / 2.0 - dimensions.height) < 1e-9 } shouldBe true
        tabletopPlanks.all { it.material == Material.BIRCH_PLANKS } shouldBe true
        blocks.filter { it.key.startsWith("long-edge-") || it.key.startsWith("end-edge-") }
            .all { it.material == Material.DARK_OAK_PLANKS } shouldBe true
        val positiveLongEdge = blocks.single { it.key == "long-edge-1.0" }
        (abs(positiveLongEdge.z - positiveLongEdge.depth / 2.0 - topDepth / 2.0) < 1e-9) shouldBe true
        val positiveEndEdge = blocks.single { it.key == "end-edge-1.0" }
        (abs(positiveEndEdge.x - positiveEndEdge.width / 2.0 - topWidth / 2.0) < 1e-9) shouldBe true
        items.minOf { it.width } shouldBe 0.40
        items.single().material shouldBe Material.IRON_AXE
        items.all { it.flat } shouldBe true
        blocks.none { it.key == "carpenter-board-sample" } shouldBe true

        val saw = blocks.single { it.key == "carpenter-table-saw" }
        saw.material shouldBe Material.STONECUTTER
        saw.width shouldBe 0.92
        saw.height shouldBe 0.72
        val bladeRows = blocks.filter { it.key.startsWith("carpenter-saw-blade-row-") }.sortedBy { it.y }
        bladeRows.size shouldBe 9
        bladeRows.map { it.width } shouldBe listOf(0.25, 0.45, 0.60, 0.72, 0.78, 0.72, 0.60, 0.45, 0.25)
        (bladeRows.minOf { it.y - it.height / 2.0 } > dimensions.height) shouldBe true
        (bladeRows.maxOf { it.y + it.height / 2.0 } > saw.y + saw.height / 2.0) shouldBe true
        bladeRows.all {
            abs(saw.z - saw.depth / 2.0 - (it.z + it.depth / 2.0) - 0.01) < 1e-9
        } shouldBe true
        val bladeHub = blocks.single { it.key == "carpenter-saw-blade-hub" }
        bladeHub.material shouldBe Material.POLISHED_ANDESITE
        (bladeHub.y - dimensions.height > 0.5) shouldBe true
        val boardFeed = blocks.single { it.key == "carpenter-board-feed" }
        val boardInFeed = blocks.single { it.key == "carpenter-board-in-feed" }
        val ripFence = blocks.single { it.key == "carpenter-rip-fence" }
        val feedSupport = blocks.single { it.key == "carpenter-feed-support-rail" }
        val motor = blocks.single { it.key == "carpenter-drive-motor" }
        val tuning = OriginWorkshopMachineTuning()
        (abs(boardInFeed.y - boardInFeed.height / 2.0 - (boardFeed.y + boardFeed.height / 2.0)) < 1e-9) shouldBe true
        (abs(boardFeed.z - (tuning.sawPivotZ + 0.075)) < 1e-9) shouldBe true
        (abs(boardInFeed.z - boardFeed.z) < 1e-9) shouldBe true
        (abs(ripFence.y - ripFence.height / 2.0 - (boardFeed.y + boardFeed.height / 2.0)) < 1e-9) shouldBe true
        (abs(feedSupport.y - feedSupport.height / 2.0 - (boardFeed.y + boardFeed.height / 2.0)) < 1e-9) shouldBe true
        val carriedBoard = OriginWorkshopPoint(-1.35, dimensions.height + 0.17, boardFeed.z)
        val carriedBoardSize = Triple(1.0, 0.08, 0.22)
        (abs(carriedBoard.y - carriedBoardSize.second / 2.0 - (boardFeed.y + boardFeed.height / 2.0)) < 1e-9) shouldBe true
        val bladeIntersectsCarriedBoard = bladeRows.any { blade ->
            val xOverlap = blade.x - blade.width / 2.0 < carriedBoard.x + carriedBoardSize.first / 2.0 &&
                blade.x + blade.width / 2.0 > carriedBoard.x - carriedBoardSize.first / 2.0
            val yOverlap = blade.y - blade.height / 2.0 < carriedBoard.y + carriedBoardSize.second / 2.0 &&
                blade.y + blade.height / 2.0 > carriedBoard.y - carriedBoardSize.second / 2.0
            val zOverlap = blade.z - blade.depth / 2.0 < carriedBoard.z + carriedBoardSize.third / 2.0 &&
                blade.z + blade.depth / 2.0 > carriedBoard.z - carriedBoardSize.third / 2.0
            xOverlap && yOverlap && zOverlap
        }
        bladeIntersectsCarriedBoard shouldBe true
        // The next operation rests a 0.72-wide board on the tabletop at local x=0.
        // The taller saw feed must end before that board, so its left end stays visible.
        (boardFeed.x + boardFeed.width / 2.0 < -0.72 / 2.0) shouldBe true
        // A small gap keeps the two separately-rendered casing faces from fighting.
        (abs(motor.z - motor.depth / 2.0 - (saw.z + saw.depth / 2.0) - 0.005) < 1e-9) shouldBe true
        val bearing = blocks.single { it.key == "carpenter-saw-bearing-post" }
        val axle = blocks.single { it.key == "carpenter-saw-axle" }
        (abs(bearing.y - bearing.height / 2.0 - dimensions.height) < 1e-9) shouldBe true
        (abs(bearing.y + bearing.height / 2.0 - bladeHub.y) < 1e-9) shouldBe true
        (abs(axle.y - bladeHub.y) < 1e-9) shouldBe true
        (abs(axle.z - axle.depth / 2.0 - bladeHub.z) < 1e-9) shouldBe true
        (abs(axle.z + axle.depth / 2.0 - bearing.z) < 1e-9) shouldBe true

        val drillHead = blocks.single { it.key == "carpenter-drill-head" }
        val drillPost = blocks.single { it.key == "carpenter-drill-post" }
        val drillArm = blocks.single { it.key == "carpenter-drill-arm" }
        val controlBracket = blocks.single { it.key == "carpenter-drill-control-bracket" }
        val controlHub = blocks.single { it.key == "carpenter-drill-control-wheel-hub" }
        val drillFoot = blocks.single { it.key == "carpenter-drill-foot" }
        val drillMount = blocks.single { it.key == "carpenter-drill-mounting-plate" }
        val drillQuill = blocks.single { it.key == "carpenter-drill-quill" }
        val drillSpindle = blocks.single { it.key == "carpenter-drill-spindle" }
        val drillBit = blocks.single { it.key == "carpenter-drill-bit" }
        fun overlapsOnAxis(a: Double, aSize: Double, b: Double, bSize: Double) =
            a - aSize / 2.0 <= b + bSize / 2.0 + 1e-9 && b - bSize / 2.0 <= a + aSize / 2.0 + 1e-9
        fun boxesOverlap(a: OriginWorkshopTablePiece, b: OriginWorkshopTablePiece) =
            overlapsOnAxis(a.x, a.width, b.x, b.width) &&
                overlapsOnAxis(a.y, a.height, b.y, b.height) &&
                overlapsOnAxis(a.z, a.depth, b.z, b.depth)
        boxesOverlap(drillFoot, drillPost) shouldBe true
        boxesOverlap(drillHead, drillPost) shouldBe true
        boxesOverlap(drillHead, drillArm) shouldBe true
        boxesOverlap(drillArm, drillQuill) shouldBe true
        boxesOverlap(drillQuill, drillSpindle) shouldBe true
        boxesOverlap(drillSpindle, drillBit) shouldBe true
        boxesOverlap(drillArm, controlBracket) shouldBe true
        boxesOverlap(controlBracket, controlHub) shouldBe true
        drillMount.material shouldBe Material.DARK_OAK_PLANKS
        boxesOverlap(drillMount, drillFoot) shouldBe true
        (abs(drillMount.y - drillMount.height / 2.0 - dimensions.height) < 1e-9) shouldBe true
        (drillMount.width > drillFoot.width && drillMount.depth > drillFoot.depth) shouldBe true

        val aprons = blocks.filter { it.key.startsWith("carpenter-bench-apron-") }
        val apronPegs = blocks.filter { it.key.startsWith("carpenter-apron-peg-") }
        aprons.size shouldBe 2
        apronPegs.size shouldBe 4
        aprons.all { it.material == Material.DARK_OAK_PLANKS } shouldBe true
        val tableLegs = blocks.filter { it.key.startsWith("leg-") }
        aprons.forEach { apron -> tableLegs.count { boxesOverlap(apron, it) } shouldBe 2 }
        apronPegs.all { peg -> aprons.any { boxesOverlap(peg, it) } } shouldBe true

        val assemblyBed = blocks.single { it.key == "carpenter-assembly-bed" }
        val looseLeft = blocks.single { it.key == "carpenter-leg-left" }
        val looseRight = blocks.single { it.key == "carpenter-leg-right" }
        (abs(assemblyBed.y - assemblyBed.height / 2.0 - dimensions.height) < 1e-9) shouldBe true
        looseLeft.x shouldBe 1.30
        looseRight.x shouldBe 1.75
        (looseLeft.y - looseLeft.height / 2.0 >= dimensions.height) shouldBe true
        (looseRight.y - looseRight.height / 2.0 >= dimensions.height) shouldBe true
        blocks.none { it.key == "carpenter-assembly-workpiece" } shouldBe true

        blocks.none { it.key.startsWith("carpenter-start-") } shouldBe true
        tabletopPlanks.map { it.key }.distinct().size shouldBe tabletopPlanks.size
        blocks.filter { it.key.startsWith("long-edge-") || it.key.startsWith("end-edge-") }.size shouldBe 4
        blocks.filter { it.key.startsWith("leg-") }.size shouldBe 4
        val sawControl = blocks.single { it.key == "carpenter-saw-control-handle" }
        val sawControlPost = blocks.single { it.key == "carpenter-saw-control-post" }
        boxesOverlap(sawControlPost, sawControl) shouldBe false
        (abs(sawControlPost.y - sawControlPost.height / 2.0 - dimensions.height) < 1e-9) shouldBe true
        val leftClamp = blocks.single { it.key == "carpenter-assembly-clamp-left" }
        val leftClampControl = blocks.single { it.key == "carpenter-assembly-clamp-control-left" }
        val rightClamp = blocks.single { it.key == "carpenter-assembly-clamp-right" }
        val rightClampControl = blocks.single { it.key == "carpenter-assembly-clamp-control-right" }
        boxesOverlap(leftClamp, leftClampControl) shouldBe true
        boxesOverlap(rightClamp, rightClampControl) shouldBe true

        val toolTray = blocks.single { it.key == "carpenter-back-tool-tray" }
        val handTools = listOf(
            blocks.single { it.key == "carpenter-mallet-head" },
            blocks.single { it.key == "carpenter-mallet-handle" },
            blocks.single { it.key == "carpenter-chisel-blade" },
            blocks.single { it.key == "carpenter-chisel-handle" },
        )
        handTools.all { boxesOverlap(toolTray, it) } shouldBe true
        val toolClearance = listOf(
            boardFeed,
            sawControl,
            drillFoot,
            controlHub,
            assemblyBed,
            looseLeft,
            looseRight,
            leftClamp,
            rightClamp,
        )
        (listOf(toolTray) + handTools).none { tool -> toolClearance.any { boxesOverlap(tool, it) } } shouldBe true
    }

    "exposed machine joints meet at faces without penetrating" {
        val dimensions = OriginWorkshopTableDimensions.DEFAULT
        val carpenter = originWorkshopTablePieces(OriginWorkshopTableRole.CARPENTER, yaw = 0, dimensions = dimensions)
            .associateBy { it.key }
        fun contact(actual: Double, expected: Double = 0.0) = (abs(actual - expected) < 1e-9)

        val rollerAxle = carpenter.getValue("carpenter-drive-feed-roller-axle-0")
        for (side in listOf(-1.0, 1.0)) {
            val bearing = carpenter.getValue("carpenter-drive-feed-bearing-0-$side")
            val gap = if (side < 0.0) {
                rollerAxle.z - rollerAxle.depth / 2.0 - (bearing.z + bearing.depth / 2.0)
            } else {
                bearing.z - bearing.depth / 2.0 - (rollerAxle.z + rollerAxle.depth / 2.0)
            }
            contact(gap) shouldBe true
        }

        val sawHandle = carpenter.getValue("carpenter-saw-control-handle")
        val sawPost = carpenter.getValue("carpenter-saw-control-post")
        contact(sawHandle.y - sawHandle.height / 2.0 - (sawPost.y + sawPost.height / 2.0), 0.002) shouldBe true

        val malletHead = carpenter.getValue("carpenter-mallet-head")
        val malletHandle = carpenter.getValue("carpenter-mallet-handle")
        contact(malletHandle.z - malletHandle.depth / 2.0 - (malletHead.z + malletHead.depth / 2.0)) shouldBe true
        val chiselBlade = carpenter.getValue("carpenter-chisel-blade")
        val chiselHandle = carpenter.getValue("carpenter-chisel-handle")
        contact(chiselHandle.z - chiselHandle.depth / 2.0 - (chiselBlade.z + chiselBlade.depth / 2.0), 0.002) shouldBe true

        val assembler = originWorkshopTablePieces(OriginWorkshopTableRole.ASSEMBLER, yaw = 0, dimensions = dimensions)
            .associateBy { it.key }
        val viseScrew = assembler.getValue("assembler-vise-screw")
        val viseHandle = assembler.getValue("assembler-vise-handle")
        contact(viseScrew.x - viseScrew.width / 2.0 - (viseHandle.x + viseHandle.width / 2.0), 0.002) shouldBe true

        val upholsterer = originWorkshopTablePieces(OriginWorkshopTableRole.UPHOLSTERER, yaw = 0, dimensions = dimensions)
            .associateBy { it.key }
        val sewingArm = upholsterer.getValue("upholsterer-sewing-arm")
        val sewingPost = upholsterer.getValue("upholsterer-sewing-post")
        contact(sewingArm.y - sewingArm.height / 2.0 - (sewingPost.y + sewingPost.height / 2.0)) shouldBe true
        val finisher = originWorkshopTablePieces(OriginWorkshopTableRole.FINISHER, yaw = 0, dimensions = dimensions)
            .associateBy { it.key }
        val brushRail = finisher.getValue("finisher-brush-rail")
        val leftBrushPost = finisher.getValue("finisher-brush-post--1.58")
        val rightBrushPost = finisher.getValue("finisher-brush-post--0.72")
        for (post in listOf(leftBrushPost, rightBrushPost)) {
            contact(brushRail.y - brushRail.height / 2.0 - (post.y + post.height / 2.0)) shouldBe true
        }
        contact(brushRail.x - brushRail.width / 2.0 - (leftBrushPost.x - leftBrushPost.width / 2.0)) shouldBe true
        contact(brushRail.x + brushRail.width / 2.0 - (rightBrushPost.x + rightBrushPost.width / 2.0)) shouldBe true

        val finishedBoard = finisher.getValue("finisher-finished-board")
        val frontDryingPost = finisher.getValue("finisher-drying-post-front-right")
        (finishedBoard.z - finishedBoard.depth / 2.0 - (frontDryingPost.z + frontDryingPost.depth / 2.0) > 0.04) shouldBe true
    }

    "right-angle yaw swaps the table footprint and rotates role props with it" {
        val dimensions = OriginWorkshopTableDimensions.DEFAULT
        val straight = originWorkshopTablePieces(
            OriginWorkshopTableRole.CARPENTER,
            yaw = 0,
            dimensions = dimensions,
        )
        val rotated = originWorkshopTablePieces(
            OriginWorkshopTableRole.CARPENTER,
            yaw = 90,
            dimensions = dimensions,
        )
        val straightTop = straight.filter { it.key.startsWith("tabletop-plank-") }
        val rotatedTop = rotated.filter { it.key.startsWith("tabletop-plank-") }
        val straightTool = straight.first { it.key == "carpenter-prop-0" }
        val rotatedTool = rotated.first { it.key == "carpenter-prop-0" }

        fun xSpan(pieces: List<OriginWorkshopTablePiece>) =
            pieces.maxOf { it.x + it.width / 2.0 } - pieces.minOf { it.x - it.width / 2.0 }
        fun zSpan(pieces: List<OriginWorkshopTablePiece>) =
            pieces.maxOf { it.z + it.depth / 2.0 } - pieces.minOf { it.z - it.depth / 2.0 }
        (abs(xSpan(rotatedTop) - zSpan(straightTop)) < 1e-9) shouldBe true
        (abs(zSpan(rotatedTop) - xSpan(straightTop)) < 1e-9) shouldBe true
        rotatedTop.size shouldBe straightTop.size
        rotatedTool.x shouldBe -straightTool.z
        rotatedTool.z shouldBe straightTool.x
    }

    "station-local anchors follow each authored yaw and floor" {
        val local = OriginWorkshopPoint(2.0, 0.5, -1.0)
        val expected = mapOf(
            0 to OriginWorkshopPoint(-34.5, 71.5, -74.5),
            90 to OriginWorkshopPoint(-35.5, 71.5, -71.5),
            180 to OriginWorkshopPoint(-38.5, 71.5, -72.5),
            270 to OriginWorkshopPoint(-37.5, 71.5, -75.5),
        )

        for ((yaw, point) in expected) {
            val table = OriginWorkshopTableDefinition(
                id = "anchor-$yaw",
                x = -36.5,
                floorY = 71.0,
                z = -73.5,
                yaw = yaw,
                role = OriginWorkshopTableRole.CARPENTER,
            )
            originWorkshopPointInWorld(table, local) shouldBe point
        }
    }

    "runtime dimensions preserve floor contact and top alignment" {
        val dimensions = OriginWorkshopTableDimensions(width = 4.4, depth = 2.0, height = 1.0)
        val blocks = originWorkshopTablePieces(
            OriginWorkshopTableRole.FINISHER,
            yaw = 270,
            dimensions = dimensions,
        )
            .filter { it.kind == OriginWorkshopTablePieceKind.BLOCK }

        blocks.minOf { it.y - it.height / 2.0 } shouldBe 0.0
        blocks.filter { it.key.startsWith("tabletop-plank-") }
            .all { abs(it.y + it.height / 2.0 - dimensions.height) < 1e-9 } shouldBe true
        (abs(maxOf(
            blocks.maxOf { it.x + it.width / 2.0 },
            blocks.maxOf { it.z + it.depth / 2.0 },
        ) - 2.2) < 1e-9) shouldBe true
        (abs(minOf(
            blocks.minOf { it.x - it.width / 2.0 },
            blocks.minOf { it.z - it.depth / 2.0 },
        ) + 2.2) < 1e-9) shouldBe true
        val props = originWorkshopTablePieces(
            OriginWorkshopTableRole.FINISHER,
            yaw = 270,
            dimensions = dimensions,
        ).filter { it.kind == OriginWorkshopTablePieceKind.ITEM }
        props.map { it.material } shouldBe listOf(Material.BRUSH)
        props.minOf { it.width } shouldBe 0.62
        props.all { it.flat && it.y > dimensions.height && it.y - dimensions.height <= 0.04 } shouldBe true
        val finishedBoard = originWorkshopTablePieces(
            OriginWorkshopTableRole.FINISHER,
            yaw = 270,
            dimensions = dimensions,
        ).single { it.key == "finisher-finished-board" }
        (abs(finishedBoard.y - finishedBoard.height / 2.0 - dimensions.height - 0.005) < 1e-9) shouldBe true
    }

    "craft-specific samples and fixtures meet their supports and stay inside the table" {
        val dimensions = OriginWorkshopTableDimensions.DEFAULT
        val upholstery = originWorkshopTablePieces(
            OriginWorkshopTableRole.UPHOLSTERER,
            yaw = 0,
            dimensions = dimensions,
        )
            .associateBy { it.key }
        val cushionBase = upholstery.getValue("upholsterer-cushion-base")
        val padding = upholstery.getValue("upholsterer-cushion-padding")
        val cover = upholstery.getValue("upholsterer-cushion-cover")
        (abs(cushionBase.y - cushionBase.height / 2.0 - dimensions.height - 0.005) < 1e-9) shouldBe true
        (abs(padding.y - padding.height / 2.0 - (cushionBase.y + cushionBase.height / 2.0)) < 1e-9) shouldBe true
        (abs(cover.y - cover.height / 2.0 - (padding.y + padding.height / 2.0)) < 1e-9) shouldBe true

        val assembly = originWorkshopTablePieces(OriginWorkshopTableRole.ASSEMBLER, yaw = 0, dimensions = dimensions)
            .associateBy { it.key }
        val viseBed = assembly.getValue("assembler-vise-bed")
        val leftPost = assembly.getValue("assembler-vise-post-left")
        val rightPost = assembly.getValue("assembler-vise-post-right")
        val crossbar = assembly.getValue("assembler-vise-crossbar")
        val board = assembly.getValue("assembler-board-sample")
        val leftClamp = assembly.getValue("assembler-clamp-left")
        val rightClamp = assembly.getValue("assembler-clamp-right")
        (abs(viseBed.y - viseBed.height / 2.0 - dimensions.height - 0.005) < 1e-9) shouldBe true
        (abs(leftPost.y - leftPost.height / 2.0 - (viseBed.y + viseBed.height / 2.0)) < 1e-9) shouldBe true
        (abs(rightPost.y - rightPost.height / 2.0 - (viseBed.y + viseBed.height / 2.0)) < 1e-9) shouldBe true
        (abs(crossbar.y - crossbar.height / 2.0 - (leftPost.y + leftPost.height / 2.0)) < 1e-9) shouldBe true
        for ((post, clamp) in listOf(leftPost to leftClamp, rightPost to rightClamp)) {
            val clampRear = clamp.z + clamp.depth / 2.0
            val postFront = post.z - post.depth / 2.0
            (postFront - clampRear > 0.039) shouldBe true
            (crossbar.z - crossbar.depth / 2.0 < post.z - post.depth / 2.0) shouldBe true
            (crossbar.z + crossbar.depth / 2.0 > post.z + post.depth / 2.0) shouldBe true
        }
        (abs(board.y - board.height / 2.0 - (viseBed.y + viseBed.height / 2.0) - 0.005) < 1e-9) shouldBe true
        (abs(leftClamp.x + leftClamp.width / 2.0 - (board.x - board.width / 2.0) + 0.10) < 1e-9) shouldBe true
        (abs(rightClamp.x - rightClamp.width / 2.0 - (board.x + board.width / 2.0) - 0.10) < 1e-9) shouldBe true

        val finishing = originWorkshopTablePieces(OriginWorkshopTableRole.FINISHER, yaw = 0, dimensions = dimensions)
            .associateBy { it.key }
        val frontPost = finishing.getValue("finisher-drying-post-front-left")
        val lowerRail = finishing.getValue("finisher-drying-rail-lower-front")
        val upperRail = finishing.getValue("finisher-drying-rail-upper-front")
        val dryingPanel = finishing.getValue("finisher-drying-panel-center")
        val topRail = finishing.getValue("finisher-drying-rail-top-front")
        (abs(frontPost.y - frontPost.height / 2.0 - dimensions.height - 0.005) < 1e-9) shouldBe true
        frontPost.height shouldBe 1.12
        (abs(dryingPanel.y - dryingPanel.height / 2.0 - (lowerRail.y + lowerRail.height / 2.0)) < 1e-9) shouldBe true
        (abs(dryingPanel.y + dryingPanel.height / 2.0 - (upperRail.y - upperRail.height / 2.0)) < 1e-9) shouldBe true
        (topRail.y + topRail.height / 2.0 > finishing.getValue("back-tool-board").y + finishing.getValue("back-tool-board").height / 2.0) shouldBe true
        (topRail.y + topRail.height / 2.0 < frontPost.y + frontPost.height / 2.0) shouldBe true

        for (role in OriginWorkshopTableRole.entries) for (yaw in listOf(0, 90, 180, 270)) {
            val pieces = originWorkshopTablePieces(role, yaw, dimensions)
            val halfX = if (yaw == 90 || yaw == 270) dimensions.depth / 2.0 else dimensions.width / 2.0
            val halfZ = if (yaw == 90 || yaw == 270) dimensions.width / 2.0 else dimensions.depth / 2.0
            (pieces.all {
                it.x - it.width / 2.0 >= -halfX - 1e-9 && it.x + it.width / 2.0 <= halfX + 1e-9 &&
                    it.z - it.depth / 2.0 >= -halfZ - 1e-9 && it.z + it.depth / 2.0 <= halfZ + 1e-9
            }) shouldBe true
        }
    }

    "runtime YAML schema loads four centered tables and shared dimensions" {
        val dataPath = Files.createTempDirectory("origin-workshop-tables-test")
        val modulesPath = Files.createDirectories(dataPath.resolve("modules"))
        Files.writeString(
            modulesPath.resolve("origin-workshop-tables.yml"),
            """
            origin-workshop-tables:
              enabled: true
              world: rc_origin_spawn
              dimensions:
                width: 4.8
                depth: 2.05
                height: 1.08
              warehouse: { enabled: true, x: -49.5, floor-y: 71.0, z: -51.5 }
              table-ids: [carpenter, upholsterer, assembler, finisher]
              tables:
                carpenter: { x: -36.5, floor-y: 71.0, z: -73.5, yaw: 270, role: carpenter, stock-offset-x: -8.0 }
                upholsterer: { x: -36.5, floor-y: 71.0, z: -58.5, yaw: 270, role: upholsterer, stock-offset-x: 7.0 }
                assembler: { x: -48.5, floor-y: 71.0, z: -46.5, yaw: 0, role: assembler, stock-offset-x: -6.0 }
                finisher: { x: -60.5, floor-y: 71.0, z: -46.5, yaw: 0, role: finisher, stock-offset-x: 6.0 }
            """.trimIndent(),
        )

        val settings = OriginWorkshopTablesSettings.load(dataPath)

        settings.enabled shouldBe true
        settings.tables.map { it.id } shouldBe listOf("carpenter", "upholsterer", "assembler", "finisher")
        settings.tables.map { it.yaw } shouldBe listOf(270, 270, 0, 0)
        settings.tables.map { it.stockOffsetX } shouldBe listOf(-8.0, 7.0, -6.0, 6.0)
        settings.tables.first().floorY shouldBe 71.0
        settings.dimensions shouldBe OriginWorkshopTableDimensions.DEFAULT
        settings.warehouse shouldBe OriginWorkshopWarehouseAnchor(-49.5, 71.0, -51.5)
    }
})
