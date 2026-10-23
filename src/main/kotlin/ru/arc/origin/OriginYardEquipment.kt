package ru.arc.origin

import org.bukkit.Bukkit
import org.bukkit.Color
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
        put(OriginYardGear.SADDLE,source,0.0,1.88+carpet,0.0)
        put(OriginYardGear.PAD,source,-.54,.6715,0.0,-90.0)
        put(OriginYardGear.BRIDLE,source,.21,.70,-.92,rx=-90.0)
        put(OriginYardGear.LEAD,source,-.28,.60,-.56)
    }
    station(OriginOddJobStationId.WELL)?.let { put(OriginYardGear.BUCKET,it,0.0,1.025,0.0) }
    station(OriginOddJobStationId.BRUSH)?.let {
        // Its centre lantern remains clear: brush sits on the north-east corner, shovel on the east hook.
        put(OriginYardGear.BRUSH,it,.29,1.025,-.32)
        put(OriginYardGear.SHOVEL,it,.62,.04,.12)
    }
    station(OriginOddJobStationId.HAY)?.let { put(OriginYardGear.HAY,it,0.0,1.0,0.0) }
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
            (p.center.z+z*p.size.z/2).toFloat()).mul(pose.scale))
    } } } }
    return OriginWorkshopAabb(pose.at.x+points.minOf { it.x },pose.at.y+points.minOf { it.y },pose.at.z+points.minOf { it.z },
        pose.at.x+points.maxOf { it.x },pose.at.y+points.maxOf { it.y },pose.at.z+points.maxOf { it.z })
}

internal fun originYardGearControl(job: OriginOddJobSession, step: OriginYardGearStep, pose: OriginYardGearPose): SpatialControl<OriginYardGearStep> {
    var bounds=originYardGearBounds(originYardGearPieces(step.gear),pose)
    if (!step.atRack && job.stage in setOf(OriginOddJobStage.YARD_STIRRUP_LEFT,OriginOddJobStage.YARD_STIRRUP_RIGHT)) {
        val side=if (step.zone==0) -1 else 1
        bounds=originYardGearBounds(listOf(OriginWorkshopWorkpiecePiece(OriginWorkshopPoint(side*.43,-.31,0.0),
            OriginWorkshopGamePartSize(.34f,.48f,.44f),Material.IRON_BLOCK)),pose)
    } else if (!step.atRack && job.stage == OriginOddJobStage.YARD_GIRTH) {
        bounds=originYardGearBounds(listOf(OriginWorkshopWorkpiecePiece(OriginWorkshopPoint(-.42,-.35,.24),
            OriginWorkshopGamePartSize(.36f,.62f,.40f),Material.BROWN_TERRACOTTA)),pose)
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

internal fun originYardGearHint(stage:OriginOddJobStage,step:OriginYardGearStep,pose:OriginYardGearPose):Location {
            val bounds=originYardGearBounds(originYardGearPieces(step.gear),pose)
            // A short caption clears the entire solid prop. Part cues sit outside the horse's flank.
            return if (!step.atRack && stage in setOf(OriginOddJobStage.YARD_GIRTH,OriginOddJobStage.YARD_STIRRUP_LEFT,OriginOddJobStage.YARD_STIRRUP_RIGHT)) {
                val side=if(stage==OriginOddJobStage.YARD_STIRRUP_RIGHT) 1 else -1
                val offset=pose.rotation.transform(Vector3f(side*.83f,.22f,.15f).mul(pose.scale))
                pose.at.clone().add(offset.x.toDouble(),offset.y.toDouble(),offset.z.toDouble())
            } else Location(pose.at.world,(bounds.minX+bounds.maxX)/2,bounds.maxY+.22,(bounds.minZ+bounds.maxZ)/2)
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
    private val motion=mutableMapOf<OriginYardGear,OriginYardGearMotion>()
    private val previousLoans=mutableMapOf<OriginYardGear,UUID>()
    private val rests=mutableMapOf<OriginYardGear,OriginWorkshopProp>()
    private val adjustments=List(3) { OriginYardGearAdjustment() }
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
        pose(job,step)?.let { originYardGearHint(job.stage,step,it) }
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
        (props.keys-validGear).toList().forEach { gear ->
            props.remove(gear)?.remove(); rests.remove(gear)?.remove(); geometry.remove(gear); placed.remove(gear)
            motion.remove(gear); previousLoans.remove(gear)
        }
        val live=jobs().filter { it.second.kind==OriginOddJobKind.MOUNTYARD }
        val loans=live.flatMap { (id,job) -> job.yard?.borrowed.orEmpty().map { it to (id to job) } }
        check(loans.map { it.first }.distinct().size==loans.size) { "yard gear has two borrowers" }
        val borrowed=loans.toMap()
        val tick=Bukkit.getCurrentTick()
        val displays=renderer()
        val saddle=slots.firstOrNull { it.gear==OriginYardGear.SADDLE }
        if(saddle?.pose?.at != standAt) {
            stand?.remove(); hooks?.remove(); stand=null; hooks=null; standAt=saddle?.pose?.at?.clone()
        }
        if(saddle!=null && stand==null) {
            val base=saddle.pose.at.clone().subtract(0.0,.88,0.0)
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
            if(slot.gear in setOf(OriginYardGear.BUCKET,OriginYardGear.BRUSH)) {
                val base=slot.pose.at.clone().subtract(0.0,.0125,0.0)
                val width=if(slot.gear==OriginYardGear.BUCKET) .44f else .34f
                val depth=if(slot.gear==OriginYardGear.BUCKET) .42f else .21f
                rests.getOrPut(slot.gear) { OriginWorkshopProp(displays,base,listOf(
                    OriginWorkshopWorkpiecePiece(OriginWorkshopPoint(0.0,0.0,0.0),
                        OriginWorkshopGamePartSize(width,.025f,depth),Material.DARK_OAK_PLANKS))) }.place(base,Quaternionf())
            }
            val (workerId,job)=borrowed[slot.gear] ?: (null to null)
            val worker=workerId?.let(Bukkit::getPlayer)
            val horse=job?.yard?.animalId?.let(animal)
            val filled=job?.stage in setOf(OriginOddJobStage.YARD_WATER,OriginOddJobStage.YARD_DRINKING,OriginOddJobStage.CLEAN_LOAD)
            val work=job?.yard
            val pieces=if(slot.gear==OriginYardGear.SADDLE) originYardGearPieces(slot.gear,filled,
                adjustments[0].sample(work?.leftStirrup==true,tick),adjustments[1].sample(work?.rightStirrup==true,tick),
                adjustments[2].sample(work?.girthTight==true,tick)) else originYardGearPieces(slot.gear,filled)
            val prop=props.getOrPut(slot.gear) { OriginWorkshopProp(displays,slot.pose.at,pieces) }
            if(geometry[slot.gear]!=pieces) {
                if(slot.gear==OriginYardGear.SADDLE && prop.pieces.size==pieces.size) prop.deform(pieces) else prop.update(pieces)
                geometry[slot.gear]=pieces
            }
            val carried=job!=null && originYardCarriedGear(job)==slot.gear && worker!=null
            val animate=job!=null || previousLoans[slot.gear]?.let { id -> live.any { it.second.id==id } }==true
            val animator=motion.getOrPut(slot.gear) { OriginYardGearMotion() }
            when {
                carried -> {
                    requireNotNull(worker)
                    val grip=originYardGearCarryPose(slot.gear,worker.bodyYaw,worker.isSneaking)
                    val feet=worker.location
                    val target=OriginYardGearPose(feet.clone().add(grip.center.x,grip.center.y,grip.center.z),grip.rotation)
                    val sampled=animator.sample(target,"hand:${worker.uniqueId}",tick,animate)
                    prop.carry(worker,OriginWorkshopCarryPose(OriginWorkshopPoint(sampled.at.x-feet.x,sampled.at.y-feet.y,sampled.at.z-feet.z),sampled.rotation))
                    placed.remove(slot.gear)
                }
                job?.stage==OriginOddJobStage.YARD_DRINKING && slot.gear==OriginYardGear.BUCKET && horse!=null -> {
                    val at=horse.location.add(horse.location.direction.setY(0).normalize().multiply(1.1)).add(0.0,.02,0.0)
                    val sampled=animator.sample(OriginYardGearPose(at),"ground:${job.id}",tick,animate)
                    prop.move(sampled.at,sampled.rotation); placed.remove(slot.gear)
                }
                job!=null && horse!=null -> {
                    // The spare coiled lead stays with this horse while cross-tied or ridden.
                    val at=originYardGearHorsePose(slot.gear,horse)
                    val sampled=animator.sample(at,"horse:${horse.uniqueId}",tick,animate)
                    prop.attach(horse,sampled.at,sampled.rotation,originYardHorsePassengerOrigin(horse),sampled.scale)
                    placed.remove(slot.gear)
                }
                else -> {
                    val sampled=animator.sample(slot.pose,"rack",tick,animate)
                    if(sampled==slot.pose) place(slot.gear,prop,slot.pose)
                    else { prop.move(sampled.at,sampled.rotation); placed.remove(slot.gear) }
                }
            }
            if(job!=null) previousLoans[slot.gear]=job.id else if(!animate) previousLoans.remove(slot.gear)
        }
        focus(live.map { it.second })
        val liveKeys=live.map { key(it.second.id) }.toSet()
        (scenes-liveKeys).toList().forEach { controls?.clear(it); scenes.remove(it) }
        live.forEach { (id,job) -> Bukkit.getPlayer(id)?.let { sync(it,job) } }
    }
    private fun place(gear:OriginYardGear,prop:OriginWorkshopProp,pose:OriginYardGearPose) {
        val old=placed[gear]
        if(old?.at!=pose.at || old.rotation!=pose.rotation) { prop.place(pose.at,pose.rotation); placed[gear]=pose }
    }
    private fun focus(live:List<OriginOddJobSession>) {
        props.values.forEach { it.glow(null) }; rests.values.forEach { it.glow(null) }
        stand?.glow(null); hooks?.glow(null); shovelHook?.glow(null)
        val color=Color.fromRGB(255,208,111)
        fun mark(prop:OriginWorkshopProp?, indices:Set<Int>?=null) {
            prop?.pieces?.forEachIndexed { index,piece -> if(indices==null || index in indices) piece.display?.let {
                it.isGlowing=true; it.glowColorOverride=color
            } }
        }
        live.forEach { job ->
            val step=originYardGearStep(job.stage) ?: return@forEach
            if(step.atRack && !step.returning && !available(step.gear,job.id)) return@forEach
            if(step.returning) when(step.gear) {
                OriginYardGear.SADDLE -> mark(stand)
                OriginYardGear.PAD -> mark(hooks,setOf(0))
                OriginYardGear.BRIDLE -> mark(hooks,setOf(1))
                OriginYardGear.LEAD -> mark(hooks,setOf(2))
                OriginYardGear.SHOVEL -> mark(shovelHook)
                else -> mark(rests[step.gear])
            } else if(step.atRack || step.gear in job.yard?.equipped.orEmpty()) mark(props[step.gear],originYardGearFocus(job.stage))
        }
    }
    fun clear(playerId:UUID,requestId:UUID) { controls?.clear(key(requestId)); scenes.remove(key(requestId)) }
    override fun close() {
        if(closed) return
        closed=true; controls?.close(); controls=null
        props.values.forEach { it.remove() }; props.clear(); geometry.clear(); placed.clear()
        motion.clear(); previousLoans.clear()
        rests.values.forEach { it.remove() }; rests.clear()
        stand?.remove(); hooks?.remove(); shovelHook?.remove(); stand=null; hooks=null; shovelHook=null
        owner?.close(); owner=null; scenes.clear()
    }
}
