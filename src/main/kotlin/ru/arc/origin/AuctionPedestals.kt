package ru.arc.origin

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import ru.arc.persistence.AtomicFileStore
import ru.arc.persistence.CoalescingAsyncWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

/** A stable identity and Origin-world floor position for one visual auction stand. */
internal data class AuctionPedestalSpec(
    val id: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
)

internal data class AuctionPedestalSnapshot(
    val schemaVersion: Int = AuctionPedestalStore.SCHEMA_VERSION,
    val pedestals: List<AuctionPedestalSpec>,
)

internal enum class AuctionPedestalPlacementFailure {
    DUPLICATE,
    OVERLAP,
    FULL,
}

/**
 * Domain rules for authored showcase footprints. The footprint follows the
 * existing 2.8-block interaction width and visible stand height, so two
 * clickable stands cannot claim the same space.
 */
internal object AuctionPedestalRules {
    const val MAX_PEDESTALS = 32
    const val INTERACTION_WIDTH = 2.8
    const val STAND_HEIGHT = 4.25

    private val ID_PATTERN = Regex("[a-zA-Z0-9_-]{1,64}")

    fun configuredId(index: Int): String = "configured-${index + 1}"

    fun create(
        x: Double,
        y: Double,
        z: Double,
        yaw: Float,
        id: String = UUID.randomUUID().toString(),
    ): AuctionPedestalSpec = AuctionPedestalSpec(id, x, y, z, yaw)

    fun placementFailure(
        candidate: AuctionPedestalSpec,
        existing: List<AuctionPedestalSpec>,
    ): AuctionPedestalPlacementFailure? {
        if (existing.any { it.x == candidate.x && it.y == candidate.y && it.z == candidate.z }) {
            return AuctionPedestalPlacementFailure.DUPLICATE
        }
        if (existing.any { overlaps(it, candidate) }) return AuctionPedestalPlacementFailure.OVERLAP
        if (existing.size >= MAX_PEDESTALS) return AuctionPedestalPlacementFailure.FULL
        return null
    }

    fun overlaps(
        left: AuctionPedestalSpec,
        right: AuctionPedestalSpec,
    ): Boolean =
        kotlin.math.abs(left.x - right.x) < INTERACTION_WIDTH &&
            kotlin.math.abs(left.z - right.z) < INTERACTION_WIDTH &&
            left.y < right.y + STAND_HEIGHT && right.y < left.y + STAND_HEIGHT

    fun validate(specs: List<AuctionPedestalSpec>) {
        require(specs.size <= MAX_PEDESTALS) { "Auction pedestal list exceeds $MAX_PEDESTALS entries" }
        require(specs.map(AuctionPedestalSpec::id).distinct().size == specs.size) {
            "Auction pedestal ids must be unique"
        }
        specs.forEach { spec ->
            require(ID_PATTERN.matches(spec.id)) { "Auction pedestal id is malformed" }
            require(spec.x.isFinite() && spec.x in -30_000_000.0..30_000_000.0) {
                "Auction pedestal ${spec.id} has an invalid x coordinate"
            }
            require(spec.y.isFinite() && spec.y in -2_048.0..2_048.0) {
                "Auction pedestal ${spec.id} has an invalid y coordinate"
            }
            require(spec.z.isFinite() && spec.z in -30_000_000.0..30_000_000.0) {
                "Auction pedestal ${spec.id} has an invalid z coordinate"
            }
            require(spec.yaw.isFinite() && spec.yaw in -360f..360f) {
                "Auction pedestal ${spec.id} has an invalid yaw"
            }
        }
        specs.forEachIndexed { index, spec ->
            require(specs.drop(index + 1).none { overlaps(spec, it) }) {
                "Auction pedestal ${spec.id} overlaps another stand"
            }
        }
    }
}

/**
 * Full-list persistence for the showcase. Construct/use this owner through
 * its asynchronous methods only: opening AtomicFileStore creates directories,
 * and its reads, atomic replacement and durability flushes are blocking IO.
 * A missing file intentionally means "use the configured initial list";
 * saving an empty list is a durable override that removes every stand.
 */
internal class AuctionPedestalStore(
    root: Path,
    private val ioExecutor: Executor = Dispatchers.IO.asExecutor(),
) {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val writerLock = Any()
    @Volatile private var writer: CoalescingAsyncWriter<AuctionPedestalSnapshot>? = null
    private var closed = false
    private var operationTail: CompletableFuture<Unit> = CompletableFuture.completedFuture(Unit)

    private val file =
        CompletableFuture.supplyAsync(
            {
                AtomicFileStore(
                    root = root,
                    relativePath = Path.of("data/auction-showcase-pedestals.json"),
                    maxBytes = MAX_FILE_BYTES,
                    encode = ::encode,
                    decode = ::decode,
                    validate = ::validate,
                ).also { atomicFile ->
                    synchronized(writerLock) {
                        if (writer == null) {
                            writer =
                                CoalescingAsyncWriter { snapshot ->
                                    CompletableFuture.supplyAsync(
                                        { atomicFile.write(snapshot); Unit },
                                        ioExecutor,
                                    )
                                }
                        }
                    }
                }
            },
            ioExecutor,
        )

    fun load(): CompletableFuture<AuctionPedestalSnapshot?> = synchronized(writerLock) {
        check(!closed) { "Auction pedestal store is closed" }
        file
            .let { atomicFile ->
                operationTail
                    .handle { _, _ -> Unit }
                    .thenCompose { atomicFile.thenApplyAsync({ it.loadOrNull() }, ioExecutor) }
            }
            .also { operationTail = it.handle { _, _ -> Unit } }
    }

    fun save(snapshot: AuctionPedestalSnapshot): CompletableFuture<Unit> = synchronized(writerLock) {
        if (closed) return CompletableFuture.failedFuture(IllegalStateException("Auction pedestal store is closed"))
        val accepted = snapshot.copy(pedestals = snapshot.pedestals.toList())
        validate(accepted)
        operationTail
            .handle { _, _ -> Unit }
            .thenCompose { file.thenCompose { requireNotNull(writer) { "Auction pedestal store is not ready" }.submit(accepted) } }
            .also { operationTail = it.handle { _, _ -> Unit } }
    }

    fun closeAsync(): CompletableFuture<Unit> {
        val previous = synchronized(writerLock) {
            closed = true
            operationTail
        }
        return previous
            .handle { _, _ -> Unit }
            .thenCompose {
                file.thenCompose {
                    synchronized(writerLock) { writer }?.closeAsync()
                        ?: CompletableFuture.completedFuture(Unit)
                }
            }
    }

    private fun encode(snapshot: AuctionPedestalSnapshot): ByteArray =
        gson.toJson(snapshot).toByteArray(StandardCharsets.UTF_8)

    private fun decode(bytes: ByteArray): AuctionPedestalSnapshot {
        val root = JsonParser.parseString(bytes.toString(StandardCharsets.UTF_8))
        require(root.isJsonObject) { "Auction pedestal snapshot root must be an object" }
        val json = root.asJsonObject
        requireFields(json, ROOT_FIELDS, "snapshot")
        val schemaVersion = json.requiredInt("schemaVersion", "snapshot")
        require(schemaVersion == SCHEMA_VERSION) { "Unsupported auction pedestal schema version: $schemaVersion" }
        val pedestalArray = json.requiredElement("pedestals", "snapshot")
        require(pedestalArray.isJsonArray) { "snapshot.pedestals must be an array" }
        val snapshot =
            AuctionPedestalSnapshot(
                schemaVersion = schemaVersion,
                pedestals =
                    pedestalArray.asJsonArray.mapIndexed { index, element ->
                        val path = "snapshot.pedestals[$index]"
                        require(element.isJsonObject) { "$path must be an object" }
                        val record = element.asJsonObject
                        requireFields(record, PEDESTAL_FIELDS, path)
                        AuctionPedestalSpec(
                            id = record.requiredString("id", path),
                            x = record.requiredFiniteDouble("x", path),
                            y = record.requiredFiniteDouble("y", path),
                            z = record.requiredFiniteDouble("z", path),
                            yaw = record.requiredFiniteDouble("yaw", path).toFloat(),
                        )
                    },
            )
        validate(snapshot)
        return snapshot
    }

    private fun validate(snapshot: AuctionPedestalSnapshot) {
        require(snapshot.schemaVersion == SCHEMA_VERSION) { "Unsupported auction pedestal schema version" }
        AuctionPedestalRules.validate(snapshot.pedestals)
    }

    internal companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_FILE_BYTES = 256L * 1024L
        val ROOT_FIELDS = setOf("schemaVersion", "pedestals")
        val PEDESTAL_FIELDS = setOf("id", "x", "y", "z", "yaw")
    }
}

private fun JsonObject.requiredElement(field: String, path: String): JsonElement =
    get(field)?.takeUnless { it.isJsonNull } ?: throw IllegalArgumentException("$path.$field is required")

private fun JsonObject.requiredString(field: String, path: String): String {
    val value = requiredElement(field, path)
    require(value.isJsonPrimitive && value.asJsonPrimitive.isString) { "$path.$field must be a string" }
    return value.asString
}

private fun JsonObject.requiredInt(field: String, path: String): Int {
    val value = requiredElement(field, path)
    require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "$path.$field must be an integer" }
    val exact = value.asString.toLongOrNull() ?: throw IllegalArgumentException("$path.$field must be an integer")
    require(exact in Int.MIN_VALUE..Int.MAX_VALUE) { "$path.$field is outside the integer range" }
    return exact.toInt()
}

private fun JsonObject.requiredFiniteDouble(field: String, path: String): Double {
    val value = requiredElement(field, path)
    require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber) { "$path.$field must be a number" }
    return value.asDouble.also { require(it.isFinite()) { "$path.$field must be finite" } }
}

private fun requireFields(json: JsonObject, allowed: Set<String>, path: String) {
    val unknown = json.keySet() - allowed
    require(unknown.isEmpty()) { "$path contains unknown fields: ${unknown.sorted().joinToString(", ")}" }
}
