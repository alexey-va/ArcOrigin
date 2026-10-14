package ru.arc.origin

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import ru.arc.config.Config
import ru.arc.config.ConfigManager
import ru.arc.text.ConfigLocaleCatalog
import ru.arc.text.LocalizedMiniMessage
import java.nio.file.Path

internal data class OriginChunkRegion(
    val minX: Int,
    val maxX: Int,
    val minZ: Int,
    val maxZ: Int,
    val maxInFlight: Int,
)

internal class OriginSpawnConfig private constructor(
    private val source: Config,
    val enabled: Boolean,
    val worldName: String,
    val regenerativeBreakingEnabled: Boolean,
    val regenerativeBreakingRestoreDelayTicks: Long,
    val regenerativeBreakingBypassPermission: String,
    val regenerativeBreakingFeedback: OriginBreakFeedbackSettings,
    val chunkRegion: OriginChunkRegion,
    val showcaseEnabled: Boolean,
    val cycleTicks: Long,
    val pageTicks: Long,
    val rotationTicks: Long,
    val clickDebounceMillis: Long,
    val pedestals: List<AuctionPedestalSpec>,
) {
    private val localized = LocalizedMiniMessage(
        catalogs = mapOf("ru" to ConfigLocaleCatalog(source)),
        defaultLocale = { "ru" },
        prefixPath = "_origin_no_prefix",
        missingMessage = { "" },
    )

    fun listingText(
        itemName: String,
        seller: String,
        price: String,
        locale: String = "ru",
    ): Component {
        val path = "auction-showcase.text.listing"
        val placeholders = mapOf(
            "name" to Component.text(itemName),
            "seller" to Component.text(seller),
            "price" to Component.text(price),
        )
        val fallback = "<#f2b84b><name>\n<#969696>Продавец: <seller>\n<#ffacd5>Цена: <price> <white><bold:false>💰</bold></white>"
        val rendered = if (source.stringOrNull(path) == null) {
            source.component(path, fallback) {
                tag("name", placeholders.getValue("name"))
                tag("seller", placeholders.getValue("seller"))
                tag("price", placeholders.getValue("price"))
            }
        } else {
            localized.render(path, locale, placeholders)
        }
        return rendered.decoration(TextDecoration.ITALIC, false)
    }

    fun message(key: String, locale: String = "ru"): Component {
        val path = "auction-showcase.messages.$key"
        val rendered = if (source.stringOrNull(path) == null) source.component(path, DEFAULT_MESSAGES.getValue(key))
        else localized.render(path, locale)
        return rendered.decoration(TextDecoration.ITALIC, false)
    }

    fun adminDialogText(key: String, locale: String = "ru"): Component {
        val path = "auction-showcase.admin-dialog.$key"
        val rendered = if (source.stringOrNull(path) == null) source.component(path, DEFAULT_ADMIN_DIALOG_TEXT.getValue(key))
        else localized.render(path, locale)
        return rendered.decoration(TextDecoration.ITALIC, false)
    }

    companion object {
        private const val RESOURCE = "origin-spawn.yml"
        const val DEFAULT_BREAK_BYPASS_PERMISSION = "arc.origin.spawn.build"

        fun load(dataPath: Path): OriginSpawnConfig {
            val source = ConfigManager.ofModule(dataPath, RESOURCE)
            source.mergeMissingFromBundled("modules/$RESOURCE")

            val minX = source.integer("chunks.min-x", -16)
            val maxX = source.integer("chunks.max-x", 16)
            val minZ = source.integer("chunks.min-z", -12)
            val maxZ = source.integer("chunks.max-z", 10)
            require(minX <= maxX && minZ <= maxZ) { "origin-spawn chunk bounds are inverted" }
            val chunkCount = (maxX.toLong() - minX + 1L) * (maxZ.toLong() - minZ + 1L)
            require(chunkCount in 1L..4_096L) { "origin-spawn chunk region must contain 1..4096 chunks" }

            val pedestals =
                source.list<Map<String, Any?>>("auction-showcase.pedestals").mapIndexed { index, raw ->
                    AuctionPedestalSpec(
                        id = AuctionPedestalRules.configuredId(index),
                        x = raw.number("x", index),
                        y = raw.number("y", index),
                        z = raw.number("z", index),
                        yaw = raw.number("yaw", index).toFloat(),
                    )
                }
            AuctionPedestalRules.validate(pedestals)
            val enabled = source.bool("enabled", false)
            val showcaseEnabled = source.bool("auction-showcase.enabled", false)
            val bypassPermission =
                source.string("regenerative-breaking.bypass-permission", DEFAULT_BREAK_BYPASS_PERMISSION).trim()
            val feedbackTiers =
                source.list<Map<String, Any?>>("regenerative-breaking.feedback.tiers").mapIndexed { index, raw ->
                    val fromAttempt = (raw["from-attempt"] as? Number)?.toInt()
                        ?: error("origin-spawn feedback tier #${index + 1} is missing numeric 'from-attempt'")
                    val messages =
                        (raw["messages"] as? List<*>)
                            ?.mapNotNull { (it as? String)?.trim()?.takeIf(String::isNotEmpty) }
                            .orEmpty()
                    require(messages.isNotEmpty()) {
                        "origin-spawn feedback tier #${index + 1} must contain at least one message"
                    }
                    OriginBreakFeedbackTier(fromAttempt, messages)
                }
            require(!enabled || source.string("world", "").isNotBlank()) {
                "origin-spawn.world must not be blank when enabled"
            }
            require(bypassPermission.isNotBlank()) {
                "origin-spawn regenerative-breaking.bypass-permission must not be blank"
            }
            require(feedbackTiers.firstOrNull()?.fromAttempt == 1) {
                "origin-spawn feedback tiers must start at attempt 1"
            }
            require(feedbackTiers.zipWithNext().all { (left, right) -> left.fromAttempt < right.fromAttempt }) {
                "origin-spawn feedback tier thresholds must be strictly increasing"
            }
            return OriginSpawnConfig(
                source = source,
                enabled = enabled,
                worldName = source.string("world", "rc_origin_spawn").trim(),
                regenerativeBreakingEnabled = source.bool("regenerative-breaking.enabled", true),
                regenerativeBreakingRestoreDelayTicks =
                    source.long("regenerative-breaking.restore-delay-ticks", 100L).coerceIn(1L, 1_200L),
                regenerativeBreakingBypassPermission = bypassPermission,
                regenerativeBreakingFeedback =
                    OriginBreakFeedbackSettings(
                        enabled = source.bool("regenerative-breaking.feedback.enabled", true),
                        countIntervalTicks =
                            source.long("regenerative-breaking.feedback.count-interval-ticks", 20L)
                                .coerceIn(1L, 1_200L),
                        messageCooldownTicks =
                            source.long("regenerative-breaking.feedback.message-cooldown-ticks", 60L)
                                .coerceIn(1L, 72_000L),
                        resetAfterTicks =
                            source.long("regenerative-breaking.feedback.reset-after-ticks", 2_400L)
                                .coerceIn(20L, 72_000L),
                        tiers = feedbackTiers,
                    ),
                chunkRegion =
                    OriginChunkRegion(
                        minX = minX,
                        maxX = maxX,
                        minZ = minZ,
                        maxZ = maxZ,
                        maxInFlight = source.integer("chunks.max-in-flight", 8).coerceIn(1, 32),
                    ),
                showcaseEnabled = showcaseEnabled,
                cycleTicks = source.long("auction-showcase.cycle-ticks", 40L).coerceIn(20L, 72_000L),
                pageTicks = source.long("auction-showcase.page-ticks", 300L).coerceIn(100L, 72_000L),
                rotationTicks = source.long("auction-showcase.rotation-ticks", 2L).coerceIn(1L, 20L),
                clickDebounceMillis =
                    source.long("auction-showcase.click-debounce-millis", 500L).coerceIn(100L, 5_000L),
                pedestals = pedestals,
            )
        }

        private fun Map<String, Any?>.number(key: String, index: Int): Double =
            (this[key] as? Number)?.toDouble()
                ?: error("origin-spawn pedestal #${index + 1} is missing numeric '$key'")

        private val DEFAULT_MESSAGES =
            mapOf(
                "stale" to "<#ff9f0f>Лот уже недоступен — витрина обновлена",
                "unavailable" to "<#ff9f0f>Аукцион сейчас недоступен",
                "failed" to "<#ff9f0f>Не удалось открыть лот — попробуйте ещё раз",
                "admin-only" to "<#ff6b61>У вас нет прав для управления витриной.",
                "not-ready" to "<#ff9f0f>Витрина ещё загружается — попробуйте через секунду.",
                "saving" to "<#ff9f0f>Сохранение витрины уже выполняется.",
                "target-required" to "<#ff9f0f>Посмотрите на блок, на котором нужно поставить пьедестал.",
                "wrong-world" to "<#ff9f0f>Пьедесталы можно ставить только в мире Origin.",
                "outside-area" to "<#ff9f0f>Поставьте пьедестал в пределах территории спавна Origin.",
                "invalid-target" to "<#ff9f0f>Нужен твёрдый блок с доступной верхней поверхностью.",
                "duplicate" to "<#ff9f0f>На этом месте уже стоит пьедестал.",
                "overlap" to "<#ff9f0f>Здесь пьедестал пересечётся с соседней витриной.",
                "full" to "<#ff9f0f>Достигнут предел пьедесталов витрины.",
                "saved" to "<#9bd48d>Пьедестал аукциона установлен.",
                "removed" to "<#9bd48d>Пьедестал аукциона удалён.",
                "save-failed" to "<#ff6b61>Не удалось сохранить витрину; изменения не применены.",
                "stale-pedestal" to "<#ff9f0f>Этот пьедестал уже изменился — откройте его меню ещё раз.",
            )

        private val DEFAULT_ADMIN_DIALOG_TEXT =
            mapOf(
                "title" to "<#f2b84b>Пьедестал аукциона",
                "empty-body" to "<#e8dfd2>На этом пьедестале сейчас нет лота.",
                "open-lot" to "<white>› Открыть текущий лот",
                "delete" to "<#ff6b61>Удалить пьедестал",
                "confirm-title" to "<#ff6b61>Удалить пьедестал?",
                "confirm-body" to "<#e8dfd2>Удаление сохранится после перезапуска. Действие не удаляет и не изменяет сам лот.",
                "confirm-delete" to "<#ff6b61>Удалить",
                "cancel" to "<white>‹ Назад",
            )
    }
}
