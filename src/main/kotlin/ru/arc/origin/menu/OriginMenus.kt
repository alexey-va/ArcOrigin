package ru.arc.origin.menu

import net.kyori.adventure.text.Component
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import ru.arc.origin.OriginServices
import ru.arc.paper.menu.PaperDialogActionId
import ru.arc.paper.menu.PaperDialogButton
import ru.arc.paper.menu.PaperDialogRuntime
import ru.arc.paper.menu.PaperDialogScreen

/** Plugin-local Paper dialog runtime; host APIs carry only navigation preferences. */
internal object OriginMenus : AutoCloseable {
    private var runtime: PaperDialogRuntime? = null

    fun initialize(plugin: Plugin) {
        check(runtime == null) { "Origin dialog runtime is already initialized" }
        runtime = PaperDialogRuntime(plugin)
    }

    fun beginDialogFlow(player: Player) {
        OriginServices.dialogNavigation.beginDialogFlow(player)
        requireNotNull(runtime) { "Origin dialog runtime is not initialized" }.beginFlow(player)
    }

    fun openDialog(
        player: Player,
        screen: PaperDialogScreen,
        reopen: (() -> Unit)? = null,
        onDismiss: () -> Unit = {},
    ) {
        val escapeGoesBack = OriginServices.dialogNavigation.escapeGoesBack(player.uniqueId)
        val closeButton = PaperDialogButton(
            id = PaperDialogActionId.of("close_menu"),
            label = Component.translatable("gui.back"),
            width = 200,
            closeDialogBeforeAction = true,
            onClick = {},
        )
        val adjusted = applyEscapeBehavior(screen, escapeGoesBack, closeButton)
        requireNotNull(runtime) { "Origin dialog runtime is not initialized" }.open(
            player,
            adjusted,
            reopen,
            onDismiss,
            !escapeGoesBack,
        )
    }

    override fun close() {
        runtime?.close()
        runtime = null
    }
}

/** Native Escape runs the footer action; ordinary navigation buttons remain unchanged. */
internal fun applyEscapeBehavior(
    screen: PaperDialogScreen,
    goesBack: Boolean,
    closeButton: PaperDialogButton,
): PaperDialogScreen {
    if (goesBack) return screen
    val regularExit = screen.exitButton?.let { it.copy(width = screen.buttons.firstOrNull()?.width ?: it.width) }
    return screen.copy(
        buttons = screen.buttons + listOfNotNull(regularExit),
        exitButton = closeButton,
    )
}
