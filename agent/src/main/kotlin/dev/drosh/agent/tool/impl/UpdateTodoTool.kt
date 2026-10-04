package dev.drosh.agent.tool.impl

import dev.drosh.domain.agent.Tool
import dev.drosh.domain.agent.ToolContext
import dev.drosh.domain.agent.ToolResult
import dev.drosh.domain.agent.toolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps a checklist of what the agent intends to do.
 *
 * ## Why it holds state instead of just printing
 *
 * A todo list the agent has to re-derive from its own transcript is a todo list
 * it will get wrong. This one is addressed by item id, so "mark 2 done" is
 * unambiguous, and the whole list is returned after every change so the model's
 * view and the user's view cannot drift apart.
 *
 * ## State is per chat and in memory
 *
 * The agent session and its runtime die with the process; a checklist that
 * outlived them would describe work nobody is doing. It is rebuilt from the
 * transcript when a session is resumed.
 */
@Singleton
class UpdateTodoTool @Inject constructor() : Tool {

    override val name: String = NAME
    override val description: String =
        "Record or update a checklist for the task at hand. Create the list once, " +
            "then mark items in_progress and completed as you work. Send the whole " +
            "list each time. Use it for work with three or more steps; skip it for " +
            "anything you can finish in one or two steps."
    override val requiresApproval: Boolean = false
    override val parameters: JsonObject = toolSchema {
        raw("todos", TODOS_SCHEMA)
    }

    /** chatId → list, ordered as the model arranged it. */
    private val lists = ConcurrentHashMap<String, List<Item>>()

    override fun summarize(input: JsonObject): String {
        val todos = input["todos"] as? JsonArray
        val count = todos?.size() ?: 0
        return "todo list ($count item${if (count == 1) "" else "s"})"
    }

    override suspend fun execute(input: JsonObject, context: ToolContext): ToolResult {
        val raw = input["todos"] as? JsonArray
            ?: return ToolResult.Error("todos must be an array of {id, title, status}")

        val parsed = raw.mapIndexedNotNull { index, element ->
            val obj = element as? JsonObject ?: return@mapIndexedNotNull null
            val id = obj["id"]?.jsonPrimitive?.content?.trim().ifEmpty { (index + 1).toString() }
            val title = obj["title"]?.jsonPrimitive?.content?.trim().orEmpty()
            if (title.isEmpty()) return@mapIndexedNotNull null
            val status = parseStatus(obj["status"]?.jsonPrimitive?.content)
            Item(id = id, title = title, status = status)
        }

        if (parsed.isEmpty()) {
            return ToolResult.Error("no usable todo items — each needs an id and a title")
        }

        lists[context.chatId] = parsed
        return ToolResult.Success(render(parsed))
    }

    /** The current list for a chat, for the UI to render as a card. */
    fun snapshot(chatId: String): List<Item> = lists[chatId].orEmpty()

    private fun parseStatus(raw: String?): Status = when (raw?.trim()?.lowercase()) {
        "completed", "done" -> Status.COMPLETED
        "in_progress", "in-progress", "doing" -> Status.IN_PROGRESS
        else -> Status.PENDING
    }

    private fun render(items: List<Item>): String {
        val done = items.count { it.status == Status.COMPLETED }
        val body = items.joinToString("\n") { item ->
            val mark = when (item.status) {
                Status.PENDING -> " "
                Status.IN_PROGRESS -> ">"
                Status.COMPLETED -> "x"
            }
            "[$mark] ${item.id}. ${item.title}"
        }
        return "TODO ($done/${items.size})\n$body"
    }

    data class Item(val id: String, val title: String, val status: Status)

    enum class Status { PENDING, IN_PROGRESS, COMPLETED }

    companion object {
        const val NAME = "update_todo"

        /** Hand-written because the schema builder covers scalars, not object arrays. */
        private val TODOS_SCHEMA = buildJsonObject {
            put(
                "type",
                "array",
            )
            put("description", "the complete checklist; send every item each time")
            put(
                "items",
                buildJsonObject {
                    put("type", "object")
                    put(
                        "properties",
                        buildJsonObject {
                            put(
                                "id",
                                buildJsonObject {
                                    put("type", "string")
                                    put("description", "stable identifier for this item")
                                },
                            )
                            put(
                                "title",
                                buildJsonObject {
                                    put("type", "string")
                                    put("description", "what needs doing")
                                },
                            )
                            put(
                                "status",
                                buildJsonObject {
                                    put("type", "string")
                                    put("description", "pending, in_progress or completed")
                                    put(
                                        "enum",
                                        buildJsonArray {
                                            add(
                                                JsonPrimitive("pending"),
                                            )
                                            add(
                                                JsonPrimitive("in_progress"),
                                            )
                                            add(
                                                JsonPrimitive("completed"),
                                            )
                                        },
                                    )
                                },
                            )
                        },
                    )
                    put("required", buildJsonArray {
                        add(JsonPrimitive("id"))
                        add(JsonPrimitive("title"))
                    })
                },
            )
        }
    }
}
