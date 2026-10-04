/*
 * Copyright (C) 2026 Drosh contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 */

package dev.drosh.data.state

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * Read-only bridge from Drosh to Drosh Keyboard.
 *
 * The keyboard is a system-wide IME: it runs inside any app and has no
 * business knowing about terminal sessions. It does need to know whether a
 * command is running and how it ended, so the whole surface is one row of
 * small values.
 *
 * A provider rather than a broadcast, for two reasons. The state has to be
 * *readable*, not merely announced, so a keyboard that starts mid-command
 * catches up on its first query instead of waiting for the next event. And
 * [android.content.ContentResolver.notifyChange] lets an observer subscribe
 * instead of polling.
 *
 * Guarded by a signature-level permission, so only builds signed with the same
 * key may read it. Both apps are signed with one key today precisely so that
 * this works.
 *
 * Everything here is optional. If Drosh is not running, or is not the
 * foreground app, the query returns an empty cursor and the keyboard stays
 * neutral — an IME must never depend on another app being alive.
 *
 * State comes from [CommandStateBus], written by the foreground service.
 */
class CommandStateProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "dev.drosh.state"

        /** Declared and required by both apps; see AndroidManifest. */
        const val PERMISSION = "dev.drosh.permission.READ_COMMAND_STATE"

        const val PATH_COMMAND = "command"
        const val URI_COMMAND = "content://$AUTHORITY/$PATH_COMMAND"

        const val COLUMN_SESSION = "session"
        const val COLUMN_STATUS = "status"
        const val COLUMN_COMMAND = "command"
        const val COLUMN_EXIT_CODE = "exit_code"
        const val COLUMN_STARTED_AT = "started_at"
        const val COLUMN_CWD = "cwd"
        const val COLUMN_LEVEL = "level"
        const val COLUMN_ACTIVITY = "activity_level"
        const val COLUMN_AMBIENT_TINT = "ambient_tint"

        /** Generous: nothing here is large and the keyboard may hold the cursor. */
        private const val MAX_COMMAND_CHARS = 512

        private val COLUMNS = arrayOf(
            COLUMN_SESSION,
            COLUMN_STATUS,
            COLUMN_COMMAND,
            COLUMN_EXIT_CODE,
            COLUMN_STARTED_AT,
            COLUMN_CWD,
            COLUMN_LEVEL,
            COLUMN_ACTIVITY,
            COLUMN_AMBIENT_TINT,
        )
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        if (uri.pathSegments.firstOrNull() != PATH_COMMAND) return null

        val cursor = MatrixCursor(COLUMNS)
        val entry = CommandStateBus.read() ?: return cursor

        cursor.addRow(
            arrayOf(
                entry.sessionId,
                CommandStateBus.statusCode(entry.snapshot.status),
                entry.snapshot.command.take(MAX_COMMAND_CHARS),
                // -1 rather than null: MatrixCursor cannot hold a null Int and a
                // reader should not have to distinguish "absent" from "no value".
                entry.snapshot.exitCode ?: -1,
                entry.snapshot.startedAtElapsedMs,
                entry.snapshot.cwd,
                CommandStateBus.levelCode(entry.snapshot.level),
                entry.activityLevel,
                // -1 rather than null: MatrixCursor cannot hold a null Int.
                entry.ambientTint ?: -1,
            ),
        )
        return cursor
    }

    /** Read-only for consumers. */
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun getType(uri: Uri): String? = null
}
