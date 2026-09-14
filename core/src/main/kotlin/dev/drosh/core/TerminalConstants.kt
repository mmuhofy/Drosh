package dev.drosh.core

object TerminalConstants {
    const val NOTIFICATION_ID = 1337
    const val COMMAND_COMPLETE_ID = 1338

    const val CHANNEL_ID = "dev.drosh_terminal_service"
    const val COMMAND_CHANNEL_ID = "dev.drosh_command_complete"

    const val COMPLETION_FILE_NAME = "dev.drosh_cmd_complete"
    const val HOOKS_FILE_NAME = "dev.drosh_hooks.zsh"

    const val ACTION_STOP = "dev.drosh.action.STOP_SERVICE"
}
