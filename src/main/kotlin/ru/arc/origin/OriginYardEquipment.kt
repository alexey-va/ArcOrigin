package ru.arc.origin

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.util.Vector
import org.joml.Quaternionf
import org.joml.Vector3f
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.scene.*
import java.util.UUID
import kotlin.math.max

internal data class OriginYardGearPose(val at: Location, val rotation: Quaternionf = Quaternionf())
internal data class OriginYardGearSlot(val gear: OriginYardGear, val pose: OriginYardGearPose, val station: OriginOddJobStation)

/** Locations are relative to the surveyed existing barrels. No blocks, inventories or chunks are changed. */
internal fun originYardGearSlots(stations: Map<OriginOddJobStationId, OriginOddJobStation>): List<OriginYardGearSlot> = buildList {
    fun station(id: OriginOddJobStationId) = stations[id]?.takeIf {
        it.block.world.isChunkLoaded(it.block.x shr 4, it.block.z shr 4) && it.valid()
    }
    fun put(gear: OriginYardGear, source: OriginOddJobStation, x: Double, y: Double, z: Double, rz: Double = 0.0, rx: Double = 0.0) {
        add(OriginYardGearSlot(gear, OriginYardGearPose(source.block.location.clone().add(.5+x,y,.5+z),
            Quaternionf().rotationXYZ(Math.toRadians(rx).toFloat(),0f,Math.toRadians(rz).toFloat())), source))
    }
    station(OriginOddJobStationId.SADDLE_RACK)?.let { source ->
        val above = source.block.getRelative(0,1,0)
        val carpet = if (above.type.name.endsWith("_CARPET")) 1.0/16 else 0.0
        put(OriginYardGear.SADDLE,source,0.0,1.75+carpet,0.0)
        put(OriginYardGear.PAD,source,-.54,.62,0.0,-90.0)
        put(OriginYardGear.BRIDLE,source,.21,.70,-.92,rx=-90.0)
        put(OriginYardGear.LEAD,source,-.28,.60,-.56)
    }
    station(OriginOddJobStationId.WELL)?.let { put(OriginYardGear.BUCKET,it,0.0,1.0,0.0) }
    station(OriginOddJobStationId.BRUSH)?.let {
        // Its centre lantern remains clear: brush sits on the north-east corner, shovel on the east hook.
        put(OriginYardGear.BRUSH,it,.29,1.0,-.32)
        put(OriginYardGear.SHOVEL,it,.62,.04,.12)
    }
    station(OriginOddJobStationId.HAY)?.let { put(OriginYardGear.HAY,it,0.0,1.0,0.0) }
}

internal fun originYardGearCarryPose(gear: OriginYardGear, bodyYaw: Float, sneaking: Boolean): OriginWorkshopCarryPose {
    val yaw=Math.toRadians(bodyYaw.toDouble())
    val forward=Vector(-kotlin.math.sin(yaw),0.0,kotlin.math.cos(yaw))
    val right=Vector(-kotlin.math.cos(yaw),0.0,-kotlin.math.sin(yaw))
    val reach=when(gear) { OriginYardGear.SADDLE -> .65; OriginYardGear.PAD -> .78; else -> .43 }
    val offset=forward.multiply(reach).add(right.multiply(.30))
    // The grip/upper edge stays at the bottom of a normal first-person view; the centre stays clear.
    val height=when(gear) {
        OriginYardGear.SHOVEL -> .28; OriginYardGear.BUCKET -> .91; OriginYardGear.BRUSH -> 1.25
        OriginYardGear.BRIDLE -> 1.39; OriginYardGear.LEAD -> 1.23; OriginYardGear.PAD -> 1.17
        else -> 1.05
    }
    return OriginWorkshopCarryPose(OriginWorkshopPoint(offset.x,height-(if(sneaking) .25 else 0.0),offset.z),
        Quaternionf().rotationY((Math.PI-yaw).toFloat()))
}

internal fun originYardGearHorsePose(gear: OriginYardGear, horse: LivingEntity): OriginYardGearPose =
    originYardGearHorsePose(gear,horse.location,horse.bodyYaw)

internal fun originYardGearHorsePose(gear: OriginYardGear, at: Location, bodyYaw: Float): OriginYardGearPose {
    val yaw = Math.toRadians(bodyYaw.toDouble())
    val rotation = Quaternionf().rotationY((Math.PI-yaw).toFloat())
    val offset = when (gear) {
        OriginYardGear.PAD -> Vector3f(0f,1.3275f,-.08f)
        OriginYardGear.SADDLE -> Vector3f(0f,1.35f,-.08f)
        OriginYardGear.BRIDLE -> Vector3f(0f,1.8562f,-1.10f)
        OriginYardGear.LEAD -> Vector3f(-.25f,1.40f,-.34f)
        else -> Vector3f()
    }
    rotation.transform(offset)
    if (gear==OriginYardGear.BRIDLE) rotation.rotateX(-Math.PI.toFloat()/6)
    return OriginYardGearPose(at.clone().add(offset.x.toDouble(),offset.y.toDouble(),offset.z.toDouble()),rotation)
}

internal fun originYardGearSideMatches(stage: OriginOddJobStage, playerAt: Location, horsePose: OriginYardGearPose): Boolean {
    val expected = when(stage) {
        OriginOddJobStage.YARD_GIRTH, OriginOddJobStage.YARD_STIRRUP_LEFT -> -1
        OriginOddJobStage.YARD_STIRRUP_RIGHT -> 1
        else -> return true
    }
    if (playerAt.world != horsePose.at.world) return false
    val local = horsePose.rotation.transformInverse(Vector3f((playerAt.x-horsePose.at.x).toFloat(),
        (playerAt.y-horsePose.at.y).toFloat(),(playerAt.z-horsePose.at.z).toFloat()))
    return local.x*expected > .20f
}

internal fun originYardGearBounds(geometry: List<OriginWorkshopWorkpiecePiece>, pose: OriginYardGearPose): OriginWorkshopAabb {
    val points = geometry.flatMap { p -> listOf(-1,1).flatMap { x -> listOf(-1,1).flatMap { y -> listOf(-1,1).map { z ->
        pose.rotation.transform(Vector3f((p.center.x+x*p.size.x/2).toFloat(),(p.center.y+y*p.size.y/2).toFloat(),
            (p.center.z+z*p.size.z/2).toFloat()))
    } } } }
    return OriginWorkshopAabb(pose.at.x+points.minOf { it.x },pose.at.y+points.minOf { it.y },pose.at.z+points.minOf { it.z },
        pose.at.x+points.maxOf { it.x },pose.at.y+points.maxOf { it.y },pose.at.z+points.maxOf { it.z })
}

internal fun originYardGearControl(job: OriginOddJobSession, step: OriginYardGearStep, pose: OriginYardGearPose): SpatialControl<OriginYardGearStep> {
    var bounds=originYardGearBounds(originYardGearPieces(step.gear),pose)
    if (!step.atRack && job.stage in setOf(OriginOddJobStage.YARD_STIRRUP_LEFT,OriginOddJobStage.YARD_STIRRUP_RIGHT)) {
        val side=if (step.zone==0) -1 else 1
        val local=pose.rotation.transform(Vector3f(side*.43f,-.375f,0f))
        bounds=OriginWorkshopAabb(pose.at.x+local.x-.10,pose.at.y+local.y-.14,pose.at.z+local.z-.12,
            pose.at.x+local.x+.10,pose.at.y+local.y+.14,pose.at.z+local.z+.12)
    } else if (!step.atRack && job.stage == OriginOddJobStage.YARD_GIRTH) {
        val local=pose.rotation.transform(Vector3f(-.36f,-.28f,.21f))
        bounds=OriginWorkshopAabb(pose.at.x+local.x-.07,pose.at.y+local.y-.15,pose.at.z+local.z-.055,
            pose.at.x+local.x+.07,pose.at.y+local.y+.15,pose.at.z+local.z+.055)
    }
    val x=(bounds.minX+bounds.maxX)/2; val y=(bounds.minY+bounds.maxY)/2; val z=(bounds.minZ+bounds.maxZ)/2
    val w=(bounds.maxX-bounds.minX).coerceAtLeast(.10); val h=(bounds.maxY-bounds.minY).coerceAtLeast(.10); val d=(bounds.maxZ-bounds.minZ).coerceAtLeast(.10)
    fun at(x:Double,y:Double,z:Double)=Location(pose.at.world,x,y,z)
    val planes=listOf(
        SpatialHitPlane(at(x,y,z-d/2),Vector(1,0,0),Vector(0,1,0),w,h),
        SpatialHitPlane(at(x,y,z+d/2),Vector(1,0,0),Vector(0,1,0),w,h),
        SpatialHitPlane(at(x-w/2,y,z),Vector(0,0,1),Vector(0,1,0),d,h),
        SpatialHitPlane(at(x+w/2,y,z),Vector(0,0,1),Vector(0,1,0),d,h),
        SpatialHitPlane(at(x,y-h/2,z),Vector(1,0,0),Vector(0,0,1),w,d),
        SpatialHitPlane(at(x,y+h/2,z),Vector(1,0,0),Vector(0,0,1),w,d))
    return SpatialControl("gear",step,planes,SpatialNativeTarget(at(x,y-h/2,z),max(w,d).toFloat(),h.toFloat()))
}

/** Persistent public props and one current private native input target per worker. Loans live only in the job. */
internal class OriginYardEquipment(
    private val createDisplays: () -> PaperPacketDisplays = { PaperPacketDisplays(ArcOriginPlugin.instance,"odd-jobs-yard-gear") },
) : AutoCloseable {
    private lateinit var stations: () -> Map<OriginOddJobStationId,OriginOddJobStation>
    private lateinit var jobs: () -> List<Pair<UUID,OriginOddJobSession>>
    private lateinit var animal: (Int) -> LivingEntity?
    private lateinit var activate: (Player,OriginOddJobSession,OriginYardGearStep) -> Unit
    private val props=mutableMapOf<OriginYardGear,OriginWorkshopProp>()
    private val geometry=mutableMapOf<OriginYardGear,List<OriginWorkshopWorkpiecePiece>>()
    private val placed=mutableMapOf<OriginYardGear,OriginYardGearPose>()
    private val scenes=mutableSetOf<SpatialSceneKey>()
    private var stand: OriginWorkshopProp?=null
    private var hooks: OriginWorkshopProp?=null
    private var shovelHook: OriginWorkshopProp?=null
    private var standAt: Location?=null
    private var shovelHookAt: Location?=null
    private var owner: PaperPacketDisplays?=null
    private var controls: PaperSpatialControls<OriginOddJobSession,OriginYardGearStep>?=null
    private var closed=false

    fun bind(stations: () -> Map<OriginOddJobStationId,OriginOddJobStation>, jobs: () -> List<Pair<UUID,OriginOddJobSession>>,
             animal: (Int) -> LivingEntity?, activate: (Player,OriginOddJobSession,OriginYardGearStep) -> Unit) {
        this.stations=stations; this.jobs=jobs; this.animal=animal; this.activate=activate
    }
    fun available(gear: OriginYardGear, requestId: UUID): Boolean = jobs().none { (_,job) -> job.id!=requestId && gear in job.yard?.borrowed.orEmpty() }
    private fun key(id:UUID)=SpatialSceneKey("odd-job-yard-gear:$id")
    private fun renderer()=owner ?: createDisplays().also { displays ->
        owner=displays
        controls=PaperSpatialControls(ArcOriginPlugin.instance,displays,object:SpatialControlController<OriginOddJobSession,OriginYardGearStep>() {
            override fun current(player:Player,scene:SpatialSceneKey):SpatialControlFrame<OriginOddJobSession,OriginYardGearStep>? =
                jobs().firstOrNull { it.first==player.uniqueId && key(it.second.id)==scene }?.second?.let { frame(player,it) }
            override fun activate(player:Player,scene:SpatialSceneKey,context:OriginOddJobSession,action:OriginYardGearStep) {
                this@OriginYardEquipment.activate(player,context,action)
            }
            override fun inputFailed(player:Player,scene:SpatialSceneKey,context:OriginOddJobSession,failure:Exception) {
                clear(player.uniqueId,context.id)
                throw failure
            }
        })
    }
    private fun pose(job:OriginOddJobSession,step:OriginYardGearStep):OriginYardGearPose? = if(step.atRack)
        originYardGearSlots(stations()).firstOrNull { it.gear==step.gear }?.pose
        else job.yard?.animalId?.let(animal)?.let { originYardGearHorsePose(step.gear,it) }
    private fun frame(player:Player,job:OriginOddJobSession):SpatialControlFrame<OriginOddJobSession,OriginYardGearStep>? {
        val step=originYardGearStep(job.stage) ?: return null
        val pose=pose(job,step) ?: return null
        if(player.world.uid!=pose.at.world?.uid) return null
        return SpatialControlFrame(job,SceneAudience.Private(player.uniqueId),SpatialSceneScope.Viewer(player.uniqueId),
            listOf(originYardGearControl(job,step,pose)),SpatialInputProfile.ENTITY_BOTH,SpatialReach.NativeInteraction)
    }
    fun target(job:OriginOddJobSession):Location? = originYardGearStep(job.stage)?.let { step ->
        pose(job,step)?.let { originYardGearControl(job,step,it).nativeTarget?.location()?.add(0.0,.12,0.0) }
    }
    fun intercept(player:Player,job:OriginOddJobSession):Boolean {
        if(originYardGearStep(job.stage)==null) return false
        sync(player,job)
        return controls?.intercept(key(job.id),player)==true
    }
    fun sync(player:Player,job:OriginOddJobSession) {
        if(closed || job.kind!=OriginOddJobKind.MOUNTYARD) return
        val frame=frame(player,job)
        if(frame==null) { controls?.clear(key(job.id)); scenes.remove(key(job.id)); return }
        renderer(); controls?.apply(key(job.id),frame); scenes.add(key(job.id))
    }
    fun tick() {
        if(closed || !::stations.isInitialized) return
        val slots=originYardGearSlots(stations())
        val validGear=slots.map { it.gear }.toSet()
        (props.keys-validGear).toList().forEach { gear -> props.remove(gear)?.remove(); geometry.remove(gear); placed.remove(gear) }
        val live=jobs().filter { it.second.kind==OriginOddJobKind.MOUNTYARD }
        val loans=live.flatMap { (id,job) -> job.yard?.borrowed.orEmpty().map { it to (id to job) } }
        check(loans.map { it.first }.distinct().size==loans.size) { "yard gear has two borrowers" }
        val borrowed=loans.toMap()
        val displays=renderer()
        val saddle=slots.firstOrNull { it.gear==OriginYardGear.SADDLE }
        if(saddle?.pose?.at != standAt) {
            stand?.remove(); hooks?.remove(); stand=null; hooks=null; standAt=saddle?.pose?.at?.clone()
        }
        if(saddle!=null && stand==null) {
            val base=saddle.pose.at.clone().subtract(0.0,.75,0.0)
            stand=OriginWorkshopProp(displays,base,originYardSaddleStandPieces())
            hooks=OriginWorkshopProp(displays,saddle.station.block.location.clone().add(.5,0.0,.5),originYardSaddleHooksPieces())
        }
        val shovel=slots.firstOrNull { it.gear==OriginYardGear.SHOVEL }
        if(shovel?.pose?.at != shovelHookAt) {
            shovelHook?.remove(); shovelHook=null; shovelHookAt=shovel?.pose?.at?.clone()
            if(shovel!=null) shovelHook=OriginWorkshopProp(displays,shovel.pose.at.clone().add(-.045,1.06,0.0),listOf(
                OriginWorkshopWorkpiecePiece(OriginWorkshopPoint(0.0,0.0,0.0),OriginWorkshopGamePartSize(.17f,.025f,.025f),Material.IRON_BLOCK)))
        }
        for(slot in slots) {
            val (workerId,job)=borrowed[slot.gear] ?: (null to null)
            val worker=workerId?.let(Bukkit::getPlayer)
            val horse=job?.yard?.animalId?.let(animal)
            val filled=job?.stage in setOf(OriginOddJobStage.YARD_WATER,OriginOddJobStage.YARD_DRINKING,OriginOddJobStage.CLEAN_LOAD)
            val work=job?.yard
            val pieces=originYardGearPieces(slot.gear,filled,work?.leftStirrup==true,work?.rightStirrup==true,work?.girthTight==true)
            val prop=props.getOrPut(slot.gear) { OriginWorkshopProp(displays,slot.pose.at,pieces) }
            if(geometry[slot.gear]!=pieces) { prop.update(pieces); geometry[slot.gear]=pieces }
            val carried=job!=null && originYardCarriedGear(job)==slot.gear && worker!=null
            when {
                carried -> {
                    requireNotNull(worker)
                    prop.carry(worker,originYardGearCarryPose(slot.gear,worker.bodyYaw,worker.isSneaking))
                    placed.remove(slot.gear)
                }
                job?.stage==OriginOddJobStage.YARD_DRINKING && slot.gear==OriginYardGear.BUCKET && horse!=null -> {
                    val at=horse.location.add(horse.location.direction.setY(0).normalize().multiply(1.1)).add(0.0,.02,0.0)
                    place(slot.gear,prop,OriginYardGearPose(at))
                }
                job!=null && horse!=null -> {
                    // The spare coiled lead stays with this horse while cross-tied or ridden.
                    val at=originYardGearHorsePose(slot.gear,horse)
                    prop.move(at.at,at.rotation); placed.remove(slot.gear)
                }
                else -> place(slot.gear,prop,slot.pose)
            }
        }
        val liveKeys=live.map { key(it.second.id) }.toSet()
        (scenes-liveKeys).toList().forEach { controls?.clear(it); scenes.remove(it) }
        live.forEach { (id,job) -> Bukkit.getPlayer(id)?.let { sync(it,job) } }
    }
    private fun place(gear:OriginYardGear,prop:OriginWorkshopProp,pose:OriginYardGearPose) {
        val old=placed[gear]
        if(old?.at!=pose.at || old.rotation!=pose.rotation) { prop.place(pose.at,pose.rotation); placed[gear]=pose }
    }
    fun clear(playerId:UUID,requestId:UUID) { controls?.clear(key(requestId)); scenes.remove(key(requestId)) }
    override fun close() {
        if(closed) return
        closed=true; controls?.close(); controls=null
        props.values.forEach { it.remove() }; props.clear(); geometry.clear(); placed.clear()
        stand?.remove(); hooks?.remove(); shovelHook?.remove(); stand=null; hooks=null; shovelHook=null
        owner?.close(); owner=null; scenes.clear()
    }
}
