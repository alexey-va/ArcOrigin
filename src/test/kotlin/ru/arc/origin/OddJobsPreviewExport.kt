package ru.arc.origin

import com.google.gson.GsonBuilder
import java.nio.file.Files
import java.nio.file.Path
import ru.arc.origin.scene.OriginScenePlan

/** Exact production workpieces above their contact plane; this is not a live-world or client capture. */
internal object OddJobsPreviewExport {
    fun write(output: Path) {
        val damaged = OriginForgeWork(listOf(2, 3, 2), listOf(0, 3, 2))
        val repaired = damaged.copy(shape = damaged.target)
        fun state(id: String, title: String, pieces: List<OriginWorkshopWorkpiecePiece>) = mapOf(
            "id" to id, "title" to title, "role" to "odd-jobs", "pieces" to pieces.mapIndexed { index, piece ->
                mapOf("key" to "$id-$index", "material" to piece.material.name,
                    "x" to piece.center.x, "y" to piece.center.y, "z" to piece.center.z,
                    "width" to piece.size.x, "height" to piece.size.y, "depth" to piece.size.z)
            })
        val states = listOf(
            state("new-raw", "Новая подкова · сырая железная заготовка", originOddJobPropPieces(OriginJobProp.FORGE_BLANK)),
            state("new-hanging-cold", "Цепь · подвешенная заготовка", originOddJobHangingPieces(originOddJobPropPieces(OriginJobProp.FORGE_BLANK))),
            state("new-hanging-hot", "Цепь · нагретая заготовка", originOddJobHangingPieces(originOddJobPropPieces(OriginJobProp.FORGE_HEATED))),
            state("new-anvil-raw", "Своя наковальня · до первого удара", originOddJobPropPieces(OriginJobProp.FORGE_HEATED)),
            state("new-first-strike", "Своя наковальня · первый изгиб", originOddJobPropPieces(OriginJobProp.FORGE_SHAPED_1)),
            state("new-shaped", "Своя наковальня · незавершённая ковка", originOddJobPropPieces(OriginJobProp.FORGE_SHAPED_2)),
            state("new-horseshoe", "Своя наковальня · выкованная подкова", originOddJobPropPieces(OriginJobProp.FORGE_HORSESHOE)),
            state("new-cooled", "Закалка · готовая подкова", originOddJobPropPieces(OriginJobProp.FORGE_COOLED)),
            state("target-outline", "Подсветка · полный контур блока", originOddJobTargetPieces()),
            state("repair-cold", "Ремонт · укороченная левая ветвь", originOddJobForgePieces(damaged, false)),
            state("repair-hot", "Ремонт · та же форма после нагрева", originOddJobForgePieces(damaged, true)),
            state("repaired-hot", "Ремонт · восстановленная ветвь", originOddJobForgePieces(repaired, true)),
            state("repaired-cold", "Ремонт · форма после закалки", originOddJobForgePieces(repaired, false)),
            state("water-carry", "Котёл · полное ведро", originOddJobPropPieces(OriginJobProp.WATER_BUCKET)),
            state("hay-in-stall", "Денник Бурана · сено на полу после доставки", originOddJobPropPieces(OriginJobProp.HAY)) + mapOf(
                "worldContact" to listOf(48.5, 70.05, -134.5), "animalApproach" to listOf(48.5, 70.0, -135.5)),
            state("water-at-animal", "Поение · ведро у лошади на 3,5 секунды", originOddJobPropPieces(OriginJobProp.WATER_BUCKET)) + mapOf(
                "worldContact" to listOf(62.4, 70.02, -119.5), "animal" to listOf(63.5, 70.0, -119.5)),
        ) + OriginForgeProduct.entries.filterNot { it == OriginForgeProduct.HORSESHOE }.flatMap { product ->
            (0..3).map { strikes ->
                val metal = if (product == OriginForgeProduct.HINGE) OriginForgeMetal.COPPER else OriginForgeMetal.IRON
                val work = OriginForgeWork(listOf(2, 2, 2), List(3) { if (it < strikes) 2 else 1 }, product, metal)
                state("${product.name.lowercase()}-$strikes", "${product.title} · удар $strikes/3", originOddJobForgePieces(work, strikes != 3))
            }
        } + OriginForgeCargo.entries.map { state("cargo-${it.name.lowercase()}", "Савва · ${it.title}", originForgeCargoPieces(it)) } +
            listOf("РУС", "ДОМ", "КОТ").flatMap { word -> (0..6).map { strokes ->
                state("engrave-$word-$strokes", "Лука · «$word» · удар $strokes/6",
                    originForgeEngravingPieces(OriginForgeEngraving(word, OriginForgeMetal.COPPER, strokes)))
            } }
        val scene = mapOf("states" to states,
            "coordinates" to mapOf("space" to "station-contact-plane", "origin" to "actual supporting plane", "unit" to "block", "yaw" to 0),
            "camera" to mapOf("position" to mapOf("x" to 0.75, "y" to 0.72, "z" to -1.2),
                "target" to mapOf("x" to 0.0, "y" to 0.04, "z" to 0.0), "fovDegrees" to 70, "near" to 0.05, "far" to 8),
            "carry" to OriginJobProp.entries.associate { kind -> kind.name to originOddJobCarryPose(0.0, false, originOddJobPropPieces(kind)).center },
            "evidence" to "Production raw/heat/hammer/target geometry and exact carry poses. Hanging origins are the chain centre. Supporting world, native lighting and glow are not simulated.")
        Files.createDirectories(output.parent)
        Files.writeString(output, GsonBuilder().setPrettyPrinting().create().toJson(scene))
        val route = OriginForgeCartRoute.from(OriginScenePlan.load(Files.createTempDirectory("odd-jobs-preview")).scene("forge"))
        val cartStates = listOf(0, 3, 2, 0).mapIndexed { index, count ->
            val cargo = (if (index == 2) 1..2 else 0 until count).flatMap { slot ->
                val offset = originForgeCartLoadOffset(slot)
                originForgeCargoPieces(OriginForgeCargo.IRON).map { piece ->
                    val center = org.joml.Vector3f(piece.center.x.toFloat(), piece.center.y.toFloat(), piece.center.z.toFloat())
                    org.joml.Quaternionf().rotationX(Math.toRadians(22.5).toFloat()).transform(center)
                    piece.copy(center = OriginWorkshopPoint((center.x + offset.x).toDouble(),
                        (center.y + offset.y).toDouble(), (center.z + offset.z).toDouble()))
                }
            }
            state("cart-$index", listOf("Тележка · пустая", "Тележка · три корзины железа", "Разгрузка · две корзины", "Возврат пустой тележки")[index], cargo).let { value ->
                value + ("pieces" to (value.getValue("pieces") as List<*>).map { piece -> (piece as Map<*, *>) + ("rotationX" to 22.5) })
            }
        }
        val cart = mapOf("states" to cartStates, "route" to route.points,
            "model" to mapOf("itemId" to route.itemId, "scale" to listOf(route.scale.x, route.scale.y, route.scale.z),
                "lift" to route.lift, "context" to route.context.name, "displayYawFromTravelYaw" to 180),
            "frames" to (0..130).map { frame ->
                val distance = frame / 10.0
                val motion = OriginForgeCartMotion(route, false)
                val (x, z) = route.direction(distance, false)
                mapOf("seconds" to frame / 20.0, "distance" to distance, "point" to route.point(distance),
                    "travelYaw" to Math.toDegrees(kotlin.math.atan2(-x, z)), "initialYaw" to motion.yaw)
            },
            "camera" to mapOf("position" to mapOf("x" to 0.0, "y" to 1.62, "z" to -1.2),
                "target" to mapOf("x" to 0.0, "y" to 0.65, "z" to 0.1), "fovDegrees" to 70),
            "evidence" to "Source route, GROUND model scale, basket geometry and poses. Offline mesh preview; native lighting, world and ItemsAdder delivery are not observed.")
        Files.writeString(output.resolveSibling("cart.json"), GsonBuilder().setPrettyPrinting().create().toJson(cart))
    }
}
