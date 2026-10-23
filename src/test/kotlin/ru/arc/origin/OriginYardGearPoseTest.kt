package ru.arc.origin

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import org.bukkit.Location
import org.joml.Quaternionf
import org.joml.Vector3f
import org.mockbukkit.mockbukkit.world.WorldMock
import java.util.UUID
import kotlin.math.abs

class OriginYardGearPoseTest : StringSpec({
    "native scaled horse back clears cloth and tightened girth meets belly at all cardinal yaws" {
        val world=WorldMock()
        for(yaw in listOf(0f,90f,180f,270f)) for(scale in listOf(1f,1.2f)) {
            val at=Location(world,4.0,70.0,-8.0)
            val pad=originYardGearHorsePose(OriginYardGear.PAD,at,yaw,scale)
            val saddle=originYardGearHorsePose(OriginYardGear.SADDLE,at,yaw,scale)
            val cloth=originYardGearPieces(OriginYardGear.PAD)
            val top=cloth.first()
            val underside=pad.at.y+(top.center.y-top.size.y/2)*scale
            (underside > at.y+ORIGIN_YARD_HORSE_BACK*scale) shouldBe true
            // Actual native body outer side is .3471875; inner cloth surface is .351.
            (abs(cloth[5].center.x)-cloth[5].size.x/2 > .3471875) shouldBe true
            val belt=originYardGearPieces(OriginYardGear.SADDLE,girthTight=true)[23]
            val beltTop=saddle.at.y+(belt.center.y+belt.size.y/2)*scale
            (beltTop <= at.y+.7539125*scale) shouldBe true
            (at.y+.7539125*scale-beltTop < .015*scale) shouldBe true
            val local=pad.rotation.transformInverse(Vector3f((pad.at.x-at.x).toFloat(),0f,(pad.at.z-at.z).toFloat()))
            local.x.toDouble() shouldBe (0.0 plusOrMinus .00001)
            local.z.toDouble() shouldBe (-.08*scale plusOrMinus .00001)
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
    "a second stage continues the visible handoff and cancellation snaps only to safe home" {
        val world=WorldMock()
        fun at(x:Double)=OriginYardGearPose(Location(world,x,1.0,0.0),Quaternionf())
        val motion=OriginYardGearMotion()
        motion.sample(at(0.0),"rack",0,false)
        motion.sample(at(2.0),"hand",1,true).at.x shouldBe 0.0
        val mid=motion.sample(at(2.0),"hand",6,true)
        mid.at.x shouldBe (1.0 plusOrMinus .000001)
        val next=motion.sample(at(3.0),"horse",6,true)
        next.at.distance(mid.at) shouldBe (0.0 plusOrMinus .000001)
        motion.sample(at(3.0),"horse",16,true).at.x shouldBe 3.0
        motion.sample(at(0.0),"rack",17,true).at.x shouldBe 3.0
        // Final physical return already started; removing the completed job cannot cancel its motion.
        motion.sample(at(0.0),"rack",22,false).at.x shouldBe (1.5 plusOrMinus .000001)
        motion.sample(at(0.0),"rack",27,false).at.x shouldBe 0.0
        motion.sample(at(3.0),"hand",28,true)
        motion.sample(at(0.0),"rack",29,false).at.x shouldBe 0.0
    }
    "tightening advances continuously and retains part identity" {
        val animation=OriginYardGearAdjustment()
        animation.sample(false,0) shouldBe 0.0
        animation.sample(true,1) shouldBe 0.0
        val samples=(2..9).map { animation.sample(true,it) }
        samples.zipWithNext().all { (a,b)->b>a } shouldBe true
        samples.last() shouldBe 1.0
        val before=originYardGearPieces(OriginYardGear.SADDLE)
        val after=originYardGearPieces(OriginYardGear.SADDLE,false,.5,.5,.5)
        before.size shouldBe after.size
        before.map { it.material } shouldBe after.map { it.material }
    }
    "girth has a forgiving side target and only its actual straps glow" {
        val world=WorldMock()
        val job=OriginOddJobSession(UUID.randomUUID(),OriginOddJobKind.MOUNTYARD,OriginOddJobStage.YARD_GIRTH,
            0,240000,0,OriginOddJobOffer(UUID.randomUUID(),OriginOddJobVariant.YARD_SADDLE,0,false),yard=OriginYardWork(422,"Буран"))
        val pose=originYardGearHorsePose(OriginYardGear.SADDLE,Location(world,0.0,0.0,0.0),180f)
        val control=originYardGearControl(job,requireNotNull(originYardGearStep(job.stage)),pose)
        val target=requireNotNull(control.nativeTarget)
        (target.location().x < -.2) shouldBe true
        originYardGearFocus(job.stage) shouldBe setOf(21,22,23,24)
        originYardGearSideMatches(job.stage,Location(world,-1.0,0.0,0.0),pose) shouldBe true
        originYardGearSideMatches(job.stage,Location(world,1.0,0.0,0.0),pose) shouldBe false
        originYardGearFocus(OriginOddJobStage.YARD_STIRRUP_LEFT)!!.intersect(originYardGearFocus(OriginOddJobStage.YARD_STIRRUP_RIGHT)!!) shouldBe emptySet()
    }
})
