package ru.arc.origin

import com.google.gson.GsonBuilder
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import org.bukkit.Material
import org.bukkit.Location
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.paper.testing.MockBukkitTestRuntime
import org.joml.Quaternionf
import org.joml.Vector3f

class OriginYardGearTest : StringSpec({
    "every physical tool has bounded finite solid geometry and only contents change when filled" {
        OriginYardGear.entries.filterNot { it in setOf(OriginYardGear.PAD,OriginYardGear.BRIDLE) }.forEach { gear ->
            val pieces = originYardGearPieces(gear)
            (pieces.size in 1..25) shouldBe true
            pieces.forEach { p ->
                listOf(p.center.x, p.center.y, p.center.z).all(Double::isFinite) shouldBe true
                listOf(p.size.x, p.size.y, p.size.z).all { it.isFinite() && it > 0f && it <= 1.0f } shouldBe true
            }
        }
        originYardGearPieces(OriginYardGear.BUCKET, true).dropLast(1) shouldBe originYardGearPieces(OriginYardGear.BUCKET)
        originYardGearPieces(OriginYardGear.SHOVEL, true).dropLast(1) shouldBe originYardGearPieces(OriginYardGear.SHOVEL)
        originYardGearPieces(OriginYardGear.PAD) shouldBe emptyList()
        originYardGearPieces(OriginYardGear.BRIDLE) shouldBe emptyList()
        val saddle=originYardGearPieces(OriginYardGear.SADDLE)
        saddle.size shouldBe 5
        saddle.all { it.center.y-it.size.y/2 >= -.071 } shouldBe true
    }
    "retired tack stages expose no prop interaction or carried item" {
        val retired=OriginOddJobStage.entries.filter { stage ->
            listOf("YARD_PAD", "YARD_BRIDLE", "YARD_GIRTH", "YARD_STIRRUP", "YARD_UNTACK",
                "YARD_SADDLE_RETURN", "YARD_LEAD_RETURN").any { stage.name.startsWith(it) }
        }
        retired.forEach { originYardGearStep(it) shouldBe null }
    }
    "export exact representative gear geometry on the surveyed barrel and carpet" {
        YardGearPreviewExport.write(Path.of("build/yard-gear-preview/scene.json"))
    }
})

internal object YardGearPreviewExport {
    fun write(output: Path) {
        MockBukkitTestRuntime.open().use { paper ->
            val world=WorldMock().also { it.name="rc_origin_spawn"; paper.server.addWorld(it) }
            fun box(x:Double,y:Double,z:Double,w:Float,h:Float,d:Float,m:Material)=
                OriginWorkshopWorkpiecePiece(OriginWorkshopPoint(x,y,z),OriginWorkshopGamePartSize(w,h,d),m)
            fun pose(x:Double=0.0,y:Double=0.0,z:Double=0.0,rx:Double=0.0)=
                OriginYardGearPose(Location(world,x,y,z),Quaternionf().rotationX(Math.toRadians(rx).toFloat()))
            fun export(key:String,pieces:List<OriginWorkshopWorkpiecePiece>,pose:OriginYardGearPose=pose()):List<Map<String,Any>> {
                val euler=pose.rotation.getEulerAnglesXYZ(Vector3f())
                return pieces.mapIndexed { index,p ->
                    val v=pose.rotation.transform(Vector3f(p.center.x.toFloat(),p.center.y.toFloat(),p.center.z.toFloat()).mul(pose.scale))
                    mapOf("key" to "$key-$index","material" to p.material.name,
                        "x" to pose.at.x+v.x,"y" to pose.at.y+v.y,"z" to pose.at.z+v.z,
                        "width" to p.size.x*pose.scale,"height" to p.size.y*pose.scale,"depth" to p.size.z*pose.scale,
                        "rotationX" to Math.toDegrees(euler.x.toDouble()),"rotationY" to Math.toDegrees(euler.y.toDouble()),
                        "rotationZ" to Math.toDegrees(euler.z.toDouble()))
                }
            }
            world.getChunkAt(4,-9)
            val barrel=world.getBlockAt(65,70,-134).also { it.type=Material.BARREL }
            world.getBlockAt(65,71,-134).type=Material.BROWN_CARPET
            val station=OriginOddJobStation(Location(world,65.5,70.0,-133.5),barrel,setOf(Material.BARREL))
            val slots=originYardGearSlots(mapOf(OriginOddJobStationId.SADDLE_RACK to station)).associateBy { it.gear }
            fun slot(gear:OriginYardGear)=slots.getValue(gear).pose.let {
                OriginYardGearPose(it.at.clone().subtract(65.5,70.0,-133.5),it.rotation)
            }
            val native=export("native",listOf(box(0.0,.5,0.0,1f,1f,1f,Material.BARREL),
                box(0.0,1.03125,0.0,1f,.0625f,1f,Material.BROWN_WOOL),
                box(1.0,.5,0.0,1f,1f,1f,Material.BARREL),box(1.0,1.5,0.0,1f,1f,1f,Material.BARREL),
                box(0.0,.5,1.0,1f,1f,1f,Material.BARREL),box(0.0,1.5,1.0,1f,1f,1f,Material.BARREL)))
            val support=export("stand",originYardSaddleStandPieces(),pose(y=1.0625))+export("hooks",originYardSaddleHooksPieces())
            val rest=native+support+listOf(OriginYardGear.LEAD).flatMap {
                export(it.name,originYardGearPieces(it),slot(it)) }
            val saddle=originYardGearPieces(OriginYardGear.SADDLE)
            val rack=rest+export("saddle",saddle,slot(OriginYardGear.SADDLE))
            val carryPose=originYardGearCarryPose(OriginYardGear.SADDLE,180f,false)
            val carryAt=Location(world,-1.0,0.0,-1.2).add(carryPose.center.x,carryPose.center.y,carryPose.center.z)
            val carried=export("saddle",saddle,OriginYardGearPose(carryAt,carryPose.rotation))
            fun carried(gear: OriginYardGear): List<Map<String,Any>> {
                val local=originYardGearCarryPose(gear,180f,false)
                val at=Location(world,-1.0,0.0,-1.2).add(local.center.x,local.center.y,local.center.z)
                return export(gear.name,originYardGearPieces(gear),OriginYardGearPose(at,local.rotation))
            }
            fun camera(x:Double,y:Double,z:Double,tx:Double=0.0,ty:Double=1.2,tz:Double=0.0)=mapOf(
                "position" to mapOf("x" to x,"y" to y,"z" to z),"target" to mapOf("x" to tx,"y" to ty,"z" to tz),"fovDegrees" to 70)
            fun state(id:String,title:String,pieces:List<Map<String,Any>>,camera:Map<String,Any>)=mapOf(
                "id" to id,"title" to title,"role" to "Source-derived station/hand props only. Native horse saddle and client lighting require a Minecraft client.",
                "pieces" to pieces,"camera" to camera)
            val transferring=(0..20).map { tick ->
                val target=OriginYardGearPose(carryAt,carryPose.rotation)
                mapOf("pieces" to rest+export("saddle",saddle,originYardGearTransfer(slot(OriginYardGear.SADDLE),target,tick/10.0)))
            }
            val transfer=state("transfer","Стойка → рука — production transfer",transferring.first().getValue("pieces"),camera(-2.4,1.62,-3.0,-.4,1.1,-.6))+
                mapOf("frames" to transferring,"frameMillis" to 50)
            val yawStates=listOf(0f,90f,270f).map { yaw ->
                val grip=originYardGearCarryPose(OriginYardGear.BRUSH,yaw,false)
                val p=OriginYardGearPose(Location(world,grip.center.x,grip.center.y,grip.center.z),grip.rotation)
                state("brush-yaw-${yaw.toInt()}","Щётка · view yaw ${yaw.toInt()}°",export("BRUSH",originYardGearPieces(OriginYardGear.BRUSH),p),
                    camera(-1.8,1.62,-2.0,0.0,1.0,0.0))+mapOf("nativePlayer" to mapOf("yaw" to yaw,"x" to 0,"z" to 0))
            }
            val text=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                java.io.InputStreamReader(requireNotNull(YardGearPreviewExport::class.java.getResourceAsStream("/modules/origin-odd-jobs.yml"))))
            fun cue(stage:OriginOddJobStage,at:OriginYardGearPose):Map<String,Any> {
                val point=originYardGearHint(stage,requireNotNull(originYardGearStep(stage)),at)
                return mapOf("text" to requireNotNull(text.getString("text.gear-hints.${stage.name.lowercase()}")),
                    "anchor" to mapOf("x" to point.x,"y" to point.y,"z" to point.z),"style" to mapOf("scale" to .34,"lineWidth" to 160))
            }
            val pickup=state("pickup-saddle","Седло — текущий предмет",rack.map { p -> if((p["key"] as String).startsWith("saddle-")) p+mapOf("highlight" to true) else p },camera(-1.35,1.62,-2.0))+
                mapOf("guidance" to cue(OriginOddJobStage.YARD_TACK,slot(OriginYardGear.SADDLE)))
            val readScales=listOf(2.0,4.0,6.0).map { distance ->
                val scale=originYardGearDistanceScale(distance)
                val brushPose=originYardRackPose(pose(.29,1.025,-.32),scale)
                val bucketPose=originYardRackPose(pose(1.5,1.025,0.0),scale)
                val support=export("shelves",listOf(box(0.0,.5,0.0,1f,1f,1f,Material.BARREL),
                    box(1.5,.5,0.0,1f,1f,1f,Material.BARREL),box(0.0,1.5,0.0,1f,1f,1f,Material.LANTERN)))
                state("distance-${distance.toInt()}","Предметы на полке · ${distance.toInt()} м · ×${"%.2f".format(java.util.Locale.ROOT,scale)}",
                    support+export("brush",originYardGearPieces(OriginYardGear.BRUSH),brushPose)+export("bucket",originYardGearPieces(OriginYardGear.BUCKET),bucketPose),
                    camera(.75,1.62,-distance,.75,1.35,0.0))+
                    mapOf("guidance" to cue(OriginOddJobStage.YARD_BRUSH,brushPose))
            }
            val carryTurns=listOf(OriginYardGear.BUCKET,OriginYardGear.HAY).map { gear ->
                val frames=(0..80).map { frame ->
                    val yaw=(180.0+45.0*kotlin.math.sin(frame*Math.PI/40.0)).toFloat()
                    val grip=originYardGearCarryPose(gear,yaw,false)
                    mapOf("pieces" to export(gear.name,originYardGearPieces(gear),
                        pose(grip.center.x,grip.center.y,grip.center.z).copy(rotation=grip.rotation)))
                }
                state("carry-turn-${gear.name.lowercase()}","${gear.title} · непрерывные позы поворота, 20 Гц",frames.first().getValue("pieces"),
                    camera(-1.8,1.62,-2.0,0.0,1.05,0.0))+mapOf("frames" to frames,"frameMillis" to 50)
            }
            val scene=mapOf("states" to listOf(
                state("rack","Седло и повод на стойке",rack,camera(-1.35,1.62,-2.0)),
                state("carry","Седло в руке — production carry pose",rest+carried,camera(-2.4,1.62,-3.0,-.4,1.1,-.6)),
                state("carry-eye-saddle","Седло: вид несущего игрока",carried(OriginYardGear.SADDLE),camera(-1.0,1.62,-1.2,-1.0,1.62,-4.2)),
                state("carry-eye-bucket","Ведро: вид несущего игрока",carried(OriginYardGear.BUCKET),camera(-1.0,1.62,-1.2,-1.0,1.62,-4.2)),
                state("carry-eye-shovel","Лопата: вид несущего игрока",carried(OriginYardGear.SHOVEL),camera(-1.0,1.62,-1.2,-1.0,1.62,-4.2)),
                state("carry-down-saddle","Седло: взгляд вниз 30°",carried(OriginYardGear.SADDLE),camera(-1.0,1.62,-1.2,-1.0,1.62-kotlin.math.sqrt(3.0),-4.2)),
                state("carry-down-bucket","Ведро: взгляд вниз 30°",carried(OriginYardGear.BUCKET),camera(-1.0,1.62,-1.2,-1.0,1.62-kotlin.math.sqrt(3.0),-4.2)),
                state("carry-down-shovel","Лопата: взгляд вниз 30°",carried(OriginYardGear.SHOVEL),camera(-1.0,1.62,-1.2,-1.0,1.62-kotlin.math.sqrt(3.0),-4.2)),
                state("brush-hand","Щётка — правая ладонь native player",carried(OriginYardGear.BRUSH),camera(-2.6,1.62,-2.9,-1.0,1.0,-1.2)),
                state("empty-stand","Седло взято — опора остаётся",rest,camera(-1.35,1.62,-2.0)))+listOf(transfer,pickup)+yawStates+readScales+carryTurns)
            Files.createDirectories(output.parent)
            Files.writeString(output,GsonBuilder().setPrettyPrinting().create().toJson(scene))
        }
    }
}
