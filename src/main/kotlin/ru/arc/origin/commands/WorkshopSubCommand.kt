package ru.arc.origin.commands

import org.bukkit.command.CommandSender
import ru.arc.origin.OriginFurnitureWorkshopModule
import ru.arc.origin.OriginFurnitureWorkshopSleepRequestResult
import ru.arc.origin.originWorkshopSleepNpcIds
import ru.arc.util.TextUtils

/** Queue one configured Origin workshop NPC as the next resting worker. */
object WorkshopSubCommand : OriginCommandHandler {
    const val USAGE = "/arc workshop sleep <npc-id>"

    override fun execute(sender: CommandSender, args: Array<String>): Boolean {
        if (args.size != 2 || !args[0].equals("sleep", ignoreCase = true)) {
            sendOriginUsage(sender, USAGE)
            return true
        }
        val npcId = resolveWorkshopSleepNpcId(args[1])
        if (npcId == null) {
            sender.sendMessage(TextUtils.mm(
                "<red>Укажите ID жителя мастерской: <white>${originWorkshopSleepNpcIds().joinToString(", ")}<red>.",
                true,
            ))
            return true
        }

        when (OriginFurnitureWorkshopModule.requestSleep(npcId)) {
            OriginFurnitureWorkshopSleepRequestResult.QUEUED -> sender.sendMessage(TextUtils.mm(
                "<green>Житель <white>$npcId<green> поставлен следующим на отдых. Запрос не прерывает рабочий цикл или игру на станции.",
                true,
            ))
            OriginFurnitureWorkshopSleepRequestResult.ALREADY_SLEEPING -> sender.sendMessage(TextUtils.mm(
                "<yellow>Житель <white>$npcId<yellow> уже отдыхает; расписание не изменено.",
                true,
            ))
            OriginFurnitureWorkshopSleepRequestResult.UNKNOWN_NPC -> sender.sendMessage(TextUtils.mm(
                "<red>Житель мастерской с ID <white>$npcId<red> не настроен.",
                true,
            ))
            OriginFurnitureWorkshopSleepRequestResult.NPC_UNAVAILABLE -> sender.sendMessage(TextUtils.mm(
                "<red>Житель <white>$npcId<red> сейчас не загружен или недоступен.",
                true,
            ))
            OriginFurnitureWorkshopSleepRequestResult.SHIFT_UNAVAILABLE -> sender.sendMessage(TextUtils.mm(
                "<red>Ротация мастерской сейчас восстанавливается; запрос не поставлен в очередь.",
                true,
            ))
            OriginFurnitureWorkshopSleepRequestResult.WORKSHOP_UNAVAILABLE -> sender.sendMessage(TextUtils.mm(
                "<red>Мастерская сейчас выключена или не готова.",
                true,
            ))
        }
        return true
    }

    override fun tabComplete(sender: CommandSender, args: Array<String>): List<String> =
        workshopSleepTabCompletions(args)
}

internal fun resolveWorkshopSleepNpcId(value: String): Int? =
    originWorkshopSleepNpcIds().firstOrNull { it.toString() == value }

internal fun workshopSleepTabCompletions(args: Array<String>): List<String> = when {
    args.size == 1 -> listOf("sleep").filter { it.startsWith(args[0], ignoreCase = true) }
    args.size == 2 && args[0].equals("sleep", ignoreCase = true) ->
        originWorkshopSleepNpcIds().map { it.toString() }.filter { it.startsWith(args[1]) }
    else -> emptyList()
}
