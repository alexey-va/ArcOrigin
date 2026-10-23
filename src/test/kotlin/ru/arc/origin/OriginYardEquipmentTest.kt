package ru.arc.origin

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.data.BlockData
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.util.BoundingBox
import org.mockbukkit.mockbukkit.entity.PlayerMock
import org.mockbukkit.mockbukkit.world.WorldMock
import ru.arc.paper.display.PacketBlockDisplay
import ru.arc.paper.display.PaperPacketDisplays
import ru.arc.paper.testing.MockBukkitTestRuntime
import java.util.UUID

class OriginYardEquipmentTest : StringSpec({
    "two workers pick up their own shovel and one departure preserves the other's props and glow" {
        MockBukkitTestRuntime.open().use { paper ->
            paper.loadSimplePlugin(ControlledArcOriginPlugin::class.java)
            val world=WorldMock().also(paper.server::addWorld)
            fun player(name:String)=object:PlayerMock(paper.server,name) {
                override fun getBodyYaw()=0f
                override fun getHeight()=1.8
            }.also { paper.server.addPlayer(it); it.teleport(Location(world,.5,70.0,-2.0)) }
            val first=player("FirstWorker"); val second=player("SecondWorker")
            val barrel=world.getBlockAt(0,70,0).also { it.type=Material.BARREL }
            world.getChunkAt(0,0)
            val station=OriginOddJobStation(barrel.location,barrel,setOf(Material.BARREL))
            fun job(animal:Int)=OriginOddJobSession(UUID.randomUUID(),OriginOddJobKind.MOUNTYARD,OriginOddJobStage.CLEAN_TOOL,
                0,240000,0,OriginOddJobOffer(UUID.randomUUID(),OriginOddJobVariant.YARD_CLEANUP,0,false),yard=OriginYardWork(animal,"Лошадь"))
            var firstJob=job(422); var secondJob=job(423)
            var live=listOf(first.uniqueId to firstJob,second.uniqueId to secondJob)
            val harness=YardEquipmentDisplays()
            val equipment=OriginYardEquipment { harness.owner }
            equipment.bind({mapOf(OriginOddJobStationId.BRUSH to station)},{live},{null}) { _,_,_ -> }
            try {
                equipment.tick()
                val idle=harness.handles.filter { it.defaultVisible }
                val firstHandles=harness.privateFor(first)
                val secondHandles=harness.privateFor(second)
                firstHandles.isNotEmpty() shouldBe true
                firstHandles.size shouldBe secondHandles.size
                harness.handles.none { it.shown.size > 1 } shouldBe true
                idle.all { !it.glowing && first.uniqueId in it.hidden && second.uniqueId in it.hidden } shouldBe true
                firstHandles.any { it.glowing } shouldBe true
                secondHandles.any { it.glowing } shouldBe true

                firstJob=firstJob.copy(stage=OriginOddJobStage.CLEAN_CART,
                    yard=firstJob.yard!!.copy(borrowed=setOf(OriginYardGear.SHOVEL)))
                live=listOf(first.uniqueId to firstJob,second.uniqueId to secondJob)
                equipment.tick()
                harness.privateFor(first).any { it.parent==first.uniqueId } shouldBe true
                secondHandles.all { it.valid && it.parent==null } shouldBe true
                secondHandles.any { it.glowing } shouldBe true

                secondJob=secondJob.copy(stage=OriginOddJobStage.CLEAN_CART,
                    yard=secondJob.yard!!.copy(borrowed=setOf(OriginYardGear.SHOVEL)))
                live=listOf(first.uniqueId to firstJob,second.uniqueId to secondJob)
                equipment.tick()
                firstHandles.any { it.parent==first.uniqueId } shouldBe true
                secondHandles.any { it.parent==second.uniqueId } shouldBe true

                // A late cleanup for an older request cannot erase the player's current scene.
                equipment.clear(second.uniqueId,UUID.randomUUID())
                secondHandles.all { it.valid } shouldBe true
                live=listOf(second.uniqueId to secondJob)
                equipment.clear(first.uniqueId,firstJob.id)
                equipment.tick()
                firstHandles.none { it.valid } shouldBe true
                secondHandles.all { it.valid } shouldBe true
                harness.privateFor(second) shouldBe secondHandles
                idle.all { first.uniqueId !in it.hidden && second.uniqueId in it.hidden && !it.glowing } shouldBe true
            } finally { equipment.close() }
            harness.handles.none { it.valid } shouldBe true
        }
    }

    "equipped saddle disappears from the private prop scene and never attaches cosmetic tack to the horse" {
        MockBukkitTestRuntime.open().use { paper ->
            paper.loadSimplePlugin(ControlledArcOriginPlugin::class.java)
            val world=WorldMock().also(paper.server::addWorld)
            val player=object:PlayerMock(paper.server,"Saddler") {
                override fun getBodyYaw()=0f
                override fun getHeight()=1.8
            }.also { paper.server.addPlayer(it); it.teleport(Location(world,.5,70.0,-2.0)) }
            world.getChunkAt(0,0)
            val barrel=world.getBlockAt(0,70,0).also { it.type=Material.BARREL }
            val station=OriginOddJobStation(barrel.location,barrel,setOf(Material.BARREL))
            val horse=mockk<LivingEntity> {
                every { location } returns Location(world,2.0,70.0,0.0)
                every { boundingBox } returns BoundingBox(1.3,70.0,-.7,2.7,71.6,.7)
            }
            var job=OriginOddJobSession(UUID.randomUUID(),OriginOddJobKind.MOUNTYARD,OriginOddJobStage.YARD_SADDLE,
                0,240000,0,OriginOddJobOffer(UUID.randomUUID(),OriginOddJobVariant.YARD_SADDLE,0,false),
                yard=OriginYardWork(422,"Лошадь",borrowed=setOf(OriginYardGear.SADDLE,OriginYardGear.LEAD)))
            val harness=YardEquipmentDisplays()
            val equipment=OriginYardEquipment { harness.owner }
            equipment.bind({mapOf(OriginOddJobStationId.SADDLE_RACK to station)},{listOf(player.uniqueId to job)},{horse}) { _,_,_ -> }
            try {
                equipment.tick()
                val saddle=harness.privateFor(player).filter { it.parent==player.uniqueId }
                saddle.size shouldBe originYardGearPieces(OriginYardGear.SADDLE).size
                job=job.copy(stage=OriginOddJobStage.YARD_DELIVER,yard=job.yard!!.copy(equipped=setOf(OriginYardGear.SADDLE)))
                equipment.tick()
                saddle.none { it.valid } shouldBe true
                harness.handles.forEach { verify(exactly=0) { it.display.attachTo(any<LivingEntity>(),any()) } }
                originYardGearSlots(mapOf(OriginOddJobStationId.SADDLE_RACK to station)).map { it.gear }.toSet() shouldBe
                    setOf(OriginYardGear.SADDLE,OriginYardGear.LEAD)
            } finally { equipment.close() }
        }
    }
})

private class YardEquipmentDisplays {
    class Handle(val display:PacketBlockDisplay) {
        var valid=true
        var defaultVisible=true
        var glowing=false
        var parent:UUID?=null
        val shown=mutableSetOf<UUID>()
        val hidden=mutableSetOf<UUID>()
    }
    val owner=mockk<PaperPacketDisplays>(relaxed=true)
    val handles=mutableListOf<Handle>()
    init {
        every { owner.spawnBlock(any(),any()) } answers {
            var data=secondArg<BlockData>()
            val display=mockk<PacketBlockDisplay>(relaxed=true)
            val handle=Handle(display).also(handles::add)
            every { display.blockData } answers { data }
            every { display.blockData=any() } answers { data=firstArg() }
            every { display.isValid } answers { handle.valid }
            every { display.remove() } answers { handle.valid=false }
            every { display.isVisibleByDefault=any() } answers { handle.defaultVisible=firstArg() }
            every { display.isGlowing=any() } answers { handle.glowing=firstArg() }
            every { display.showTo(any<Player>()) } answers { handle.shown+=firstArg<Player>().uniqueId; handle.hidden-=firstArg<Player>().uniqueId }
            every { display.showTo(any<UUID>()) } answers { handle.shown+=firstArg<UUID>(); handle.hidden-=firstArg<UUID>() }
            every { display.hideFrom(any<UUID>()) } answers { handle.hidden+=firstArg<UUID>(); handle.shown-=firstArg<UUID>() }
            every { display.attachTo(any<Player>()) } answers { handle.parent=firstArg<Player>().uniqueId }
            every { display.detach() } answers { handle.parent=null }
            display
        }
    }
    fun privateFor(player:Player)=handles.filter { it.valid && !it.defaultVisible && it.shown==setOf(player.uniqueId) }
}
