package ru.arc.origin

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import kotlin.math.abs

class OriginWorkshopResultPlacementTest : FreeSpec({
    "finished models touch the configured tabletop and stay within its support area" {
        for (height in listOf(0.9, 1.08, 1.2)) for (role in OriginWorkshopTableRole.entries) {
            val dimensions = OriginWorkshopTableDimensions(4.8, 2.05, height)
            val item = resultProduct(role)
            val bounds = originWorkshopResultBounds(item)!!
            val at = originWorkshopResultAnchor(role, item, dimensions)
            (abs(at.y + bounds.min.y - height) <= 1e-4) shouldBe true
            (at.x + bounds.min.x >= -dimensions.width / 2 + 0.1) shouldBe true
            (at.x + bounds.max.x <= dimensions.width / 2 - 0.1) shouldBe true
            (at.z + bounds.min.z >= -dimensions.depth / 2 + 0.1) shouldBe true
            (at.z + bounds.max.z <= dimensions.depth / 2 - 0.1) shouldBe true
        }
    }

    "outputs clear the canonical machines after only the carpenter jig is removed" {
        val dimensions = OriginWorkshopTableDimensions.DEFAULT
        for (role in OriginWorkshopTableRole.entries) {
            val product = resultProduct(role)
            val bounds = originWorkshopResultBounds(product)!!
            val at = originWorkshopResultAnchor(role, product, dimensions)
            val removed = originWorkshopFinishedAssemblyFixturePieceKeys(role) + ORIGIN_WORKSHOP_MACHINE_HIDDEN_IDLE_PIECES +
                if (role == OriginWorkshopTableRole.UPHOLSTERER) setOf("upholsterer-cushion-cover", "upholsterer-cushion-padding") else emptySet()
            val overlaps = originWorkshopTablePieces(role, 0, dimensions).filter {
                it.kind == OriginWorkshopTablePieceKind.BLOCK && it.key !in removed &&
                    at.x + bounds.max.x > it.x - it.width / 2 + 1e-4 && at.x + bounds.min.x < it.x + it.width / 2 - 1e-4 &&
                    at.y + bounds.max.y > it.y - it.height / 2 + 1e-4 && at.y + bounds.min.y < it.y + it.height / 2 - 1e-4 &&
                    at.z + bounds.max.z > it.z - it.depth / 2 + 1e-4 && at.z + bounds.min.z < it.z + it.depth / 2 - 1e-4
            }
            overlaps.map { it.key } shouldBe emptyList()
        }
    }

    "station yaw preserves the model's table contact without reusing inventory context" {
        for (yaw in listOf(0, 90, 180, 270)) for (role in OriginWorkshopTableRole.entries) {
            val table = OriginWorkshopTableDefinition("result", 100.0, 64.0, -200.0, yaw, role)
            val bounds = originWorkshopResultBounds(resultProduct(role))!!
            val point = originWorkshopPointInWorld(table, originWorkshopResultAnchor(role, resultProduct(role)))
            (abs(point.y + bounds.min.y - table.floorY - OriginWorkshopTableDimensions.DEFAULT.height) <= 1e-4) shouldBe true
        }
        originWorkshopResultBounds("furnituresplus:unknown") shouldBe null
    }
})

private fun resultProduct(role: OriginWorkshopTableRole) = when (role) {
    OriginWorkshopTableRole.UPHOLSTERER -> "furnituresplus:red_wooden_sofa_single"
    OriginWorkshopTableRole.ASSEMBLER -> "furnituresplus:white_wooden_diningtable"
    else -> "furnituresplus:white_wooden_chair"
}
