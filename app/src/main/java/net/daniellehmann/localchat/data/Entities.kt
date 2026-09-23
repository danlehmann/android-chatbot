package net.daniellehmann.localchat.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** An OpenAI-compatible server, typically reached through an SSH tunnel on loopback. */
@Entity(tableName = "servers")
data class Server(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Base URL including the /v1 prefix, e.g. http://127.0.0.1:8000/v1 */
    val baseUrl: String,
    val apiKey: String = "",
    val defaultModel: String = "",
    val systemPrompt: String = "",
    /** Rough character budget for history sent per request; 0 disables trimming. */
    val maxContextChars: Int = 60_000,
    val temperature: Float = 0.7f,
    /** Context window in tokens; 0 = use what the server reports. */
    @ColumnInfo(defaultValue = "0") val contextTokens: Int = 0,
)

@Entity(
    tableName = "conversations",
    foreignKeys = [ForeignKey(
        entity = Server::class,
        parentColumns = ["id"],
        childColumns = ["serverId"],
        onDelete = ForeignKey.SET_NULL,
    )],
    indices = [Index("serverId"), Index("updatedAt")],
)
data class Conversation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String = "New chat",
    val serverId: Long? = null,
    val model: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** Token usage reported by the last completion: prompt + completion ~= context in use. */
    @ColumnInfo(defaultValue = "0") val promptTokens: Int = 0,
    @ColumnInfo(defaultValue = "0") val completionTokens: Int = 0,
)

@Entity(
    tableName = "messages",
    foreignKeys = [ForeignKey(
        entity = Conversation::class,
        parentColumns = ["id"],
        childColumns = ["conversationId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("conversationId")],
)
data class Message(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    /** "user", "assistant" or "system" */
    val role: String,
    val content: String,
    /** Chain-of-thought text from reasoning models, shown collapsed. */
    val reasoning: String = "",
    /** Error text if generation failed; the message is kept so the user can retry. */
    val error: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)
