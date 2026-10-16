package ru.arc.origin

import org.bukkit.Material

internal fun OriginForgeMetal.block(): Material = when (this) {
    OriginForgeMetal.IRON -> Material.IRON_BLOCK
    OriginForgeMetal.COPPER -> Material.COPPER_BLOCK
}

/** These shapes use the same contact plane, material and progress in hand and on the anvil. */
internal fun originForgeDetailPieces(work: OriginForgeWork, heated: Boolean): List<OriginWorkshopWorkpiecePiece> {
    val material = if (heated) Material.ORANGE_TERRACOTTA else work.metal.block()
    val steps = (3 - work.remainingStrikes).coerceIn(0, 3)
    return buildList {
        fun part(x: Double, y: Double, z: Double, w: Float, h: Float, d: Float) =
            add(forgePiece(material, x, y, z, w, h, d))
        when (work.product) {
            OriginForgeProduct.HOOK -> {
                part(-0.17, 0.045, -0.08, 0.10f, 0.09f, 0.52f)
                if (steps >= 1) part(-0.06, 0.045, 0.23, 0.32f, 0.09f, 0.10f)
                if (steps >= 2) part(0.15, 0.045, 0.17, 0.10f, 0.09f, 0.22f)
                if (steps >= 3) part(-0.17, 0.045, -0.38, 0.20f, 0.09f, 0.08f)
            }
            OriginForgeProduct.HINGE -> {
                part(0.0, 0.05, 0.0, 0.08f, 0.10f, 0.44f)
                for (side in listOf(-1, 1).take(steps.coerceAtMost(2))) {
                    // Two broad leaves with real openings around their fastening holes.
                    part(side * 0.08, 0.025, 0.0, 0.08f, 0.05f, 0.36f)
                    part(side * 0.24, 0.025, 0.0, 0.08f, 0.05f, 0.36f)
                    part(side * 0.16, 0.025, -0.14, 0.08f, 0.05f, 0.08f)
                    part(side * 0.16, 0.025, 0.14, 0.08f, 0.05f, 0.08f)
                }
                if (steps >= 3) {
                    part(0.0, 0.05, -0.25, 0.12f, 0.10f, 0.06f)
                    part(0.0, 0.05, 0.25, 0.12f, 0.10f, 0.06f)
                }
            }
            OriginForgeProduct.BRACKET -> {
                part(0.0, 0.035, 0.0, 0.48f, 0.07f, 0.18f)
                if (steps >= 1) part(0.0, 0.035, -0.14, 0.48f, 0.07f, 0.10f)
                if (steps >= 2) part(-0.205, 0.12, 0.0, 0.07f, 0.10f, 0.18f)
                if (steps >= 3) part(-0.205, 0.22, 0.0, 0.07f, 0.10f, 0.18f)
            }
            OriginForgeProduct.HORSESHOE -> error("horseshoes use their existing profile")
        }
    }
}

internal fun originForgeCargoPieces(cargo: OriginForgeCargo): List<OriginWorkshopWorkpiecePiece> {
    val material = when (cargo) {
        OriginForgeCargo.IRON -> Material.RAW_IRON_BLOCK
        OriginForgeCargo.COPPER -> Material.RAW_COPPER_BLOCK
        OriginForgeCargo.COAL -> Material.COAL_BLOCK
    }
    return listOf(
        forgePiece(Material.DARK_OAK_PLANKS, 0.0, 0.025, 0.0, 0.56f, 0.05f, 0.44f),
        forgePiece(material, -0.13, 0.17, -0.10, 0.24f, 0.24f, 0.20f),
        forgePiece(material, 0.13, 0.16, -0.08, 0.24f, 0.22f, 0.24f),
        forgePiece(material, 0.0, 0.16, 0.14, 0.32f, 0.22f, 0.16f),
        forgePiece(material, -0.04, 0.34, 0.0, 0.22f, 0.12f, 0.20f),
        forgePiece(Material.DARK_OAK_PLANKS, -0.31, 0.09, 0.0, 0.06f, 0.13f, 0.20f),
        forgePiece(Material.DARK_OAK_PLANKS, 0.31, 0.09, 0.0, 0.06f, 0.13f, 0.20f),
    )
}

private val FORGE_LETTERS = mapOf(
    'Р' to listOf("111", "101", "111", "100", "100"),
    'У' to listOf("101", "101", "111", "001", "110"),
    'С' to listOf("111", "100", "100", "100", "111"),
    'Д' to listOf("011", "101", "101", "111", "101"),
    'О' to listOf("111", "101", "101", "101", "111"),
    'М' to listOf("101", "111", "111", "101", "101"),
    'К' to listOf("101", "110", "100", "110", "101"),
    'Т' to listOf("111", "010", "010", "010", "010"),
)

/** Each accepted strike reveals half a letter; horizontal runs share one display cuboid. */
internal fun originForgeEngravingPieces(work: OriginForgeEngraving): List<OriginWorkshopWorkpiecePiece> = buildList {
    add(forgePiece(work.metal.block(), 0.0, 0.03, 0.0, 0.72f, 0.06f, 0.35f))
    work.word.forEachIndexed { letterIndex, letter ->
        val rows = ((work.strokes - letterIndex * 2).coerceIn(0, 2) * 5 + 1) / 2
        FORGE_LETTERS.getValue(letter).take(rows).forEachIndexed { row, pattern ->
            var column = 0
            while (column < pattern.length) {
                if (pattern[column] == '0') { column++; continue }
                val from = column
                while (column < pattern.length && pattern[column] == '1') column++
                add(forgePiece(Material.GRAY_CONCRETE, -0.275 + letterIndex * 0.20 + (from + column) * 0.025,
                    0.065, -0.104 + row * 0.052, (column - from) * 0.05f, 0.01f, 0.052f))
            }
        }
    }
}

private fun forgePiece(material: Material, x: Double, y: Double, z: Double, w: Float, h: Float, d: Float) =
    OriginWorkshopWorkpiecePiece(OriginWorkshopPoint(x, y, z), OriginWorkshopGamePartSize(w, h, d), material)
