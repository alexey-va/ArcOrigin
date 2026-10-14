package ru.arc.origin

import org.bukkit.Material

private val ASSEMBLER_TABLETOP_SIZE = OriginWorkshopGamePartSize(0.68f, 0.06f, 0.30f)
private val ASSEMBLER_LEG_SIZE = OriginWorkshopGamePartSize(0.14f, 0.52f, 0.14f)
private val ASSEMBLER_LEG_FASTENER_SIZE = OriginWorkshopGamePartSize(0.035f, 0.035f, 0.035f)
private const val ASSEMBLER_LEG_FASTENER_BASE_CLEARANCE = 0.0025
private val ASSEMBLER_LEG_FASTENER_OFFSET = OriginWorkshopPoint(
    0.0,
    -ASSEMBLER_LEG_SIZE.y / 2.0 + ASSEMBLER_LEG_FASTENER_SIZE.y / 2.0 + ASSEMBLER_LEG_FASTENER_BASE_CLEARANCE,
    -ASSEMBLER_LEG_SIZE.z / 2.0 - ASSEMBLER_LEG_FASTENER_SIZE.z / 2.0,
)

/** The finished assembly adds a small iron pin near the leg's tabletop attachment end. */
internal fun originWorkshopAssemblerLegPieces(fastened: Boolean): List<OriginWorkshopWorkpiecePiece> = buildList {
    add(OriginWorkshopWorkpiecePiece(OriginWorkshopPoint(0.0, 0.0, 0.0), ASSEMBLER_LEG_SIZE, Material.STRIPPED_SPRUCE_LOG))
    if (fastened) add(
        OriginWorkshopWorkpiecePiece(
            ASSEMBLER_LEG_FASTENER_OFFSET,
            ASSEMBLER_LEG_FASTENER_SIZE,
            Material.IRON_BLOCK,
        ),
    )
}

/** A deliberate player input at a real station-local point. */
internal data class OriginWorkshopRecipeInteraction(
    val action: OriginWorkshopGameAction,
    val target: OriginWorkshopPoint,
    val control: String?,
    val nextStage: OriginWorkshopGameStage,
)

/** A machine-owned operation; player input is required before and after this stage. */
internal data class OriginWorkshopTimedStep(
    val nextStage: OriginWorkshopGameStage,
    val durationTicks: Long,
    val machine: String?,
    val target: OriginWorkshopPoint,
    val control: String?,
)

/** Data for the one shared game controller. Materials are visual packet props, never inventory items. */
internal data class OriginWorkshopGameRecipe(
    val role: OriginWorkshopTableRole,
    val productId: String,
    val title: String,
    val totalSteps: Int,
    val initialStage: OriginWorkshopGameStage,
    val resultAnchor: OriginWorkshopPoint,
    val interactions: Map<OriginWorkshopGameStage, OriginWorkshopRecipeInteraction>,
    val timedStages: Map<OriginWorkshopGameStage, OriginWorkshopTimedStep>,
    val materials: Map<OriginWorkshopGameAction, Material>,
    val partSizes: Map<OriginWorkshopGameAction, OriginWorkshopGamePartSize>,
) {
    init {
        require(productId.matches(Regex("[a-z0-9_:-]+")))
        require(title.isNotBlank() && totalSteps in 1..OriginWorkshopGameStage.TOTAL_STEPS)
        require(interactions.keys.intersect(timedStages.keys).isEmpty())
        require(materials.keys == partSizes.keys)
    }
}

/** Shared single-controller recipes for the four staffed furniture stations. */
internal fun originWorkshopGameRecipe(
    role: OriginWorkshopTableRole,
    productId: String,
    dimensions: OriginWorkshopTableDimensions,
    tuning: OriginWorkshopMachineTuning,
    stock: OriginWorkshopPoint,
    rules: OriginWorkshopGameRules,
    workpieceRenderer: OriginWorkshopWorkpieceRenderer = OriginWorkshopWorkpieceRenderer.MODEL,
): OriginWorkshopGameRecipe {
    fun point(x: Double, y: Double, z: Double) = OriginWorkshopPoint(x, y, z)
    fun interaction(
        stage: OriginWorkshopGameStage,
        action: OriginWorkshopGameAction,
        target: OriginWorkshopPoint,
        control: String?,
        next: OriginWorkshopGameStage,
    ) = stage to OriginWorkshopRecipeInteraction(action, target, control, next)
    fun timed(
        stage: OriginWorkshopGameStage,
        next: OriginWorkshopGameStage,
        duration: Long,
        machine: String?,
        target: OriginWorkshopPoint,
        control: String?,
    ) = stage to OriginWorkshopTimedStep(next, duration, machine, target, control)

    val h = dimensions.height
    val carpenterBoard = OriginWorkshopGamePartSize(1.0f, 0.08f, 0.22f)
    val leg = ASSEMBLER_LEG_SIZE
    val interactions: Map<OriginWorkshopGameStage, OriginWorkshopRecipeInteraction>
    val timed: Map<OriginWorkshopGameStage, OriginWorkshopTimedStep>
    val materials: Map<OriginWorkshopGameAction, Material>
    val partSizes: Map<OriginWorkshopGameAction, OriginWorkshopGamePartSize>
    val totalSteps: Int
    val initialStage: OriginWorkshopGameStage
    val title: String
    val resultAnchor = originWorkshopResultAnchor(role, productId, dimensions)

    when (role) {
        OriginWorkshopTableRole.CARPENTER -> {
            val boardZ = tuning.sawPivotZ + 0.075
            val sawInput = point(tuning.sawFeedStartX, h + 0.17, boardZ)
            val sawOutput = point(tuning.sawFeedStartX + tuning.sawFeedDistance, h + 0.17, boardZ)
            val sawControl = point(-1.65, h + 0.34, -0.70)
            val drillHoleEdge = if (workpieceRenderer == OriginWorkshopWorkpieceRenderer.CUBES) 0.24 else 0.23
            val drillInput = point(drillHoleEdge, h + 0.14, -0.55)
            val drillCenter = point(0.0, h + 0.14, -0.55)
            val drillLast = point(-drillHoleEdge, h + 0.14, -0.55)
            val drillControl = point(0.30, h + 0.58, -0.42)
            val assembly = point(1.35, h + 0.035, -0.35)
            val leftLeg = point(1.30, h + 0.07, 0.50)
            val rightLeg = point(1.75, h + 0.07, 0.50)
            val clampLeft = point(0.95, h + 0.35, -0.65)
            val clampRight = point(1.95, h + 0.35, -0.65)
            interactions = listOf(
                interaction(OriginWorkshopGameStage.STOCK, OriginWorkshopGameAction.PICK_STOCK, stock, "stock", OriginWorkshopGameStage.CARRY_RAW_TO_SAW),
                interaction(OriginWorkshopGameStage.CARRY_RAW_TO_SAW, OriginWorkshopGameAction.PLACE_SAW, sawInput, null, OriginWorkshopGameStage.START_SAW),
                interaction(OriginWorkshopGameStage.START_SAW, OriginWorkshopGameAction.ACTIVATE_SAW, sawControl, "saw", OriginWorkshopGameStage.SAWING),
                interaction(OriginWorkshopGameStage.SAW_REPOSITION, OriginWorkshopGameAction.REPOSITION_SAW, sawOutput, null, OriginWorkshopGameStage.START_SAW_SECOND),
                interaction(OriginWorkshopGameStage.START_SAW_SECOND, OriginWorkshopGameAction.ACTIVATE_SAW, sawControl, "saw", OriginWorkshopGameStage.SAWING_SECOND),
                interaction(OriginWorkshopGameStage.PICK_SAWN_BOARD, OriginWorkshopGameAction.PICK_SAWN_BOARD, sawOutput, null, OriginWorkshopGameStage.CARRY_BOARD_TO_DRILL),
                interaction(OriginWorkshopGameStage.CARRY_BOARD_TO_DRILL, OriginWorkshopGameAction.PLACE_DRILL, drillInput, null, OriginWorkshopGameStage.START_DRILL),
                interaction(OriginWorkshopGameStage.START_DRILL, OriginWorkshopGameAction.ACTIVATE_DRILL, drillControl, "drill", OriginWorkshopGameStage.DRILLING),
                interaction(OriginWorkshopGameStage.DRILL_ALIGN_CENTER, OriginWorkshopGameAction.ALIGN_DRILL_CENTER, drillCenter, null, OriginWorkshopGameStage.START_DRILL_SECOND),
                interaction(OriginWorkshopGameStage.START_DRILL_SECOND, OriginWorkshopGameAction.ACTIVATE_DRILL, drillControl, "drill", OriginWorkshopGameStage.DRILLING_SECOND),
                interaction(OriginWorkshopGameStage.DRILL_ALIGN_LAST, OriginWorkshopGameAction.ALIGN_DRILL_LAST, drillLast, null, OriginWorkshopGameStage.START_DRILL_THIRD),
                interaction(OriginWorkshopGameStage.START_DRILL_THIRD, OriginWorkshopGameAction.ACTIVATE_DRILL, drillControl, "drill", OriginWorkshopGameStage.DRILLING_THIRD),
                interaction(OriginWorkshopGameStage.PICK_DRILLED_BOARD, OriginWorkshopGameAction.PICK_DRILLED_BOARD, drillLast, null, OriginWorkshopGameStage.CARRY_BOARD_TO_JIG),
                interaction(OriginWorkshopGameStage.CARRY_BOARD_TO_JIG, OriginWorkshopGameAction.PLACE_JIG, assembly, null, OriginWorkshopGameStage.LEG_LEFT),
                interaction(OriginWorkshopGameStage.LEG_LEFT, OriginWorkshopGameAction.PICK_LEFT_LEG, leftLeg, "leg-left", OriginWorkshopGameStage.CARRY_LEG_LEFT),
                interaction(OriginWorkshopGameStage.CARRY_LEG_LEFT, OriginWorkshopGameAction.PLACE_LEFT_LEG, point(1.13, h + 0.33, -0.35), null, OriginWorkshopGameStage.LEG_RIGHT),
                interaction(OriginWorkshopGameStage.LEG_RIGHT, OriginWorkshopGameAction.PICK_RIGHT_LEG, rightLeg, "leg-right", OriginWorkshopGameStage.CARRY_LEG_RIGHT),
                interaction(OriginWorkshopGameStage.CARRY_LEG_RIGHT, OriginWorkshopGameAction.PLACE_RIGHT_LEG, point(1.57, h + 0.33, -0.35), null, OriginWorkshopGameStage.CLAMP_LEFT),
                interaction(OriginWorkshopGameStage.CLAMP_LEFT, OriginWorkshopGameAction.TIGHTEN_LEFT, clampLeft, "clamp-left", OriginWorkshopGameStage.CLAMPING_LEFT),
                interaction(OriginWorkshopGameStage.CLAMP_RIGHT, OriginWorkshopGameAction.TIGHTEN_RIGHT, clampRight, "clamp-right", OriginWorkshopGameStage.CLAMPING_RIGHT),
            ).toMap()
            timed = listOf(
                timed(OriginWorkshopGameStage.SAWING, OriginWorkshopGameStage.SAW_REPOSITION, rules.sawTicks, "saw", sawOutput, null),
                timed(OriginWorkshopGameStage.SAWING_SECOND, OriginWorkshopGameStage.PICK_SAWN_BOARD, rules.sawTicks, "saw", sawOutput, null),
                timed(OriginWorkshopGameStage.DRILLING, OriginWorkshopGameStage.DRILL_ALIGN_CENTER, rules.drillTicks, "drill", drillInput, null),
                timed(OriginWorkshopGameStage.DRILLING_SECOND, OriginWorkshopGameStage.DRILL_ALIGN_LAST, rules.drillTicks, "drill", drillCenter, null),
                timed(OriginWorkshopGameStage.DRILLING_THIRD, OriginWorkshopGameStage.PICK_DRILLED_BOARD, rules.drillTicks, "drill", drillLast, null),
                timed(OriginWorkshopGameStage.CLAMPING_LEFT, OriginWorkshopGameStage.CLAMP_RIGHT, rules.clampTicks, "clamp-left", clampLeft, null),
                timed(OriginWorkshopGameStage.CLAMPING_RIGHT, OriginWorkshopGameStage.FINISHING, rules.clampTicks, "clamp-right", clampRight, null),
                timed(OriginWorkshopGameStage.FINISHING, OriginWorkshopGameStage.REWARDING, rules.chairHoldTicks, null, assembly, null),
            ).toMap()
            materials = mapOf(
                OriginWorkshopGameAction.PICK_STOCK to Material.OAK_PLANKS,
                OriginWorkshopGameAction.PICK_LEFT_LEG to Material.STRIPPED_SPRUCE_LOG,
                OriginWorkshopGameAction.PICK_RIGHT_LEG to Material.STRIPPED_SPRUCE_LOG,
            )
            partSizes = mapOf(
                OriginWorkshopGameAction.PICK_STOCK to carpenterBoard,
                OriginWorkshopGameAction.PICK_LEFT_LEG to leg,
                OriginWorkshopGameAction.PICK_RIGHT_LEG to leg,
            )
            totalSteps = 21
            initialStage = OriginWorkshopGameStage.STOCK
            title = "Столярная мастерская · белый стул"
        }
        OriginWorkshopTableRole.UPHOLSTERER -> {
            val press = point(tuning.pressCenterX, h + 0.74, tuning.pressCenterZ)
            val fabricBed = point(tuning.pressCenterX, h + 0.74, tuning.pressCenterZ)
            val pressHandle = point(tuning.pressCenterX - 0.46, h + 0.42, tuning.pressCenterZ - 0.45)
            val sewingStart = originWorkshopSewingClothPoint(dimensions, 0.0)
            val sewingNeedleFeed = originWorkshopSewingClothPoint(dimensions, 0.5)
            val sewingEnd = originWorkshopSewingClothPoint(dimensions, 1.0)
            val sewingFoot = point(0.515, h + 0.24, -0.58)
            val sewingHandwheel = point(1.22, h + 0.44, -0.79)
            val cushion = point(0.96, h + 0.085, 0.38)
            val padding = point(0.96, h + 0.105, 0.38)
            val cushionNear = point(0.96, h + 0.185, 0.18)
            val cushionFar = point(0.96, h + 0.185, 0.58)
            val cushionLeft = point(0.622, h + 0.085, 0.38)
            val cushionRight = point(1.298, h + 0.085, 0.38)
            interactions = listOf(
                interaction(OriginWorkshopGameStage.UPHOLSTER_FABRIC, OriginWorkshopGameAction.PICK_FABRIC, stock, "stock", OriginWorkshopGameStage.UPHOLSTER_PLACE_FABRIC),
                interaction(OriginWorkshopGameStage.UPHOLSTER_PLACE_FABRIC, OriginWorkshopGameAction.PLACE_FABRIC, fabricBed, null, OriginWorkshopGameStage.UPHOLSTER_STRETCH_LEFT),
                interaction(OriginWorkshopGameStage.UPHOLSTER_STRETCH_LEFT, OriginWorkshopGameAction.STRETCH_FABRIC_LEFT, point(tuning.pressCenterX - 0.20, h + 0.74, tuning.pressCenterZ), null, OriginWorkshopGameStage.UPHOLSTER_STRETCH_RIGHT),
                interaction(OriginWorkshopGameStage.UPHOLSTER_STRETCH_RIGHT, OriginWorkshopGameAction.STRETCH_FABRIC_RIGHT, point(tuning.pressCenterX + 0.20, h + 0.74, tuning.pressCenterZ), null, OriginWorkshopGameStage.UPHOLSTER_START_PRESS),
                interaction(OriginWorkshopGameStage.UPHOLSTER_START_PRESS, OriginWorkshopGameAction.ACTIVATE_PRESS, pressHandle, "press", OriginWorkshopGameStage.UPHOLSTER_PRESSING),
                interaction(OriginWorkshopGameStage.UPHOLSTER_PICK_PRESSED_COVER, OriginWorkshopGameAction.PICK_PRESSED_COVER, press, null, OriginWorkshopGameStage.UPHOLSTER_PLACE_SEWING),
                interaction(OriginWorkshopGameStage.UPHOLSTER_PLACE_SEWING, OriginWorkshopGameAction.PLACE_SEWING, sewingStart, null, OriginWorkshopGameStage.UPHOLSTER_LOWER_SEWING_FOOT),
                interaction(OriginWorkshopGameStage.UPHOLSTER_LOWER_SEWING_FOOT, OriginWorkshopGameAction.LOWER_SEWING_FOOT, sewingFoot, "sewing-foot", OriginWorkshopGameStage.UPHOLSTER_START_SEWING),
                interaction(OriginWorkshopGameStage.UPHOLSTER_START_SEWING, OriginWorkshopGameAction.START_SEWING, sewingHandwheel, "sewing", OriginWorkshopGameStage.UPHOLSTER_SEWING),
                interaction(OriginWorkshopGameStage.UPHOLSTER_TURN_FABRIC, OriginWorkshopGameAction.TURN_FABRIC, sewingEnd, null, OriginWorkshopGameStage.UPHOLSTER_START_SEWING_SECOND),
                interaction(OriginWorkshopGameStage.UPHOLSTER_START_SEWING_SECOND, OriginWorkshopGameAction.START_SEWING, sewingHandwheel, "sewing", OriginWorkshopGameStage.UPHOLSTER_SEWING_SECOND),
                interaction(OriginWorkshopGameStage.UPHOLSTER_PICK_COVER, OriginWorkshopGameAction.PICK_SEWN_COVER, sewingEnd, null, OriginWorkshopGameStage.UPHOLSTER_PLACE_COVER),
                interaction(OriginWorkshopGameStage.UPHOLSTER_PLACE_COVER, OriginWorkshopGameAction.PLACE_CUSHION_COVER, cushion, null, OriginWorkshopGameStage.UPHOLSTER_PICK_PADDING),
                interaction(OriginWorkshopGameStage.UPHOLSTER_PICK_PADDING, OriginWorkshopGameAction.PICK_PADDING, point(stock.x + 0.62, stock.y, stock.z), "stock", OriginWorkshopGameStage.UPHOLSTER_PLACE_PADDING),
                interaction(OriginWorkshopGameStage.UPHOLSTER_PLACE_PADDING, OriginWorkshopGameAction.PLACE_PADDING, padding, null, OriginWorkshopGameStage.UPHOLSTER_TUCK_NEAR),
                interaction(OriginWorkshopGameStage.UPHOLSTER_TUCK_NEAR, OriginWorkshopGameAction.TUCK_PADDING_NEAR, cushionNear, null, OriginWorkshopGameStage.UPHOLSTER_TUCK_FAR),
                interaction(OriginWorkshopGameStage.UPHOLSTER_TUCK_FAR, OriginWorkshopGameAction.TUCK_PADDING_FAR, cushionFar, null, OriginWorkshopGameStage.UPHOLSTER_FASTEN_LEFT),
                interaction(OriginWorkshopGameStage.UPHOLSTER_FASTEN_LEFT, OriginWorkshopGameAction.FASTEN_COVER_LEFT, cushionLeft, "cushion", OriginWorkshopGameStage.UPHOLSTER_FASTEN_RIGHT),
                interaction(OriginWorkshopGameStage.UPHOLSTER_FASTEN_RIGHT, OriginWorkshopGameAction.FASTEN_COVER_RIGHT, cushionRight, "cushion", OriginWorkshopGameStage.UPHOLSTER_FINISHING),
            ).toMap()
            timed = listOf(
                timed(OriginWorkshopGameStage.UPHOLSTER_PRESSING, OriginWorkshopGameStage.UPHOLSTER_PICK_PRESSED_COVER, rules.pressTicks, "press", press, "press"),
                timed(OriginWorkshopGameStage.UPHOLSTER_SEWING, OriginWorkshopGameStage.UPHOLSTER_TURN_FABRIC, rules.pressTicks, "sewing", sewingNeedleFeed, "sewing"),
                timed(OriginWorkshopGameStage.UPHOLSTER_SEWING_SECOND, OriginWorkshopGameStage.UPHOLSTER_PICK_COVER, rules.pressTicks, "sewing", sewingNeedleFeed, "sewing"),
                timed(OriginWorkshopGameStage.UPHOLSTER_FINISHING, OriginWorkshopGameStage.REWARDING, rules.finishingTicks, null, cushion, null),
            ).toMap()
            materials = mapOf(
                OriginWorkshopGameAction.PICK_FABRIC to Material.RED_WOOL,
                OriginWorkshopGameAction.PICK_PADDING to Material.WHITE_WOOL,
            )
            partSizes = mapOf(
                OriginWorkshopGameAction.PICK_FABRIC to OriginWorkshopGamePartSize(0.64f, 0.05f, 0.46f),
                OriginWorkshopGameAction.PICK_PADDING to OriginWorkshopGamePartSize(0.62f, 0.16f, 0.40f),
            )
            totalSteps = 19
            initialStage = OriginWorkshopGameStage.UPHOLSTER_FABRIC
            title = "Обивочная мастерская · красный диван"
        }
        OriginWorkshopTableRole.ASSEMBLER -> {
            val vise = point(tuning.viseCenterX, h + 0.10, tuning.viseCenterZ)
            val anvil = point(tuning.anvilCenterX, h + 0.67, tuning.anvilCenterZ)
            val anvilSecondSupport = point(tuning.anvilCenterX + 0.18, h + 0.67, tuning.anvilCenterZ)
            val assemblyTop = point(0.0, h + ASSEMBLER_TABLETOP_SIZE.y / 2.0, -0.45)
            val legCenterY = h + ASSEMBLER_TABLETOP_SIZE.y + ASSEMBLER_LEG_SIZE.y / 2.0
            val leftLeg = point(-0.24, legCenterY, -0.45)
            val rightLeg = point(0.24, legCenterY, -0.45)
            val legFastenerOffset = originWorkshopAssemblerLegPieces(fastened = true).last().center
            val leftLegFastener = point(
                leftLeg.x + legFastenerOffset.x,
                leftLeg.y + legFastenerOffset.y,
                leftLeg.z + legFastenerOffset.z,
            )
            val rightLegFastener = point(
                rightLeg.x + legFastenerOffset.x,
                rightLeg.y + legFastenerOffset.y,
                rightLeg.z + legFastenerOffset.z,
            )
            val leftLegStock = point(stock.x - 0.55, 0.32, stock.z - 0.55)
            val rightLegStock = point(stock.x + 0.55, 0.32, stock.z - 0.55)
            val viseHandle = point(tuning.viseCenterX - 0.7495, h + 0.17, tuning.viseCenterZ)
            val hammer = point(
                tuning.anvilCenterX,
                h + tuning.hammerPivotYOffset - tuning.hammerArmLength * kotlin.math.sin(Math.toRadians(tuning.hammerRestAngleDegrees)),
                tuning.hammerPivotZ + tuning.hammerArmLength * kotlin.math.cos(Math.toRadians(tuning.hammerRestAngleDegrees)),
            )
            interactions = listOf(
                interaction(OriginWorkshopGameStage.ASSEMBLER_STOCK, OriginWorkshopGameAction.PICK_TABLETOP, stock, "stock", OriginWorkshopGameStage.ASSEMBLER_PLACE_VISE),
                interaction(OriginWorkshopGameStage.ASSEMBLER_PLACE_VISE, OriginWorkshopGameAction.PLACE_VISE, vise, null, OriginWorkshopGameStage.ASSEMBLER_START_VISE),
                interaction(OriginWorkshopGameStage.ASSEMBLER_START_VISE, OriginWorkshopGameAction.TIGHTEN_VISE, viseHandle, "vise", OriginWorkshopGameStage.ASSEMBLER_VISING),
                interaction(OriginWorkshopGameStage.ASSEMBLER_ROTATE_TOP, OriginWorkshopGameAction.ROTATE_TABLETOP, vise, null, OriginWorkshopGameStage.ASSEMBLER_RETIGHTEN_VISE),
                interaction(OriginWorkshopGameStage.ASSEMBLER_RETIGHTEN_VISE, OriginWorkshopGameAction.TIGHTEN_VISE, viseHandle, "vise", OriginWorkshopGameStage.ASSEMBLER_VISING_SECOND),
                interaction(OriginWorkshopGameStage.ASSEMBLER_RELEASE_VISE, OriginWorkshopGameAction.RELEASE_VISE, viseHandle, "vise", OriginWorkshopGameStage.ASSEMBLER_PICK_VISED),
                interaction(OriginWorkshopGameStage.ASSEMBLER_PICK_VISED, OriginWorkshopGameAction.PICK_VISED_TABLETOP, vise, null, OriginWorkshopGameStage.ASSEMBLER_PLACE_ANVIL),
                interaction(OriginWorkshopGameStage.ASSEMBLER_PLACE_ANVIL, OriginWorkshopGameAction.PLACE_ANVIL, anvil, null, OriginWorkshopGameStage.ASSEMBLER_START_ANVIL),
                interaction(OriginWorkshopGameStage.ASSEMBLER_START_ANVIL, OriginWorkshopGameAction.ACTIVATE_ANVIL, hammer, "anvil", OriginWorkshopGameStage.ASSEMBLER_HAMMERING),
                interaction(OriginWorkshopGameStage.ASSEMBLER_ALIGN_SECOND, OriginWorkshopGameAction.ALIGN_JOIN_SECOND, anvilSecondSupport, null, OriginWorkshopGameStage.ASSEMBLER_START_ANVIL_SECOND),
                interaction(OriginWorkshopGameStage.ASSEMBLER_START_ANVIL_SECOND, OriginWorkshopGameAction.ACTIVATE_ANVIL, hammer, "anvil", OriginWorkshopGameStage.ASSEMBLER_HAMMERING_SECOND),
                interaction(OriginWorkshopGameStage.ASSEMBLER_PICK_JOINED_TOP, OriginWorkshopGameAction.PICK_JOINED_TABLETOP, anvil, null, OriginWorkshopGameStage.ASSEMBLER_PLACE_ASSEMBLY_TOP),
                interaction(OriginWorkshopGameStage.ASSEMBLER_PLACE_ASSEMBLY_TOP, OriginWorkshopGameAction.PLACE_ASSEMBLY_TOP, assemblyTop, null, OriginWorkshopGameStage.ASSEMBLER_PICK_LEFT_LEG),
                interaction(OriginWorkshopGameStage.ASSEMBLER_PICK_LEFT_LEG, OriginWorkshopGameAction.PICK_LEFT_LEG, leftLegStock, "leg-left", OriginWorkshopGameStage.ASSEMBLER_PLACE_LEFT_LEG),
                interaction(OriginWorkshopGameStage.ASSEMBLER_PLACE_LEFT_LEG, OriginWorkshopGameAction.PLACE_LEFT_LEG, leftLeg, null, OriginWorkshopGameStage.ASSEMBLER_PICK_RIGHT_LEG),
                interaction(OriginWorkshopGameStage.ASSEMBLER_PICK_RIGHT_LEG, OriginWorkshopGameAction.PICK_RIGHT_LEG, rightLegStock, "leg-right", OriginWorkshopGameStage.ASSEMBLER_PLACE_RIGHT_LEG),
                interaction(OriginWorkshopGameStage.ASSEMBLER_PLACE_RIGHT_LEG, OriginWorkshopGameAction.PLACE_RIGHT_LEG, rightLeg, null, OriginWorkshopGameStage.ASSEMBLER_TIGHTEN_LEFT),
                interaction(OriginWorkshopGameStage.ASSEMBLER_TIGHTEN_LEFT, OriginWorkshopGameAction.TIGHTEN_LEFT, leftLegFastener, null, OriginWorkshopGameStage.ASSEMBLER_CLAMPING_LEFT),
                interaction(OriginWorkshopGameStage.ASSEMBLER_TIGHTEN_RIGHT, OriginWorkshopGameAction.TIGHTEN_RIGHT, rightLegFastener, null, OriginWorkshopGameStage.ASSEMBLER_CLAMPING_RIGHT),
            ).toMap()
            timed = listOf(
                timed(OriginWorkshopGameStage.ASSEMBLER_VISING, OriginWorkshopGameStage.ASSEMBLER_ROTATE_TOP, rules.viseTicks, "vise", vise, "vise"),
                timed(OriginWorkshopGameStage.ASSEMBLER_VISING_SECOND, OriginWorkshopGameStage.ASSEMBLER_RELEASE_VISE, rules.viseTicks, "vise", vise, "vise"),
                timed(OriginWorkshopGameStage.ASSEMBLER_HAMMERING, OriginWorkshopGameStage.ASSEMBLER_ALIGN_SECOND, rules.hammerTicks, "anvil", anvil, "anvil"),
                timed(OriginWorkshopGameStage.ASSEMBLER_HAMMERING_SECOND, OriginWorkshopGameStage.ASSEMBLER_PICK_JOINED_TOP, rules.hammerTicks, "anvil", anvil, "anvil"),
                timed(OriginWorkshopGameStage.ASSEMBLER_CLAMPING_LEFT, OriginWorkshopGameStage.ASSEMBLER_TIGHTEN_RIGHT, rules.clampTicks, null, leftLegFastener, null),
                timed(OriginWorkshopGameStage.ASSEMBLER_CLAMPING_RIGHT, OriginWorkshopGameStage.ASSEMBLER_FINISHING, rules.clampTicks, null, rightLegFastener, null),
                timed(OriginWorkshopGameStage.ASSEMBLER_FINISHING, OriginWorkshopGameStage.REWARDING, rules.finishingTicks, null, assemblyTop, null),
            ).toMap()
            materials = mapOf(
                OriginWorkshopGameAction.PICK_TABLETOP to Material.SPRUCE_PLANKS,
                OriginWorkshopGameAction.PICK_LEFT_LEG to Material.STRIPPED_SPRUCE_LOG,
                OriginWorkshopGameAction.PICK_RIGHT_LEG to Material.STRIPPED_SPRUCE_LOG,
            )
            partSizes = mapOf(
                OriginWorkshopGameAction.PICK_TABLETOP to ASSEMBLER_TABLETOP_SIZE,
                OriginWorkshopGameAction.PICK_LEFT_LEG to leg,
                OriginWorkshopGameAction.PICK_RIGHT_LEG to leg,
            )
            totalSteps = 19
            initialStage = OriginWorkshopGameStage.ASSEMBLER_STOCK
            title = "Сборочная мастерская · белый стол"
        }
        OriginWorkshopTableRole.FINISHER -> {
            val rackHandle = point(1.10, h + 0.14, -0.42)
            val panel = point(1.10, h + 0.52, -0.35)
            val workPanelCenter = point(0.0, h + 0.074, -0.45)
            val sander = point(-1.87, h + 0.09, -0.14)
            val near = point(0.0, h + 0.074, -0.67)
            val center = point(0.0, h + 0.074, -0.45)
            val far = point(0.0, h + 0.074, -0.23)
            val bath = point(-1.15, h + 0.30, -0.14)
            interactions = listOf(
                interaction(OriginWorkshopGameStage.FINISHER_START_PANEL, OriginWorkshopGameAction.START_FINISH_PANEL, rackHandle, "rack", OriginWorkshopGameStage.FINISHER_WITHDRAWING),
                interaction(OriginWorkshopGameStage.FINISHER_PICK_ABRASIVE, OriginWorkshopGameAction.PICK_ABRASIVE, sander, "abrasive", OriginWorkshopGameStage.FINISHER_SAND_NEAR),
                interaction(OriginWorkshopGameStage.FINISHER_SAND_NEAR, OriginWorkshopGameAction.SAND_PANEL_NEAR, near, null, OriginWorkshopGameStage.FINISHER_SAND_CENTER),
                interaction(OriginWorkshopGameStage.FINISHER_SAND_CENTER, OriginWorkshopGameAction.SAND_PANEL_CENTER, center, null, OriginWorkshopGameStage.FINISHER_SAND_FAR),
                interaction(OriginWorkshopGameStage.FINISHER_SAND_FAR, OriginWorkshopGameAction.SAND_PANEL_FAR, far, null, OriginWorkshopGameStage.FINISHER_FLIP_FOR_BACK_SANDING),
                interaction(OriginWorkshopGameStage.FINISHER_FLIP_FOR_BACK_SANDING, OriginWorkshopGameAction.FLIP_PANEL, workPanelCenter, null, OriginWorkshopGameStage.FINISHER_SAND_BACK_NEAR),
                interaction(OriginWorkshopGameStage.FINISHER_SAND_BACK_NEAR, OriginWorkshopGameAction.SAND_PANEL_NEAR, near, null, OriginWorkshopGameStage.FINISHER_SAND_BACK_CENTER),
                interaction(OriginWorkshopGameStage.FINISHER_SAND_BACK_CENTER, OriginWorkshopGameAction.SAND_PANEL_CENTER, center, null, OriginWorkshopGameStage.FINISHER_SAND_BACK_FAR),
                interaction(OriginWorkshopGameStage.FINISHER_SAND_BACK_FAR, OriginWorkshopGameAction.SAND_PANEL_FAR, far, null, OriginWorkshopGameStage.FINISHER_DIP_BRUSH),
                interaction(OriginWorkshopGameStage.FINISHER_DIP_BRUSH, OriginWorkshopGameAction.DIP_FINISH_BRUSH, bath, "bath", OriginWorkshopGameStage.FINISHER_COAT_NEAR),
                interaction(OriginWorkshopGameStage.FINISHER_COAT_NEAR, OriginWorkshopGameAction.COAT_PANEL_NEAR, near, null, OriginWorkshopGameStage.FINISHER_COAT_CENTER),
                interaction(OriginWorkshopGameStage.FINISHER_COAT_CENTER, OriginWorkshopGameAction.COAT_PANEL_CENTER, center, null, OriginWorkshopGameStage.FINISHER_COAT_FAR),
                interaction(OriginWorkshopGameStage.FINISHER_COAT_FAR, OriginWorkshopGameAction.COAT_PANEL_FAR, far, null, OriginWorkshopGameStage.FINISHER_FLIP_FOR_BACK_COAT),
                interaction(OriginWorkshopGameStage.FINISHER_FLIP_FOR_BACK_COAT, OriginWorkshopGameAction.FLIP_PANEL, workPanelCenter, null, OriginWorkshopGameStage.FINISHER_DIP_BRUSH_SECOND),
                interaction(OriginWorkshopGameStage.FINISHER_DIP_BRUSH_SECOND, OriginWorkshopGameAction.DIP_FINISH_BRUSH, bath, "bath", OriginWorkshopGameStage.FINISHER_COAT_BACK_NEAR),
                interaction(OriginWorkshopGameStage.FINISHER_COAT_BACK_NEAR, OriginWorkshopGameAction.COAT_PANEL_NEAR, near, null, OriginWorkshopGameStage.FINISHER_COAT_BACK_CENTER),
                interaction(OriginWorkshopGameStage.FINISHER_COAT_BACK_CENTER, OriginWorkshopGameAction.COAT_PANEL_CENTER, center, null, OriginWorkshopGameStage.FINISHER_COAT_BACK_FAR),
                interaction(OriginWorkshopGameStage.FINISHER_COAT_BACK_FAR, OriginWorkshopGameAction.COAT_PANEL_FAR, far, null, OriginWorkshopGameStage.FINISHER_HANG_PANEL),
                interaction(OriginWorkshopGameStage.FINISHER_HANG_PANEL, OriginWorkshopGameAction.HANG_FINISHED_PANEL, rackHandle, "rack", OriginWorkshopGameStage.FINISHER_DRYING),
            ).toMap()
            timed = listOf(
                timed(OriginWorkshopGameStage.FINISHER_WITHDRAWING, OriginWorkshopGameStage.FINISHER_PICK_ABRASIVE, rules.finishingTicks, "finish", workPanelCenter, null),
                timed(OriginWorkshopGameStage.FINISHER_DRYING, OriginWorkshopGameStage.FINISHER_FINISHING, rules.finishingTicks, "finish", panel, null),
                timed(OriginWorkshopGameStage.FINISHER_FINISHING, OriginWorkshopGameStage.REWARDING, rules.chairHoldTicks, null, panel, null),
            ).toMap()
            materials = mapOf(
                OriginWorkshopGameAction.START_FINISH_PANEL to Material.OAK_PLANKS,
                OriginWorkshopGameAction.PICK_ABRASIVE to Material.SANDSTONE,
            )
            partSizes = mapOf(
                OriginWorkshopGameAction.START_FINISH_PANEL to OriginWorkshopGamePartSize(0.18f, 0.06f, 0.68f),
                OriginWorkshopGameAction.PICK_ABRASIVE to OriginWorkshopGamePartSize(0.18f, 0.06f, 0.18f),
            )
            totalSteps = 19
            initialStage = OriginWorkshopGameStage.FINISHER_START_PANEL
            title = "Отделочная мастерская · защитное покрытие"
        }
    }

    return OriginWorkshopGameRecipe(
        role, productId, title, totalSteps, initialStage, resultAnchor,
        interactions, timed, materials, partSizes,
    )
}

/** Recipe-backed transitions used by the shared runtime and deterministic role-chain tests. */
internal fun originWorkshopTransition(
    progress: OriginWorkshopGameProgress,
    action: OriginWorkshopGameAction,
    now: Long,
    recipe: OriginWorkshopGameRecipe,
): OriginWorkshopGameProgress? {
    val interaction = recipe.interactions[progress.stage] ?: return null
    if (interaction.action != action) return null
    return OriginWorkshopGameProgress(interaction.nextStage, now)
}

internal fun originWorkshopAdvance(
    progress: OriginWorkshopGameProgress,
    now: Long,
    recipe: OriginWorkshopGameRecipe,
): OriginWorkshopGameProgress {
    val timed = recipe.timedStages[progress.stage] ?: return progress
    if (now - progress.stageStartedAt < timed.durationTicks) return progress
    return OriginWorkshopGameProgress(timed.nextStage, now)
}

/** A drilled board settles at the center of the hole just processed, directly under the fixed spindle. */
internal fun originWorkshopDrillSettleTarget(
    recipe: OriginWorkshopGameRecipe,
    stage: OriginWorkshopGameStage,
): OriginWorkshopPoint? {
    if (recipe.role != OriginWorkshopTableRole.CARPENTER) return null
    return when (stage) {
        OriginWorkshopGameStage.DRILLING,
        OriginWorkshopGameStage.DRILLING_SECOND,
        OriginWorkshopGameStage.DRILLING_THIRD,
        -> recipe.timedStages[stage]?.target
        else -> null
    }
}
