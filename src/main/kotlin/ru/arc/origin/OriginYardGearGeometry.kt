package ru.arc.origin

import org.bukkit.Material

internal enum class OriginYardGear(val title: String) {
    HAY("Сено"), BUCKET("Ведро"), BRUSH("Щётка"), PAD("Вальтрап"),
    SADDLE("Седло"), BRIDLE("Уздечка"), LEAD("Чомбур"), SHOVEL("Лопата"),
}

/** One geometry source for the rack, hand, horse and returned object. Metres, saddle seat at y=0. */
internal fun originYardGearPieces(gear: OriginYardGear, filled: Boolean = false,
    leftStirrup: Boolean = false, rightStirrup: Boolean = false, girthTight: Boolean = false): List<OriginWorkshopWorkpiecePiece> = buildList {
    fun box(m: Material, x: Double, y: Double, z: Double, w: Double, h: Double, d: Double) {
        add(OriginWorkshopWorkpiecePiece(OriginWorkshopPoint(x, y, z),
            OriginWorkshopGamePartSize(w.toFloat(), h.toFloat(), d.toFloat()), m))
    }
    fun iron(x: Double, y: Double, z: Double, w: Double, h: Double, d: Double) = box(Material.IRON_BLOCK, x,y,z,w,h,d)
    val leather = Material.BROWN_TERRACOTTA
    when (gear) {
        OriginYardGear.SADDLE -> {
            // Proportions follow the authored saddle-rack model; straps now actually hang below its seat.
            box(leather, 0.0,.06,0.0, .52,.12,.64)
            box(leather, 0.0,.18,-.30, .56,.12,.12)
            box(leather, 0.0,.20,.30, .58,.16,.14)
            for (side in listOf(-1, 1)) {
                box(leather, side*.31,-.035,.03, .16,.07,.76)
                box(Material.YELLOW_TERRACOTTA, side*.235,.126,0.0, .018,.012,.45)
                val bottom = if (if (side < 0) leftStirrup else rightStirrup) -.40 else -.47
                val strapBottom = bottom+.1725
                box(Material.BLACK_TERRACOTTA, side*.43,(.02+strapBottom)/2,0.0, .045,.02-strapBottom,.07)
                box(Material.CUT_COPPER, side*.42,-.08,0.0, .08,.065,.11)
                iron(side*.43, bottom+.095,-.06, .035,.12,.035)
                iron(side*.43, bottom+.095,.06, .035,.12,.035)
                iron(side*.43, bottom+.0175,0.0, .035,.035,.155)
                iron(side*.43, bottom+.1725,0.0, .035,.035,.155)
            }
            box(leather, 0.0,.31,-.30, .08,.14,.08)
            box(leather, 0.0,.3975,-.30, .14,.035,.12)
            val girthBottom = if (girthTight) -.66 else -.70
            for (side in listOf(-1,1)) box(leather,side*.36,(girthBottom-.02)/2,.21,
                .035,-girthBottom-.02,.12)
            box(leather,0.0,girthBottom-.0175,.21,.755,.035,.12)
        }
        OriginYardGear.PAD -> {
            // Flush cloth panels and binding meet at their edges, with no coplanar overlap.
            box(Material.BLUE_WOOL, 0.0,0.0,0.0, .84,.03,.78)
            box(Material.WHITE_WOOL, -.435,0.0,0.0, .03,.03,.78)
            box(Material.WHITE_WOOL, .435,0.0,0.0, .03,.03,.78)
            box(Material.WHITE_WOOL, 0.0,0.0,-.405, .90,.03,.03)
            box(Material.WHITE_WOOL, 0.0,0.0,.405, .90,.03,.03)
            for (side in listOf(-1,1)) {
                box(Material.BLUE_WOOL, side*.435,-.105,0.0, .03,.18,.78)
                box(Material.WHITE_WOOL, side*.435,-.21,0.0, .03,.03,.84)
            }
        }
        OriginYardGear.BRIDLE -> {
            // Local head coordinates: crown behind the ears, cheek straps and a separate muzzle loop.
            // Equipped pose shares vanilla adult horse's resting head plane (30 degrees).
            for (side in listOf(-1,1)) {
                box(leather, side*.205,-.17,.20, .03,.34,.035)
                box(leather, side*.205,-.18,-.1125, .03,.035,.59)
                box(leather, side*.145,-.175,-.425, .03,.32,.035)
                val x = side*.18
                iron(x,-.1375,-.425,.09,.015,.085)
                iron(x,-.2225,-.425,.09,.015,.085)
                iron(x,-.18,-.460,.09,.07,.015)
                iron(x,-.18,-.390,.09,.07,.015)
            }
            box(leather,0.0,.015,.20,.44,.03,.035)
            box(leather,0.0,0.0,-.425,.26,.03,.035)
            box(leather,0.0,-.35,-.425,.32,.03,.035)
            iron(0.0,-.245,-.425,.36,.022,.022)
        }
        OriginYardGear.BUCKET -> {
            iron(0.0,.025,0.0,.32,.05,.30)
            iron(-.175,.16,0.0,.03,.32,.30); iron(.175,.16,0.0,.03,.32,.30)
            iron(0.0,.16,-.165,.38,.32,.03); iron(0.0,.16,.165,.38,.32,.03)
            iron(-.20,.35,0.0,.025,.24,.025); iron(.20,.35,0.0,.025,.24,.025)
            iron(0.0,.4825,0.0,.425,.025,.025)
            if (filled) box(Material.BLUE_CONCRETE,0.0,.255,0.0,.32,.015,.30)
        }
        OriginYardGear.BRUSH -> {
            box(Material.STRIPPED_OAK_WOOD,0.0,.08,0.0,.30,.08,.17)
            box(Material.BROWN_TERRACOTTA,0.0,.145,0.0,.18,.05,.075)
            for (x in listOf(-.115,-.058,0.0,.058,.115))
                for (z in listOf(-.055,0.0,.055)) box(Material.BLACK_WOOL,x,.02,z,.036,.04,.035)
        }
        OriginYardGear.LEAD -> {
            // Two woven coils with stepped corners keep the small rope readable as a solid object.
            for (z in listOf(-.025,.025)) {
                for (side in listOf(-1,1)) {
                    box(Material.BROWN_WOOL,0.0,side*.18,z,.18,.035,.035)
                    box(Material.BROWN_WOOL,side*.1225,0.0,z,.035,.255,.035)
                    box(Material.BROWN_WOOL,side*.10625,.145,z,.0675,.035,.035)
                    box(Material.BROWN_WOOL,side*.10625,-.145,z,.0675,.035,.035)
                }
            }
            iron(.11,-.225,0.0,.035,.055,.04)
        }
        OriginYardGear.SHOVEL -> {
            box(Material.STRIPPED_OAK_WOOD,0.0,.57,0.0,.055,.84,.055)
            iron(0.0,.13,0.0,.23,.22,.035)
            iron(0.0,.01,.025,.23,.02,.085)
            iron(0.0,.99,0.0,.165,.03,.045)
            iron(-.065,1.035,0.0,.025,.09,.025); iron(.065,1.035,0.0,.025,.09,.025)
            iron(0.0,1.0925,0.0,.155,.025,.025)
            if (filled) box(Material.MUD,0.0,.13,.055,.18,.12,.075)
        }
        OriginYardGear.HAY -> {
            box(Material.HAY_BLOCK,0.0,.18,0.0,.46,.36,.34)
            box(Material.STRIPPED_OAK_WOOD,0.0,.366,0.0,.055,.012,.34)
        }
    }
}

internal fun originYardSaddleStandPieces(): List<OriginWorkshopWorkpiecePiece> = listOf(
    OriginWorkshopWorkpiecePiece(OriginWorkshopPoint(-.18,.045,-.10), OriginWorkshopGamePartSize(.08f,.09f,.44f), Material.DARK_OAK_PLANKS),
    OriginWorkshopWorkpiecePiece(OriginWorkshopPoint(.18,.045,-.10), OriginWorkshopGamePartSize(.08f,.09f,.44f), Material.DARK_OAK_PLANKS),
    OriginWorkshopWorkpiecePiece(OriginWorkshopPoint(-.18,.385,0.0), OriginWorkshopGamePartSize(.06f,.59f,.12f), Material.DARK_OAK_PLANKS),
    OriginWorkshopWorkpiecePiece(OriginWorkshopPoint(.18,.385,0.0), OriginWorkshopGamePartSize(.06f,.59f,.12f), Material.DARK_OAK_PLANKS),
    OriginWorkshopWorkpiecePiece(OriginWorkshopPoint(0.0,.715,0.0), OriginWorkshopGamePartSize(.44f,.07f,.62f), Material.DARK_OAK_PLANKS),
)

internal fun originYardSaddleHooksPieces(): List<OriginWorkshopWorkpiecePiece> = listOf(
    OriginWorkshopWorkpiecePiece(OriginWorkshopPoint(-.55,1.07,0.0),OriginWorkshopGamePartSize(.10f,.035f,.65f),Material.DARK_OAK_PLANKS),
    OriginWorkshopWorkpiecePiece(OriginWorkshopPoint(.21,.895,-.72),OriginWorkshopGamePartSize(.035f,.035f,.45f),Material.IRON_BLOCK),
    OriginWorkshopWorkpiecePiece(OriginWorkshopPoint(-.28,.80,-.54),OriginWorkshopGamePartSize(.035f,.035f,.10f),Material.IRON_BLOCK),
)
