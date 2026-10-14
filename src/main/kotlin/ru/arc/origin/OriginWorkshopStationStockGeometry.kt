package ru.arc.origin

import org.bukkit.Material

/** Low stock kept inside the two-block awning gap after the station's 270-degree placement. */
internal fun originWorkshopStationStockGeometry(role: OriginWorkshopTableRole): OriginWorkshopWarehouseGeometry {
    val blocks = buildList {
        fun block(key: String, material: Material, x: Double, y: Double, z: Double, w: Double, h: Double, d: Double) {
            add(OriginWorkshopTablePiece(key, material, OriginWorkshopTablePieceKind.BLOCK, x, y, z, w, h, d))
        }
        for (x in listOf(-0.80, 0.0, 0.80)) {
            block("pallet-runner-$x", Material.STRIPPED_DARK_OAK_LOG, x, 0.04, -0.20, 0.12, 0.08, 2.10)
        }
        for ((index, z) in listOf(-0.95, -0.20, 0.55).withIndex()) {
            block("pallet-deck-$index", Material.DARK_OAK_PLANKS, 0.0, 0.13, z, 1.90, 0.10, 0.70)
        }

        when (role) {
            OriginWorkshopTableRole.CARPENTER -> {
                for (index in 0..2) block("board-bundle-layer-$index",
                    if (index % 2 == 0) Material.OAK_PLANKS else Material.SPRUCE_PLANKS,
                    0.0, 0.225 + index * 0.09, 0.30, 1.80, 0.09, 0.22)
                for (index in 0..2) block("chair-stock-board-layer-$index",
                    if (index % 2 == 0) Material.SPRUCE_PLANKS else Material.OAK_PLANKS,
                    0.0, 0.225 + index * 0.09, -0.75, 1.80, 0.09, 0.45)
                for (index in 0..1) block("stacked-log-$index", Material.STRIPPED_OAK_LOG,
                    0.80, 0.25 + index * 0.14, -0.20, 0.18, 0.14, 0.64)
            }
            OriginWorkshopTableRole.UPHOLSTERER -> {
                listOf(Material.RED_WOOL, Material.WHITE_WOOL, Material.BROWN_WOOL).forEachIndexed { index, material ->
                    val x = -0.62 + index * 0.62
                    block("fabric-bale-$index", material, x, 0.39, 0.30, 0.50, 0.42, 0.24)
                    block("fabric-bale-strap-$index", Material.BROWN_WOOL, x, 0.61, 0.30, 0.07, 0.02, 0.26)
                }
            }
            OriginWorkshopTableRole.ASSEMBLER -> {
                for (index in 0..3) block("tabletop-blank-layer-$index",
                    if (index % 2 == 0) Material.SPRUCE_PLANKS else Material.OAK_PLANKS,
                    0.0, 0.225 + index * 0.09, 0.30, 1.80, 0.09, 0.22)
                for (x in listOf(-0.55, 0.55)) block("assembly-leg-stock-$x", Material.STRIPPED_SPRUCE_LOG,
                    x, 0.25, -0.25, 0.14, 0.14, 0.52)
            }
            OriginWorkshopTableRole.FINISHER -> {
                for (index in 0..2) block("cured-panel-layer-$index",
                    listOf(Material.BIRCH_PLANKS, Material.OAK_PLANKS, Material.SPRUCE_PLANKS)[index],
                    0.0, 0.225 + index * 0.09, 0.30, 1.65, 0.09, 0.22)
                block("coating-crate", Material.BARREL, -0.70, 0.39, -0.90, 0.42, 0.42, 0.42)
            }
        }
    }
    val product = when (role) {
        OriginWorkshopTableRole.CARPENTER, OriginWorkshopTableRole.FINISHER -> "furnituresplus:white_wooden_chair"
        OriginWorkshopTableRole.UPHOLSTERER -> "furnituresplus:red_wooden_sofa_single"
        OriginWorkshopTableRole.ASSEMBLER -> "furnituresplus:white_wooden_diningtable"
    }
    // Carpenter's stock gap holds materials only; finished furniture stays under the awning.
    val items = if (role == OriginWorkshopTableRole.CARPENTER) emptyList() else
        listOf(OriginWorkshopWarehouseItem(product, 0.58, 0.18, -0.93, 0f, 0.65))
    return OriginWorkshopWarehouseGeometry(blocks, items)
}
