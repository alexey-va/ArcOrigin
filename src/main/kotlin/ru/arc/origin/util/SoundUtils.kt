package ru.arc.origin.util

import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.Sound
import org.bukkit.entity.Player
import java.util.Locale
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap

/**
 * Utilities for working with sounds.
 *
 * Provides cached sound lookup and convenient playback methods.
 */
object SoundUtils {
    // Use Optional to cache "not found" results (ConcurrentHashMap doesn't allow null values)
    private val soundCache = ConcurrentHashMap<String, Optional<Sound>>()
    private val keyCache = ConcurrentHashMap<String, Optional<NamespacedKey>>()

    /**
     * Gets a Sound by name, with caching.
     *
     * Supports both:
     * - Bukkit enum names: "ENTITY_PLAYER_LEVELUP"
     * - Namespaced keys: "minecraft:entity.player.levelup", "block_amethyst_cluster_hit"
     *
     * @param name the sound name
     * @return the Sound or null if not found
     */
    @JvmStatic
    fun getSound(name: String): Sound? {
        val normalizedName = normalizeName(name) ?: return null

        return soundCache
            .getOrPut(normalizedName) {
                Optional.ofNullable(findSound(normalizedName))
            }.orElse(null)
    }

    private fun findSound(name: String): Sound? {
        // Try as namespaced key first (handles "minecraft:block.stone.break", "block.stone.break")
        NamespacedKey
            .fromString(name)
            ?.let { Registry.SOUNDS.get(it) }
            ?.let { return it }

        // Try as legacy Bukkit field name (e.g. BLOCK_STONE_BREAK) via interface field reflection
        try {
            @Suppress("UNCHECKED_CAST")
            (Sound::class.java.getField(name.uppercase(Locale.ROOT)).get(null) as? Sound)
                ?.let { return it }
        } catch (_: ReflectiveOperationException) {
            // field doesn't exist - not a legacy enum name
        }

        return null
    }

    /**
     * Gets a NamespacedKey from a string, with caching.
     *
     * @param name the key string (e.g., "minecraft:entity.player.levelup" or "block_amethyst_cluster_hit")
     * @return the NamespacedKey or null if invalid
     */
    @JvmStatic
    fun getNamespacedKey(name: String): NamespacedKey? {
        val normalizedName = normalizeName(name) ?: return null

        return keyCache
            .getOrPut(normalizedName) {
                Optional.ofNullable(getNamespacedKeyInternal(normalizedName))
            }.orElse(null)
    }

    private fun getNamespacedKeyInternal(name: String): NamespacedKey? = NamespacedKey.fromString(name)

    private fun normalizeName(name: String): String? =
        name.trim().takeIf { it.isNotEmpty() }?.lowercase(Locale.ROOT)

    /**
     * Plays a sound at a location for all nearby players.
     *
     * @param location the location to play at
     * @param soundName the sound name (Bukkit enum or namespaced key)
     * @param volume the volume (default 1.0)
     * @param pitch the pitch (default 1.0)
     * @return true if the sound was found and played
     */
    @JvmStatic
    fun playSound(
        location: Location,
        soundName: String,
        volume: Float = 1.0f,
        pitch: Float = 1.0f,
    ): Boolean {
        val sound = getSound(soundName) ?: return false
        val world = location.world ?: return false
        world.playSound(location, sound, volume, pitch)
        return true
    }

    /**
     * Plays a sound to a specific player.
     *
     * @param player the player to play to
     * @param soundName the sound name (Bukkit enum or namespaced key)
     * @param volume the volume (default 1.0)
     * @param pitch the pitch (default 1.0)
     * @return true if the sound was found and played
     */
    @JvmStatic
    fun playSound(
        player: Player,
        soundName: String,
        volume: Float = 1.0f,
        pitch: Float = 1.0f,
    ): Boolean {
        val sound = getSound(soundName) ?: return false
        player.playSound(player.location, sound, volume, pitch)
        return true
    }

    /**
     * Plays a sound at a location to a specific player.
     *
     * @param player the player to play to
     * @param location the location of the sound
     * @param soundName the sound name
     * @param volume the volume (default 1.0)
     * @param pitch the pitch (default 1.0)
     * @return true if the sound was found and played
     */
    @JvmStatic
    fun playSoundAt(
        player: Player,
        location: Location,
        soundName: String,
        volume: Float = 1.0f,
        pitch: Float = 1.0f,
    ): Boolean {
        val sound = getSound(soundName) ?: return false
        player.playSound(location, sound, volume, pitch)
        return true
    }

    /**
     * Clears the sound cache.
     * Useful for reloading.
     */
    @JvmStatic
    fun clearCache() {
        soundCache.clear()
        keyCache.clear()
    }
}

// === Extension Functions ===

/**
 * Plays a sound at this location.
 *
 * @param soundName the sound name
 * @param volume the volume
 * @param pitch the pitch
 * @return true if played
 */
fun Location.playSound(
    soundName: String,
    volume: Float = 1.0f,
    pitch: Float = 1.0f,
): Boolean = SoundUtils.playSound(this, soundName, volume, pitch)

/**
 * Plays a sound to this player at their location.
 *
 * @param soundName the sound name
 * @param volume the volume
 * @param pitch the pitch
 * @return true if played
 */
fun Player.playSound(
    soundName: String,
    volume: Float = 1.0f,
    pitch: Float = 1.0f,
): Boolean = SoundUtils.playSound(this, soundName, volume, pitch)

/**
 * Plays a sound to this player at a specific location.
 *
 * @param location the location
 * @param soundName the sound name
 * @param volume the volume
 * @param pitch the pitch
 * @return true if played
 */
fun Player.playSoundAt(
    location: Location,
    soundName: String,
    volume: Float = 1.0f,
    pitch: Float = 1.0f,
): Boolean = SoundUtils.playSoundAt(this, location, soundName, volume, pitch)
