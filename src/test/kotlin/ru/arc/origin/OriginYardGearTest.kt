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
        OriginYardGear.entries.forEach { gear ->
            val pieces = originYardGearPieces(gear)
            (pieces.size in 1..24) shouldBe true
            pieces.forEach { p ->
                listOf(p.center.x, p.center.y, p.center.z).all(Double::isFinite) shouldBe true
                listOf(p.size.x, p.size.y, p.size.z).all { it.isFinite() && it > 0f && it <= 1.0f } shouldBe true
            }
        }
        originYardGearPieces(OriginYardGear.BUCKET, true).dropLast(1) shouldBe originYardGearPieces(OriginYardGear.BUCKET)
        originYardGearPieces(OriginYardGear.SHOVEL, true).dropLast(1) shouldBe originYardGearPieces(OriginYardGear.SHOVEL)
        originYardGearPieces(OriginYardGear.SADDLE, leftStirrup = true).zip(originYardGearPieces(OriginYardGear.SADDLE))
            .count { (a,b) -> a != b } shouldBe 5
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
                    val v=pose.rotation.transform(Vector3f(p.center.x.toFloat(),p.center.y.toFloat(),p.center.z.toFloat()))
                    mapOf("key" to "$key-$index","material" to p.material.name,
                        "x" to pose.at.x+v.x,"y" to pose.at.y+v.y,"z" to pose.at.z+v.z,
                        "width" to p.size.x,"height" to p.size.y,"depth" to p.size.z,
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
            val rest=native+support+listOf(OriginYardGear.PAD,OriginYardGear.BRIDLE,OriginYardGear.LEAD).flatMap {
                export(it.name,originYardGearPieces(it),slot(it)) }
            val saddle=originYardGearPieces(OriginYardGear.SADDLE)
            val rack=rest+export("saddle",saddle,slot(OriginYardGear.SADDLE))
            val carryPose=originYardGearCarryPose(OriginYardGear.SADDLE,180f,false)
            val carryAt=Location(world,-1.0,0.0,-1.2).add(carryPose.center.x,carryPose.center.y,carryPose.center.z)
            val carried=export("saddle",saddle,OriginYardGearPose(carryAt,carryPose.rotation))
            val carriedAfterRide=export("saddle",originYardGearPieces(OriginYardGear.SADDLE,
                girthTight=true,leftStirrup=true,rightStirrup=true),OriginYardGearPose(carryAt,carryPose.rotation))
            fun carried(gear: OriginYardGear): List<Map<String,Any>> {
                val local=originYardGearCarryPose(gear,180f,false)
                val at=Location(world,-1.0,0.0,-1.2).add(local.center.x,local.center.y,local.center.z)
                return export(gear.name,originYardGearPieces(gear),OriginYardGearPose(at,local.rotation))
            }
            // Adult resting horse cubes from official MC26.1.2 AbstractEquineModel, in metres.
            // Neutral wool is a fit mannequin; this is not a capture of the client's horse texture or gait.
            val horse=export("horse-body",listOf(box(0.0,1.0,-.0625,.63125f,.63125f,1.38125f,Material.WHITE_WOOL)))+
                export("horse-head",listOf(box(-.003125,0.0,.09375,.25f,.75f,.4375f,Material.WHITE_WOOL),
                    box(0.0,.53125,.09375,.375f,.3125f,.4375f,Material.WHITE_WOOL),
                    box(0.0,.53125,-.28125,.25f,.3125f,.3125f,Material.WHITE_WOOL),
                    box(0.0,.1875,.375625,.125f,1f,.125f,Material.BLACK_WOOL)),pose(y=1.25,z=-.75,rx=-30.0))+
                export("horse-legs",listOf(-1,1).flatMap { side -> listOf(
                    box(side*.1875,.34375,.5,.25f,.6875f,.25f,Material.WHITE_WOOL),
                    box(side*.1875,.34375,-.5625,.25f,.6875f,.25f,Material.WHITE_WOOL)) })
            val equipped=listOf(OriginYardGear.PAD,OriginYardGear.SADDLE,OriginYardGear.BRIDLE).flatMap { gear ->
                export(gear.name,originYardGearPieces(gear,girthTight=true,leftStirrup=true,rightStirrup=true),
                    originYardGearHorsePose(gear,Location(world,0.0,0.0,0.0),180f)) }
            val unsaddled=horse+export("PAD",originYardGearPieces(OriginYardGear.PAD),
                originYardGearHorsePose(OriginYardGear.PAD,Location(world,0.0,0.0,0.0),180f))
            fun camera(x:Double,y:Double,z:Double,tx:Double=0.0,ty:Double=1.2,tz:Double=0.0)=mapOf(
                "position" to mapOf("x" to x,"y" to y,"z" to z),"target" to mapOf("x" to tx,"y" to ty,"z" to tz),"fovDegrees" to 70)
            fun state(id:String,title:String,pieces:List<Map<String,Any>>,camera:Map<String,Any>)=mapOf(
                "id" to id,"title" to title,"role" to "Source geometry and production poses. Native horse is a source-derived fit mannequin; client gait/lighting unverified.",
                "pieces" to pieces,"camera" to camera)
            val scene=mapOf("states" to listOf(
                state("rack","Амуниция на стойке",rack,camera(-1.35,1.62,-2.0)),
                state("carry","Седло в руке — production carry pose",rest+carried,camera(-2.4,1.62,-3.0,-.4,1.1,-.6)),
                state("carry-eye-saddle","Седло: вид несущего игрока",carried(OriginYardGear.SADDLE),camera(-1.0,1.62,-1.2,-1.0,1.62,-4.2)),
                state("carry-eye-bucket","Ведро: вид несущего игрока",carried(OriginYardGear.BUCKET),camera(-1.0,1.62,-1.2,-1.0,1.62,-4.2)),
                state("carry-eye-shovel","Лопата: вид несущего игрока",carried(OriginYardGear.SHOVEL),camera(-1.0,1.62,-1.2,-1.0,1.62,-4.2)),
                state("carry-down-saddle","Седло: взгляд вниз 30°",carried(OriginYardGear.SADDLE),camera(-1.0,1.62,-1.2,-1.0,1.62-kotlin.math.sqrt(3.0),-4.2)),
                state("carry-down-bucket","Ведро: взгляд вниз 30°",carried(OriginYardGear.BUCKET),camera(-1.0,1.62,-1.2,-1.0,1.62-kotlin.math.sqrt(3.0),-4.2)),
                state("carry-down-shovel","Лопата: взгляд вниз 30°",carried(OriginYardGear.SHOVEL),camera(-1.0,1.62,-1.2,-1.0,1.62-kotlin.math.sqrt(3.0),-4.2)),
                state("horse-left","Лошадь: вальтрап, седло, подпруга, уздечка",horse+equipped,camera(-2.6,1.62,-1.6)),
                state("horse-right","Лошадь: второе стремя",horse+equipped,camera(2.6,1.62,-1.6)),
                state("untack","Седло снято вручную",unsaddled+carriedAfterRide,camera(-2.6,1.62,-2.0)),
                state("return","Седло возвращено на ту же опору",rack,camera(-1.35,1.62,-2.0))))
            Files.createDirectories(output.parent)
            Files.writeString(output,GsonBuilder().setPrettyPrinting().create().toJson(scene))
        }
    }
}
