package ru.arc.origin

import org.bukkit.entity.Entity
import ru.arc.origin.api.ArcOriginFurniture

internal enum class OriginFurnitureFamily { SIMPLE, COMPLEX }

internal data class OriginFurnitureHandle(
    val root: Entity,
    val family: OriginFurnitureFamily,
    val namespacedId: String?,
)

/** Local seam for consumers that need the host-owned ItemsAdder furniture classifier. */
internal interface OriginFurnitureAccess {
    val available: Boolean
    fun inspect(entity: Entity): OriginFurnitureHandle?
    fun remove(entity: Entity, family: OriginFurnitureFamily): Boolean
}

internal object SharedOriginFurnitureAccess : OriginFurnitureAccess {
    private fun service(): ArcOriginFurniture? = OriginServices.furniture?.takeIf(ArcOriginFurniture::isAvailable)

    override val available: Boolean get() = service() != null

    override fun inspect(entity: Entity): OriginFurnitureHandle? = service()?.inspect(entity)?.let { handle ->
        OriginFurnitureHandle(handle.root, handle.family.toLocal(), handle.namespacedId)
    }

    override fun remove(entity: Entity, family: OriginFurnitureFamily): Boolean =
        service()?.remove(entity, family.toHost()) ?: false

    private fun ArcOriginFurniture.FurnitureFamily.toLocal(): OriginFurnitureFamily = when (this) {
        ArcOriginFurniture.FurnitureFamily.SIMPLE -> OriginFurnitureFamily.SIMPLE
        ArcOriginFurniture.FurnitureFamily.COMPLEX -> OriginFurnitureFamily.COMPLEX
    }

    private fun OriginFurnitureFamily.toHost(): ArcOriginFurniture.FurnitureFamily = when (this) {
        OriginFurnitureFamily.SIMPLE -> ArcOriginFurniture.FurnitureFamily.SIMPLE
        OriginFurnitureFamily.COMPLEX -> ArcOriginFurniture.FurnitureFamily.COMPLEX
    }
}
