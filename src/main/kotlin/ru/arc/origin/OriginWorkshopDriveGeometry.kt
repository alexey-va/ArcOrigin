package ru.arc.origin

import org.bukkit.Material

/** Extra supported drive assemblies; all coordinates share the table's local transform. */
internal fun originWorkshopDrivePieces(
    role: OriginWorkshopTableRole,
    dimensions: OriginWorkshopTableDimensions,
    tuning: OriginWorkshopMachineTuning,
): List<OriginWorkshopTablePiece> = buildList {
    val h = dimensions.height
    fun part(key: String, material: Material, x: Double, y: Double, z: Double, w: Double, height: Double, d: Double) {
        add(OriginWorkshopTablePiece(key, material, OriginWorkshopTablePieceKind.BLOCK, x, y, z, w, height, d))
    }
    fun wheel(prefix: String, x: Double, y: Double, z: Double, radius: Double) {
        for ((index, width) in listOf(0.95, 1.50, 1.85, 2.0, 2.0, 1.85, 1.50, 0.95).withIndex()) {
            part("$prefix-rim-$index", Material.COPPER_BLOCK,
                x, y + (index - 3.5) * radius / 4.0, z, width * radius, radius / 4.0, 0.07)
        }
        part("$prefix-spoke-x", Material.IRON_BLOCK, x, y, z - 0.045, radius * 1.8, 0.045, 0.04)
        part("$prefix-spoke-y", Material.IRON_BLOCK, x, y, z - 0.045, 0.045, radius * 1.8, 0.04)
        part("$prefix-hub", Material.POLISHED_ANDESITE, x, y, z - 0.04, 0.11, 0.11, 0.11)
    }
    when (role) {
        OriginWorkshopTableRole.CARPENTER -> Unit // Mounted on the existing saw shaft in the main geometry.
        OriginWorkshopTableRole.UPHOLSTERER -> {
            // Right-hand sewing station, ahead of the tool board and clear of the cushion sample behind it.
            part("upholsterer-sewing-bed", Material.POLISHED_BLACKSTONE, 0.65, h + 0.06, -0.58, 1.50, 0.12, 0.58)
            part("upholsterer-sewing-post", Material.POLISHED_BLACKSTONE, 1.22, h + 0.40, -0.52, 0.18, 0.56, 0.18)
            part("upholsterer-sewing-arm", Material.POLISHED_BLACKSTONE, 0.80, h + 0.74, -0.52, 1.02, 0.12, 0.18)
            part("upholsterer-sewing-fabric", Material.RED_WOOL, 0.42, h + 0.135, -0.58, 0.50, 0.03, 0.44)
            part("upholsterer-drive-needle", Material.IRON_BLOCK, 0.42, h + 0.34, -0.58, 0.035, 0.24, 0.035)
            part("upholsterer-sewing-needle-guide", Material.IRON_BLOCK, 0.42, h + 0.52, -0.58, 0.08, 0.28, 0.08)
            part("upholsterer-sewing-foot-toe-left", Material.IRON_BLOCK, 0.375, h + 0.264, -0.58, 0.045, 0.02, 0.10)
            part("upholsterer-sewing-foot-toe-right", Material.IRON_BLOCK, 0.465, h + 0.264, -0.58, 0.045, 0.02, 0.10)
            part("upholsterer-sewing-foot-bridge", Material.IRON_BLOCK, 0.42, h + 0.294, -0.46, 0.21, 0.04, 0.14)
            part("upholsterer-sewing-foot-shank", Material.IRON_BLOCK, 0.52, h + 0.497, -0.43, 0.035, 0.366, 0.035)
            // A compact hand lever sits beside the presser foot where the recipe cue points.
            part("upholsterer-sewing-foot-lifter", Material.COPPER_BLOCK, 0.515, h + 0.24, -0.58, 0.045, 0.12, 0.045)
            part("upholsterer-drive-shuttle", Material.COPPER_BLOCK, 0.42, h + 0.075, -0.895, 0.15, 0.045, 0.055)
            part("upholsterer-sewing-shuttle-rail", Material.IRON_BLOCK, 0.42, h + 0.035, -0.895, 0.60, 0.035, 0.08)
            wheel("upholsterer-drive-handwheel", 1.22, h + 0.44, -0.75, 0.22)
            part("upholsterer-sewing-wheel-axle", Material.IRON_BLOCK, 1.22, h + 0.44, -0.635, 0.07, 0.07, 0.23)
            for ((index, x) in listOf(0.15, 0.72).withIndex()) {
                part("upholsterer-drive-fabric-roller-$index", Material.DARK_OAK_PLANKS, x, h + 0.225, -0.58, 0.10, 0.10, 0.46)
                for (z in listOf(-0.84, -0.32)) part("upholsterer-roller-bearing-$index-$z", Material.IRON_BLOCK,
                    x, h + 0.18, z, 0.055, 0.12, 0.05)
            }
        }
        OriginWorkshopTableRole.ASSEMBLER -> {
            // A visible eccentric at the existing hammer hinge follows its actual stroke.
            part("assembler-hammer-cam-axle", Material.IRON_BLOCK, tuning.anvilCenterX,
                h + tuning.hammerPivotYOffset, tuning.hammerPivotZ - 0.12, 0.09, 0.09, 0.25)
            wheel("assembler-hammer-cam", tuning.anvilCenterX, h + tuning.hammerPivotYOffset,
                tuning.hammerPivotZ - 0.27, 0.18)
            part("assembler-drive-bearing", Material.STRIPPED_SPRUCE_LOG, -1.80, h + 0.27, 0.43, 0.12, 0.54, 0.12)
            part("assembler-drive-axle", Material.IRON_BLOCK, -1.80, h + 0.54, 0.22, 0.07, 0.07, 0.42)
            wheel("assembler-drive-freewheel", -1.80, h + 0.54, 0.0, 0.23)
        }
        OriginWorkshopTableRole.FINISHER -> {
            // A narrow sander at the left end; the moving rack panel uses the separate centre bay.
            part("finisher-sander-base", Material.POLISHED_BLACKSTONE, -1.87, h + 0.09, -0.14, 0.54, 0.18, 0.60)
            part("finisher-sander-bearing", Material.IRON_BLOCK, -1.87, h + 0.28, -0.14, 0.12, 0.20, 0.12)
            part("finisher-drive-sanding-disc", Material.BROWN_CONCRETE, -1.87, h + 0.40, -0.14, 0.42, 0.04, 0.42)
            part("finisher-drive-disc-marker", Material.STRIPPED_OAK_LOG, -1.73, h + 0.427, -0.14, 0.07, 0.01, 0.12)
            // The finishing brush hangs from a supported transverse gantry over the coating bath.
            for (x in listOf(-1.58, -0.72)) part("finisher-brush-post-$x", Material.STRIPPED_SPRUCE_LOG,
                x, h + 0.56, 0.34, 0.08, 1.12, 0.08)
            part("finisher-brush-rail", Material.IRON_BLOCK, -1.15, h + 1.155, 0.34, 0.94, 0.07, 0.08)
            part("finisher-drive-brush-carriage", Material.COPPER_BLOCK, -1.15, h + 1.15, 0.24, 0.18, 0.13, 0.12)
            part("finisher-drive-brush-handle", Material.STRIPPED_OAK_LOG, -1.15, h + 0.91, 0.20, 0.05, 0.36, 0.05)
            part("finisher-drive-brush-head", Material.BROWN_WOOL, -1.15, h + 0.67, 0.20, 0.24, 0.12, 0.12)
        }
    }
}
