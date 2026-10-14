package ru.arc.origin

import org.bukkit.Bukkit
import ru.arc.origin.api.ArcOriginAuctions
import ru.arc.origin.api.ArcOriginCaseFiles
import ru.arc.origin.api.ArcOriginChunkLeases
import ru.arc.origin.api.ArcOriginConfiguration
import ru.arc.origin.api.ArcOriginDialogNavigation
import ru.arc.origin.api.ArcOriginEconomy
import ru.arc.origin.api.ArcOriginFurniture
import ru.arc.origin.api.ArcOriginNpcHolograms
import ru.arc.origin.api.ArcOriginPortalVisuals
import ru.arc.origin.api.ArcOriginTransfers
import ru.arc.origin.api.ArcOriginViewerGlow

/** Resolves individual host-owned ports; there is deliberately no aggregate host context. */
internal object OriginServices {
    val configuration: ArcOriginConfiguration
        get() = require(ArcOriginConfiguration::class.java)

    val chunkLeases: ArcOriginChunkLeases
        get() = require(ArcOriginChunkLeases::class.java)

    val portalVisuals: ArcOriginPortalVisuals
        get() = require(ArcOriginPortalVisuals::class.java)

    val dialogNavigation: ArcOriginDialogNavigation
        get() = require(ArcOriginDialogNavigation::class.java)

    val viewerGlow: ArcOriginViewerGlow
        get() = require(ArcOriginViewerGlow::class.java)

    val npcHolograms: ArcOriginNpcHolograms?
        get() = optional(ArcOriginNpcHolograms::class.java)

    val economy: ArcOriginEconomy?
        get() = optional(ArcOriginEconomy::class.java)

    val furniture: ArcOriginFurniture?
        get() = optional(ArcOriginFurniture::class.java)

    val auctions: ArcOriginAuctions?
        get() = optional(ArcOriginAuctions::class.java)

    val caseFiles: ArcOriginCaseFiles?
        get() = optional(ArcOriginCaseFiles::class.java)

    val transfers: ArcOriginTransfers?
        get() = optional(ArcOriginTransfers::class.java)

    private fun <T : Any> optional(type: Class<T>): T? = Bukkit.getServicesManager().load(type)

    private fun <T : Any> require(type: Class<T>): T =
        optional(type) ?: error("Required ARC host service ${type.name} is unavailable")
}
