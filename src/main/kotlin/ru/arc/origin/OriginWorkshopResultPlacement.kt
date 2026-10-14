package ru.arc.origin

/** Exact current furniture model bounds: NONE context, native Y180, uniform entity scale 0.65.
 * Derived from the generated pack (SHA256 5c2c672c8904d3fae6987286f111ae57270c1f0de2a5d3c4f8d31aa623344b22).
 */
internal data class OriginWorkshopResultBounds(val min: OriginWorkshopPoint, val max: OriginWorkshopPoint) {
    val minY get() = min.y
}

internal fun originWorkshopResultBounds(productId: String): OriginWorkshopResultBounds? = when (productId) {
    "furnituresplus:white_wooden_chair" -> OriginWorkshopResultBounds(OriginWorkshopPoint(-0.26, -0.325, -0.2275), OriginWorkshopPoint(0.2559375, 0.6865625, 0.284375))
    "furnituresplus:red_wooden_sofa_single" -> OriginWorkshopResultBounds(OriginWorkshopPoint(-0.365625, -0.325, -0.325), OriginWorkshopPoint(0.365625, 0.325, 0.325))
    "furnituresplus:white_wooden_diningtable" -> OriginWorkshopResultBounds(OriginWorkshopPoint(-0.325, -0.3371875, -0.365625), OriginWorkshopPoint(0.325, 0.325, 0.365625))
    else -> null
}

/** Model-derived contact with the existing tabletop, clear of the role's machine assembly. */
internal fun originWorkshopResultAnchor(
    role: OriginWorkshopTableRole,
    productId: String? = null,
    dimensions: OriginWorkshopTableDimensions = OriginWorkshopTableDimensions.DEFAULT,
): OriginWorkshopPoint {
    val offsetY = productId?.let(::originWorkshopResultBounds)?.let { -it.minY }
        ?: if (role == OriginWorkshopTableRole.ASSEMBLER) 0.3371875 else 0.325
    return when (role) {
        OriginWorkshopTableRole.CARPENTER -> OriginWorkshopPoint(1.35, dimensions.height + offsetY, -0.35)
        OriginWorkshopTableRole.UPHOLSTERER -> OriginWorkshopPoint(0.0, dimensions.height + offsetY, 0.08)
        else -> OriginWorkshopPoint(0.0, dimensions.height + offsetY, -0.45)
    }
}
