package ru.arc.origin.util

import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
import org.bukkit.entity.Player
import ru.arc.util.TextUtils
import java.time.Duration

fun Player.showTitleMM(
    title: String,
    subtitle: String = "",
    fadeIn: Int = 10,
    stay: Int = 70,
    fadeOut: Int = 20,
) {
    val titleComponent = TextUtils.mm(title)
    val subtitleComponent = if (subtitle.isNotEmpty()) TextUtils.mm(subtitle) else Component.empty()
    val times = Title.Times.times(
        Duration.ofMillis(fadeIn * 50L),
        Duration.ofMillis(stay * 50L),
        Duration.ofMillis(fadeOut * 50L),
    )
    showTitle(Title.title(titleComponent, subtitleComponent, times))
}
