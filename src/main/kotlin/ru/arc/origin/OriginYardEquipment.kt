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
import ru.arc.paper.display.PacketDisplay
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

/** The whole native animal is the saddle target, independently of head/body animation. */
internal fun originYardHorseBounds(horse: LivingEntity): OriginWorkshopAabb = horse.boundingBox.let {
    OriginWorkshopAabb(it.minX-.10,it.minY,it.minZ-.10,it.maxX+.10,it.maxY+.08,it.maxZ+.10)
}

internal fun originYardGearBounds(geometry: List<OriginWorkshopWorkpiecePiece>, pose: OriginYardGearPose): OriginWorkshopAabb {
    val points = geometry.flatMap { p -> listOf(-1,1).flatMap { x -> listOf(-1,1).flatMap { y -> listOf(-1,1).map { z ->
        pose.rotation.transform(Vector3f((p.center.x+x*p.size.x/2).toFloat(),(p.center.y+y*p.size.y/2).toFloat(),
            (p.center.z+z*p.size.z/2).toFloat()).mul(pose.scale))
    } } } }
    return OriginWorkshopAabb(pose.at.x+points.minOf { it.x },pose.at.y+points.minOf { it.y },pose.at.z+points.minOf { it.z },
        pose.at.x+points.maxOf { it.x },pose.at.y+points.maxOf { it.y },pose.at.z+points.maxOf { it.z })
}

internal fun originYardGearControl(job: OriginOddJobSession, step: OriginYardGearStep, pose: OriginYardGearPose,
    horseBounds: OriginWorkshopAabb? = null): SpatialControl<OriginYardGearStep> {
    val returningShovel=job.stage==OriginOddJobStage.CLEAN_TOOL_RETURN
    var bounds=if(!step.atRack) requireNotNull(horseBounds) else
        originYardGearBounds(if(returningShovel) originYardShovelHookPieces() else originYardGearPieces(step.gear),pose)
    if(returningShovel) bounds=OriginWorkshopAabb(bounds.minX-.08,bounds.minY-.08,bounds.minZ-.08,
        bounds.maxX+.08,bounds.maxY+.08,bounds.maxZ+.08)
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

internal fun originYardGearHint(stage: OriginOddJobStage, step: OriginYardGearStep, pose: OriginYardGearPose,
    horseBounds: OriginWorkshopAabb? = null): Location {
    val bounds=if(!step.atRack) requireNotNull(horseBounds) else originYardGearBounds(
        if(stage==OriginOddJobStage.CLEAN_TOOL_RETURN) originYardShovelHookPieces() else originYardGearPieces(step.gear),pose)
    return Location(pose.at.world,(bounds.minX+bounds.maxX)/2,bounds.maxY+.22,(bounds.minZ+bounds.maxZ)/2)
}

/** A shared idle rack and one complete private set of tools per worker. */
internal class OriginYardEquipment(
    private val createDisplays: () -> PaperPacketDisplays = { PaperPacketDisplays(ArcOriginPlugin.instance,"odd-jobs-yard-gear") },
) : AutoCloseable {
    private class GearScene(val requestId: UUID? = null, val viewer: Player? = null) {
        val worldId = viewer?.world?.uid
        val handles=mutableListOf<PacketDisplay>()
        val props=mutableMapOf<OriginYardGear,OriginWorkshopProp>()
        val geometry=mutableMapOf<OriginYardGear,List<OriginWorkshopWorkpiecePiece>>()
        val placed=mutableMapOf<OriginYardGear,OriginYardGearPose>()
        val motion=mutableMapOf<OriginYardGear,OriginYardGearMotion>()
        val rests=mutableMapOf<OriginYardGear,OriginWorkshopProp>()
        var stand: OriginWorkshopProp?=null
        var hooks: OriginWorkshopProp?=null
        var shovelHook: OriginWorkshopProp?=null
        var standAt: Location?=null
        var shovelHookAt: Location?=null

        fun removeGear(gear: OriginYardGear) {
            props.remove(gear)?.remove(); geometry.remove(gear); placed.remove(gear); motion.remove(gear)
        }
        fun remove() { handles.filter(PacketDisplay::isValid).forEach(PacketDisplay::remove); handles.clear() }
    }

    private lateinit var stations: () -> Map<OriginOddJobStationId,OriginOddJobStation>
    private lateinit var jobs: () -> List<Pair<UUID,OriginOddJobSession>>
    private lateinit var animal: (Int) -> LivingEntity?
    private lateinit var activate: (Player,OriginOddJobSession,OriginYardGearStep) -> Unit
    private val idle=GearScene()
    private val personal=mutableMapOf<UUID,GearScene>()
    private val hiddenIdle=mutableSetOf<UUID>()
    private val scenes=mutableSetOf<SpatialSceneKey>()
    private var owner: PaperPacketDisplays?=null
    private var controls: PaperSpatialControls<OriginOddJobSession,OriginYardGearStep>?=null
    private var closed=false

    fun bind(stations: () -> Map<OriginOddJobStationId,OriginOddJobStation>, jobs: () -> List<Pair<UUID,OriginOddJobSession>>,
             animal: (Int) -> LivingEntity?, activate: (Player,OriginOddJobSession,OriginYardGearStep) -> Unit) {
        this.stations=stations; this.jobs=jobs; this.animal=animal; this.activate=activate
    }
    private fun key(id:UUID)=SpatialSceneKey("odd-job-yard-gear:$id")
    private fun renderer()=owner ?: createDisplays().also { owner=it }
    private fun inputs()=controls ?: PaperSpatialControls(ArcOriginPlugin.instance,renderer(),
        object:SpatialControlController<OriginOddJobSession,OriginYardGearStep>() {
            override fun current(player:Player,scene:SpatialSceneKey):SpatialControlFrame<OriginOddJobSession,OriginYardGearStep>? =
                jobs().firstOrNull { it.first==player.uniqueId && key(it.second.id)==scene }?.second?.let { frame(player,it) }
            override fun activate(player:Player,scene:SpatialSceneKey,context:OriginOddJobSession,action:OriginYardGearStep) {
                this@OriginYardEquipment.activate(player,context,action)
            }
            override fun inputFailed(player:Player,scene:SpatialSceneKey,context:OriginOddJobSession,failure:Exception) {
                clear(player.uniqueId,context.id)
                throw failure
            }
        }).also { controls=it }

    private fun pose(job:OriginOddJobSession,step:OriginYardGearStep):OriginYardGearPose? = if(step.atRack)
        originYardGearSlots(stations()).firstOrNull { it.gear==step.gear }?.pose
        else job.yard?.animalId?.let(animal)?.let { OriginYardGearPose(it.location) }
    private fun horseBounds(job:OriginOddJobSession,step:OriginYardGearStep):OriginWorkshopAabb? =
        if(step.atRack) null else job.yard?.animalId?.let(animal)?.let(::originYardHorseBounds)
    private fun frame(player:Player,job:OriginOddJobSession):SpatialControlFrame<OriginOddJobSession,OriginYardGearStep>? {
        val step=originYardGearStep(job.stage) ?: return null
        val pose=pose(job,step) ?: return null
        if(player.world.uid!=pose.at.world?.uid) return null
        val bounds=horseBounds(job,step)
        if(!step.atRack && bounds==null) return null
        return SpatialControlFrame(job,SceneAudience.Private(player.uniqueId),SpatialSceneScope.Viewer(player.uniqueId),
            listOf(originYardGearControl(job,step,pose,bounds)),SpatialInputProfile.ENTITY_BOTH,SpatialReach.NativeInteraction)
    }
    fun target(job:OriginOddJobSession):Location? = originYardGearStep(job.stage)?.let { step ->
        val pose=pose(job,step) ?: return@let null
        val bounds=horseBounds(job,step)
        if(!step.atRack && bounds==null) null else originYardGearHint(job.stage,step,pose,bounds)
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
        inputs().apply(key(job.id),frame); scenes.add(key(job.id))
    }
    fun tick() {
        if(closed || !::stations.isInitialized) return
        val slots=originYardGearSlots(stations())
        val live=jobs().filter { it.second.kind==OriginOddJobKind.MOUNTYARD }.mapNotNull { (id,job) ->
            Bukkit.getPlayer(id)?.takeIf { it.isOnline && !it.isDead }?.let { it to job }
        }
        val tick=Bukkit.getCurrentTick()
        render(idle,slots,null,tick)
        val livePlayers=live.map { it.first.uniqueId }.toSet()
        (personal.keys-livePlayers).toList().forEach { id -> personal.remove(id)?.remove() }
        for((player,job) in live) {
            val previous=personal[player.uniqueId]
            val scene=if(previous?.requestId==job.id && previous.worldId==player.world.uid) previous else {
                previous?.remove()
                GearScene(job.id,player).also { personal[player.uniqueId]=it }
            }
            render(scene,slots,job,tick)
            focus(scene,job)
            sync(player,job)
        }
        // The idle rack is still visible to bystanders; workers see only their own tools and glow.
        (hiddenIdle-livePlayers).forEach { id -> idle.handles.forEach { it.showTo(id) } }
        livePlayers.forEach { id -> idle.handles.forEach { it.hideFrom(id) } }
        hiddenIdle.clear(); hiddenIdle.addAll(livePlayers)
        val liveKeys=live.map { key(it.second.id) }.toSet()
        (scenes-liveKeys).toList().forEach { controls?.clear(it); scenes.remove(it) }
    }
    private fun render(scene:GearScene,slots:List<OriginYardGearSlot>,job:OriginOddJobSession?,tick:Int) {
        val validGear=slots.map { it.gear }.toSet()
        (scene.props.keys-validGear).toList().forEach { scene.removeGear(it); scene.rests.remove(it)?.remove() }
        fun prop(at:Location,pieces:List<OriginWorkshopWorkpiecePiece>)=OriginWorkshopProp(renderer(),at,pieces,
            privateViewer=scene.viewer,onSpawn={ scene.handles += it })
        val saddle=slots.firstOrNull { it.gear==OriginYardGear.SADDLE }
        if(saddle?.pose?.at != scene.standAt) {
            scene.stand?.remove(); scene.hooks?.remove(); scene.stand=null; scene.hooks=null; scene.standAt=saddle?.pose?.at?.clone()
        }
        if(saddle!=null && scene.stand==null) {
            scene.stand=prop(saddle.pose.at.clone().subtract(0.0,.88,0.0),originYardSaddleStandPieces())
            scene.hooks=prop(saddle.station.block.location.clone().add(.5,0.0,.5),originYardSaddleHooksPieces())
        }
        val shovel=slots.firstOrNull { it.gear==OriginYardGear.SHOVEL }
        if(shovel?.pose?.at != scene.shovelHookAt) {
            scene.shovelHook?.remove(); scene.shovelHook=null; scene.shovelHookAt=shovel?.pose?.at?.clone()
            if(shovel!=null) scene.shovelHook=prop(shovel.pose.at,originYardShovelHookPieces())
        }
        for(slot in slots) {
            if(slot.gear in setOf(OriginYardGear.BUCKET,OriginYardGear.BRUSH)) {
                val base=slot.pose.at.clone().subtract(0.0,.0125,0.0)
                val width=if(slot.gear==OriginYardGear.BUCKET) .44f else .34f
                val depth=if(slot.gear==OriginYardGear.BUCKET) .42f else .21f
                scene.rests.getOrPut(slot.gear) { prop(base,listOf(OriginWorkshopWorkpiecePiece(
                    OriginWorkshopPoint(0.0,0.0,0.0),OriginWorkshopGamePartSize(width,.025f,depth),Material.DARK_OAK_PLANKS))) }
                    .place(base,Quaternionf())
            }
            val borrowed=slot.gear in job?.yard?.borrowed.orEmpty()
            val carried=borrowed && originYardCarriedGear(requireNotNull(job))==slot.gear
            val horse=if(borrowed) job?.yard?.animalId?.let(animal) else null
            val drinking=borrowed && job?.stage==OriginOddJobStage.YARD_DRINKING && slot.gear==OriginYardGear.BUCKET && horse!=null
            if(borrowed && !carried && !drinking) {
                // Consumed hay and equipped native saddle/lead have no duplicate display on the animal.
                scene.removeGear(slot.gear)
                continue
            }
            val filled=borrowed && job?.stage in setOf(OriginOddJobStage.YARD_WATER,OriginOddJobStage.YARD_DRINKING,OriginOddJobStage.CLEAN_LOAD)
            val pieces=originYardGearPieces(slot.gear,filled)
            val rendered=scene.props.getOrPut(slot.gear) { prop(slot.pose.at,pieces) }
            if(scene.geometry[slot.gear]!=pieces) { rendered.update(pieces); scene.geometry[slot.gear]=pieces }
            val animator=scene.motion.getOrPut(slot.gear) { OriginYardGearMotion() }
            when {
                carried -> {
                    val player=requireNotNull(scene.viewer)
                    val grip=originYardGearCarryPose(slot.gear,player.bodyYaw,player.isSneaking)
                    val feet=player.location
                    val target=OriginYardGearPose(feet.clone().add(grip.center.x,grip.center.y,grip.center.z),grip.rotation)
                    val sampled=animator.sample(target,"hand",tick,true)
                    rendered.carry(player,OriginWorkshopCarryPose(OriginWorkshopPoint(sampled.at.x-feet.x,sampled.at.y-feet.y,sampled.at.z-feet.z),sampled.rotation))
                    scene.placed.remove(slot.gear)
                }
                drinking -> {
                    requireNotNull(horse)
                    val at=horse.location.add(horse.location.direction.setY(0).normalize().multiply(1.1)).add(0.0,.02,0.0)
                    val sampled=animator.sample(OriginYardGearPose(at),"ground",tick,true)
                    rendered.move(sampled.at,sampled.rotation); scene.placed.remove(slot.gear)
                }
                else -> {
                    val sampled=animator.sample(slot.pose,"rack",tick,job!=null)
                    if(sampled==slot.pose) {
                        val old=scene.placed[slot.gear]
                        if(old?.at!=slot.pose.at || old.rotation!=slot.pose.rotation) {
                            rendered.place(slot.pose.at,slot.pose.rotation); scene.placed[slot.gear]=slot.pose
                        }
                    } else { rendered.move(sampled.at,sampled.rotation); scene.placed.remove(slot.gear) }
                }
            }
        }
        scene.handles.removeAll { !it.isValid }
    }
    private fun focus(scene:GearScene,job:OriginOddJobSession) {
        scene.props.values.forEach { it.glow(null) }; scene.rests.values.forEach { it.glow(null) }
        scene.stand?.glow(null); scene.hooks?.glow(null); scene.shovelHook?.glow(null)
        val step=originYardGearStep(job.stage)?.takeIf { it.atRack } ?: return
        val target=if(step.returning) when(step.gear) {
            OriginYardGear.SHOVEL -> scene.shovelHook
            else -> scene.rests[step.gear]
        } else scene.props[step.gear]
        target?.glow(Color.fromRGB(255,208,111))
    }
    fun clear(playerId:UUID,requestId:UUID) {
        controls?.clear(key(requestId)); scenes.remove(key(requestId))
        personal[playerId]?.takeIf { it.requestId==requestId }?.let { scene ->
            personal.remove(playerId); scene.remove()
            idle.handles.forEach { it.showTo(playerId) }; hiddenIdle.remove(playerId)
        }
    }
    override fun close() {
        if(closed) return
        closed=true; controls?.close(); controls=null
        idle.remove(); personal.values.forEach { it.remove() }; personal.clear(); hiddenIdle.clear()
        owner?.close(); owner=null; scenes.clear()
    }
}
