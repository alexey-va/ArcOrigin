package ru.arc.origin

internal fun originWorkshopUsesStockGuidance(
    role: OriginWorkshopTableRole,
    interaction: OriginWorkshopRecipeInteraction?,
): Boolean = interaction?.control == "stock" ||
    (role == OriginWorkshopTableRole.ASSEMBLER && interaction?.action in setOf(
        OriginWorkshopGameAction.PICK_LEFT_LEG, OriginWorkshopGameAction.PICK_RIGHT_LEG,
    ))

/** Keep the instructions across the work surface, away from the player's near plane. */
internal fun originWorkshopGuidanceAnchor(
    dimensions: OriginWorkshopTableDimensions,
    target: OriginWorkshopPoint,
    stock: Boolean,
): OriginWorkshopPoint = if (stock) {
    OriginWorkshopPoint(target.x, 1.45, target.z + 0.55)
} else {
    OriginWorkshopPoint(0.0, dimensions.height + 1.15, dimensions.depth / 2.0 - 0.42)
}
