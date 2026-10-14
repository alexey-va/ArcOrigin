package ru.arc.origin

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import ru.arc.origin.api.ArcOriginAuctions

class AuctionShowcasePlannerTest :
    FreeSpec({
        "switches the whole showcase to the next page of six visual items" {
            val materials =
                listOf(
                    Material.STONE,
                    Material.DIRT,
                    Material.COBBLESTONE,
                    Material.OAK_LOG,
                    Material.IRON_INGOT,
                    Material.GOLD_INGOT,
                    Material.DIAMOND,
                    Material.EMERALD,
                )
            val listings = materials.mapIndexed { index, material -> listing(index + 1, material) }

            AuctionShowcasePlanner.select(listings, 6, 0).map { it?.id }
                .shouldContainExactly(1, 2, 3, 4, 5, 6)
            AuctionShowcasePlanner.select(listings, 6, 1).map { it?.id }
                .shouldContainExactly(7, 8, 1, 2, 3, 4)
        }

        "never repeats the same visual item to fill empty pedestals" {
            val listings =
                listOf(
                    listing(1, Material.STONE),
                    listing(2, Material.STONE),
                    listing(3, Material.DIRT),
                    listing(4, Material.COBBLESTONE),
                    listing(5, Material.OAK_LOG),
                )

            AuctionShowcasePlanner.select(listings, 6, 0).map { it?.id }
                .shouldContainExactly(1, 3, 4, 5, null, null)
            AuctionShowcasePlanner.select(listings, 6, 1).map { it?.id }
                .shouldContainExactly(1, 3, 4, 5, null, null)
        }

        "treats custom models on the same base material as different visuals" {
            val listings =
                listOf(
                    listing(1, Material.STICK, 11137),
                    listing(2, Material.STICK, 11138),
                    listing(3, Material.STICK, 11137),
                )

            AuctionShowcasePlanner.select(listings, 6, 0).map { it?.id }
                .shouldContainExactly(1, 2, null, null, null, null)
        }

        "renders a fully empty state when no listing is active" {
            AuctionShowcasePlanner.select(emptyList(), 6, 0).map { it?.id }
                .shouldContainExactly(null, null, null, null, null, null)
        }
    })

private fun listing(
    id: Int,
    material: Material = Material.STONE,
    customModelData: Int? = null,
): ArcOriginAuctions.Listing =
    ArcOriginAuctions.Listing(
        id,
        stack(material, customModelData),
        "Камень $id",
        "Seller$id",
        id.toString(),
    )

// Existing showcase items use the integer CustomModelData contract.
@Suppress("DEPRECATION")
private fun stack(
    material: Material,
    customModelData: Int?,
): ItemStack {
    val meta = mockk<ItemMeta>(relaxed = true)
    every { meta.hasCustomModelData() } returns (customModelData != null)
    customModelData?.let { value -> every { meta.customModelData } returns value }
    return mockk {
        every { type } returns material
        every { itemMeta } returns meta
    }
}
