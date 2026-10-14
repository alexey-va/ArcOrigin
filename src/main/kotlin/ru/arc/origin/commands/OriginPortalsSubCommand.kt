package ru.arc.origin.commands

import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import ru.arc.origin.OriginPortalId
import ru.arc.origin.OriginPortalEnterResult
import ru.arc.origin.OriginPortalsModule
import ru.arc.origin.originPortalMoveYaw
import ru.arc.util.TextUtils
import java.util.Locale

/** `/arc originportals move <portal>` is administrative; the explicit enter action is public. */
object OriginPortalsSubCommand : OriginCommandHandler {
    const val USAGE = "/arc originportals <enter slimefun|move <survival|mining|vanilla|gallery_exit|slimefun|furniture_entry>>"

    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        val player = sender as? Player ?: run {
            sender.sendMessage(TextUtils.mm("<red>Эта команда доступна только игрокам.", true))
            return true
        }
        if (args.size == 2 && args[0].equals("enter", ignoreCase = true)) {
            val id = OriginPortalId.parse(args[1])
            if (id != OriginPortalId.SLIMEFUN) {
                sender.sendMessage(TextUtils.mm("<red>Доступен только вход в портал Slimefun."))
                return true
            }
            val result = OriginPortalsModule.enter(id, player)
            when (result) {
                OriginPortalEnterResult.REQUESTED -> Unit
                OriginPortalEnterResult.PORTAL_DISABLED -> sender.sendMessage(
                    TextUtils.mm("<red>Портал Slimefun пока закрыт."),
                )
                OriginPortalEnterResult.WRONG_WORLD -> sender.sendMessage(
                    TextUtils.mm("<red>Войти в Slimefun можно только из зала Origin."),
                )
                OriginPortalEnterResult.TOO_FAR -> sender.sendMessage(
                    TextUtils.mm("<red>Подойдите ближе к порталу Slimefun."),
                )
                OriginPortalEnterResult.ALREADY_PENDING -> sender.sendMessage(
                    TextUtils.mm("<yellow>Запрос перехода уже выполняется."),
                )
                OriginPortalEnterResult.COOLDOWN -> sender.sendMessage(
                    TextUtils.mm("<yellow>Подождите немного перед повторным запросом перехода."),
                )
                OriginPortalEnterResult.TRANSFER_UNAVAILABLE -> sender.sendMessage(
                    TextUtils.mm("<red>Не удалось отправить запрос на переход. Попробуйте позже."),
                )
                OriginPortalEnterResult.UNSUPPORTED_DESTINATION -> sender.sendMessage(
                    TextUtils.mm("<red>Этот портал не поддерживает переход на другой сервер."),
                )
            }
            return true
        }
        if (args.size != 2 || !args[0].equals("move", ignoreCase = true)) {
            sendOriginUsage(sender, USAGE)
            return true
        }
        if (!player.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage(TextUtils.mm("<red>Недостаточно прав для перемещения портала."))
            return true
        }
        val id = OriginPortalId.parse(args[1]) ?: run {
            sender.sendMessage(TextUtils.mm("<red>Неизвестный портал: <white>${args[1]}", true))
            sendOriginUsage(sender, USAGE)
            return true
        }
        val location = player.location
        if (!OriginPortalsModule.move(id, location)) {
            sender.sendMessage(TextUtils.mm("<red>Не удалось сохранить координаты портала.", true))
            return true
        }
        val yaw = originPortalMoveYaw(id, location.yaw)
        sender.sendMessage(
            TextUtils.mm(
                "<green>Портал <white>${id.key}<green> перемещён на <white>%.3f %.3f %.3f <green>(yaw %.1f).".format(Locale.ROOT,
                    location.x,
                    location.y,
                    location.z,
                    yaw,
                ),
                true,
            ),
        )
        return true
    }

    override fun tabComplete(sender: CommandSender, args: Array<String>): List<String> =
        when (args.size) {
            1 -> listOf("enter", "move").originTabComplete(args[0])
            2 -> when {
                args[0].equals("move", ignoreCase = true) -> OriginPortalId.entries.map { it.key }.originTabComplete(args[1])
                args[0].equals("enter", ignoreCase = true) -> listOf(OriginPortalId.SLIMEFUN.key).originTabComplete(args[1])
                else -> emptyList()
            }
            else -> null
        }

    fun isPublicAction(args: Array<String>): Boolean =
        args.size == 2 && args[0].equals("enter", ignoreCase = true) &&
            args[1].equals(OriginPortalId.SLIMEFUN.key, ignoreCase = true)

    private const val ADMIN_PERMISSION = "arc.origin.portals.admin"
}
