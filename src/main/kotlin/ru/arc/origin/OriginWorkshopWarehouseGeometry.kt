package ru.arc.origin

import org.bukkit.Material

/** Static furniture's Y is the supporting surface; the display owner applies the model's anchor offset. */
internal data class OriginWorkshopWarehouseItem(
    val itemId: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val scale: Double,
)

internal data class OriginWorkshopWarehouseGeometry(
    val blocks: List<OriginWorkshopTablePiece>,
    val items: List<OriginWorkshopWarehouseItem>,
)

/** Three low, separated material pallets fit inside the existing 10 by 3 footprint. */
internal fun originWorkshopWarehouseGeometry(): OriginWorkshopWarehouseGeometry {
    val palletCenters = listOf(-3.0, 0.0, 3.0)
    val blocks = buildList {
        palletCenters.forEachIndexed { pallet, x ->
            for (runnerX in listOf(-1.25, 0.0, 1.25)) {
                add(warehouseBlock("pallet-$pallet-runner-$runnerX", Material.STRIPPED_DARK_OAK_LOG,
                    x + runnerX, 0.04, 0.2, 0.12, 0.08, 1.62))
            }
            for ((board, z) in listOf(-0.35, 0.20, 0.75).withIndex()) {
                add(warehouseBlock("pallet-$pallet-deck-$board", Material.DARK_OAK_PLANKS,
                    x, 0.13, z, 2.80, 0.10, 0.52))
            }
        }

        for (index in 0..3) add(warehouseBlock("lumber-bundle-layer-$index",
            if (index % 2 == 0) Material.OAK_PLANKS else Material.SPRUCE_PLANKS,
            -3.25, 0.225 + index * 0.09, 0.80, 1.60, 0.09, 0.18))
        for (index in 0..1) add(warehouseBlock("lumber-log-$index", Material.STRIPPED_OAK_LOG,
            -2.05, 0.25 + index * 0.14, 0.80, 0.18, 0.14, 0.48))

        listOf(Material.RED_WOOL, Material.WHITE_WOOL, Material.BROWN_WOOL).forEachIndexed { index, material ->
            val x = -0.85 + index * 0.85
            add(warehouseBlock("fabric-bale-$index", material, x, 0.39, 0.80, 0.56, 0.42, 0.28))
            add(warehouseBlock("fabric-bale-strap-$index", Material.BROWN_WOOL, x, 0.61, 0.80, 0.07, 0.02, 0.30))
        }

        for (index in 0..2) add(warehouseBlock("finished-panel-layer-$index",
            listOf(Material.BIRCH_PLANKS, Material.OAK_PLANKS, Material.SPRUCE_PLANKS)[index],
            3.0, 0.225 + index * 0.09, 0.80, 2.50, 0.09, 0.20))
    }

    val items = listOf(
        OriginWorkshopWarehouseItem("furnituresplus:white_wooden_chair", -3.70, 0.18, 0.0, 0f, 0.84),
        OriginWorkshopWarehouseItem("furnituresplus:white_wooden_chair", -2.30, 0.18, 0.0, 0f, 0.84),
        OriginWorkshopWarehouseItem("furnituresplus:red_wooden_sofa_single", -0.78, 0.18, 0.0, 0f, 0.84),
        OriginWorkshopWarehouseItem("furnituresplus:white_wooden_diningtable", 0.78, 0.18, 0.0, 0f, 0.84),
        OriginWorkshopWarehouseItem("furnituresplus:red_wooden_sofa_single", 2.30, 0.18, 0.0, 0f, 0.84),
        OriginWorkshopWarehouseItem("furnituresplus:red_wooden_sofa_single", 3.70, 0.18, 0.0, 0f, 0.84),
    )
    return OriginWorkshopWarehouseGeometry(blocks, items)
}

private fun warehouseBlock(
    key: String,
    material: Material,
    x: Double,
    y: Double,
    z: Double,
    width: Double,
    height: Double,
    depth: Double,
) = OriginWorkshopTablePiece(key, material, OriginWorkshopTablePieceKind.BLOCK, x, y, z, width, height, depth)
