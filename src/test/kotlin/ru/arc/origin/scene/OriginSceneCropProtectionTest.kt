package ru.arc.origin.scene

import io.kotest.core.spec.style.FreeSpec
import io.kotest.matchers.shouldBe
import org.bukkit.Material

class OriginSceneCropProtectionTest : FreeSpec({
    "field actors cannot trample farmland or destroy crops" {
        val protection = OriginSceneCropProtection("origin", setOf(412, 413, 414, 415))
        for (material in listOf(Material.FARMLAND, Material.WHEAT, Material.CARROTS)) {
            protection.protects("origin", 413, material) shouldBe true
            protection.protects("origin", 415, material) shouldBe true
        }
    }

    "crop protection does not affect players other actors or other worlds" {
        val protection = OriginSceneCropProtection("origin", setOf(412, 413))
        protection.protects("origin", null, Material.FARMLAND) shouldBe false
        protection.protects("origin", 999, Material.FARMLAND) shouldBe false
        protection.protects("survival", 413, Material.FARMLAND) shouldBe false
        protection.protects("origin", 413, Material.STONE) shouldBe false
    }
})
