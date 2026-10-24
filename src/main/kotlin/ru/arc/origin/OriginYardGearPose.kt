package ru.arc.origin

import org.bukkit.Location
import org.bukkit.attribute.Attribute
import org.bukkit.entity.LivingEntity
import org.bukkit.util.Vector
import org.joml.Quaternionf
import org.joml.Vector3f
import kotlin.math.cos
import kotlin.math.sin

internal data class OriginYardGearPose(val at: Location, val rotation: Quaternionf = Quaternionf(), val scale: Float = 1f)

/** Rack enlargement preserves the authored contact point; held tools always use their normal size. */
internal fun originYardRackPose(pose: OriginYardGearPose, scale: Float, anchor: Vector3f = Vector3f()): OriginYardGearPose {
    val offset = pose.rotation.transform(Vector3f(anchor).mul(1f-scale))
    return OriginYardGearPose(pose.at.clone().add(offset.x.toDouble(),offset.y.toDouble(),offset.z.toDouble()),
        Quaternionf(pose.rotation),scale)
}

// Official 26.1.2: AbstractEquineModel body, LayerDefinitions.HORSE scaling(1.1),
// LivingEntityRenderer's 1.501 ground pivot. This is mesh scale, not Attribute.SCALE.
internal const val ORIGIN_YARD_HORSE_BACK = (1.501 - (11.0 - 8.0 - .05) / 16.0) * 1.1
internal const val ORIGIN_YARD_HORSE_SEAT = 1.44375

internal fun originYardHorseScale(horse: LivingEntity): Float =
    (horse.getAttribute(Attribute.SCALE)?.value ?: 1.0).toFloat()

/** HORSE declares one seat; native getClamped uses it for the real rider and every cosmetic child. */
internal fun originYardHorsePassengerOrigin(horse: LivingEntity): Vector =
    Vector(0.0, ORIGIN_YARD_HORSE_SEAT * originYardHorseScale(horse), 0.0)

/** World-axis offset. PacketDisplay's player parent contributes position/height, never a yaw transform. */
internal fun originYardGearCarryPose(gear: OriginYardGear, yawDegrees: Float, sneaking: Boolean): OriginWorkshopCarryPose {
    val yaw = Math.toRadians(yawDegrees.toDouble())
    val reach = when (gear) { OriginYardGear.SADDLE -> .65; OriginYardGear.PAD -> .62; OriginYardGear.BRUSH -> .09; else -> .43 }
    val handSide = if (gear == OriginYardGear.BRUSH) .352 else .30
    // AvatarRenderer scales the native arm by .9375: an idle palm is at y≈.82.
    // The brush's grip is .145 above its pivot, so the brush belongs below the hand, not at the shoulder.
    val height = when (gear) {
        OriginYardGear.SHOVEL -> .28; OriginYardGear.BUCKET -> .91; OriginYardGear.BRUSH -> .675
        OriginYardGear.BRIDLE -> 1.39; OriginYardGear.LEAD -> 1.23; OriginYardGear.PAD -> 1.17
        else -> 1.05
    }
    return OriginWorkshopCarryPose(OriginWorkshopPoint(-sin(yaw)*reach-cos(yaw)*handSide,
        height-(if (sneaking) .25 else 0.0), cos(yaw)*reach-sin(yaw)*handSide),
        Quaternionf().rotationY((Math.PI-yaw).toFloat()))
}

internal fun originYardGearHorsePose(gear: OriginYardGear, horse: LivingEntity): OriginYardGearPose =
    originYardGearHorsePose(gear, horse.location, horse.bodyYaw, originYardHorseScale(horse), horse.yaw, horse.pitch)

/** Mesh-local pose transformed once by the horse body. The bridle uses the real head-parts pivot. */
internal fun originYardGearHorsePose(gear: OriginYardGear, at: Location, bodyYaw: Float,
    scale: Float = 1f, headYaw: Float = bodyYaw, headPitch: Float = 0f): OriginYardGearPose {
    val body = Quaternionf().rotationY(Math.toRadians(180.0-bodyYaw).toFloat())
    var rotation = Quaternionf(body)
    val offset = when (gear) {
        OriginYardGear.PAD -> Vector3f(0f,(ORIGIN_YARD_HORSE_BACK+.020).toFloat(),-.08f)
        OriginYardGear.SADDLE -> Vector3f(0f,(ORIGIN_YARD_HORSE_BACK+.038).toFloat(),-.08f)
        OriginYardGear.BRIDLE -> {
            val relativeHead = ((headYaw-bodyYaw+540f)%360f-180f).coerceIn(-20f,20f)
            val head = Quaternionf().rotationY(Math.toRadians(-relativeHead.toDouble()).toFloat())
                .rotateX(Math.toRadians(-30.0-headPitch).toFloat())
            rotation.mul(head)
            head.transform(Vector3f(0f,.76625f,.05f)).add(0f,1.3761f,-.825f)
        }
        OriginYardGear.LEAD -> Vector3f(-.30f,1.63f,-.34f)
        else -> Vector3f()
    }
    body.transform(offset.mul(scale))
    return OriginYardGearPose(at.clone().add(offset.x.toDouble(),offset.y.toDouble(),offset.z.toDouble()),rotation,scale)
}

/** Smooth handoff from the currently rendered pose; a subsequent action can continue without a snap. */
internal fun originYardGearTransfer(from: OriginYardGearPose, target: OriginYardGearPose, progress: Double): OriginYardGearPose {
    val t = progress.coerceIn(0.0,1.0)
    val eased = t*t*(3-2*t)
    val at = from.at.clone().add((target.at.x-from.at.x)*eased,(target.at.y-from.at.y)*eased,
        (target.at.z-from.at.z)*eased).add(0.0,sin(Math.PI*t)*.12,0.0)
    return OriginYardGearPose(at,Quaternionf(from.rotation).slerp(target.rotation,eased.toFloat()),
        (from.scale+(target.scale-from.scale)*eased).toFloat())
}
