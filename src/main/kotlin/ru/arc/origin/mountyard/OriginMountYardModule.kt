package ru.arc.origin.mountyard

import net.citizensnpcs.api.CitizensAPI
import org.bukkit.Bukkit
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import ru.arc.config.ConfigManager
import ru.arc.core.LifecycleTaskScope
import ru.arc.core.PluginModule
import ru.arc.origin.ArcOriginPlugin
import ru.arc.origin.OriginNpcHolograms
import java.nio.file.Path
import java.util.UUID

/** Stable petting and nameplates. Paid shifts own Senya's only player interaction. */
object OriginMountYardModule : PluginModule {
    override val name = "OriginMountYard"
    override val priority = 84
    private var runtime: MountYardCareRuntime? = null

    override fun init() {
        if (!Bukkit.getPluginManager().isPluginEnabled("Citizens")) return
        start(MountYardCareConfig.load(ArcOriginPlugin.instance.legacyDataPath), OriginMountYardAnimalsConfig.load(ArcOriginPlugin.instance.legacyDataPath))
    }

    override fun reload() {
        val care = MountYardCareConfig.load(ArcOriginPlugin.instance.legacyDataPath)
        val animals = OriginMountYardAnimalsConfig.load(ArcOriginPlugin.instance.legacyDataPath)
        shutdown()
        start(care, animals)
    }

    private fun start(care: MountYardCareConfig, animals: OriginMountYardAnimalsConfig) {
        if (Bukkit.getWorld(care.world) == null) return
        runtime = MountYardCareRuntime(care, animals).also { it.start() }
    }

    override fun shutdown() {
        runtime?.close()
        runtime = null
    }
}

private data class MountYardCareConfig(
    val world: String,
    val guideId: Int,
    val guideUuid: UUID,
    val guideName: String,
    val guideLines: List<String>,
) {
    companion object {
        fun load(path: Path): MountYardCareConfig {
            val config = ConfigManager.of(path, "modules/origin-mount-care.yml")
            return MountYardCareConfig(
                config.string("world", "rc_origin_spawn"),
                config.integer("guide.npc-id", 440),
                UUID.fromString(config.string("guide.npc-uuid", "f975fcc7-c044-48f8-818d-ac9939eda2aa")),
                config.string("guide.name", "Сеня"),
                config.stringList("guide.lines"),
            )
        }
    }

}

private class MountYardCareRuntime(
    private val config: MountYardCareConfig,
    animalConfig: OriginMountYardAnimalsConfig,
) : Listener, AutoCloseable {
    private val tasks = LifecycleTaskScope()
    private val animals = OriginMountYardAnimals(animalConfig)

    fun start() {
        Bukkit.getPluginManager().registerEvents(this, ArcOriginPlugin.instance)
        Bukkit.getPluginManager().registerEvents(animals, ArcOriginPlugin.instance)
        tasks.runLater(1) {
            val registry = CitizensAPI.getNPCRegistry()
            registry.getById(config.guideId)?.takeIf { it.uniqueId == config.guideUuid }?.let { npc ->
                OriginNpcHolograms.patchName(npc.id, config.guideName)
                OriginNpcHolograms.patchNameplate(npc.id, "true")
                OriginNpcHolograms.patchHologram(npc.id, config.guideLines, viewRange = 18)
            }
            animals.animals.forEach { animal ->
                registry.getById(animal.npcId)?.takeIf { it.uniqueId == animal.npcUuid }?.let { npc ->
                    OriginNpcHolograms.patchName(npc.id, animal.name)
                    OriginNpcHolograms.patchNameplate(npc.id, "true")
                    OriginNpcHolograms.patchHologram(npc.id, listOf("Погладить"), viewRange = 12)
                }
            }
        }
    }

    override fun close() {
        tasks.close()
        HandlerList.unregisterAll(this)
        HandlerList.unregisterAll(animals)
        animals.clear()
    }

}
