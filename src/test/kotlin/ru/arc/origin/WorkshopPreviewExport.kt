package ru.arc.origin

import com.google.gson.GsonBuilder
import java.nio.file.Files
import java.nio.file.Path
import org.joml.Quaternionf
import org.joml.Vector3f

/** Offline geometry receipt; uses production models/poses without a Bukkit world or spawned entities. */
object WorkshopPreviewExport {
    private const val GUIDE_SCALE = 0.42f
    private const val GUIDE_LINE_WIDTH = 220
    private const val GUIDE_VIEW_RANGE = 0.55f

    internal fun guidanceAnchor(
        dimensions: OriginWorkshopTableDimensions,
        recipe: OriginWorkshopGameRecipe,
        stage: OriginWorkshopGameStage,
    ): OriginWorkshopPoint {
        val interaction = recipe.interactions[stage]
        val target = interaction?.target ?: recipe.timedStages[stage]?.target ?: recipe.resultAnchor
        return originWorkshopGuidanceAnchor(
            dimensions,
            target,
            stock = originWorkshopUsesStockGuidance(recipe.role, interaction),
        )
    }

    internal fun guidanceMetadata(
        dimensions: OriginWorkshopTableDimensions,
        recipe: OriginWorkshopGameRecipe,
        stage: OriginWorkshopGameStage,
        progressPercent: Int? = null,
    ): Map<String, Any> {
        val interaction = recipe.interactions[stage]
        val step = minOf(stage.step, recipe.totalSteps)
        val actionHint = when {
            recipe.timedStages[stage] != null -> " · ${progressPercent ?: 0}%"
            interaction != null -> " · ЛКМ"
            else -> ""
        }
        val stock = originWorkshopUsesStockGuidance(recipe.role, interaction)
        return mapOf(
            "stage" to stage.name,
            "step" to step,
            "totalSteps" to recipe.totalSteps,
            "text" to "$step/${recipe.totalSteps}\n${stage.instruction}$actionHint",
            "anchorMode" to if (stock) "stock-behind-target" else "back-half-workbench",
            "anchor" to pointMetadata(guidanceAnchor(dimensions, recipe, stage)),
            "style" to mapOf(
                "scale" to GUIDE_SCALE,
                "lineWidth" to GUIDE_LINE_WIDTH,
                "billboard" to "CENTER",
                "viewRange" to GUIDE_VIEW_RANGE,
                "shadowRadius" to 0.0,
                "shadowed" to true,
                "backgroundColor" to mapOf("alpha" to 150, "red" to 12, "green" to 10, "blue" to 8),
                "seeThrough" to false,
                "defaultBackground" to false,
                "textOpacity" to 230,
                "visibleByDefault" to false,
                "visibility" to "session-player",
            ),
        )
    }

    internal fun frontEdgeCameraMetadata(
        dimensions: OriginWorkshopTableDimensions,
        recipe: OriginWorkshopGameRecipe,
        stage: OriginWorkshopGameStage,
    ): Map<String, Any> {
        val interaction = recipe.interactions.getValue(stage)
        val target = interaction.target
        val guideAnchor = guidanceAnchor(dimensions, recipe, stage)
        return mapOf(
            "id" to "front-edge-player-eye",
            "position" to pointMetadata(OriginWorkshopPoint(0.0, 1.62, -dimensions.depth / 2.0 - 0.45)),
            "target" to pointMetadata(OriginWorkshopPoint(
                (target.x + guideAnchor.x) / 2.0,
                (target.y + guideAnchor.y) / 2.0,
                (target.z + guideAnchor.z) / 2.0,
            )),
            "eyeHeight" to 1.62,
            "fovDegrees" to 70.0,
            "near" to 0.05,
            "far" to 8.0,
            "stance" to "standing",
            "coordinateSpace" to "table-local",
            "source" to "standing player eye at (0,1.62,-depth/2-0.45), aimed between step target and guidance anchor",
        )
    }

    internal fun stockCameraMetadata(
        dimensions: OriginWorkshopTableDimensions,
        recipe: OriginWorkshopGameRecipe,
        stage: OriginWorkshopGameStage,
    ): Map<String, Any> {
        val interaction = recipe.interactions.getValue(stage)
        val anchor = guidanceAnchor(dimensions, recipe, stage)
        return mapOf(
            "id" to "stock-player-eye",
            "position" to pointMetadata(OriginWorkshopPoint(interaction.target.x, 1.62, interaction.target.z - 2.0)),
            "target" to pointMetadata(OriginWorkshopPoint(
                (interaction.target.x + anchor.x) / 2.0,
                (interaction.target.y + anchor.y) / 2.0,
                (interaction.target.z + anchor.z) / 2.0,
            )),
            "eyeHeight" to 1.62,
            "fovDegrees" to 70.0,
            "near" to 0.05,
            "far" to 8.0,
            "stance" to "standing",
            "coordinateSpace" to "table-local",
            "source" to "stock interaction and stock guidance anchor; independent of workbench view",
        )
    }

    private fun pointMetadata(point: OriginWorkshopPoint): Map<String, Double> = mapOf(
        "x" to point.x,
        "y" to point.y,
        "z" to point.z,
    )

    private fun stockCameraMetadata(warehouse: Boolean): Map<String, Any> = if (warehouse) mapOf(
        "id" to "warehouse-front-wide",
        "position" to pointMetadata(OriginWorkshopPoint(0.0, 2.1, -8.0)),
        "target" to pointMetadata(OriginWorkshopPoint(0.0, 0.52, 0.20)),
        "eyeHeight" to 2.1,
        "fovDegrees" to 70.0,
        "near" to 0.05,
        "far" to 18.0,
        "coordinateSpace" to "warehouse-anchor-local",
        "source" to "wide view of the full three-pallet warehouse footprint",
    ) else mapOf(
        "id" to "station-stock-front",
        "position" to pointMetadata(OriginWorkshopPoint(0.0, 1.62, -3.4)),
        "target" to pointMetadata(OriginWorkshopPoint(0.0, 0.56, -0.15)),
        "eyeHeight" to 1.62,
        "fovDegrees" to 70.0,
        "near" to 0.05,
        "far" to 10.0,
        "coordinateSpace" to "stock-anchor-local",
        "source" to "front view centered on the two-block station stock pallet",
    )

    private fun stockFurnitureMetadata(item: OriginWorkshopWarehouseItem): Map<String, Any> {
        val model = checkNotNull(originWorkshopResultBounds(item.itemId)) {
            "Workshop stock model '${item.itemId}' has no canonical ItemsAdder bounds"
        }
        val scale = item.scale / 0.65
        val modelBottom = if (item.itemId == "furnituresplus:white_wooden_diningtable") 0.51875 else 0.5
        return mapOf(
            "key" to "stock-${item.itemId.substringAfter(':')}",
            "material" to item.itemId,
            // The baked palette retains model-space offsets from the ItemDisplay origin.
            "x" to item.x,
            "y" to item.y + modelBottom * item.scale,
            "z" to item.z,
            "width" to scale,
            "height" to scale,
            "depth" to scale,
            "rotationY" to item.yaw.toDouble(),
            "transformOrigin" to "item-display-entity",
            "itemDisplay" to mapOf(
                "context" to "none",
                "baseScale" to 0.65,
                "runtimeScale" to item.scale,
                "nativeBaseYawDegreesBaked" to 180,
                "supportSurfaceY" to item.y,
                "relativeBounds" to mapOf(
                    "min" to mapOf("x" to model.min.x * scale, "y" to model.min.y * scale, "z" to model.min.z * scale),
                    "max" to mapOf("x" to model.max.x * scale, "y" to model.max.y * scale, "z" to model.max.z * scale),
                ),
            ),
        )
    }

    private fun stockState(
        id: String,
        title: String,
        role: String,
        coordinateSpace: String,
        origin: String,
        geometry: OriginWorkshopWarehouseGeometry,
    ): Map<String, Any> {
        val pieces = geometry.blocks.map { piece ->
            mapOf<String, Any>(
                "key" to piece.key,
                "material" to piece.material.name,
                "x" to piece.x,
                "y" to piece.y,
                "z" to piece.z,
                "width" to piece.width,
                "height" to piece.height,
                "depth" to piece.depth,
                "rotationY" to 0.0,
            )
        } + geometry.items.map(::stockFurnitureMetadata)
        return mapOf(
            "id" to id,
            "title" to title,
            "role" to role,
            "coordinateSpace" to coordinateSpace,
            "coordinates" to mapOf(
                "space" to coordinateSpace,
                "origin" to origin,
                "yaw" to 0,
                "unit" to "block",
            ),
            "camera" to stockCameraMetadata(coordinateSpace == "warehouse-anchor-local"),
            "pieces" to pieces,
        )
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val dimensions = OriginWorkshopTableDimensions.DEFAULT
        val tuning = OriginWorkshopMachineTuning()
        val rules = OriginWorkshopGameRules()
        val states = mutableListOf<Map<String, Any>>()
        fun state(id: String, title: String, role: OriginWorkshopTableRole, machine: String? = null,
            progress: Double = 0.0, extras: List<Map<String, Any>> = emptyList(), finished: Boolean = false,
            hidden: Set<String> = emptySet(), guidance: Map<String, Any>? = null,
            extraMachines: List<Pair<String, Double>> = emptyList(), camera: Map<String, Any>? = null) {
            val poses = buildList {
                machine?.let { add(originWorkshopCraftMachinePose(it, progress, dimensions, tuning)) }
                extraMachines.forEach { (machineId, machineProgress) ->
                    add(originWorkshopCraftMachinePose(machineId, machineProgress, dimensions, tuning))
                }
            }
            val pieces = originWorkshopTablePieces(role, 0, dimensions, tuning)
                .filter { it.kind == OriginWorkshopTablePieceKind.BLOCK && it.key !in hidden }
                .filterNot { finished && it.key in originWorkshopFinishedAssemblyFixturePieceKeys(role) }
                .mapNotNull { piece ->
                    val motion = poses.firstNotNullOfOrNull { it.pieces[piece.key] }
                    val visible = motion?.visible ?: (piece.key !in ORIGIN_WORKSHOP_MACHINE_HIDDEN_IDLE_PIECES)
                    if (!visible) return@mapNotNull null
                    val offset = motion?.centerOffset ?: OriginWorkshopPoint(0.0, 0.0, 0.0)
                    val axis = motion?.rotationAxis
                    val restX = if (piece.key in setOf("assembler-hammer-arm", "assembler-hammer-head")) tuning.hammerRestAngleDegrees else 0.0
                    mapOf<String, Any>("key" to piece.key, "material" to piece.material.name,
                        "x" to piece.x + offset.x, "y" to piece.y + offset.y, "z" to piece.z + offset.z,
                        "width" to piece.width, "height" to piece.height * (motion?.scaleYFactor ?: 1.0), "depth" to piece.depth,
                        "rotationX" to if (axis == OriginWorkshopRotationAxis.X) motion.rotationDegrees else restX,
                        "rotationY" to if (axis == OriginWorkshopRotationAxis.Y) motion.rotationDegrees else 0.0,
                        "rotationZ" to if (axis == OriginWorkshopRotationAxis.Z) motion.rotationDegrees else 0.0)
                }
            val scene = mutableMapOf<String, Any>("id" to id, "title" to title, "role" to role.key, "pieces" to pieces + extras)
            guidance?.let { scene["guidance"] = it }
            camera?.let { scene["camera"] = it }
            states += scene
        }
        fun cuboid(key: String, material: String, x: Double, y: Double, z: Double, w: Double, h: Double, d: Double,
            ry: Double = 0.0) = mapOf<String, Any>("key" to key, "material" to material, "x" to x, "y" to y, "z" to z,
                "width" to w, "height" to h, "depth" to d, "rotationY" to ry)
        fun item(key: String, model: String, center: OriginWorkshopPoint, ry: Double = 0.0) =
            cuboid(key, "arc_workshop:$model", center.x, center.y, center.z, 1.0, 1.0, 1.0, ry)
        fun board(holes: Int, center: OriginWorkshopPoint) = listOf(item("player-board",
            if (holes == 0) "board_cut" else "board_drilled_$holes", center))
        val names = mapOf(OriginWorkshopTableRole.CARPENTER to "Столяр", OriginWorkshopTableRole.UPHOLSTERER to "Обивщик",
            OriginWorkshopTableRole.ASSEMBLER to "Сборщик", OriginWorkshopTableRole.FINISHER to "Отделочник")
        OriginWorkshopTableRole.entries.forEach { state(it.key, "${names[it]} · станок", it) }
        val recipe = originWorkshopGameRecipe(OriginWorkshopTableRole.CARPENTER, "furnituresplus:white_wooden_chair",
            dimensions, tuning, OriginWorkshopPoint(-7.5, 0.405, 0.30), rules)
        for ((id, stage) in listOf("load-saw" to OriginWorkshopGameStage.CARRY_RAW_TO_SAW,
            "load-drill" to OriginWorkshopGameStage.CARRY_BOARD_TO_DRILL, "assembly-slot" to OriginWorkshopGameStage.CARRY_BOARD_TO_JIG)) {
            val target = recipe.interactions.getValue(stage).target
            val marker = originWorkshopPlacementMarker().mapIndexed { i, p ->
                cuboid("target-$i", p.material.name, target.x + p.center.x, target.y + p.center.y, target.z + p.center.z,
                    p.size.x.toDouble(), p.size.y.toDouble(), p.size.z.toDouble()) + ("cue" to true)
            }
            state(id, "Столяр · ${stage.instruction}", OriginWorkshopTableRole.CARPENTER, extras = marker)
        }
        val cutAt = recipe.interactions.getValue(OriginWorkshopGameStage.PICK_SAWN_BOARD).target
        val offcuts = listOf(0.30, 0.58).mapIndexed { i, z ->
            item("offcut-$i", "board_offcut", OriginWorkshopPoint(cutAt.x - 0.60, cutAt.y - 0.13, cutAt.z + z))
        }
        state("raw-board", "Столяр · целая заготовка", OriginWorkshopTableRole.CARPENTER, extras = listOf(item("player-board", "board_raw", cutAt)))
        state("one-cut", "Столяр · первый распил", OriginWorkshopTableRole.CARPENTER, extras = listOf(item("player-board", "board_cut_once", cutAt)) + offcuts.take(1))
        state("two-cuts", "Столяр · два распила и обрезки", OriginWorkshopTableRole.CARPENTER, "saw", 1.0, board(0, cutAt) + offcuts)
        val drill = recipe.interactions.getValue(OriginWorkshopGameStage.CARRY_BOARD_TO_DRILL).target
        for (holes in 1..3) state("holes-$holes", "Столяр · отверстий: $holes", OriginWorkshopTableRole.CARPENTER, "drill", 1.0,
            board(holes, drill.copy(x = drill.x - 0.23 * (holes - 1))) + offcuts)
        state("chair-clearance", "Столяр · место готового стула (без модели ItemsAdder)", OriginWorkshopTableRole.CARPENTER, finished = true)
        val carryAnchorZ = -2.2
        fun carryCamera(sneaking: Boolean) = mapOf<String, Any>(
            "id" to "carry-player-eye",
            "position" to pointMetadata(OriginWorkshopPoint(0.0, if (sneaking) 1.32 else 1.62, -3.65)),
            "target" to pointMetadata(OriginWorkshopPoint(0.0, if (sneaking) 0.72 else 1.02, carryAnchorZ)),
            "eyeHeight" to if (sneaking) 1.32 else 1.62,
            "fovDegrees" to 70.0,
            "near" to 0.05,
            "far" to 8.0,
            "stance" to if (sneaking) "sneaking" else "standing",
            "coordinateSpace" to "table-local",
            "source" to "local player-eye view centered on the carried workpiece and mannequin",
        )
        fun carryMannequin(sneaking: Boolean): List<Map<String, Any>> {
            val headCenterY = if (sneaking) 1.35 else 1.65
            val torsoCenterY = if (sneaking) 0.89 else 1.04
            val torsoHeight = if (sneaking) 0.42 else 0.72
            return listOf(
                cuboid("mannequin-head", "TERRACOTTA", 0.0, headCenterY, carryAnchorZ, 0.50, 0.50, 0.50),
                cuboid("mannequin-body", "BLUE_TERRACOTTA", 0.0, torsoCenterY, carryAnchorZ, 0.50, torsoHeight, 0.25),
                cuboid("mannequin-arm-right", "BLUE_TERRACOTTA", -0.36, torsoCenterY, carryAnchorZ, 0.22, torsoHeight, 0.25),
                cuboid("mannequin-arm-left", "BLUE_TERRACOTTA", 0.36, torsoCenterY, carryAnchorZ, 0.22, torsoHeight, 0.25),
                cuboid("mannequin-leg-right", "GRAY_TERRACOTTA", -0.13, 0.34, carryAnchorZ, 0.24, 0.68, 0.25),
                cuboid("mannequin-leg-left", "GRAY_TERRACOTTA", 0.13, 0.34, carryAnchorZ, 0.24, 0.68, 0.25),
            )
        }
        fun carryCuboids(key: String, geometry: List<OriginWorkshopWorkpiecePiece>, pose: OriginWorkshopCarryPose): List<Map<String, Any>> {
            val euler = Vector3f().also { pose.rotation.getEulerAnglesXYZ(it) }
            return geometry.mapIndexed { index, piece ->
                val offset = pose.rotation.transform(Vector3f(
                    piece.center.x.toFloat(), piece.center.y.toFloat(), piece.center.z.toFloat(),
                ))
                cuboid(
                    "$key-$index", piece.material.name,
                    pose.center.x + offset.x,
                    pose.center.y + offset.y,
                    carryAnchorZ + pose.center.z + offset.z,
                    piece.size.x.toDouble(), piece.size.y.toDouble(), piece.size.z.toDouble(),
                ) + mapOf(
                    "rotationX" to Math.toDegrees(euler.x.toDouble()),
                    "rotationY" to Math.toDegrees(euler.y.toDouble()),
                    "rotationZ" to Math.toDegrees(euler.z.toDouble()),
                )
            }
        }
        val rawBoard = listOf(OriginWorkshopWorkpiecePiece(
            OriginWorkshopPoint(0.0, 0.0, 0.0),
            recipe.partSizes.getValue(OriginWorkshopGameAction.PICK_STOCK),
            recipe.materials.getValue(OriginWorkshopGameAction.PICK_STOCK),
        ))
        fun carriedBoard(sneaking: Boolean): List<Map<String, Any>> {
            val pose = originWorkshopCarryPose(0.0, sneaking, rawBoard)
            val euler = Vector3f().also { pose.rotation.getEulerAnglesXYZ(it) }
            return listOf(item(
                "carried-raw-board", "board_raw",
                OriginWorkshopPoint(pose.center.x, pose.center.y, carryAnchorZ + pose.center.z),
                Math.toDegrees(euler.y.toDouble()),
            ))
        }
        val upholstery = originWorkshopGameRecipe(
            OriginWorkshopTableRole.UPHOLSTERER,
            "preview:carry-upholstery",
            dimensions,
            tuning,
            OriginWorkshopPoint(-7.5, 0.405, 0.30),
            rules,
        )
        val cloth = originWorkshopUpholsteryPieces(stretchedEdges = 2)
        val tabletop = originWorkshopJoinedTopPieces(edges = 2, joints = 2)
        val leg = originWorkshopAssemblerLegPieces(fastened = false)
        val padding = listOf(OriginWorkshopWorkpiecePiece(
            OriginWorkshopPoint(0.0, 0.0, 0.0),
            upholstery.partSizes.getValue(OriginWorkshopGameAction.PICK_PADDING),
            upholstery.materials.getValue(OriginWorkshopGameAction.PICK_PADDING),
        ))
        fun carryState(id: String, title: String, role: OriginWorkshopTableRole,
            geometry: List<OriginWorkshopWorkpiecePiece>, sneaking: Boolean,
            modelBoard: Boolean = false) {
            val pose = originWorkshopCarryPose(0.0, sneaking, geometry)
            val prop = if (modelBoard) carriedBoard(sneaking) else carryCuboids(id, geometry, pose)
            state(
                id, title, role,
                extras = carryMannequin(sneaking) + prop,
                camera = carryCamera(sneaking),
            )
        }
        carryState("carry-raw-board-standing", "Столяр · доска на плече, стоя", OriginWorkshopTableRole.CARPENTER, rawBoard, false, modelBoard = true)
        carryState("carry-raw-board-sneaking", "Столяр · доска на плече, присев", OriginWorkshopTableRole.CARPENTER, rawBoard, true, modelBoard = true)
        carryState("carry-cloth-standing", "Обивщик · ткань сбоку, стоя", OriginWorkshopTableRole.UPHOLSTERER, cloth, false)
        carryState("carry-cloth-sneaking", "Обивщик · ткань сбоку, присев", OriginWorkshopTableRole.UPHOLSTERER, cloth, true)
        carryState("carry-tabletop-sneaking", "Сборщик · столешница сбоку, присев", OriginWorkshopTableRole.ASSEMBLER, tabletop, true)
        carryState("carry-leg-standing", "Сборщик · ножка у корпуса, стоя", OriginWorkshopTableRole.ASSEMBLER, leg, false)
        carryState("carry-padding-standing", "Обивщик · набивка у корпуса, стоя", OriginWorkshopTableRole.UPHOLSTERER, padding, false)
        state("press", "Обивщик · прижим в нижней точке", OriginWorkshopTableRole.UPHOLSTERER, "press", 0.65,
            listOf(cuboid("player-cloth", "RED_WOOL", tuning.pressCenterX, dimensions.height + 0.74, tuning.pressCenterZ, 0.64, 0.05, 0.46)))
        state("hammer", "Сборщик · удар молота", OriginWorkshopTableRole.ASSEMBLER, "anvil", 0.65,
            listOf(cuboid("player-tabletop", "SPRUCE_PLANKS", tuning.anvilCenterX, dimensions.height + 0.67, tuning.anvilCenterZ, 0.68, 0.06, 0.30)))
        state("panel", "Отделочник · панель на столе", OriginWorkshopTableRole.FINISHER, "finish", 0.25)
        fun prop(key: String, geometry: List<OriginWorkshopWorkpiecePiece>, center: OriginWorkshopPoint,
            flip: Boolean = false, rotateY: Boolean = false): List<Map<String, Any>> = geometry.mapIndexed { i, piece ->
            val sign = if (flip) -1.0 else 1.0
            cuboid("$key-$i", piece.material.name, center.x + if (rotateY) -piece.center.x else piece.center.x,
                center.y + sign * piece.center.y, center.z + if (rotateY) -piece.center.z else sign * piece.center.z,
                piece.size.x.toDouble(), piece.size.y.toDouble(), piece.size.z.toDouble()) +
                mapOf("rotationX" to if (flip) 180.0 else 0.0, "rotationY" to if (rotateY) 180.0 else 0.0)
        }
        fun turnedProp(
            key: String,
            geometry: List<OriginWorkshopWorkpiecePiece>,
            from: OriginWorkshopPoint,
            to: OriginWorkshopPoint,
            flip: Boolean,
            lift: Double,
            progress: Double,
        ): List<Map<String, Any>> {
            val pose = originWorkshopTurnPose(from, to, Quaternionf(), flip, lift, progress)
            val euler = Vector3f().also { pose.rotation.getEulerAnglesXYZ(it) }
            return geometry.mapIndexed { index, piece ->
                val localCenter = Vector3f(piece.center.x.toFloat(), piece.center.y.toFloat(), piece.center.z.toFloat())
                pose.rotation.transform(localCenter)
                cuboid(
                    "$key-$index", piece.material.name,
                    pose.center.x + localCenter.x, pose.center.y + localCenter.y, pose.center.z + localCenter.z,
                    piece.size.x.toDouble(), piece.size.y.toDouble(), piece.size.z.toDouble(),
                ) + mapOf(
                    "rotationX" to Math.toDegrees(euler.x.toDouble()),
                    "rotationY" to Math.toDegrees(euler.y.toDouble()),
                    "rotationZ" to Math.toDegrees(euler.z.toDouble()),
                )
            }
        }
        fun cue(recipe: OriginWorkshopGameRecipe, stage: OriginWorkshopGameStage, hovered: Boolean = false): List<Map<String, Any>> {
            val interaction = recipe.interactions.getValue(stage)
            val targetFaceDepth = if (
                recipe.role == OriginWorkshopTableRole.ASSEMBLER &&
                interaction.action in setOf(OriginWorkshopGameAction.TIGHTEN_LEFT, OriginWorkshopGameAction.TIGHTEN_RIGHT)
            ) originWorkshopAssemblerLegPieces(fastened = true).last().size.z.toDouble() else null
            return prop("cue", originWorkshopPlacementMarker(interaction.action, targetFaceDepth), interaction.target)
                .map { it + mapOf("cue" to true, "nativeGlow" to true, "hovered" to hovered) +
                    if (hovered) mapOf("material" to "WHITE_CONCRETE") else emptyMap() }
        }
        fun recipeFor(role: OriginWorkshopTableRole) = originWorkshopGameRecipe(role, "preview:${role.key}",
            dimensions, tuning, OriginWorkshopPoint(-7.5, 0.405, 0.30), rules)
        val upholster = recipeFor(OriginWorkshopTableRole.UPHOLSTERER)
        val pressAt = upholster.interactions.getValue(OriginWorkshopGameStage.UPHOLSTER_PLACE_FABRIC).target
        state("press-cue", "Обивщик · нижняя ручка и первый шов", OriginWorkshopTableRole.UPHOLSTERER,
            extras = prop("cloth", originWorkshopUpholsteryPieces(2, 0), pressAt) + cue(upholster, OriginWorkshopGameStage.UPHOLSTER_START_PRESS))
        state("press-cue-hover", "Обивщик · наведение на ручку", OriginWorkshopTableRole.UPHOLSTERER,
            extras = prop("cloth", originWorkshopUpholsteryPieces(2, 0), pressAt) + cue(upholster, OriginWorkshopGameStage.UPHOLSTER_START_PRESS, hovered = true))
        val sewingStart = originWorkshopSewingClothPoint(dimensions, 0.0)
        val sewingMid = originWorkshopSewingClothPoint(dimensions, 0.5)
        val sewingFinish = originWorkshopSewingClothPoint(dimensions, 1.0)
        state("sewing-feed-ready", "Обивщик · ткань уложена под лапку", OriginWorkshopTableRole.UPHOLSTERER,
            extras = prop("sewing-cloth", originWorkshopUpholsteryPieces(stretchedEdges = 2), sewingStart) +
                cue(upholster, OriginWorkshopGameStage.UPHOLSTER_LOWER_SEWING_FOOT),
            hidden = setOf("upholsterer-sewing-fabric"),
            guidance = guidanceMetadata(dimensions, upholster, OriginWorkshopGameStage.UPHOLSTER_LOWER_SEWING_FOOT),
            extraMachines = listOf("sewing-foot" to 1.0))
        state("sewing-first-pass", "Обивщик · первая кромка под иглой", OriginWorkshopTableRole.UPHOLSTERER,
            "sewing", 0.5,
            extras = prop("sewing-cloth", originWorkshopUpholsteryPieces(stretchedEdges = 2), sewingMid),
            hidden = setOf("upholsterer-sewing-fabric"),
            guidance = guidanceMetadata(dimensions, upholster, OriginWorkshopGameStage.UPHOLSTER_SEWING, progressPercent = 50))
        state("sewing-turnover", "Обивщик · первая кромка прошита, разворот ткани", OriginWorkshopTableRole.UPHOLSTERER,
            "sewing-foot", 0.0,
            extras = prop("sewing-cloth", originWorkshopUpholsteryPieces(stretchedEdges = 2, seams = 1), sewingFinish) +
                cue(upholster, OriginWorkshopGameStage.UPHOLSTER_TURN_FABRIC),
            hidden = setOf("upholsterer-sewing-fabric"),
            guidance = guidanceMetadata(dimensions, upholster, OriginWorkshopGameStage.UPHOLSTER_TURN_FABRIC))
        val seamTurnFrom = originWorkshopSewingClothPoint(dimensions, 1.0)
        val seamTurnTo = originWorkshopSewingClothPoint(dimensions, 0.0)
        val firstSeam = originWorkshopUpholsteryPieces(stretchedEdges = 2, seams = 1)
        for ((suffix, progress) in listOf("start" to 0.0, "mid" to 0.5, "end" to 1.0)) {
            state(
                "sewing-seam1-cover-turn-$suffix",
                "Обивщик · разворот ткани со швом №1 ${when (suffix) { "start" -> "до"; "mid" -> "90°"; else -> "после" }}",
                OriginWorkshopTableRole.UPHOLSTERER,
                "sewing-foot", if (progress == 1.0) 1.0 else 0.0,
                extras = turnedProp("cloth-turn", firstSeam, seamTurnFrom, seamTurnTo,
                    flip = false, lift = 0.025, progress = progress),
                hidden = setOf("upholsterer-sewing-fabric"),
            )
        }
        state("sewing-second-pass", "Обивщик · вторая кромка после разворота", OriginWorkshopTableRole.UPHOLSTERER,
            "sewing", 0.5,
            extras = prop("sewing-cloth", originWorkshopUpholsteryPieces(stretchedEdges = 2, seams = 1), sewingMid, rotateY = true),
            hidden = setOf("upholsterer-sewing-fabric"),
            guidance = guidanceMetadata(dimensions, upholster, OriginWorkshopGameStage.UPHOLSTER_SEWING_SECOND, progressPercent = 50))
        val cushionAt = upholster.interactions.getValue(OriginWorkshopGameStage.UPHOLSTER_PLACE_COVER).target
        val hiddenCushion = setOf("upholsterer-cushion-cover", "upholsterer-cushion-padding")
        for (tucked in 0..2) state("cushion-$tucked", "Обивщик · набивка и края: $tucked/2", OriginWorkshopTableRole.UPHOLSTERER,
            extras = prop("cushion", originWorkshopUpholsteryPieces(2, 2, true, tucked, if (tucked == 2) 2 else 0), cushionAt),
            hidden = hiddenCushion)
        val assembler = recipeFor(OriginWorkshopTableRole.ASSEMBLER)
        val assemblyAt = assembler.interactions.getValue(OriginWorkshopGameStage.ASSEMBLER_PLACE_ASSEMBLY_TOP).target
        val assembledLegs = listOf(OriginWorkshopGameStage.ASSEMBLER_PLACE_LEFT_LEG, OriginWorkshopGameStage.ASSEMBLER_PLACE_RIGHT_LEG).flatMap { stage ->
            prop(stage.name, originWorkshopAssemblerLegPieces(fastened = stage == OriginWorkshopGameStage.ASSEMBLER_PLACE_LEFT_LEG),
                assembler.interactions.getValue(stage).target)
        }
        state("joined-top", "Сборщик · соединения после двух ударов", OriginWorkshopTableRole.ASSEMBLER,
            extras = prop("top", originWorkshopJoinedTopPieces(2, 2), assembler.interactions.getValue(OriginWorkshopGameStage.ASSEMBLER_PLACE_ANVIL).target),
            guidance = guidanceMetadata(dimensions, assembler, OriginWorkshopGameStage.ASSEMBLER_PLACE_ANVIL))
        val assemblerTurnAt = assembler.interactions.getValue(OriginWorkshopGameStage.ASSEMBLER_ROTATE_TOP).target
        val onePlanedEdge = originWorkshopJoinedTopPieces(edges = 1)
        for ((suffix, progress) in listOf("start" to 0.0, "mid" to 0.5, "end" to 1.0)) {
            state(
                "assembler-planed-edge-turn-$suffix",
                "Сборщик · поворот столешницы, кромка ${when (suffix) { "start" -> "до"; "mid" -> "90°"; else -> "после" }}",
                OriginWorkshopTableRole.ASSEMBLER,
                "vise", 1.0,
                extras = turnedProp("top-turn", onePlanedEdge, assemblerTurnAt, assemblerTurnAt,
                    flip = false, lift = 0.18, progress = progress),
            )
        }
        state("table-assembly", "Сборщик · столешница и обе ножки", OriginWorkshopTableRole.ASSEMBLER,
            extras = prop("top", originWorkshopJoinedTopPieces(2, 2), assemblyAt) + assembledLegs + cue(assembler, OriginWorkshopGameStage.ASSEMBLER_TIGHTEN_RIGHT),
            guidance = guidanceMetadata(dimensions, assembler, OriginWorkshopGameStage.ASSEMBLER_TIGHTEN_RIGHT))
        val assemblerStage14 = OriginWorkshopGameStage.ASSEMBLER_PICK_LEFT_LEG
        state("assembler-stage14", "Сборщик · 14/19 · ${assemblerStage14.instruction}", OriginWorkshopTableRole.ASSEMBLER,
            extras = prop("top", originWorkshopJoinedTopPieces(2, 2), assemblyAt, flip = true) + cue(assembler, assemblerStage14),
            guidance = guidanceMetadata(dimensions, assembler, assemblerStage14))
        val finisher = recipeFor(OriginWorkshopTableRole.FINISHER)
        state("rack-cue", "Отделочник · нижняя ручка сушилки", OriginWorkshopTableRole.FINISHER,
            extras = cue(finisher, OriginWorkshopGameStage.FINISHER_START_PANEL))
        val panelAt = OriginWorkshopPoint(0.0, dimensions.height + 0.04, -0.45)
        val panelTurnAt = panelAt
        val frontSandedPanel = originWorkshopFinishingPanelPieces(sandedFront = 3)
        for ((suffix, progress) in listOf("start" to 0.0, "mid" to 0.5, "end" to 1.0)) {
            state(
                "finisher-front-back-flip-$suffix",
                "Отделочник · переворот панели ${when (suffix) { "start" -> "до"; "mid" -> "90°"; else -> "после" }}",
                OriginWorkshopTableRole.FINISHER,
                extras = turnedProp("panel-turn", frontSandedPanel, panelTurnAt, panelTurnAt,
                    flip = true, lift = 0.36, progress = progress),
                hidden = setOf("finisher-drying-panel-center"),
            )
        }
        state("coated", "Отделочник · три полосы покрытия", OriginWorkshopTableRole.FINISHER, "finish", 0.25,
            extras = prop("panel", originWorkshopFinishingPanelPieces(sandedFront = 3, coatedFront = 3), panelAt),
            hidden = setOf("finisher-drying-panel-center"))
        for (back in listOf(false, true)) for (coat in 0..3) state("finish-${if (back) "back" else "front"}-$coat",
            "Отделочник · ${if (back) "обратная" else "лицевая"} сторона, покрыто: $coat/3", OriginWorkshopTableRole.FINISHER,
            extras = prop("panel", originWorkshopFinishingPanelPieces(3, if (back) 3 else 0,
                if (back) 3 else coat, if (back) coat else 0), panelAt, back) + cue(finisher, when (coat) {
                    0 -> OriginWorkshopGameStage.FINISHER_COAT_NEAR
                    1 -> OriginWorkshopGameStage.FINISHER_COAT_CENTER
                    2 -> OriginWorkshopGameStage.FINISHER_COAT_FAR
                    else -> if (back) OriginWorkshopGameStage.FINISHER_HANG_PANEL else OriginWorkshopGameStage.FINISHER_FLIP_FOR_BACK_COAT
                }),
            hidden = setOf("finisher-drying-panel-center"))
        for (role in OriginWorkshopTableRole.entries) {
            val product = when (role) {
                OriginWorkshopTableRole.UPHOLSTERER -> "furnituresplus:red_wooden_sofa_single"
                OriginWorkshopTableRole.ASSEMBLER -> "furnituresplus:white_wooden_diningtable"
                else -> "furnituresplus:white_wooden_chair"
            }
            val at = originWorkshopResultAnchor(role, product, dimensions)
            state("result-${role.key}", "${names[role]} · готовая мебель на свободной площадке", role,
                extras = listOf(cuboid("finished-furniture", product, at.x, at.y, at.z, 1.0, 1.0, 1.0)),
                finished = role == OriginWorkshopTableRole.CARPENTER,
                hidden = if (role == OriginWorkshopTableRole.UPHOLSTERER) hiddenCushion else emptySet())
        }
        val stockTitles = mapOf(
            OriginWorkshopTableRole.CARPENTER to "Столяр · материалы под навесом",
            OriginWorkshopTableRole.UPHOLSTERER to "Обивщик · рулоны ткани под навесом",
            OriginWorkshopTableRole.ASSEMBLER to "Сборщик · заготовки под навесом",
            OriginWorkshopTableRole.FINISHER to "Отделочник · панели и покрытие под навесом",
        )
        OriginWorkshopTableRole.entries.forEach { role ->
            states += stockState(
                id = "station-stock-${role.key}",
                title = stockTitles.getValue(role),
                role = role.key,
                coordinateSpace = "stock-anchor-local",
                origin = "station stock spawn anchor at floor level; station table yaw is not applied",
                geometry = originWorkshopStationStockGeometry(role),
            )
        }
        states += stockState(
            id = "warehouse",
            title = "Склад · три паллеты, мебель и материалы",
            role = "warehouse",
            coordinateSpace = "warehouse-anchor-local",
            origin = "warehouse spawn anchor at floor level",
            geometry = originWorkshopWarehouseGeometry(),
        )
        val output = Path.of(args.single())
        Files.createDirectories(output.parent)
        Files.writeString(output, GsonBuilder().setPrettyPrinting().create().toJson(mapOf(
            "states" to states,
            "coordinates" to mapOf("space" to "table-local", "origin" to "station base origin", "yaw" to 0, "unit" to "block"),
            "camera" to frontEdgeCameraMetadata(dimensions, assembler, OriginWorkshopGameStage.ASSEMBLER_PLACE_ANVIL),
            "cameras" to mapOf("assembler-stage14" to stockCameraMetadata(dimensions, assembler, assemblerStage14)),
            "evidence" to "Production geometry, processing poses, guidance anchors and solid full-brightness cues; native Minecraft glow silhouette is not simulated; local yaw 0; exact vanilla and ItemsAdder face UVs. Finished furniture uses NONE, scale 0.65, native Y180 and analyzed tabletop contact. Finished anchors are checked against canonical machine assemblies; the carpenter fixture is hidden. No live world geometry is rendered. Workshop board native Y180 and compensation cancel.")))
        println("WORKSHOP_PREVIEW states=${states.size} output=$output")
    }
}
