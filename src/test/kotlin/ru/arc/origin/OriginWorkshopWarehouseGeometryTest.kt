package ru.arc.origin

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import kotlin.math.abs

class OriginWorkshopWarehouseGeometryTest : FreeSpec({
    "warehouse pallets have visible gaps, grounded furniture and non-overlapping stock" {
        val geometry = originWorkshopWarehouseGeometry()

        geometry.blocks.size shouldBe 33
        (geometry.blocks.size <= 42) shouldBe true
        geometry.blocks.minOf { it.y - it.height / 2.0 } shouldBe 0.0
        (geometry.blocks.maxOf { it.y + it.height / 2.0 } <= 0.62 + 1e-9) shouldBe true
        geometry.blocks.none { it.key.startsWith("rack-") || it.key.contains("shelf") } shouldBe true

        val blockBounds = geometry.blocks.map { it.key to it.stockBounds() }
        val itemBounds = geometry.items.mapIndexed { index, item -> "warehouse-item-$index:${item.itemId}" to item.stockBounds() }
        assertNoPositiveVolumeIntersections(blockBounds + itemBounds)

        geometry.blocks.filter { it.key.contains("deck") }.all {
            it.material == org.bukkit.Material.DARK_OAK_PLANKS &&
                it.width == 2.80 && it.depth == 0.52 && abs(it.y + it.height / 2.0 - 0.18) < 1e-9
        } shouldBe true
        geometry.blocks.filter { it.key.contains("runner") }.all {
            it.material == org.bukkit.Material.STRIPPED_DARK_OAK_LOG
        } shouldBe true
        geometry.blocks.all { piece ->
            val bounds = piece.stockBounds()
            bounds.minX >= -5.0 - 1e-9 && bounds.maxX <= 5.0 + 1e-9 &&
                bounds.minZ >= -1.5 - 1e-9 && bounds.maxZ <= 1.5 + 1e-9
        } shouldBe true

        val decks = geometry.blocks.filter { it.key.contains("deck") }.map { it.stockBounds() }
        for (first in decks.indices) for (second in first + 1 until decks.size) {
            val a = decks[first]
            val b = decks[second]
            val xGap = maxOf(a.minX - b.maxX, b.minX - a.maxX)
            val zGap = maxOf(a.minZ - b.maxZ, b.minZ - a.maxZ)
            (xGap > 0.0 || zGap > 0.0) shouldBe true
        }

        geometry.items.size shouldBe 6
        geometry.items.map { it.itemId.substringAfter(':') }.groupingBy { it }.eachCount() shouldBe mapOf(
            "white_wooden_chair" to 2,
            "red_wooden_sofa_single" to 3,
            "white_wooden_diningtable" to 1,
        )
        geometry.items.map { it.x } shouldBe listOf(-3.70, -2.30, -0.78, 0.78, 2.30, 3.70)

        geometry.items.forEach { item ->
            val furniture = item.stockBounds()
            (abs(furniture.minY - item.y) < 1e-9) shouldBe true
            (furniture.maxY <= 1.5 + 1e-9) shouldBe true
            val supported = geometry.blocks.filter { it.key.contains("deck") }.any { deck ->
                val support = deck.stockBounds()
                abs(support.maxY - furniture.minY) < 1e-9 && support.positiveAreaIntersectionXZ(furniture)
            }
            supported shouldBe true

            val palletCenterX = listOf(-3.0, 0.0, 3.0).first { center ->
                furniture.minX >= center - 1.40 - 1e-9 && furniture.maxX <= center + 1.40 + 1e-9
            }
            (furniture.minX >= palletCenterX - 1.40 - 1e-9 &&
                furniture.maxX <= palletCenterX + 1.40 + 1e-9 &&
                furniture.minZ >= -0.61 - 1e-9 && furniture.maxZ <= 1.01 + 1e-9) shouldBe true
        }
    }
})
