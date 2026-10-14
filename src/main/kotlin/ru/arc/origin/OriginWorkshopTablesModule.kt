package ru.arc.origin

import dev.lone.itemsadder.api.CustomStack
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Directional
import org.bukkit.entity.ItemDisplay
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.config.ConfigManager
import ru.arc.core.PluginModule
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PacketDisplay
import ru.arc.paper.display.PaperPacketDisplays
import java.nio.file.Path
import java.util.UUID
import java.util.logging.Level

internal data class OriginWorkshopTableDimensions(
    val width: Double,
    val depth: Double,
    val height: Double,
) {
    init {
        require(width.isFinite() && width in 4.0..5.2) { "workshop-table width must be within 4.0..5.2 blocks" }
        require(depth.isFinite() && depth in 1.8..2.3) { "workshop-table depth must be within 1.8..2.3 blocks" }
        require(height.isFinite() && height in 0.9..1.2) { "workshop-table height must be within 0.9..1.2 blocks" }
    }

    companion object {
        val DEFAULT = OriginWorkshopTableDimensions(width = 4.8, depth = 2.05, height = 1.08)
    }
}

internal enum class OriginWorkshopTableRole(val key: String) {
    CARPENTER("carpenter"),
    UPHOLSTERER("upholsterer"),
    ASSEMBLER("assembler"),
    FINISHER("finisher");

    companion object {
        fun parse(value: String): OriginWorkshopTableRole =
            entries.firstOrNull { it.key == value.trim().lowercase() }
                ?: error("Unknown Origin workshop table role '$value'")
    }
}

private val CARPENTER_FINISHED_ASSEMBLY_FIXTURE_PIECE_KEYS = setOf(
    "carpenter-assembly-bed",
    "carpenter-assembly-guide-front",
    "carpenter-assembly-guide-back",
    "carpenter-assembly-stop-left",
    "carpenter-assembly-stop-right",
    "carpenter-assembly-clamp-left",
    "carpenter-assembly-clamp-right",
    "carpenter-assembly-clamp-control-left",
    "carpenter-assembly-clamp-control-right",
    "carpenter-leg-left",
    "carpenter-leg-right",
)

internal fun originWorkshopFinishedAssemblyFixturePieceKeys(role: OriginWorkshopTableRole): Set<String> =
    if (role == OriginWorkshopTableRole.CARPENTER) CARPENTER_FINISHED_ASSEMBLY_FIXTURE_PIECE_KEYS else emptySet()

internal data class OriginWorkshopTableDefinition(
    val id: String,
    val x: Double,
    val floorY: Double,
    val z: Double,
    val yaw: Int,
    val role: OriginWorkshopTableRole,
    val stockOffsetX: Double? = null,
) {
    init {
        require(id.matches(Regex("[a-z0-9][a-z0-9_-]{0,31}"))) { "Invalid workshop table id '$id'" }
        require(x.isFinite() && x in -30_000_000.0..30_000_000.0) { "workshop table '$id' has invalid x" }
        require(floorY.isFinite() && floorY in -64.0..320.0) { "workshop table '$id' has invalid floor-y" }
        require(z.isFinite() && z in -30_000_000.0..30_000_000.0) { "workshop table '$id' has invalid z" }
        require(yaw in setOf(0, 90, 180, 270)) { "workshop table '$id' yaw must be 0, 90, 180 or 270" }
        require(stockOffsetX == null || (stockOffsetX.isFinite() && kotlin.math.abs(stockOffsetX) in 3.9..12.0)) {
            "workshop table '$id' stock-offset-x must leave room for the table and stay within the local stock area"
        }
    }
}

internal data class OriginWorkshopTablesSettings(
    val enabled: Boolean,
    val world: String,
    val dimensions: OriginWorkshopTableDimensions,
    val tables: List<OriginWorkshopTableDefinition>,
    val warehouse: OriginWorkshopWarehouseAnchor?,
    val machineTuning: OriginWorkshopMachineTuning,
    val driveCycleTicks: Long,
) {
    companion object {
        private const val RESOURCE = "origin-workshop-tables.yml"
        private const val ROOT = "origin-workshop-tables"

        fun load(dataPath: Path): OriginWorkshopTablesSettings {
            val source = ConfigManager.ofModule(dataPath, RESOURCE)
            source.mergeMissingFromBundled("modules/$RESOURCE")
            val ids = source.stringList("$ROOT.table-ids").map(String::trim)
            require(ids.size <= MAX_TABLES) { "$ROOT.table-ids supports at most $MAX_TABLES tables" }
            require(ids.distinct().size == ids.size) { "$ROOT.table-ids contains duplicate ids" }

            val dimensions = OriginWorkshopTableDimensions(
                width = source.real("$ROOT.dimensions.width", OriginWorkshopTableDimensions.DEFAULT.width),
                depth = source.real("$ROOT.dimensions.depth", OriginWorkshopTableDimensions.DEFAULT.depth),
                height = source.real("$ROOT.dimensions.height", OriginWorkshopTableDimensions.DEFAULT.height),
            )
            val tables = ids.map { id ->
                val path = "$ROOT.tables.$id"
                OriginWorkshopTableDefinition(
                    id = id,
                    x = source.doubleOrNull("$path.x") ?: error("$path.x is required"),
                    floorY = source.doubleOrNull("$path.floor-y") ?: error("$path.floor-y is required"),
                    z = source.doubleOrNull("$path.z") ?: error("$path.z is required"),
                    yaw = source.intOrNull("$path.yaw") ?: error("$path.yaw is required"),
                    role = OriginWorkshopTableRole.parse(source.string("$path.role")),
                    stockOffsetX = source.doubleOrNull("$path.stock-offset-x"),
                )
            }
            require(tables.map { listOf(it.x, it.floorY, it.z) }.distinct().size == tables.size) {
                "$ROOT contains tables with duplicate centers"
            }
            require(tables.all { it.stockOffsetX == null || kotlin.math.abs(it.stockOffsetX) >= dimensions.width / 2.0 + 1.50 }) {
                "$ROOT stock racks must stay clear of the configured table width"
            }
            val enabled = source.bool("$ROOT.enabled", false)
            require(!enabled || tables.size == MAX_TABLES) {
                "$ROOT must configure exactly $MAX_TABLES tables when enabled"
            }
            val world = source.string("$ROOT.world", "rc_origin_spawn").trim()
            require(world.isNotEmpty()) { "$ROOT.world must not be blank" }
            val warehousePath = "$ROOT.warehouse"
            val warehouse = if (source.bool("$warehousePath.enabled", false)) {
                OriginWorkshopWarehouseAnchor(
                    source.doubleOrNull("$warehousePath.x") ?: error("$warehousePath.x is required"),
                    source.doubleOrNull("$warehousePath.floor-y") ?: error("$warehousePath.floor-y is required"),
                    source.doubleOrNull("$warehousePath.z") ?: error("$warehousePath.z is required"),
                )
            } else null
            val driveCycleTicks = source.long("$ROOT.machinery.idle-cycle-ticks", 80L)
            require(driveCycleTicks in 40L..400L) { "workshop drive cycle must be within 40..400 ticks" }
            return OriginWorkshopTablesSettings(
                enabled = enabled,
                world = world,
                dimensions = dimensions,
                tables = tables,
                warehouse = warehouse,
                machineTuning = OriginWorkshopMachineTuning.load(source),
                driveCycleTicks = driveCycleTicks,
            )
        }

        private const val MAX_TABLES = 4
    }
}

internal data class OriginWorkshopWarehouseAnchor(val x: Double, val floorY: Double, val z: Double) {
    init {
        require(x.isFinite() && x in -30_000_000.0..30_000_000.0) { "warehouse x is invalid" }
        require(floorY.isFinite() && floorY in -64.0..320.0) { "warehouse floor-y is invalid" }
        require(z.isFinite() && z in -30_000_000.0..30_000_000.0) { "warehouse z is invalid" }
    }
}

private fun rotateWorkshopLocal(x: Double, z: Double, yaw: Int): Pair<Double, Double> = when (yaw) {
    90 -> -z to x
    180 -> -x to -z
    270 -> z to -x
    else -> x to z
}

/** Resolve a station-local anchor against its authored center, floor and yaw. */
internal fun originWorkshopPointInWorld(
    table: OriginWorkshopTableDefinition,
    local: OriginWorkshopPoint,
): OriginWorkshopPoint {
    val (x, z) = rotateWorkshopLocal(local.x, local.z, table.yaw)
    return OriginWorkshopPoint(table.x + x, table.floorY + local.y, table.z + z)
}

internal enum class OriginWorkshopTablePieceKind { BLOCK, ITEM }

/** A piece center and size relative to its table center and floor. */
internal data class OriginWorkshopTablePiece(
    val key: String,
    val material: Material,
    val kind: OriginWorkshopTablePieceKind,
    val x: Double,
    val y: Double,
    val z: Double,
    val width: Double,
    val height: Double,
    val depth: Double,
    val flat: Boolean = false,
)

private data class WorkshopItemProp(
    val material: Material,
    val xRatio: Double,
    val zRatio: Double,
    val scale: Double,
    val flat: Boolean,
)

/** Large workbench geometry with separated trim, storage and recognizable role equipment. */
internal fun originWorkshopTablePieces(
    role: OriginWorkshopTableRole,
    yaw: Int,
    dimensions: OriginWorkshopTableDimensions = OriginWorkshopTableDimensions.DEFAULT,
    tuning: OriginWorkshopMachineTuning = OriginWorkshopMachineTuning(),
): List<OriginWorkshopTablePiece> {
    require(yaw in setOf(0, 90, 180, 270)) { "workshop table yaw must be 0, 90, 180 or 270" }
    val topThickness = 0.18
    val border = 0.10
    val legSize = 0.22
    val legX = dimensions.width / 2.0 - 0.42
    val legZ = dimensions.depth / 2.0 - 0.32
    val legHeight = dimensions.height - topThickness + 0.02
    val pieces = buildList {
        val topY = dimensions.height - topThickness / 2
        val topWidth = dimensions.width - border * 2
        val topDepth = dimensions.depth - border * 2
        val plankColumns = kotlin.math.ceil(topWidth / 0.92).toInt()
        val plankRows = kotlin.math.ceil(topDepth / 0.92).toInt()
        val plankWidth = topWidth / plankColumns
        val plankDepth = topDepth / plankRows
        for (column in 0 until plankColumns) for (row in 0 until plankRows) {
            val x = -topWidth / 2.0 + (column + 0.5) * plankWidth
            val z = -topDepth / 2.0 + (row + 0.5) * plankDepth
            add(blockPiece("tabletop-plank-$column-$row", Material.BIRCH_PLANKS, x, topY, z,
                plankWidth, topThickness, plankDepth))
        }
        for (side in listOf(-1.0, 1.0)) {
            add(blockPiece("long-edge-$side", Material.DARK_OAK_PLANKS, 0.0, topY,
                side * (dimensions.depth / 2.0 - border / 2.0), dimensions.width, topThickness, border))
            add(blockPiece("end-edge-$side", Material.DARK_OAK_PLANKS,
                side * (dimensions.width / 2.0 - border / 2.0), topY, 0.0,
                border, topThickness, dimensions.depth - border * 2))
        }

        for (x in listOf(-legX, legX)) for (z in listOf(-legZ, legZ)) {
            add(blockPiece("leg-$x-$z", Material.STRIPPED_DARK_OAK_LOG, x, legHeight / 2.0, z,
                legSize, legHeight, legSize))
        }

        val braceThickness = 0.08
        val braceY = legHeight * 0.74
        for (z in listOf(-legZ, legZ)) {
            add(blockPiece("long-brace-$z", Material.POLISHED_BLACKSTONE, 0.0, braceY, z,
                dimensions.width - legSize * 2, braceThickness, braceThickness))
        }
        for (x in listOf(-legX, legX)) {
            add(blockPiece("end-brace-$x", Material.POLISHED_BLACKSTONE, x, braceY, 0.0,
                braceThickness, braceThickness, dimensions.depth - legSize * 2))
        }

        if (role == OriginWorkshopTableRole.CARPENTER) {
            val apronY = dimensions.height - 0.30
            val apronZ = dimensions.depth / 2.0 - 0.20
            for (side in listOf(-1.0, 1.0)) {
                add(blockPiece("carpenter-bench-apron-$side", Material.DARK_OAK_PLANKS,
                    0.0, apronY, side * apronZ, dimensions.width - 0.84, 0.20, 0.12))
                for (x in listOf(-dimensions.width / 2.0 + 0.75, dimensions.width / 2.0 - 0.75)) {
                    add(blockPiece("carpenter-apron-peg-$side-$x", Material.COPPER_BLOCK,
                        x, apronY, side * (apronZ + 0.075), 0.055, 0.055, 0.03))
                }
            }
        }

        if (role != OriginWorkshopTableRole.CARPENTER) {
            val cabinetWidth = 0.84
            val cabinetDepth = 0.72
            val cabinetBottom = 0.14
            val cabinetHeight = dimensions.height - topThickness - cabinetBottom
            val drawerFaceHeight = 0.19
            val cabinetZ = -0.10
            for (x in listOf(-1.15, 1.15)) {
                add(blockPiece("drawer-cabinet-$x", Material.DARK_OAK_PLANKS, x,
                    cabinetBottom + cabinetHeight / 2.0, cabinetZ,
                    cabinetWidth, cabinetHeight, cabinetDepth))
                val faceZ = cabinetZ - cabinetDepth / 2.0 - 0.025
                for (faceY in listOf(cabinetBottom + 0.20, cabinetBottom + 0.49)) {
                    add(blockPiece("drawer-face-$x-$faceY", Material.SPRUCE_PLANKS, x, faceY, faceZ,
                        cabinetWidth - 0.12, drawerFaceHeight, 0.05))
                    add(blockPiece("drawer-handle-$x-$faceY", Material.IRON_BLOCK, x, faceY, faceZ - 0.045,
                        0.18, 0.045, 0.045))
                }
            }
        }

        if (role != OriginWorkshopTableRole.CARPENTER) {
            add(blockPiece("lower-stock-shelf", Material.DARK_OAK_PLANKS, 0.0, 0.22, 0.36,
                1.55, 0.10, 0.78))
            listOf(
                Triple(Material.SPRUCE_PLANKS, 0.28, 0.18),
                Triple(Material.OAK_PLANKS, 0.40, 0.39),
                Triple(Material.BIRCH_PLANKS, 0.52, 0.60),
            ).forEachIndexed { index, (material, y, z) ->
                add(blockPiece("stock-board-$index", material, 0.0, y, z, 1.38, 0.07, 0.16))
            }
        }

        if (role != OriginWorkshopTableRole.CARPENTER) {
            val boardZ = dimensions.depth / 2.0 - 0.30
            add(blockPiece("back-tool-board", Material.DARK_OAK_PLANKS, 0.0, dimensions.height + 0.39, boardZ,
                2.46, 0.78, 0.12))
            for (x in listOf(-1.30, 1.30)) {
                add(blockPiece("rack-post-$x", Material.STRIPPED_SPRUCE_LOG, x, dimensions.height + 0.48,
                    dimensions.depth / 2.0 - 0.12, 0.14, 0.96, 0.14))
            }
            add(blockPiece("back-rack-shelf", Material.SPRUCE_PLANKS, 0.0, dimensions.height + 0.96,
                dimensions.depth / 2.0 - 0.18, 2.75, 0.12, 0.25))
        }
    }

    val machine = when (role) {
        OriginWorkshopTableRole.CARPENTER -> buildList {
            add(blockPiece("carpenter-table-saw", Material.STONECUTTER, tuning.sawPivotX,
                dimensions.height - 0.36, tuning.sawPivotZ + 0.505, 0.92, 0.72, 0.92))
            // Center the feed surface on the carried board path; its forward edge
            // still reaches the saw blade at sawPivotZ.
            val boardZ = tuning.sawPivotZ + 0.075
            val flywheelZ = tuning.sawPivotZ + 1.05
            val flywheelY = dimensions.height + tuning.sawPivotYOffset
            add(blockPiece("carpenter-board-feed", Material.SPRUCE_PLANKS, tuning.sawPivotX,
                dimensions.height + 0.08, boardZ, 1.10, 0.10, 0.44))
            add(blockPiece("carpenter-rip-fence", Material.DARK_OAK_PLANKS, tuning.sawPivotX,
                dimensions.height + 0.16, boardZ - 0.20, 1.50, 0.06, 0.05))
            add(blockPiece("carpenter-feed-support-rail", Material.DARK_OAK_PLANKS, tuning.sawPivotX,
                dimensions.height + 0.16, boardZ + 0.20, 1.50, 0.06, 0.05))
            add(blockPiece("carpenter-drive-motor", Material.BLACKSTONE, tuning.sawPivotX,
                dimensions.height + 0.17, tuning.sawPivotZ + 1.16, 0.56, 0.34, 0.38))

            add(blockPiece("carpenter-saw-bearing-post", Material.POLISHED_DEEPSLATE, tuning.sawPivotX,
                dimensions.height + 0.31, tuning.sawPivotZ + 1.16, 0.14, 0.62, 0.14))
            add(blockPiece("carpenter-saw-axle", Material.IRON_BLOCK, tuning.sawPivotX,
                dimensions.height + tuning.sawPivotYOffset, tuning.sawPivotZ + 0.555, 0.09, 0.09, 1.21))
            add(blockPiece("carpenter-drive-flywheel-hub", Material.POLISHED_ANDESITE,
                tuning.sawPivotX, flywheelY, flywheelZ - 0.04, 0.14, 0.14, 0.10))
            for ((index, width) in listOf(0.95, 1.50, 1.85, 2.0, 2.0, 1.85, 1.50, 0.95).withIndex()) {
                add(blockPiece("carpenter-drive-flywheel-rim-$index", Material.COPPER_BLOCK,
                    tuning.sawPivotX, flywheelY + (index - 3.5) * 0.23 / 4.0, flywheelZ,
                    width * 0.23, 0.23 / 4.0, 0.07))
            }
            add(blockPiece("carpenter-drive-flywheel-spoke-x", Material.IRON_BLOCK,
                tuning.sawPivotX, flywheelY, flywheelZ - 0.045, 0.40, 0.055, 0.04))
            add(blockPiece("carpenter-drive-flywheel-spoke-y", Material.IRON_BLOCK,
                tuning.sawPivotX, flywheelY, flywheelZ - 0.045, 0.055, 0.40, 0.04))
            for (index in 0..1) {
                val rollerX = tuning.sawFeedStartX + if (index == 0) 0.17 else 0.50
                add(blockPiece("carpenter-drive-feed-roller-$index", Material.DARK_OAK_PLANKS,
                    rollerX, dimensions.height + 0.38, boardZ, 0.18, 0.18, 0.35))
                add(blockPiece("carpenter-drive-feed-roller-axle-$index", Material.IRON_BLOCK,
                    rollerX, dimensions.height + 0.38, boardZ, 0.05, 0.05, 0.41))
                for (side in listOf(-1.0, 1.0)) add(blockPiece("carpenter-drive-feed-bearing-$index-$side", Material.IRON_BLOCK,
                    rollerX, dimensions.height + 0.255, boardZ + side * 0.22, 0.05, 0.25, 0.03))
            }

            // The built-in stonecutter blade sits too low to read at workshop scale, so add a
            // raised, pixel-rounded metal disk on the operator-facing side of the housing.
            val bladeWidths = listOf(0.25, 0.45, 0.60, 0.72, 0.78, 0.72, 0.60, 0.45, 0.25)
            bladeWidths.forEachIndexed { index, width ->
                add(blockPiece(
                    "carpenter-saw-blade-row-$index",
                    if (index == 0 || index == 4 || index == 8) Material.LIGHT_GRAY_CONCRETE else Material.IRON_BLOCK,
                    tuning.sawPivotX,
                    dimensions.height + tuning.sawPivotYOffset + (index - 4) * 0.095,
                    tuning.sawPivotZ,
                    width,
                    0.095,
                    0.07,
                ))
            }
            add(blockPiece("carpenter-saw-blade-hub", Material.POLISHED_ANDESITE, tuning.sawPivotX,
                dimensions.height + tuning.sawPivotYOffset, tuning.sawPivotZ - 0.05, 0.18, 0.18, 0.07))
            add(blockPiece("carpenter-saw-control-handle", Material.WAXED_COPPER_BLOCK,
                -1.65, dimensions.height + 0.352, -0.70, 0.09, 0.18, 0.09))
            add(blockPiece("carpenter-saw-control-post", Material.STRIPPED_SPRUCE_LOG,
                -1.65, dimensions.height + 0.13, -0.70, 0.09, 0.26, 0.09))

            // A cantilevered drill leaves its operator-facing work surface clear for the carried board.
            add(blockPiece("carpenter-drill-foot", Material.POLISHED_BLACKSTONE,
                0.0, dimensions.height + 0.035, 0.67, 0.48, 0.07, 0.42))
            add(blockPiece("carpenter-drill-mounting-plate", Material.DARK_OAK_PLANKS,
                0.0, dimensions.height + 0.018, 0.67, 0.60, 0.036, 0.52))
            add(blockPiece("carpenter-drill-post", Material.POLISHED_DEEPSLATE,
                0.0, dimensions.height + 0.56, 0.77, 0.14, 1.12, 0.14))
            add(blockPiece("carpenter-drill-head", Material.BLACKSTONE,
                0.0, dimensions.height + 1.01, 0.40, 0.42, 0.24, 0.80))
            add(blockPiece("carpenter-drill-arm", Material.DARK_OAK_PLANKS,
                0.0, dimensions.height + 1.02, -0.20, 0.64, 0.12, 0.72))
            add(blockPiece("carpenter-drill-board-support-front", Material.POLISHED_DEEPSLATE,
                0.0, dimensions.height + 0.05, -0.66, 1.30, 0.10, 0.06))
            add(blockPiece("carpenter-drill-board-support-back", Material.POLISHED_DEEPSLATE,
                0.0, dimensions.height + 0.05, -0.44, 1.30, 0.10, 0.06))
            add(blockPiece("carpenter-drill-control-bracket", Material.IRON_BLOCK,
                0.30, dimensions.height + 0.82, -0.42, 0.07, 0.48, 0.07))
            add(blockPiece("carpenter-drill-quill", Material.IRON_BLOCK,
                0.0, dimensions.height + 0.93, -0.49, 0.12, 0.38, 0.12))
            add(blockPiece("carpenter-drill-spindle", Material.IRON_BLOCK,
                0.0, dimensions.height + 0.64, -0.55, 0.065, 0.34, 0.065))
            add(blockPiece("carpenter-drill-bit", Material.IRON_BLOCK,
                0.0, dimensions.height + 0.415, -0.55, 0.045, 0.11, 0.045))
            add(blockPiece("carpenter-drill-control-wheel-hub", Material.WAXED_OXIDIZED_COPPER,
                0.30, dimensions.height + 0.58, -0.42, 0.09, 0.09, 0.09))
            for ((index, width) in listOf(0.95, 1.50, 1.85, 2.0, 2.0, 1.85, 1.50, 0.95).withIndex()) {
                add(blockPiece("carpenter-drill-control-wheel-rim-$index", Material.COPPER_BLOCK,
                    0.30, dimensions.height + 0.58 + (index - 3.5) * 0.14 / 4.0, -0.42,
                    width * 0.14, 0.14 / 4.0, 0.035))
            }
            add(blockPiece("carpenter-drill-control-wheel-spoke-x", Material.IRON_BLOCK,
                0.30, dimensions.height + 0.58, -0.44, 0.24, 0.035, 0.025))
            add(blockPiece("carpenter-drill-control-wheel-spoke-y", Material.IRON_BLOCK,
                0.30, dimensions.height + 0.58, -0.44, 0.035, 0.24, 0.025))

            // The assembly bed is deliberately empty: the game mounts its transient board/legs here.
            add(blockPiece("carpenter-assembly-bed", Material.DARK_OAK_PLANKS,
                1.35, dimensions.height + 0.035, -0.35, 1.18, 0.07, 0.64))
            add(blockPiece("carpenter-assembly-guide-front", Material.STRIPPED_SPRUCE_LOG,
                1.35, dimensions.height + 0.13, -0.65, 1.18, 0.12, 0.08))
            add(blockPiece("carpenter-assembly-guide-back", Material.STRIPPED_SPRUCE_LOG,
                1.35, dimensions.height + 0.13, -0.05, 1.18, 0.12, 0.08))
            add(blockPiece("carpenter-assembly-stop-left", Material.IRON_BLOCK,
                0.79, dimensions.height + 0.12, -0.35, 0.07, 0.16, 0.42))
            add(blockPiece("carpenter-assembly-stop-right", Material.IRON_BLOCK,
                1.91, dimensions.height + 0.12, -0.35, 0.07, 0.16, 0.42))
            add(blockPiece("carpenter-assembly-clamp-left", Material.IRON_BLOCK,
                0.95, dimensions.height + 0.19, -0.65, 0.12, 0.24, 0.16))
            add(blockPiece("carpenter-assembly-clamp-right", Material.IRON_BLOCK,
                1.95, dimensions.height + 0.19, -0.65, 0.12, 0.24, 0.16))
            add(blockPiece("carpenter-assembly-clamp-control-left", Material.COPPER_BLOCK,
                0.95, dimensions.height + 0.35, -0.65, 0.08, 0.08, 0.08))
            add(blockPiece("carpenter-assembly-clamp-control-right", Material.COPPER_BLOCK,
                1.95, dimensions.height + 0.35, -0.65, 0.08, 0.08, 0.08))
            add(blockPiece("carpenter-leg-left", Material.STRIPPED_SPRUCE_LOG,
                1.30, dimensions.height + 0.07, 0.50, 0.14, 0.14, 0.52))
            add(blockPiece("carpenter-leg-right", Material.STRIPPED_SPRUCE_LOG,
                1.75, dimensions.height + 0.07, 0.50, 0.14, 0.14, 0.52))
            val toolX = -minOf(2.02, dimensions.width / 2.0 - 0.28)
            val toolZ = dimensions.depth / 2.0 - 0.445
            add(blockPiece("carpenter-back-tool-tray", Material.DARK_OAK_PLANKS,
                toolX, dimensions.height + 0.035, toolZ, 0.56, 0.07, 0.62))
            add(blockPiece("carpenter-mallet-head", Material.OAK_PLANKS,
                toolX - 0.10, dimensions.height + 0.11, toolZ - 0.24, 0.24, 0.08, 0.14))
            add(blockPiece("carpenter-mallet-handle", Material.STRIPPED_BIRCH_LOG,
                toolX - 0.10, dimensions.height + 0.11, toolZ - 0.03, 0.06, 0.08, 0.28))
            add(blockPiece("carpenter-chisel-blade", Material.IRON_BLOCK,
                toolX + 0.12, dimensions.height + 0.0875, toolZ + 0.07, 0.06, 0.035, 0.14))
            add(blockPiece("carpenter-chisel-handle", Material.STRIPPED_SPRUCE_LOG,
                toolX + 0.12, dimensions.height + 0.10, toolZ + 0.232, 0.06, 0.06, 0.18))
        }
        OriginWorkshopTableRole.UPHOLSTERER -> buildList {
            add(blockPiece("upholsterer-loom", Material.LOOM, tuning.pressCenterX, dimensions.height + 0.36,
                tuning.pressCenterZ, 0.68, 0.72, 0.62))
            add(blockPiece("upholsterer-press-post-left", Material.STRIPPED_DARK_OAK_LOG,
                tuning.pressCenterX - 0.46, dimensions.height + 0.65, tuning.pressCenterZ, 0.12, 1.30, 0.12))
            add(blockPiece("upholsterer-press-post-right", Material.STRIPPED_DARK_OAK_LOG,
                tuning.pressCenterX + 0.46, dimensions.height + 0.65, tuning.pressCenterZ, 0.12, 1.30, 0.12))
            add(blockPiece("upholsterer-press-crossbeam", Material.DARK_OAK_PLANKS,
                tuning.pressCenterX, dimensions.height + 1.30, tuning.pressCenterZ, 1.06, 0.10, 0.78))
            add(blockPiece("upholsterer-press-platen", Material.IRON_BLOCK,
                tuning.pressCenterX, dimensions.height + 0.90, tuning.pressCenterZ, 0.76, 0.08, 0.68))
            add(blockPiece("upholsterer-press-ram", Material.WAXED_COPPER_BLOCK,
                tuning.pressCenterX, dimensions.height + 1.095, tuning.pressCenterZ, 0.12, 0.31, 0.12))
            add(blockPiece("upholsterer-press-control-arm", Material.IRON_BLOCK,
                tuning.pressCenterX - 0.46, dimensions.height + 0.42, tuning.pressCenterZ - 0.225, 0.06, 0.06, 0.45))
            add(blockPiece("upholsterer-press-control-grip", Material.WAXED_OXIDIZED_COPPER,
                tuning.pressCenterX - 0.46, dimensions.height + 0.42, tuning.pressCenterZ - 0.45, 0.09, 0.26, 0.09))
        }
        OriginWorkshopTableRole.ASSEMBLER -> buildList {
            add(blockPiece("assembler-anvil", Material.ANVIL, tuning.anvilCenterX,
                dimensions.height + 0.31, tuning.anvilCenterZ,
                0.76, 0.62, 0.70),
            )
            add(blockPiece("assembler-vise-bed", Material.DARK_OAK_PLANKS, tuning.viseCenterX,
                dimensions.height + 0.035, tuning.viseCenterZ,
                1.12, 0.06, 0.46),
            )
            add(blockPiece("assembler-vise-post-left", Material.STRIPPED_SPRUCE_LOG, tuning.viseCenterX - 0.48,
                dimensions.height + 0.23, tuning.viseCenterZ + 0.18,
                0.08, 0.33, 0.12),
            )
            add(blockPiece("assembler-vise-post-right", Material.STRIPPED_SPRUCE_LOG, tuning.viseCenterX + 0.48,
                dimensions.height + 0.23, tuning.viseCenterZ + 0.18,
                0.08, 0.33, 0.12),
            )
            add(blockPiece("assembler-vise-crossbar", Material.DARK_OAK_PLANKS, tuning.viseCenterX,
                dimensions.height + 0.435, tuning.viseCenterZ + 0.18,
                0.96, 0.08, 0.30),
            )
            add(blockPiece("assembler-clamp-left", Material.IRON_BLOCK, tuning.viseCenterX - 0.40 - tuning.viseJawTravel,
                dimensions.height + 0.17, tuning.viseCenterZ,
                0.12, 0.20, 0.16),
            )
            add(blockPiece("assembler-clamp-right", Material.IRON_BLOCK, tuning.viseCenterX + 0.40 + tuning.viseJawTravel,
                dimensions.height + 0.17, tuning.viseCenterZ,
                0.12, 0.20, 0.16),
            )
            add(blockPiece("assembler-vise-screw", Material.IRON_BLOCK, tuning.viseCenterX - 0.57,
                dimensions.height + 0.17, tuning.viseCenterZ, 0.30, 0.055, 0.055))
            add(blockPiece("assembler-vise-handle", Material.WAXED_OXIDIZED_COPPER, tuning.viseCenterX - 0.7495,
                dimensions.height + 0.17, tuning.viseCenterZ, 0.055, 0.20, 0.055))
            val pivotY = dimensions.height + tuning.hammerPivotYOffset
            val pivotZ = tuning.hammerPivotZ
            val restRadians = Math.toRadians(tuning.hammerRestAngleDegrees)
            val armDy = -tuning.hammerArmLength * kotlin.math.sin(restRadians)
            val armDz = tuning.hammerArmLength * kotlin.math.cos(restRadians)
            add(blockPiece("assembler-trip-hammer-post", Material.POLISHED_DEEPSLATE,
                tuning.anvilCenterX, dimensions.height + 0.90, pivotZ + 0.07, 0.14, 1.00, 0.14))
            add(blockPiece("assembler-trip-hammer-pivot", Material.IRON_BLOCK,
                tuning.anvilCenterX, pivotY, pivotZ, 0.22, 0.22, 0.22))
            add(blockPiece("assembler-hammer-arm", Material.IRON_BLOCK,
                tuning.anvilCenterX, pivotY + armDy / 2.0, pivotZ + armDz / 2.0,
                0.12, 0.12, tuning.hammerArmLength))
            add(blockPiece("assembler-hammer-head", Material.IRON_BLOCK,
                tuning.anvilCenterX, pivotY + armDy, pivotZ + armDz, 0.28, 0.14, 0.18))
            add(blockPiece("assembler-anvil-workpiece", Material.IRON_BLOCK,
                tuning.anvilCenterX, dimensions.height + 0.645, tuning.anvilCenterZ, 0.22, 0.05, 0.22))
        }
        OriginWorkshopTableRole.FINISHER -> listOf(
            blockPiece("finisher-abrasive-stock", Material.SANDSTONE, -1.87, dimensions.height + 0.06, -0.14,
                0.18, 0.06, 0.18),
            blockPiece("finisher-paint-bath", Material.WATER_CAULDRON, -1.15, dimensions.height + 0.30, -0.14,
                0.72, 0.60, 0.72),
            blockPiece("finisher-drying-post-front-left", Material.STRIPPED_BIRCH_LOG, 0.55,
                dimensions.height + 0.565, -0.35, 0.10, 1.12, 0.10),
            blockPiece("finisher-drying-post-back-left", Material.STRIPPED_BIRCH_LOG, 0.55,
                dimensions.height + 0.565, 0.10, 0.10, 1.12, 0.10),
            blockPiece("finisher-drying-post-front-right", Material.STRIPPED_BIRCH_LOG, 1.65,
                dimensions.height + 0.565, -0.35, 0.10, 1.12, 0.10),
            blockPiece("finisher-drying-post-back-right", Material.STRIPPED_BIRCH_LOG, 1.65,
                dimensions.height + 0.565, 0.10, 0.10, 1.12, 0.10),
            blockPiece("finisher-drying-rail-lower-front", Material.DARK_OAK_PLANKS, 1.10,
                dimensions.height + 0.14, -0.35, 1.00, 0.08, 0.08),
            blockPiece("finisher-drying-rail-lower-back", Material.DARK_OAK_PLANKS, 1.10,
                dimensions.height + 0.14, 0.10, 1.00, 0.08, 0.08),
            blockPiece("finisher-drying-rail-upper-front", Material.DARK_OAK_PLANKS, 1.10,
                dimensions.height + 0.90, -0.35, 1.00, 0.08, 0.08),
            blockPiece("finisher-drying-rail-upper-back", Material.DARK_OAK_PLANKS, 1.10,
                dimensions.height + 0.90, 0.10, 1.00, 0.08, 0.08),
            blockPiece("finisher-drying-rail-top-front", Material.SPRUCE_PLANKS, 1.10,
                dimensions.height + 1.05, -0.35, 1.00, 0.08, 0.08),
            blockPiece("finisher-drying-rail-top-back", Material.SPRUCE_PLANKS, 1.10,
                dimensions.height + 1.05, 0.10, 1.00, 0.08, 0.08),
            blockPiece("finisher-drying-panel-left", Material.BIRCH_PLANKS, 0.75,
                dimensions.height + 0.52, -0.35, 0.18, 0.68, 0.06),
            blockPiece("finisher-drying-panel-center", Material.OAK_PLANKS, 1.10,
                dimensions.height + 0.52, -0.35, 0.18, 0.68, 0.06),
            blockPiece("finisher-drying-panel-right", Material.SPRUCE_PLANKS, 1.45,
                dimensions.height + 0.52, -0.35, 0.18, 0.68, 0.06),
            blockPiece("finisher-rack-control-grip", Material.WAXED_OXIDIZED_COPPER, 1.10,
                dimensions.height + 0.14, -0.42, 0.20, 0.065, 0.07),
        )
    }
    val workpieces = when (role) {
        OriginWorkshopTableRole.CARPENTER -> listOf(
            blockPiece("carpenter-board-in-feed", Material.OAK_PLANKS, tuning.sawFeedStartX,
                dimensions.height + 0.19, tuning.sawPivotZ + 0.075, 0.82, 0.12, 0.35),
        )
        OriginWorkshopTableRole.UPHOLSTERER -> listOf(
            blockPiece("upholsterer-cloth-roll", Material.RED_WOOL,
                0.29 * dimensions.width, dimensions.height + 0.085, -0.10 * dimensions.depth,
                0.65, 0.16, 0.35),
            blockPiece("upholsterer-cushion-base", Material.SPRUCE_PLANKS, 0.96, dimensions.height + 0.035, 0.38,
                0.76, 0.06, 0.50),
            blockPiece("upholsterer-cushion-padding", Material.WHITE_WOOL, 0.96, dimensions.height + 0.155, 0.38,
                0.72, 0.18, 0.46),
            blockPiece("upholsterer-cushion-cover", Material.RED_WOOL, 0.96, dimensions.height + 0.265, 0.38,
                0.70, 0.04, 0.44),
            blockPiece("upholsterer-press-cloth", Material.RED_WOOL,
                tuning.pressCenterX, dimensions.height + 0.74, tuning.pressCenterZ, 0.52, 0.04, 0.46),
        )
        OriginWorkshopTableRole.ASSEMBLER -> listOf(blockPiece(
            "assembler-board-sample", Material.SPRUCE_PLANKS,
            tuning.viseCenterX, dimensions.height + 0.10, tuning.viseCenterZ,
            0.68, 0.06, 0.30,
        ))
        OriginWorkshopTableRole.FINISHER -> listOf(
            blockPiece("finisher-finished-board", Material.SPRUCE_PLANKS,
                0.29 * dimensions.width, dimensions.height + 0.035, -0.05 * dimensions.depth,
                0.65, 0.06, 0.30),
        )
    }
    val worldPieces = (pieces + machine + workpieces + originWorkshopDrivePieces(role, dimensions, tuning)).map { piece -> rotatePiece(piece, yaw) }.toMutableList()
    roleProps(role).forEachIndexed { index, prop ->
        val local = OriginWorkshopTablePiece(
            key = "${role.key}-prop-$index",
            material = prop.material,
            kind = OriginWorkshopTablePieceKind.ITEM,
            x = prop.xRatio * dimensions.width,
            y = dimensions.height + prop.scale / 32.0 + 0.01,
            z = prop.zRatio * dimensions.depth,
            width = prop.scale,
            height = prop.scale,
            depth = prop.scale,
            flat = prop.flat,
        )
        worldPieces += rotatePiece(local, yaw)
    }
    return worldPieces
}

private fun blockPiece(
    key: String,
    material: Material,
    x: Double,
    y: Double,
    z: Double,
    width: Double,
    height: Double,
    depth: Double,
) = OriginWorkshopTablePiece(key, material, OriginWorkshopTablePieceKind.BLOCK, x, y, z, width, height, depth)

private fun rotatePiece(piece: OriginWorkshopTablePiece, yaw: Int): OriginWorkshopTablePiece {
    val (x, z) = when (yaw) {
        0 -> piece.x to piece.z
        90 -> -piece.z to piece.x
        180 -> -piece.x to -piece.z
        else -> piece.z to -piece.x
    }
    val swapsAxes = yaw == 90 || yaw == 270
    return piece.copy(
        x = x,
        z = z,
        width = if (swapsAxes) piece.depth else piece.width,
        depth = if (swapsAxes) piece.width else piece.depth,
    )
}

private fun roleProps(role: OriginWorkshopTableRole): List<WorkshopItemProp> = when (role) {
    OriginWorkshopTableRole.CARPENTER -> listOf(
        WorkshopItemProp(Material.IRON_AXE, 0.05, -0.40, 0.40, flat = true),
    )
    OriginWorkshopTableRole.UPHOLSTERER -> listOf(
        WorkshopItemProp(Material.SHEARS, 0.05, -0.10, 0.60, flat = true),
    )
    OriginWorkshopTableRole.ASSEMBLER -> listOf(
        WorkshopItemProp(Material.IRON_INGOT, 0.05, -0.10, 0.58, flat = true),
    )
    OriginWorkshopTableRole.FINISHER -> listOf(
        WorkshopItemProp(Material.BRUSH, 0.05, -0.10, 0.62, flat = true),
    )
}

internal object OriginWorkshopTablesModule : PluginModule {
    override val name = "OriginWorkshopTables"
    override val priority = 24

    private val craftGlow = Color.fromRGB(0xD8, 0x91, 0x35)
    private val craftHoverGlow = Color.fromRGB(0xFF, 0xD2, 0x63)

    private var displays: PaperPacketDisplays? = null
    private val handles = mutableListOf<PacketDisplay>()
    private val machineTables = linkedMapOf<String, WorkshopMachineTable>()
    private val activeMachineKeys = mutableMapOf<String, Set<String>>()
    private val activeMechanisms = mutableMapOf<String, OriginWorkshopMechanism>()
    private val runningDrives = mutableSetOf<String>()
    private val activeCraftKeys = mutableMapOf<String, Set<String>>()
    private val activeCraftHighlights = mutableMapOf<String, Map<PacketDisplay, Color>>()
    private val activeCraftOverlays = mutableMapOf<String, CraftGlowOverlay>()

    private data class CraftGlowOverlay(
        val viewerId: UUID,
        val control: String,
        val sourceToOverlay: Map<PacketDisplay, PacketDisplay>,
    )

    private data class WorkshopMachinePart(
        val display: PacketDisplay,
        val piece: OriginWorkshopTablePiece,
        val location: Location,
        val transformation: Transformation,
        val visible: Boolean,
    )

    private data class WorkshopMachineTable(
        val definition: OriginWorkshopTableDefinition,
        val pieces: Map<String, WorkshopMachinePart>,
        val dimensions: OriginWorkshopTableDimensions,
        val tuning: OriginWorkshopMachineTuning,
        val world: org.bukkit.World,
        val driveCycleTicks: Long,
        val craftControls: Map<String, List<PacketDisplay>>,
    )

    override fun init() = reload()

    override fun reload() {
        val settings = try {
            OriginWorkshopTablesSettings.load(ArcOriginPlugin.instance.legacyDataPath)
        } catch (failure: Exception) {
            ArcOriginPlugin.instance.logger.log(Level.WARNING, "Origin workshop tables config rejected; keeping current scene", failure)
            return
        }
        if (!settings.enabled || settings.tables.isEmpty()) {
            clearScene()
            return
        }
        val world = Bukkit.getWorld(settings.world)
        if (world == null) {
            ArcOriginPlugin.instance.logger.warning("Origin workshop tables skipped: world '${settings.world}' is not loaded; keeping current scene")
            return
        }

        val owner = displays ?: try {
            PaperPacketDisplays(ArcOriginPlugin.instance, "workshop-tables").also { displays = it }
        } catch (failure: Exception) {
            ArcOriginPlugin.instance.logger.log(Level.WARNING, "Origin workshop tables could not open the packet display owner", failure)
            return
        }
        val created = mutableListOf<PacketDisplay>()
        val createdMachineTables = linkedMapOf<String, WorkshopMachineTable>()
        try {
            settings.tables.forEach { table ->
                val machineParts = linkedMapOf<String, WorkshopMachinePart>()
                val driveKeys = originWorkshopDrivePose(table.role, 0.0, settings.dimensions, settings.machineTuning).pieces.keys
                val workKeys = OriginWorkshopMechanism.entries.filter { supports(table.role, it) }.flatMap {
                    originWorkshopMachinePose(it, 0.0, 0.0, settings.dimensions, settings.machineTuning).pieces.keys
                }.toSet()
                check(driveKeys.intersect(workKeys).isEmpty()) { "Workshop ${table.id} has competing drive and work animation owners" }
                originWorkshopTablePieces(table.role, table.yaw, settings.dimensions, settings.machineTuning).forEach { piece ->
                    if (piece.kind == OriginWorkshopTablePieceKind.BLOCK) {
                        val location = Location(
                            world,
                            table.x + piece.x - piece.width / 2.0,
                            table.floorY + piece.y - piece.height / 2.0,
                            table.z + piece.z - piece.depth / 2.0,
                        )
                        val block = piece.material.createBlockData()
                        if (block is Directional) {
                            val front = when (table.yaw) {
                                90 -> BlockFace.EAST
                                180 -> BlockFace.SOUTH
                                270 -> BlockFace.WEST
                                else -> BlockFace.NORTH
                            }
                            if (front in block.faces) block.facing = front
                        }
                        val display = owner.spawnBlock(location, block)
                        created += display
                        display.transformation = cuboidTransform(piece.width, piece.height, piece.depth)
                        if (piece.key == "assembler-hammer-arm" || piece.key == "assembler-hammer-head") {
                            display.transformation = centeredRotation(
                                display.transformation,
                                piece,
                                table.yaw,
                                OriginWorkshopRotationAxis.X,
                                settings.machineTuning.hammerRestAngleDegrees,
                            )
                        }
                        if (piece.key in workKeys || piece.key in driveKeys) {
                            display.isVisibleByDefault = piece.key !in ORIGIN_WORKSHOP_MACHINE_HIDDEN_IDLE_PIECES
                        }
                        machineParts[piece.key] = WorkshopMachinePart(
                            display = display,
                            piece = piece,
                            location = display.location.clone(),
                            transformation = copyTransformation(display.transformation),
                            visible = display.isVisibleByDefault,
                        )
                    } else {
                        val location = Location(
                            world,
                            table.x + piece.x,
                            table.floorY + piece.y,
                            table.z + piece.z,
                            table.yaw.toFloat(),
                            0f,
                        )
                        val display = owner.spawnItem(location, ItemStack(piece.material))
                        created += display
                        display.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.NONE
                        display.transformation = itemTransform(piece.width, piece.flat)
                        machineParts[piece.key] = WorkshopMachinePart(
                            display = display,
                            piece = piece,
                            location = display.location.clone(),
                            transformation = copyTransformation(display.transformation),
                            visible = display.isVisibleByDefault,
                        )
                    }
                }
                check(machineParts.keys.containsAll(driveKeys + workKeys)) { "Workshop ${table.id} has an animation without a matching model part" }
                val stockHandles = table.stockOffsetX?.let { stockX ->
                    val (dx, dz) = rotateLocal(stockX, 0.0, table.yaw)
                    spawnStock(owner, created, world, table.x + dx, table.floorY, table.z + dz,
                        table.yaw, originWorkshopStationStockGeometry(table.role))
                }.orEmpty()
                createdMachineTables[table.id] = WorkshopMachineTable(
                    table,
                    machineParts,
                    settings.dimensions,
                    settings.machineTuning,
                    world,
                    settings.driveCycleTicks,
                    craftControlDisplays(table.role, machineParts, stockHandles),
                )
            }
            settings.warehouse?.let { anchor ->
                spawnStock(owner, created, world, anchor.x, anchor.floorY, anchor.z, 0, originWorkshopWarehouseGeometry())
            }
            clearScene()
            handles += created
            machineTables.putAll(createdMachineTables)
            ArcOriginPlugin.instance.logger.info(
                "Origin workshop tables loaded: tables=${settings.tables.size} local-stock=${settings.tables.count { it.stockOffsetX != null }} warehouse=${settings.warehouse != null} client-only displays=${created.size}",
            )
        } catch (failure: Exception) {
            created.forEach(PacketDisplay::remove)
            ArcOriginPlugin.instance.logger.log(Level.WARNING, "Origin workshop tables could not be constructed; keeping current scene", failure)
        }
    }

    private fun spawnStock(
        owner: PaperPacketDisplays,
        created: MutableList<PacketDisplay>,
        world: org.bukkit.World,
        x: Double,
        floorY: Double,
        z: Double,
        yaw: Int,
        stock: OriginWorkshopWarehouseGeometry,
    ): List<PacketDisplay> {
        val craftStockHandles = mutableListOf<PacketDisplay>()
        stock.blocks.forEach { local ->
            val piece = rotatePiece(local, yaw)
            val display = owner.spawnBlock(
                Location(world, x + piece.x - piece.width / 2.0,
                    floorY + piece.y - piece.height / 2.0, z + piece.z - piece.depth / 2.0),
                piece.material.createBlockData(),
            )
            created += display
            display.transformation = cuboidTransform(piece.width, piece.height, piece.depth)
            if (local.key in setOf(
                    "board-bundle-layer-2",
                    "fabric-bale-0",
                    "tabletop-blank-layer-3",
                    "cured-panel-layer-2",
                )
            ) craftStockHandles += display
        }
        stock.items.forEach { piece ->
            val item = CustomStack.getInstance(piece.itemId)?.itemStack?.clone()
                ?: error("Workshop stock furniture '${piece.itemId}' is missing from ItemsAdder")
            val (dx, dz) = rotateLocal(piece.x, piece.z, yaw)
            val modelBottom = if (piece.itemId == "furnituresplus:white_wooden_diningtable") 0.51875 else 0.5
            val display = owner.spawnItem(
                Location(world, x + dx, floorY + piece.y + modelBottom * piece.scale,
                    z + dz, yaw + piece.yaw, 0f), item,
            )
            created += display
            display.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.NONE
            display.transformation = itemTransform(piece.scale, flat = false)
        }
        return craftStockHandles
    }

    private fun craftControlDisplays(
        role: OriginWorkshopTableRole,
        pieces: Map<String, WorkshopMachinePart>,
        stock: List<PacketDisplay>,
    ): Map<String, List<PacketDisplay>> {
        fun parts(vararg keys: String) = keys.map { pieces.getValue(it).display }
        val benchBody = pieces.values.filter { part ->
            val key = part.piece.key
            key.startsWith("tabletop-plank-") || key.startsWith("long-edge-") || key.startsWith("end-edge-")
        }.map { it.display }
        val controls = linkedMapOf("start" to benchBody, "stock" to stock)
        when (role) {
            OriginWorkshopTableRole.CARPENTER -> controls.putAll(mapOf(
                "saw" to parts("carpenter-saw-control-handle"),
                "drill" to parts("carpenter-drill-control-wheel-hub", "carpenter-drill-control-wheel-spoke-y"),
                "assembly" to parts("carpenter-assembly-bed"),
                "clamp-left" to parts("carpenter-assembly-clamp-control-left"),
                "clamp-right" to parts("carpenter-assembly-clamp-control-right"),
                "leg-left" to parts("carpenter-leg-left"),
                "leg-right" to parts("carpenter-leg-right"),
            ))
            OriginWorkshopTableRole.UPHOLSTERER -> controls.putAll(mapOf(
                "press" to parts("upholsterer-press-control-grip"),
                "sewing" to parts("upholsterer-drive-handwheel-hub", "upholsterer-drive-handwheel-spoke-x"),
                "sewing-foot" to parts(
                    "upholsterer-sewing-foot-lifter",
                    "upholsterer-sewing-foot-toe-left",
                    "upholsterer-sewing-foot-toe-right",
                ),
                "cushion" to parts("upholsterer-cushion-cover"),
            ))
            OriginWorkshopTableRole.ASSEMBLER -> controls.putAll(mapOf(
                "vise" to parts("assembler-vise-handle"),
                "anvil" to parts("assembler-hammer-head"),
            ))
            OriginWorkshopTableRole.FINISHER -> controls.putAll(mapOf(
                "rack" to parts("finisher-rack-control-grip"),
                "bath" to parts("finisher-paint-bath"),
                "abrasive" to parts("finisher-abrasive-stock"),
                "panel" to parts("finisher-drying-panel-center"),
            ))
        }
        return controls
    }

    override fun shutdown() {
        clearScene()
        displays?.close()
        displays = null
    }

    /** Resolve a station-local anchor without reading or mutating world state. Call on the server thread. */
    internal fun pointAt(tableId: String, local: OriginWorkshopPoint): Location? {
        val table = machineTables[tableId] ?: return null
        val point = originWorkshopPointInWorld(table.definition, local)
        return Location(table.world, point.x, point.y, point.z)
    }

    /** Resolve a table-local result anchor, including the station's world yaw for spawned models. */
    internal fun resultAt(tableId: String, local: OriginWorkshopPoint): Location? {
        val table = machineTables[tableId] ?: return null
        val at = pointAt(tableId, local) ?: return null
        at.yaw = table.definition.yaw.toFloat()
        return at
    }

    internal fun resultAt(tableId: String, productId: String): Location? {
        val table = machineTables[tableId] ?: return null
        if (originWorkshopResultBounds(productId) == null) return null
        return resultAt(tableId, originWorkshopResultAnchor(table.definition.role, productId, table.dimensions))
    }

    /** Configured dimensions for a currently loaded station, used to resolve its live interaction bounds. */
    internal fun dimensionsFor(tableId: String): OriginWorkshopTableDimensions? =
        machineTables[tableId]?.dimensions

    /** Build a game recipe from this loaded station's role, stock and machine tuning. */
    internal fun recipeFor(
        tableId: String,
        productId: String,
        rules: OriginWorkshopGameRules,
        workpieceRenderer: OriginWorkshopWorkpieceRenderer = OriginWorkshopWorkpieceRenderer.MODEL,
    ): OriginWorkshopGameRecipe? {
        val table = machineTables[tableId] ?: return null
        val stock = when (table.definition.role) {
            OriginWorkshopTableRole.FINISHER -> OriginWorkshopPoint(1.10, table.dimensions.height + 0.52, -0.35)
            else -> {
                val stockX = table.definition.stockOffsetX ?: return null
                when (table.definition.role) {
                    OriginWorkshopTableRole.CARPENTER -> OriginWorkshopPoint(stockX, 0.405, 0.30)
                    OriginWorkshopTableRole.UPHOLSTERER -> OriginWorkshopPoint(stockX - 0.62, 0.62, 0.30)
                    OriginWorkshopTableRole.ASSEMBLER -> OriginWorkshopPoint(stockX, 0.54, 0.30)
                    OriginWorkshopTableRole.FINISHER -> error("Finisher input comes from its drying rack")
                }
            }
        }
        return originWorkshopGameRecipe(
            table.definition.role,
            productId,
            table.dimensions,
            table.tuning,
            stock,
            rules,
            workpieceRenderer,
        )
    }

    private fun clearScene() {
        machineTables.keys.toList().forEach {
            resetCraft(it)
            resetWork(it)
        }
        handles.forEach(PacketDisplay::remove)
        handles.clear()
        machineTables.clear()
        activeMachineKeys.clear()
        activeMechanisms.clear()
        runningDrives.clear()
        activeCraftKeys.clear()
        activeCraftHighlights.clear()
        activeCraftOverlays.clear()
    }

    /** Free-running shafts share the scene tick; never touch the material/contact animation keys. */
    fun animateDrive(tableId: String, tick: Long, enabled: Boolean) {
        val table = machineTables[tableId] ?: return
        if (!enabled && !runningDrives.remove(tableId)) return
        val phase = if (enabled) {
            runningDrives += tableId
            ((tick + table.definition.role.ordinal * 17L) % table.driveCycleTicks).toDouble() / table.driveCycleTicks
        } else 0.0
        val pose = originWorkshopDrivePose(table.definition.role, phase, table.dimensions, table.tuning)
        pose.pieces.forEach { (key, motion) ->
            val part = table.pieces[key] ?: return@forEach
            applyMotion(part, table.definition.yaw, motion)
        }
    }

    /** Apply one table-local pose to the already-owned client-only display handles. */
    fun animateWork(
        tableId: String,
        mechanism: OriginWorkshopMechanism,
        progress: Double,
        strokeProgress: Double,
    ): Location? {
        val table = machineTables[tableId] ?: return null
        if (mechanism == OriginWorkshopMechanism.NONE || !supports(table.definition.role, mechanism)) {
            resetWork(tableId)
            return null
        }
        if (activeMechanisms[tableId] != mechanism) {
            resetWork(tableId)
        }
        val pose = originWorkshopMachinePose(mechanism, progress, strokeProgress, table.dimensions, table.tuning)
        pose.pieces.forEach { (key, motion) ->
            val part = table.pieces[key] ?: return@forEach
            applyMotion(part, table.definition.yaw, motion)
        }
        activeMachineKeys[tableId] = pose.pieces.keys
        activeMechanisms[tableId] = mechanism
        val contact = pose.contact ?: return null
        val (worldX, worldZ) = rotateLocal(contact.x, contact.z, table.definition.yaw)
        return Location(
            table.world,
            table.definition.x + worldX,
            table.definition.floorY + contact.y,
            table.definition.z + worldZ,
        )
    }

    /** Glow only the real stock, machine or source-part displays that correspond to a game control. */
    fun highlightCraftControl(
        tableId: String,
        control: String?,
        hovered: Boolean = false,
        viewer: org.bukkit.entity.Player? = null,
    ) {
        val table = machineTables[tableId] ?: return
        val color = if (hovered) craftHoverGlow else craftGlow

        if (viewer != null) {
            clearSharedCraftHighlight(tableId)
            val name = control
            if (name == null) {
                removeCraftOverlay(tableId)
                return
            }
            val sources = (table.craftControls[name] ?: error("Unknown ${table.definition.role.key} craft control '$name'"))
                .distinct().filter(PacketDisplay::isValid)
            val current = activeCraftOverlays[tableId]
            val reusable = current != null && current.viewerId == viewer.uniqueId && current.control == name &&
                current.sourceToOverlay.keys == sources.toSet() && current.sourceToOverlay.values.all(PacketDisplay::isValid)
            val overlay = if (reusable) current else {
                removeCraftOverlay(tableId)
                val copies = linkedMapOf<PacketDisplay, PacketDisplay>()
                val owner = displays ?: return
                sources.forEach { source ->
                    val sourceBlock = source as? PacketBlockDisplay
                        ?: error("${table.definition.role.key} craft control '$name' contains a non-block display")
                    val copy = owner.spawnBlock(source.location, sourceBlock.blockData)
                    handles += copy
                    copy.isVisibleByDefault = false
                    copy.showTo(viewer)
                    copy.billboard = source.billboard
                    copy.brightness = source.brightness
                    copy.interpolationDelay = source.interpolationDelay
                    copy.interpolationDuration = source.interpolationDuration
                    copy.teleportDuration = source.teleportDuration
                    copy.viewRange = source.viewRange
                    copy.shadowRadius = source.shadowRadius
                    copy.shadowStrength = source.shadowStrength
                    copy.displayWidth = source.displayWidth
                    copy.displayHeight = source.displayHeight
                    copy.transformation = expandedGlowTransform(source.transformation)
                    copy.isGlowing = true
                    copies[source] = copy
                }
                CraftGlowOverlay(viewer.uniqueId, name, copies).also { activeCraftOverlays[tableId] = it }
            }
            overlay.sourceToOverlay.values.forEach { copy ->
                if (copy.isValid && copy.glowColorOverride != color) copy.glowColorOverride = color
            }
            return
        }

        removeCraftOverlay(tableId)
        val next = control?.let { name ->
            (table.craftControls[name] ?: error("Unknown carpenter craft control '$name'"))
                .distinct().associateWith { color }
        }.orEmpty()
        val previous = activeCraftHighlights[tableId].orEmpty()
        (previous.keys - next.keys).forEach { display ->
            if (display.isValid && display.isGlowing) {
                display.isGlowing = false
                display.glowColorOverride = null
            }
        }
        next.forEach { (display, nextColor) ->
            if (!display.isValid) return@forEach
            val currentColor = previous[display]
            if (currentColor == null) {
                display.glowColorOverride = nextColor
                display.isGlowing = true
            } else if (currentColor != nextColor) {
                display.glowColorOverride = nextColor
            }
        }
        if (next.isEmpty()) activeCraftHighlights.remove(tableId) else activeCraftHighlights[tableId] = next
    }

    /** Physical display hitboxes behind a named game control. */
    internal fun craftControlDisplaysFor(tableId: String, control: String): List<PacketDisplay> {
        val overlay = activeCraftOverlays[tableId]?.takeIf { it.control == control }
        return overlay?.sourceToOverlay?.values?.filter(PacketDisplay::isValid)
            ?: machineTables[tableId]?.craftControls?.get(control).orEmpty().filter(PacketDisplay::isValid)
    }

    private fun clearSharedCraftHighlight(tableId: String) {
        activeCraftHighlights.remove(tableId).orEmpty().keys.forEach { display ->
            if (display.isValid) {
                display.isGlowing = false
                display.glowColorOverride = null
            }
        }
    }

    private fun removeCraftOverlay(tableId: String) {
        activeCraftOverlays.remove(tableId)?.sourceToOverlay?.values?.forEach { copy ->
            if (copy.isValid) copy.remove()
            handles.remove(copy)
        }
    }

    private fun syncCraftOverlay(tableId: String) {
        activeCraftOverlays[tableId]?.sourceToOverlay?.forEach { (source, copy) ->
            if (!source.isValid || !copy.isValid) return@forEach
            copy.teleport(source.location)
            copy.interpolationDelay = source.interpolationDelay
            copy.interpolationDuration = source.interpolationDuration
            copy.teleportDuration = source.teleportDuration
            copy.transformation = expandedGlowTransform(source.transformation)
        }
    }

    /** Drive one craft machine directly, without the autonomous display cycle used by other roles. */
    fun animateCraftMachine(tableId: String, machine: String, progress: Double, strokeProgress: Double = progress) {
        require(progress.isFinite() && strokeProgress.isFinite()) { "Craft machine progress must be finite" }
        val table = machineTables[tableId] ?: return
        val roleMatches = when (machine) {
            "saw", "drill", "clamp-left", "clamp-right" -> table.definition.role == OriginWorkshopTableRole.CARPENTER
            "press", "sewing", "sewing-foot" -> table.definition.role == OriginWorkshopTableRole.UPHOLSTERER
            "vise", "anvil" -> table.definition.role == OriginWorkshopTableRole.ASSEMBLER
            "finish" -> table.definition.role == OriginWorkshopTableRole.FINISHER
            else -> error("Unknown workshop craft machine '$machine'")
        }
        if (!roleMatches) return
        val pose = originWorkshopCraftMachinePose(machine, progress, table.dimensions, table.tuning, strokeProgress)
        pose.pieces.forEach { (key, motion) ->
            val part = table.pieces[key] ?: error("Carpenter craft machine '$machine' has no model part '$key'")
            applyMotion(part, table.definition.yaw, motion)
        }
        syncCraftOverlay(tableId)
        activeCraftKeys[tableId] = activeCraftKeys[tableId].orEmpty() + pose.pieces.keys
    }

    /** Hide a picked static source stick; resetCraft restores its authored visibility. */
    fun setCraftPartVisible(tableId: String, part: String, visible: Boolean) {
        val table = machineTables[tableId] ?: return
        val key = when (table.definition.role to part) {
            OriginWorkshopTableRole.CARPENTER to "leg-left" -> "carpenter-leg-left"
            OriginWorkshopTableRole.CARPENTER to "leg-right" -> "carpenter-leg-right"
            OriginWorkshopTableRole.UPHOLSTERER to "press-cloth" -> "upholsterer-press-cloth"
            OriginWorkshopTableRole.UPHOLSTERER to "sewing-fabric" -> "upholsterer-sewing-fabric"
            OriginWorkshopTableRole.UPHOLSTERER to "cushion-padding" -> "upholsterer-cushion-padding"
            OriginWorkshopTableRole.UPHOLSTERER to "cushion-cover" -> "upholsterer-cushion-cover"
            OriginWorkshopTableRole.ASSEMBLER to "tabletop" -> "assembler-board-sample"
            OriginWorkshopTableRole.ASSEMBLER to "anvil-workpiece" -> "assembler-anvil-workpiece"
            OriginWorkshopTableRole.FINISHER to "finished-board" -> "finisher-finished-board"
            OriginWorkshopTableRole.FINISHER to "panel" -> "finisher-drying-panel-center"
            else -> error("Unknown ${table.definition.role.key} craft part '$part'")
        }
        val display = table.pieces[key]?.display ?: error("${table.definition.role.key} craft source '$part' has no model part")
        if (!display.isValid) return
        display.isVisibleByDefault = visible
        if (!visible && activeCraftOverlays[tableId]?.sourceToOverlay?.containsKey(display) == true) {
            removeCraftOverlay(tableId)
        }
        activeCraftKeys[tableId] = activeCraftKeys[tableId].orEmpty() + key
    }

    /** Hide only the carpenter's assembly jig while its finished chair occupies that work area. */
    fun setCraftAssemblyFixtureVisible(tableId: String, visible: Boolean) {
        val table = machineTables[tableId] ?: return
        if (table.definition.role != OriginWorkshopTableRole.CARPENTER) return
        if (!visible) removeCraftOverlay(tableId)
        originWorkshopFinishedAssemblyFixturePieceKeys(table.definition.role).forEach { key ->
            val part = table.pieces[key] ?: error("Carpenter assembly fixture has no model part '$key'")
            if (!part.display.isValid) return@forEach
            part.display.isVisibleByDefault = visible
            activeCraftKeys[tableId] = activeCraftKeys[tableId].orEmpty() + key
        }
    }

    /** Recolor the finisher's rack panel after both faces have been sanded and coated. */
    fun setCraftFinishedPanel(tableId: String, finished: Boolean) {
        val table = machineTables[tableId] ?: return
        if (table.definition.role != OriginWorkshopTableRole.FINISHER) return
        val key = "finisher-drying-panel-center"
        val part = table.pieces[key] ?: error("Finisher rack has no center panel")
        val display = part.display as? PacketBlockDisplay ?: error("Finisher rack center panel is not a block display")
        if (!display.isValid) return
        display.blockData = (if (finished) Material.STRIPPED_DARK_OAK_WOOD else part.piece.material).createBlockData()
        activeCraftKeys[tableId] = activeCraftKeys[tableId].orEmpty() + key
    }

    private fun resetFinishedPanel(table: WorkshopMachineTable) {
        if (table.definition.role != OriginWorkshopTableRole.FINISHER) return
        val panel = table.pieces["finisher-drying-panel-center"] ?: return
        val display = panel.display as? PacketBlockDisplay ?: return
        if (display.isValid) display.blockData = panel.piece.material.createBlockData()
    }

    /** Restore every craft-touched machine/source handle and clear the current glow target. */
    fun resetCraft(tableId: String) {
        val table = machineTables[tableId] ?: return
        removeCraftOverlay(tableId)
        activeCraftKeys.remove(tableId).orEmpty().forEach { key ->
            val part = table.pieces[key] ?: return@forEach
            if (!part.display.isValid) return@forEach
            part.display.interpolationDuration = 0
            part.display.interpolationDelay = 0
            part.display.teleportDuration = 0
            part.display.teleport(part.location)
            part.display.transformation = copyTransformation(part.transformation)
            part.display.isVisibleByDefault = part.visible
            if (key == "finisher-drying-panel-center" && part.display is PacketBlockDisplay) {
                part.display.blockData = part.piece.material.createBlockData()
            }
        }
        resetFinishedPanel(table)
        activeCraftHighlights.remove(tableId).orEmpty().keys.forEach { display ->
            if (display.isValid) {
                display.isGlowing = false
                display.glowColorOverride = null
            }
        }
    }

    private fun expandedGlowTransform(source: Transformation): Transformation {
        val scale = Vector3f(source.scale).mul(1.04f)
        val localCenter = Vector3f(0.5f, 0.5f, 0.5f).also(source.rightRotation::transform)
        val oldCenter = Vector3f(localCenter).mul(source.scale).also(source.leftRotation::transform)
        val newCenter = Vector3f(localCenter).mul(scale).also(source.leftRotation::transform)
        val translation = Vector3f(source.translation).add(oldCenter).sub(newCenter)
        return Transformation(
            translation,
            Quaternionf(source.leftRotation),
            scale,
            Quaternionf(source.rightRotation),
        )
    }

    /** Restore every touched display to its captured pose and idle visibility. */
    fun resetWork(tableId: String) {
        val table = machineTables[tableId] ?: return
        val active = activeMachineKeys.remove(tableId).orEmpty()
        activeMechanisms.remove(tableId)
        active.forEach { key ->
            val part = table.pieces[key] ?: return@forEach
            if (!part.display.isValid) return@forEach
            part.display.interpolationDuration = 0
            part.display.interpolationDelay = 0
            part.display.teleportDuration = 0
            part.display.teleport(part.location)
            part.display.transformation = copyTransformation(part.transformation)
            part.display.isVisibleByDefault = part.visible
        }
        resetFinishedPanel(table)
    }

    private fun supports(role: OriginWorkshopTableRole, mechanism: OriginWorkshopMechanism): Boolean = when (mechanism) {
        OriginWorkshopMechanism.NONE -> false
        OriginWorkshopMechanism.SAW, OriginWorkshopMechanism.DRILL,
        OriginWorkshopMechanism.CLAMP_LEFT, OriginWorkshopMechanism.CLAMP_RIGHT -> role == OriginWorkshopTableRole.CARPENTER
        OriginWorkshopMechanism.VISE, OriginWorkshopMechanism.ANVIL -> role == OriginWorkshopTableRole.ASSEMBLER
        OriginWorkshopMechanism.PRESS -> role == OriginWorkshopTableRole.UPHOLSTERER
        OriginWorkshopMechanism.FINISH -> role == OriginWorkshopTableRole.FINISHER
    }

    private fun applyMotion(part: WorkshopMachinePart, yaw: Int, motion: OriginWorkshopPieceMotion) {
        val factor = motion.scaleYFactor
        require(factor.isFinite() && factor > 0.0) { "Workshop machine scale must be positive and finite" }
        val offset = rotateLocal(motion.centerOffset.x, motion.centerOffset.z, yaw)
        val base = part.location
        val width = part.piece.width
        val height = part.piece.height
        val depth = part.piece.depth
        val centerX = base.x + width / 2.0 + offset.first
        val centerY = base.y + height / 2.0 + motion.centerOffset.y
        val centerZ = base.z + depth / 2.0 + offset.second
        part.display.teleport(Location(
            base.world,
            centerX - width / 2.0,
            centerY - height * factor / 2.0,
            centerZ - depth / 2.0,
            base.yaw,
            base.pitch,
        ))
        part.display.transformation = motionTransformation(part, yaw, motion)
        part.display.interpolationDelay = 0
        part.display.interpolationDuration = WORKSHOP_MACHINE_INTERPOLATION_TICKS
        part.display.teleportDuration = WORKSHOP_MACHINE_INTERPOLATION_TICKS
        motion.visible?.let { part.display.isVisibleByDefault = it }
    }

    private fun motionTransformation(
        part: WorkshopMachinePart,
        yaw: Int,
        motion: OriginWorkshopPieceMotion,
    ): Transformation {
        val base = part.transformation
        val scale = Vector3f(base.scale.x, base.scale.y * motion.scaleYFactor.toFloat(), base.scale.z)
        val rotation = motion.rotationAxis?.let { axis ->
            val worldAxis = worldAxis(axis, yaw)
            Quaternionf().rotationAxis(
                Math.toRadians(motion.rotationDegrees).toFloat(),
                worldAxis.x,
                worldAxis.y,
                worldAxis.z,
            )
        } ?: Quaternionf(base.leftRotation)
        return centeredTransform(base, part.piece, scale, rotation)
    }

    private fun centeredRotation(
        base: Transformation,
        piece: OriginWorkshopTablePiece,
        yaw: Int,
        axis: OriginWorkshopRotationAxis,
        degrees: Double,
    ): Transformation {
        val worldAxis = worldAxis(axis, yaw)
        val rotation = Quaternionf().rotationAxis(
            Math.toRadians(degrees).toFloat(), worldAxis.x, worldAxis.y, worldAxis.z,
        )
        return centeredTransform(base, piece, Vector3f(base.scale), rotation)
    }

    private fun centeredTransform(
        base: Transformation,
        piece: OriginWorkshopTablePiece,
        scale: Vector3f,
        rotation: Quaternionf,
    ): Transformation {
        val center = Vector3f(scale.x / 2f, scale.y / 2f, scale.z / 2f)
        val rotatedCenter = Vector3f(center).also(rotation::transform)
        val translation = Vector3f(center).sub(rotatedCenter)
        return Transformation(translation, rotation, scale, Quaternionf(base.rightRotation))
    }

    private fun worldAxis(axis: OriginWorkshopRotationAxis, yaw: Int): Vector3f {
        val local = when (axis) {
            OriginWorkshopRotationAxis.X -> OriginWorkshopPoint(1.0, 0.0, 0.0)
            OriginWorkshopRotationAxis.Y -> OriginWorkshopPoint(0.0, 1.0, 0.0)
            OriginWorkshopRotationAxis.Z -> OriginWorkshopPoint(0.0, 0.0, 1.0)
        }
        val (x, z) = rotateLocal(local.x, local.z, yaw)
        return Vector3f(x.toFloat(), local.y.toFloat(), z.toFloat()).normalize()
    }

    private fun rotateLocal(x: Double, z: Double, yaw: Int): Pair<Double, Double> =
        rotateWorkshopLocal(x, z, yaw)

    private fun copyTransformation(source: Transformation) = Transformation(
        Vector3f(source.translation),
        Quaternionf(source.leftRotation),
        Vector3f(source.scale),
        Quaternionf(source.rightRotation),
    )

    private fun cuboidTransform(width: Double, height: Double, depth: Double) = Transformation(
        Vector3f(0f, 0f, 0f),
        Quaternionf(),
        Vector3f(width.toFloat(), height.toFloat(), depth.toFloat()),
        Quaternionf(),
    )

    private fun itemTransform(scale: Double, flat: Boolean) = Transformation(
        Vector3f(0f, 0f, 0f),
        if (flat) Quaternionf().rotationX((Math.PI / 2.0).toFloat()) else Quaternionf(),
        Vector3f(scale.toFloat(), scale.toFloat(), scale.toFloat()),
        Quaternionf(),
    )

}
