package ru.arc.origin

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import kotlin.math.abs

class OriginWorkshopStationStockGeometryTest : FreeSpec({
    "station stock has separated pallet boards, grounded furniture and clear output space" {
        for (role in OriginWorkshopTableRole.entries) {
            val stock = originWorkshopStationStockGeometry(role)
            stock.blocks.map { it.key }.distinct().size shouldBe stock.blocks.size
            (stock.blocks.size <= 29) shouldBe true
            stock.blocks.minOf { it.y - it.height / 2.0 } shouldBe 0.0
            (stock.blocks.maxOf { it.y + it.height / 2.0 } <= 0.62 + 1e-9) shouldBe true

            val blockBounds = stock.blocks.map { it.key to it.stockBounds() }
            val itemBounds = stock.items.mapIndexed { index, item -> "stock-item-$index:${item.itemId}" to item.stockBounds() }
            assertNoPositiveVolumeIntersections(blockBounds + itemBounds)

            stock.blocks.all { piece ->
                val bounds = piece.stockBounds()
                bounds.minX >= -1.0 - 1e-9 && bounds.maxX <= 1.0 + 1e-9 &&
                    bounds.minZ >= -1.325 - 1e-9 && bounds.maxZ <= 0.95 + 1e-9 &&
                    (piece.key.startsWith("pallet-") || bounds.minY >= 0.18 - 1e-9)
            } shouldBe true
            stock.items.all { item ->
                val bounds = item.stockBounds()
                bounds.minX >= -1.0 - 1e-9 && bounds.maxX <= 1.0 + 1e-9 &&
                    bounds.minZ >= -1.325 - 1e-9 && bounds.maxZ <= 0.95 + 1e-9 &&
                    abs(bounds.minY - item.y) < 1e-9 && bounds.maxY <= 1.5 + 1e-9
            } shouldBe true
            stock.items.forEach { item ->
                val furniture = item.stockBounds()
                stock.blocks.filter { it.key.startsWith("pallet-deck-") }.any { deck ->
                    val support = deck.stockBounds()
                    abs(support.maxY - furniture.minY) < 1e-9 && support.positiveAreaIntersectionXZ(furniture)
                } shouldBe true
            }
            stock.blocks.none { it.key.startsWith("rack-") || it.key.contains("shelf") } shouldBe true

            val expectedProduct = when (role) {
                OriginWorkshopTableRole.CARPENTER, OriginWorkshopTableRole.FINISHER -> "furnituresplus:white_wooden_chair"
                OriginWorkshopTableRole.UPHOLSTERER -> "furnituresplus:red_wooden_sofa_single"
                OriginWorkshopTableRole.ASSEMBLER -> "furnituresplus:white_wooden_diningtable"
            }
            if (role == OriginWorkshopTableRole.CARPENTER) stock.items shouldBe emptyList()
            else stock.items.single().itemId shouldBe expectedProduct

            val output = checkNotNull(originWorkshopResultBounds(expectedProduct))
                .at(originWorkshopResultAnchor(role, expectedProduct))
            for (stockOffsetX in listOf(-3.9, 3.9)) {
                val placedStock = stock.blocks.map { it.key to it.stockBounds(anchorX = stockOffsetX) } +
                    stock.items.mapIndexed { index, item ->
                        "stock-item-$index:${item.itemId}" to item.stockBounds(anchorX = stockOffsetX)
                    }
                placedStock.none { (_, bounds) -> bounds.positiveVolumeIntersection(output) } shouldBe true
            }

            val decks = stock.blocks.filter { it.key.startsWith("pallet-deck-") }
            decks.size shouldBe 3
            decks.all { it.material == org.bukkit.Material.DARK_OAK_PLANKS && it.depth == 0.70 } shouldBe true
            stock.blocks.filter { it.key.startsWith("pallet-runner-") }.all {
                it.material == org.bukkit.Material.STRIPPED_DARK_OAK_LOG
            } shouldBe true

            if (role == OriginWorkshopTableRole.CARPENTER) {
                val boardStack = stock.blocks.filter { it.key.startsWith("chair-stock-board-layer-") }
                boardStack.size shouldBe 3
                boardStack.zip(listOf(0.225, 0.315, 0.405)).all { (piece, y) -> abs(piece.y - y) < 1e-9 } shouldBe true
                boardStack.all { it.z == -0.75 && it.width == 1.80 && it.depth == 0.45 } shouldBe true
                stock.blocks.filter { it.key.startsWith("stacked-log-") }.all { it.z == -0.20 } shouldBe true
            }

            if (role == OriginWorkshopTableRole.FINISHER) {
                val crate = stock.blocks.single { it.key == "coating-crate" }.stockBounds()
                val panels = stock.blocks.filter { it.key.startsWith("cured-panel-layer-") }
                panels.none { it.stockBounds().positiveVolumeIntersection(crate) } shouldBe true
            }
        }
    }
})
