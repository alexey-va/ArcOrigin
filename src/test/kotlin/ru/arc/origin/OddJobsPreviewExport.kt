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
            state("yard-destination", "Развязка · открытая площадка для лошади", originOddJobDestinationPieces()) + mapOf(
                "worldContact" to listOf(ORIGIN_YARD_CARE_PAD.x, ORIGIN_YARD_CARE_PAD.y + 0.05, ORIGIN_YARD_CARE_PAD.z)),
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
        val layout = OriginForgeCartLayout.from(OriginScenePlan.load(Files.createTempDirectory("odd-jobs-preview")).scene("forge"))
        val cartStates = listOf(0, 3).mapIndexed { index, count ->
            val cargo = (0 until count).flatMap { slot ->
                val offset = originForgeCartLoadOffset(slot)
                originForgeCargoPieces(OriginForgeCargo.IRON).map { piece ->
                    val center = org.joml.Vector3f(piece.center.x.toFloat(), piece.center.y.toFloat(), piece.center.z.toFloat())
                    org.joml.Quaternionf().rotationX(Math.toRadians(22.5).toFloat()).transform(center)
                    piece.copy(center = OriginWorkshopPoint((center.x + offset.x).toDouble(),
                        (center.y + offset.y).toDouble(), (center.z + offset.z).toDouble()))
                }
            }
            state("cart-$index", listOf("Тележка · загрузка", "Тележка · гружёная")[index], cargo).let { value ->
                value + ("pieces" to (value.getValue("pieces") as List<*>).map { piece -> (piece as Map<*, *>) + ("rotationX" to 22.5) })
            }
        }
        val cart = mapOf("states" to cartStates, "parking" to layout.start,
            "model" to mapOf("itemId" to layout.itemId, "scale" to listOf(layout.scale.x, layout.scale.y, layout.scale.z),
                "lift" to layout.lift, "context" to layout.context.name, "parkingYaw" to layout.yaw),
            "attachment" to listOf(0.0, 90.0, 180.0, 270.0).map { yaw ->
                val pose = originForgeCartCarryPose(yaw)
                mapOf("bodyYaw" to yaw, "feetOffset" to pose.center, "bedRotation" to pose.rotation)
            },
            "camera" to mapOf("position" to mapOf("x" to 0.0, "y" to 1.62, "z" to -1.2),
                "target" to mapOf("x" to 0.0, "y" to 0.65, "z" to 0.1), "fovDegrees" to 70),
            "evidence" to "Source parking, GROUND model scale, basket geometry and native player attachment poses. Offline mesh preview; native lighting, world and ItemsAdder delivery are not observed.")
        Files.writeString(output.resolveSibling("cart.json"), GsonBuilder().setPrettyPrinting().create().toJson(cart))
        writeCrossTies(output.resolveSibling("cross-ties.json"))
        writeCleaning(output.resolveSibling("cleaning.json"))
    }
    private fun writeCleaning(output: Path) {
        val plan = OriginScenePlan.load(Files.createTempDirectory("cleaning-preview"))
        val layout = requireNotNull(OriginStableCleaningLayout.from(plan.scene("mount-yard"), OriginForgeCartLayout.from(plan.scene("forge"))))
        val portion = originStableManurePieces()
        fun pieces(geometry: List<OriginWorkshopWorkpiecePiece>, prefix: String, offset: OriginWorkshopPoint,
            rotation: org.joml.Quaternionf = org.joml.Quaternionf(), xRotation: Double = 0.0) = geometry.mapIndexed { index, piece ->
            val at = rotation.transform(org.joml.Vector3f(piece.center.x.toFloat(), piece.center.y.toFloat(), piece.center.z.toFloat()))
            mapOf("key" to "$prefix-$index", "material" to piece.material.name,
                "x" to offset.x + at.x, "y" to offset.y + at.y, "z" to offset.z + at.z,
                "width" to piece.size.x, "height" to piece.size.y, "depth" to piece.size.z, "rotationX" to xRotation)
        }
        val stall = layout.stalls.getValue(422)
        val floor = stall.piles.flatMapIndexed { i, point -> pieces(portion, "floor-$i", OriginWorkshopPoint(
            point.x - stall.cart.x, point.y - stall.cart.y, point.z - stall.cart.z)) }
        fun cargo(count: Int) = (0 until count).flatMap { index ->
            val offset = originForgeCartLoadOffset(index)
            pieces(portion, "cargo-$index", OriginWorkshopPoint(offset.x.toDouble(), offset.y.toDouble(), offset.z.toDouble()),
                org.joml.Quaternionf().rotationX(Math.toRadians(22.5).toFloat()), 22.5)
        }
        val scoop = originStableScoopPose(0.0, false)
        val dumped = (0..2).flatMap { index ->
            val at = originStableUnloadOffset(index)
            pieces(portion, "dump-$index", OriginWorkshopPoint(at.x.toDouble(), at.y.toDouble(), at.z.toDouble()))
        }
        fun camera(x: Double, y: Double, z: Double, tx: Double, ty: Double, tz: Double) = mapOf(
            "position" to mapOf("x" to x, "y" to y, "z" to z), "target" to mapOf("x" to tx, "y" to ty, "z" to tz), "fovDegrees" to 70)
        val states = listOf(
            mapOf("id" to "clean-floor", "title" to "Стойло Бурана · три порции и припаркованная тачка", "pieces" to floor,
                "cartRotationY" to (180.0 - stall.cart.yaw), "camera" to camera(-2.3, 1.62, -2.0, -2.3, 0.25, 1.3)),
            mapOf("id" to "clean-scoop", "title" to "Порция перед игроком · источник положения из runtime", "pieces" to pieces(portion, "scoop", scoop.center),
                "hideCart" to true, "camera" to camera(0.0, 1.62, 0.0, 0.15, 1.1, 1.0)),
            mapOf("id" to "clean-loaded-one", "title" to "Загрузка · одна порция в кузове", "pieces" to cargo(1)),
            mapOf("id" to "clean-loaded-full", "title" to "Полная тачка · три порции в кузове", "pieces" to cargo(3)),
            mapOf("id" to "clean-unloaded", "title" to "Выгрузка · пустая тачка и видимый груз на земле", "pieces" to dumped),
            mapOf("id" to "clean-return", "title" to "Возврат · пустая тачка", "pieces" to emptyList<Any>()),
        )
        val result = mapOf("states" to states,
            "model" to mapOf("itemId" to layout.cart.itemId, "scale" to listOf(layout.cart.scale.x, layout.cart.scale.y, layout.cart.scale.z),
                "lift" to layout.cart.lift, "context" to layout.cart.context.name),
            "anchors" to layout,
            "camera" to camera(0.0, 1.62, -1.0, 0.0, 0.65, 0.15),
            "coordinates" to mapOf("space" to "cart-ground-contact", "unit" to "block"),
            "evidence" to "Production manure geometry, cargo-bed offsets, scoop pose and source stall anchors. Native hand/shovel pose, world geometry, lighting and client motion are not simulated.")
        Files.writeString(output, GsonBuilder().setPrettyPrinting().create().toJson(result))
    }

    private fun writeCrossTies(output: Path) {
        val pad = ORIGIN_YARD_CARE_PAD
        val anchors = listOf(ORIGIN_YARD_TIE_FIRST, ORIGIN_YARD_TIE_SECOND)
        fun local(point: ru.arc.origin.scene.OriginScenePoint) = org.joml.Vector3f(
            (point.x - pad.x).toFloat(), (point.y - pad.y).toFloat(), (point.z - pad.z).toFloat())
        data class Frame(val name: String, val stage: OriginOddJobStage, val mask: Int, val pending: Int? = null,
            val nearHorse: Boolean = false, val clicks: Int = 0)
        val states = (0..1).flatMap { first ->
            val second = 1 - first
            val frames = listOf(
                Frame("choose-wall", OriginOddJobStage.YARD_TIE_FIRST, 0),
                Frame("first-loose-wall", OriginOddJobStage.YARD_TIE_HORSE_FIRST, 0, first, clicks = 1),
                Frame("first-loose-horse", OriginOddJobStage.YARD_TIE_HORSE_FIRST, 0, first, nearHorse = true, clicks = 1),
                Frame("first-attached", OriginOddJobStage.YARD_TIE_SECOND, 1 shl first, clicks = 2),
                Frame("second-loose-wall", OriginOddJobStage.YARD_TIE_HORSE_SECOND, 1 shl first, second, clicks = 3),
                Frame("second-loose-horse", OriginOddJobStage.YARD_TIE_HORSE_SECOND, 1 shl first, second, nearHorse = true, clicks = 3),
                Frame("both-attached", OriginOddJobStage.YARD_GROOM, 3, clicks = 4),
                Frame("place-saddle", OriginOddJobStage.YARD_SADDLE, 3, clicks = 4),
                Frame("lead-away", OriginOddJobStage.YARD_DELIVER, 0, clicks = 4),
            )
            frames.map { frame ->
                val work = OriginYardWork(422, "Буран", tieMask = frame.mask, pendingTieSide = frame.pending)
                val id = java.util.UUID(0, 0)
                val job = OriginOddJobSession(id, OriginOddJobKind.MOUNTYARD, frame.stage, 0, 240_000, 0,
                    OriginOddJobOffer(id, OriginOddJobVariant.YARD_SADDLE, 0, false), yard = work)
                val side = frame.pending ?: first
                val workerX = if (frame.nearHorse) (if (side == 0) -1.05 else 1.05)
                    else (anchors[side].x - pad.x) + if (side == 0) 0.7 else -0.7
                val worker = org.bukkit.Location(null, pad.x + workerX, pad.y, pad.z - 0.8, if (side == 0) -90f else 90f, 0f)
                val hand = originYardTieHand(worker, 1.8).let { org.joml.Vector3f((it.x - pad.x).toFloat(), (it.y - pad.y).toFloat(), (it.z - pad.z).toFloat()) }
                val targets = originYardTieTargets(job)
                val pieces = if (frame.stage == OriginOddJobStage.YARD_DELIVER) emptyList() else anchors.flatMapIndexed { side, anchor ->
                    val center = local(anchor)
                    val normal = org.joml.Vector3f(if (side == 0) 1f else -1f, 0f, 0f)
                    val cheek = originYardTieCheek(org.bukkit.Location(null,pad.x,pad.y,pad.z,pad.yaw,0f),1.6,side).let {
                        org.joml.Vector3f((it.x-pad.x).toFloat(),(it.y-pad.y).toFloat(),(it.z-pad.z).toFloat()) }
                    originYardRingPieces(side, center, normal) +
                        (when {
                            frame.pending == side -> originYardRopePieces(side, hand, center, slack = 0.22f) +
                                originYardRingPieces(side + 2, cheek, org.joml.Vector3f(normal).negate(), cue = true)
                            frame.mask and (1 shl side) != 0 -> originYardRopePieces(side, cheek, center)
                            else -> emptyList()
                        }) +
                        (if (side in targets) originYardRingPieces(side, center, normal, cue = true) else emptyList())
                }
                mapOf("id" to "side-$first-${frame.name}", "stage" to frame.stage.name, "mask" to frame.mask,
                    "pendingTieSide" to frame.pending, "wallTargets" to targets, "clicks" to frame.clicks,
                    "worker" to listOf(worker.x - pad.x, 0.0, worker.z - pad.z), "workerYaw" to worker.yaw,
                    "hand" to listOf(hand.x, hand.y, hand.z),
                    "saddled" to (frame.stage == OriginOddJobStage.YARD_DELIVER),
                    "pieces" to pieces.map { piece -> mapOf("key" to piece.key, "material" to piece.material.name,
                        "center" to listOf(piece.center.x, piece.center.y, piece.center.z),
                        "size" to listOf(piece.size.x, piece.size.y, piece.size.z),
                        "quaternion" to listOf(piece.rotation.x, piece.rotation.y, piece.rotation.z, piece.rotation.w)) })
            }
        }
        val scene = mapOf("states" to states, "origin" to listOf(pad.x, pad.y, pad.z), "horseYaw" to pad.yaw,
            "supports" to anchors.mapIndexed { side, anchor -> mapOf("x" to (if (side == 0) 55 else 64), "y" to 71, "z" to -128,
                "material" to "STONE_BRICKS", "anchor" to listOf(anchor.x, anchor.y, anchor.z)) },
            "evidence" to "Production rope/ring geometry, calm-horse cheek endpoint, right-hand endpoint and four-click state masks in both wall orders. Ties release automatically when the native saddle is equipped. Loose and attached poses share rope keys. Worker movement samples are a storyboard, not observed playback. Horse silhouette is approximate context; native hand pose, interpolation, lighting and glow require player verification.")
        Files.writeString(output, GsonBuilder().setPrettyPrinting().create().toJson(scene))
    }

}
