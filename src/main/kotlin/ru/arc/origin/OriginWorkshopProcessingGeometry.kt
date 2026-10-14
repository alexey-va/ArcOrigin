package ru.arc.origin

import org.bukkit.Material

private const val UPHOLSTERY_WIDTH = 0.64f
private const val UPHOLSTERY_HEIGHT = 0.04f
private const val UPHOLSTERY_DEPTH = 0.46f
private const val UPHOLSTERY_BOTTOM = -0.02

private const val FINISH_PANEL_WIDTH = 0.18f
private const val FINISH_PANEL_DEPTH = 0.68f
private const val FINISH_PANEL_THICKNESS = 0.06f
private const val FINISH_PANEL_SKIN = 0.003f
private const val FINISH_PANEL_CORE = FINISH_PANEL_THICKNESS - 2 * FINISH_PANEL_SKIN

/** Canonical centered cushion and cover geometry, shared by the live game and previews. */
internal fun originWorkshopUpholsteryPieces(
    stretchedEdges: Int = 0,
    seams: Int = 0,
    padded: Boolean = false,
    tuckedEdges: Int = 0,
    fasteners: Int = 0,
): List<OriginWorkshopWorkpiecePiece> {
    require(stretchedEdges in 0..2) { "upholstery stretchedEdges must be 0..2" }
    require(seams in 0..2) { "upholstery seams must be 0..2" }
    require(tuckedEdges in 0..2) { "upholstery tuckedEdges must be 0..2" }
    require(fasteners in 0..2) { "upholstery fasteners must be 0..2" }
    require(padded || tuckedEdges == 0) { "upholstery edges cannot be tucked before padding is placed" }

    val width = UPHOLSTERY_WIDTH + 0.012f * stretchedEdges
    val height = UPHOLSTERY_HEIGHT - 0.004f * stretchedEdges
    val base = OriginWorkshopGamePartSize(width, height, UPHOLSTERY_DEPTH)
    val baseY = UPHOLSTERY_BOTTOM + height.toDouble() / 2.0
    val baseTop = UPHOLSTERY_BOTTOM + height
    val pieces = mutableListOf(processingPiece(0.0, baseY, 0.0, base, Material.RED_WOOL))

    var visibleTop = baseTop
    if (padded) {
        val padSize = OriginWorkshopGamePartSize(width - 0.06f, 0.08f, 0.40f)
        val padTop = baseTop + padSize.y
        val padY = baseTop + padSize.y / 2.0
        visibleTop = padTop

        when (tuckedEdges) {
            0 -> pieces += processingPiece(0.0, padY, 0.0, padSize, Material.WHITE_WOOL)
            1 -> {
                val tuckedDepth = 0.10f
                val remainingDepth = padSize.z - tuckedDepth
                val remainingPadding = OriginWorkshopGamePartSize(padSize.x, padSize.y, remainingDepth)
                val tuckedPadding = OriginWorkshopGamePartSize(padSize.x, padSize.y, tuckedDepth)
                pieces += processingPiece(0.0, padY, tuckedDepth / 2.0, remainingPadding, Material.WHITE_WOOL)
                pieces += processingPiece(0.0, padY, -padSize.z / 2.0 + tuckedDepth / 2.0, tuckedPadding, Material.RED_WOOL)
                addUpholsteryTuckRidge(pieces, width, padTop, near = true)
            }
            2 -> {
                // Keep the cushion's volume and outer bounds fixed while its white padding disappears under the cover.
                pieces += processingPiece(0.0, padY, 0.0, padSize, Material.RED_WOOL)
                addUpholsteryTuckRidge(pieces, width, padTop, near = true)
                addUpholsteryTuckRidge(pieces, width, padTop, near = false)
            }
        }
    }

    // Before padding is installed, seams rest directly on the cloth. Once it is padded, show them again
    // only after both edges have been covered; they then sit on the red cover surface.
    if (seams > 0 && (!padded || tuckedEdges == 2)) {
        val seam = OriginWorkshopGamePartSize(width - 0.04f, 0.004f, 0.008f)
        val tuckRidgeHeight = if (padded) 0.006 else 0.0
        val seamY = visibleTop + tuckRidgeHeight + seam.y / 2.0
        val nearZ = -UPHOLSTERY_DEPTH / 2.0 + 0.04
        pieces += processingPiece(0.0, seamY, nearZ, seam, Material.WHITE_WOOL)
        if (seams == 2) pieces += processingPiece(0.0, seamY, -nearZ, seam, Material.WHITE_WOOL)
    }

    val fastener = OriginWorkshopGamePartSize(0.012f, 0.016f, 0.024f)
    val fastenerX = width / 2.0 + fastener.x / 2.0
    if (fasteners > 0) pieces += processingPiece(-fastenerX, baseY, 0.0, fastener, Material.COPPER_BLOCK)
    if (fasteners > 1) pieces += processingPiece(fastenerX, baseY, 0.0, fastener, Material.IRON_BLOCK)
    return pieces
}

private fun addUpholsteryTuckRidge(
    pieces: MutableList<OriginWorkshopWorkpiecePiece>,
    stretchedWidth: Float,
    padTop: Double,
    near: Boolean,
) {
    val ridge = OriginWorkshopGamePartSize(stretchedWidth - 0.06f, 0.006f, 0.10f)
    val z = if (near) -0.15 else 0.15
    pieces += processingPiece(0.0, padTop + ridge.y / 2.0, z, ridge, Material.RED_TERRACOTTA)
}

/** Spruce tabletop with attached, progressive planed edge bands and joint plates. */
internal fun originWorkshopJoinedTopPieces(
    edges: Int = 0,
    joints: Int = 0,
): List<OriginWorkshopWorkpiecePiece> {
    require(edges in 0..2) { "joined tabletop edges must be 0..2" }
    require(joints in 0..2) { "joined tabletop joints must be 0..2" }

    val size = OriginWorkshopGamePartSize(0.68f, 0.06f, 0.30f)
    return buildList {
        add(processingPiece(0.0, 0.0, 0.0, size, Material.SPRUCE_PLANKS))
        val edgeBand = OriginWorkshopGamePartSize(0.04f, 0.005f, size.z)
        val edgeX = size.x / 2.0 - edgeBand.x / 2.0
        val bandY = size.y / 2.0 + edgeBand.y / 2.0
        if (edges > 0) add(processingPiece(-edgeX, bandY, 0.0, edgeBand, Material.BIRCH_PLANKS))
        if (edges > 1) add(processingPiece(edgeX, bandY, 0.0, edgeBand, Material.BIRCH_PLANKS))

        val plate = OriginWorkshopGamePartSize(0.08f, 0.012f, 0.06f)
        val plateY = size.y / 2.0 + plate.y / 2.0
        if (joints > 0) add(processingPiece(-0.14, plateY, 0.0, plate, Material.IRON_BLOCK))
        if (joints > 1) add(processingPiece(0.14, plateY, 0.0, plate, Material.IRON_BLOCK))
    }
}

/**
 * Three equal finish bands per face. The back progresses from local +Z so the runtime's X-π flip
 * makes its first band appear at world -Z, matching the front face's near-to-far order.
 */
internal fun originWorkshopFinishingPanelPieces(
    sandedFront: Int = 0,
    sandedBack: Int = 0,
    coatedFront: Int = 0,
    coatedBack: Int = 0,
): List<OriginWorkshopWorkpiecePiece> {
    require(sandedFront in 0..3 && sandedBack in 0..3) { "finishing panel sand counts must be 0..3" }
    require(coatedFront in 0..3 && coatedBack in 0..3) { "finishing panel coat counts must be 0..3" }
    require(coatedFront <= sandedFront && coatedBack <= sandedBack) {
        "finishing panel bands must be sanded before coating"
    }

    val core = OriginWorkshopGamePartSize(FINISH_PANEL_WIDTH, FINISH_PANEL_CORE, FINISH_PANEL_DEPTH)
    val bandDepth = FINISH_PANEL_DEPTH / 3.0
    val pieces = mutableListOf(processingPiece(0.0, 0.0, 0.0, core, Material.OAK_PLANKS))

    fun addFace(front: Boolean, sanded: Int, coated: Int) {
        val skin = OriginWorkshopGamePartSize(FINISH_PANEL_WIDTH, FINISH_PANEL_SKIN, bandDepth.toFloat())
        val y = if (front) FINISH_PANEL_CORE / 2.0 + FINISH_PANEL_SKIN / 2.0 else
            -FINISH_PANEL_CORE / 2.0 - FINISH_PANEL_SKIN / 2.0
        for (band in 0..2) {
            val z = -FINISH_PANEL_DEPTH / 2.0 + (band + 0.5) * bandDepth
            val progressed = if (front) band < sanded else band >= 3 - sanded
            val coatedBand = if (front) band < coated else band >= 3 - coated
            val material = when {
                coatedBand -> Material.STRIPPED_DARK_OAK_WOOD
                progressed -> Material.BIRCH_PLANKS
                else -> Material.OAK_PLANKS
            }
            pieces += processingPiece(0.0, y, z, skin, material)
        }
    }

    addFace(front = true, sanded = sandedFront, coated = coatedFront)
    addFace(front = false, sanded = sandedBack, coated = coatedBack)
    return pieces
}

private fun processingPiece(
    x: Double,
    y: Double,
    z: Double,
    size: OriginWorkshopGamePartSize,
    material: Material,
) = OriginWorkshopWorkpiecePiece(OriginWorkshopPoint(x, y, z), size, material)
