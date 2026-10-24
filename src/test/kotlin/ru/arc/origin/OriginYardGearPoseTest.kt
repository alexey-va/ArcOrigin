package ru.arc.origin

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.bukkit.Location
import org.bukkit.entity.LivingEntity
import org.bukkit.util.BoundingBox
import org.joml.Quaternionf
import org.joml.Vector3f
import org.mockbukkit.mockbukkit.world.WorldMock
import java.util.UUID

class OriginYardGearPoseTest : StringSpec({
    "distance scaling is bounded and continuous and preserves the shelf or hook contact" {
        originYardGearDistanceScale(0.0) shouldBe 1f
        originYardGearDistanceScale(2.0) shouldBe 1f
        originYardGearDistanceScale(4.0).toDouble() shouldBe (1.275 plusOrMinus .000001)
        originYardGearDistanceScale(6.0) shouldBe 1.55f
        originYardGearDistanceScale(40.0) shouldBe 1.55f
        val scales=(0..800).map { originYardGearDistanceScale(it/100.0) }
        scales.zipWithNext().all { (a,b) -> b>=a && b-a<.003f } shouldBe true
        var smooth=1f
        repeat(30) {
            val next=originYardGearScaleStep(smooth,1.55f)
            (next in smooth..1.55f && next-smooth<.14f) shouldBe true
            smooth=next
        }
        for(anchor in listOf(Vector3f(),Vector3f(0f,.2f,0f),Vector3f(-.11f,1.04f,0f))) {
            val base=OriginYardGearPose(Location(WorldMock(),.5,71.0,.5),Quaternionf().rotationY(.8f))
            val grown=originYardRackPose(base,1.55f,anchor)
            fun contact(pose:OriginYardGearPose)=pose.rotation.transform(Vector3f(anchor).mul(pose.scale))
                .add(pose.at.x.toFloat(),pose.at.y.toFloat(),pose.at.z.toFloat())
            (contact(grown).distance(contact(base))<.00001f) shouldBe true
        }
    }
    "enlarged rack geometry hint and interaction bounds agree for pickups and returns" {
        for((stage,gear) in listOf(OriginOddJobStage.YARD_BUCKET to OriginYardGear.BUCKET,
            OriginOddJobStage.YARD_BRUSH to OriginYardGear.BRUSH,
            OriginOddJobStage.YARD_BUCKET_RETURN to OriginYardGear.BUCKET,
            OriginOddJobStage.YARD_BRUSH_RETURN to OriginYardGear.BRUSH,
            OriginOddJobStage.CLEAN_TOOL_RETURN to OriginYardGear.SHOVEL)) {
            val pose=originYardRackPose(OriginYardGearPose(Location(WorldMock(),.5,71.0,.5)),1.55f)
            val job=OriginOddJobSession(UUID.randomUUID(),OriginOddJobKind.MOUNTYARD,stage,0,240000,0,
                OriginOddJobOffer(UUID.randomUUID(),OriginOddJobVariant.YARD_WATER,0,false),yard=OriginYardWork(422,"Буран"))
            val step=requireNotNull(originYardGearStep(stage))
            val geometry=originYardGearBounds(originYardGearTargetPieces(stage,gear),pose)
            val native=requireNotNull(originYardGearControl(job,step,pose).nativeTarget)
            val at=native.location()
            (at.x-native.width/2 <= geometry.minX+.00001 && at.x+native.width/2 >= geometry.maxX-.00001) shouldBe true
            (at.y <= geometry.minY+.00001 && at.y+native.height >= geometry.maxY-.00001) shouldBe true
            originYardGearHint(stage,step,pose).y shouldBe (geometry.maxY+.22 plusOrMinus .000001)
        }
    }
    "bucket and hay poses follow every small view turn including the yaw wrap" {
        for(gear in listOf(OriginYardGear.BUCKET,OriginYardGear.HAY)) {
            val poses=(-185..185).map { originYardGearCarryPose(gear,it.toFloat(),false) }
            poses.zipWithNext().all { (a,b) ->
                val delta=Vector3f((b.center.x-a.center.x).toFloat(),(b.center.y-a.center.y).toFloat(),(b.center.z-a.center.z).toFloat())
                delta.length() in .001f.. .011f && kotlin.math.abs(a.rotation.dot(b.rotation))>.999f
            } shouldBe true
            val left=originYardGearCarryPose(gear,179.9f,false)
            val right=originYardGearCarryPose(gear,-179.9f,false)
            (kotlin.math.abs(left.rotation.dot(right.rotation))>.999f) shouldBe true
        }
    }
    "brush handle stays at native right palm independently of look direction" {
        for(bodyYaw in listOf(0f,90f,180f,270f)) {
            val pose=originYardGearCarryPose(OriginYardGear.BRUSH,bodyYaw,false)
            (pose.center.y+.145) shouldBe (.82 plusOrMinus .000001)
            val local=pose.rotation.transformInverse(Vector3f(pose.center.x.toFloat(),0f,pose.center.z.toFloat()))
            local.x.toDouble() shouldBe (.352 plusOrMinus .000001)
            local.z.toDouble() shouldBe (-.09 plusOrMinus .000001)
        }
    }
    "tool movement stays continuous when its target changes" {
        val world=WorldMock()
        fun at(x:Double)=OriginYardGearPose(Location(world,x,1.0,0.0),Quaternionf())
        val motion=OriginYardGearMotion()
        motion.sample(at(0.0),"rack",0,false)
        motion.sample(at(2.0),"hand",1,true).at.x shouldBe 0.0
        val mid=motion.sample(at(2.0),"hand",6,true)
        mid.at.x shouldBe (1.0 plusOrMinus .000001)
        val next=motion.sample(at(3.0),"ground",6,true)
        next.at.distance(mid.at) shouldBe (0.0 plusOrMinus .000001)
        motion.sample(at(3.0),"ground",16,true).at.x shouldBe 3.0
        motion.sample(at(0.0),"rack",17,true).at.x shouldBe 3.0
        // A return already in motion reaches its rack target smoothly.
        motion.sample(at(0.0),"rack",22,false).at.x shouldBe (1.5 plusOrMinus .000001)
        motion.sample(at(0.0),"rack",27,false).at.x shouldBe 0.0
        motion.sample(at(3.0),"hand",28,true)
        motion.sample(at(0.0),"rack",29,false).at.x shouldBe 0.0
    }
    "saddling targets the whole native horse from either side and places its cue above the body" {
        val world=WorldMock()
        val job=OriginOddJobSession(UUID.randomUUID(),OriginOddJobKind.MOUNTYARD,OriginOddJobStage.YARD_SADDLE,
            0,240000,0,OriginOddJobOffer(UUID.randomUUID(),OriginOddJobVariant.YARD_SADDLE,0,false),yard=OriginYardWork(422,"Буран"))
        val native=BoundingBox(-.70,70.0,-.85,.70,71.60,.85)
        val horse=mockk<LivingEntity> { every { boundingBox } returns native }
        val bounds=originYardHorseBounds(horse)
        val pose=OriginYardGearPose(Location(world,0.0,70.0,0.0))
        val step=requireNotNull(originYardGearStep(job.stage))
        val control=originYardGearControl(job,step,pose,bounds)
        val target=requireNotNull(control.nativeTarget)
        val centre=target.location()
        (centre.x-target.width/2 <= native.minX && centre.x+target.width/2 >= native.maxX) shouldBe true
        (centre.z-target.width/2 <= native.minZ && centre.z+target.width/2 >= native.maxZ) shouldBe true
        centre.y shouldBe 70.0
        (centre.y+target.height >= native.maxY) shouldBe true
        (originYardGearHint(job.stage,step,pose,bounds).y > native.maxY) shouldBe true
        control.planes.size shouldBe 6
    }
    "returning a shovel targets its visible bracket instead of the absent blade" {
        val pose=OriginYardGearPose(Location(WorldMock(),56.12,70.04,-133.38))
        val job=OriginOddJobSession(UUID.randomUUID(),OriginOddJobKind.MOUNTYARD,OriginOddJobStage.CLEAN_TOOL_RETURN,
            0,240000,0,OriginOddJobOffer(UUID.randomUUID(),OriginOddJobVariant.YARD_CLEANUP,0,false),yard=OriginYardWork(422,"Буран"))
        val step=requireNotNull(originYardGearStep(job.stage))
        val target=requireNotNull(originYardGearControl(job,step,pose).nativeTarget)
        val hook=originYardGearBounds(originYardShovelHookPieces(),pose)
        (target.width >= .59f && target.height >= .59f) shouldBe true
        (target.location().y > pose.at.y+.7) shouldBe true
        (target.location().y < hook.minY) shouldBe true
        (target.location().y+target.height > hook.maxY) shouldBe true
        (originYardGearHint(job.stage,step,pose).y > hook.maxY) shouldBe true

        val pickup=job.copy(stage=OriginOddJobStage.CLEAN_TOOL)
        val pickupTarget=requireNotNull(originYardGearControl(pickup,requireNotNull(originYardGearStep(pickup.stage)),pose).nativeTarget)
        pickupTarget.location().y shouldBe (pose.at.y plusOrMinus .000001)
        (pickupTarget.height > 1f) shouldBe true
    }
})
