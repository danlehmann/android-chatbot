package net.daniellehmann.localchat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.daniellehmann.localchat.api.ChatMessage
import net.daniellehmann.localchat.api.ModelInfo
import net.daniellehmann.localchat.api.Usage
import net.daniellehmann.localchat.api.OpenAiClient
import net.daniellehmann.localchat.data.AppDatabase
import net.daniellehmann.localchat.data.Conversation
import net.daniellehmann.localchat.data.Message
import net.daniellehmann.localchat.data.Server

/** In-memory view of the assistant message currently being generated. */
data class Streaming(
    val messageId: Long,
    val content: String = "",
    val reasoning: String = "",
)

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.get(app)

    val servers: StateFlow<List<Server>> = db.servers().observeAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val conversations: StateFlow<List<Conversation>> = db.conversations().observeAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _currentId = MutableStateFlow<Long?>(null)
    val currentId: StateFlow<Long?> = _currentId

    val currentConversation: StateFlow<Conversation?> = _currentId
        .flatMapLatest { id -> if (id == null) flowOf(null) else db.conversations().observe(id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val messages: StateFlow<List<Message>> = _currentId
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else db.messages().observe(id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _streaming = MutableStateFlow<Streaming?>(null)
    val streaming: StateFlow<Streaming?> = _streaming

    /** Models fetched per server id. */
    private val _models = MutableStateFlow<Map<Long, List<ModelInfo>>>(emptyMap())
    val models: StateFlow<Map<Long, List<ModelInfo>>> = _models

    /** One-shot user-facing notices (snackbar). */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice
    fun clearNotice() = _notice.update { null }

    private var generation: Job? = null

    // ---- conversations -------------------------------------------------

    fun newConversation() {
        _currentId.value = null
    }

    fun select(id: Long) {
        _currentId.value = id
    }

    fun deleteConversation(id: Long) = viewModelScope.launch {
        if (_streaming.value != null && _currentId.value == id) stop()
        db.conversations().delete(id)
        if (_currentId.value == id) _currentId.value = null
    }

    fun setConversationServer(serverId: Long, model: String) = viewModelScope.launch {
        val conv = currentConversation.value ?: return@launch
        db.conversations().update(conv.copy(serverId = serverId, model = model))
    }

    fun setConversationModel(model: String) = viewModelScope.launch {
        val conv = currentConversation.value ?: return@launch
        db.conversations().update(conv.copy(model = model))
    }

    // ---- servers -------------------------------------------------------

    fun saveServer(server: Server) = viewModelScope.launch {
        if (server.id == 0L) db.servers().insert(server) else db.servers().update(server)
    }

    fun deleteServer(server: Server) = viewModelScope.launch { db.servers().delete(server) }

    fun fetchModels(server: Server) = viewModelScope.launch {
        runCatching { OpenAiClient(server.baseUrl, server.apiKey).listModels() }
            .onSuccess { list -> _models.update { it + (server.id to list) } }
            .onFailure { e -> _notice.value = "Could not list models: ${e.message}" }
    }

    // ---- chat ----------------------------------------------------------

    /**
     * Sends a user message in the current conversation (creating one if needed)
     * using [server] and [model], then streams the assistant reply.
     */
    fun send(text: String, server: Server, model: String) = viewModelScope.launch {
        if (_streaming.value != null) return@launch
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return@launch

        val convId = _currentId.value ?: run {
            val id = db.conversations().insert(
                Conversation(title = trimmed.lines().first().take(48), serverId = server.id, model = model),
            )
            _currentId.value = id
            id
        }
        val conv = db.conversations().get(convId) ?: return@launch
        if (conv.serverId != server.id || conv.model != model) {
            db.conversations().update(conv.copy(serverId = server.id, model = model))
        }
        db.messages().insert(Message(conversationId = convId, role = "user", content = trimmed))
        generate(convId, server, model)
    }

    /** Drops the last assistant reply (if any) and generates it again. */
    fun regenerate(server: Server, model: String) = viewModelScope.launch {
        if (_streaming.value != null) return@launch
        val convId = _currentId.value ?: return@launch
        val last = db.messages().list(convId).lastOrNull() ?: return@launch
        if (last.role == "assistant") db.messages().delete(last.id)
        generate(convId, server, model)
    }

    /** Removes [message] and everything after it, then re-sends [newText] as the user turn. */
    fun editAndResend(message: Message, newText: String, server: Server, model: String) = viewModelScope.launch {
        if (_streaming.value != null) return@launch
        db.messages().deleteFrom(message.conversationId, message.createdAt, message.id)
        db.messages().insert(Message(conversationId = message.conversationId, role = "user", content = newText.trim()))
        generate(message.conversationId, server, model)
    }

    fun deleteMessage(message: Message) = viewModelScope.launch { db.messages().delete(message.id) }

    fun stop() {
        generation?.cancel()
    }

    private fun generate(convId: Long, server: Server, model: String) {
        generation?.cancel()
        generation = viewModelScope.launch {
            val history = db.messages().list(convId)
            val createdAt = System.currentTimeMillis()
            val assistantId = db.messages().insert(
                Message(conversationId = convId, role = "assistant", content = "", createdAt = createdAt),
            )
            _streaming.value = Streaming(messageId = assistantId)

            val content = StringBuilder()
            val reasoning = StringBuilder()
            var lastFlush = 0L
            var error = ""
            var usage: Usage? = null
            try {
                OpenAiClient(server.baseUrl, server.apiKey)
                    .streamChat(model, buildRequest(server, history), server.temperature)
                    .collect { d ->
                        d.usage?.let { usage = it }
                        content.append(d.content)
                        reasoning.append(d.reasoning)
                        _streaming.update { it?.copy(content = content.toString(), reasoning = reasoning.toString()) }
                        val now = System.currentTimeMillis()
                        if (now - lastFlush > 500) {
                            lastFlush = now
                            persist(assistantId, convId, createdAt, content, reasoning, "")
                        }
                    }
            } catch (e: CancellationException) {
                // User pressed stop or navigated away; keep what we have.
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            } finally {
                persist(assistantId, convId, createdAt, content, reasoning, error)
                usage?.let { u ->
                    db.conversations().get(convId)?.let {
                        db.conversations().update(it.copy(promptTokens = u.promptTokens, completionTokens = u.completionTokens))
                    }
                }
                _streaming.value = null
                generation = null
            }
        }
    }

    private suspend fun persist(
        id: Long,
        convId: Long,
        createdAt: Long,
        content: StringBuilder,
        reasoning: StringBuilder,
        error: String,
    ) {
        db.messages().update(
            Message(
                id = id,
                conversationId = convId,
                role = "assistant",
                content = content.toString(),
                reasoning = reasoning.toString(),
                error = error,
                createdAt = createdAt,
            ),
        )
        db.conversations().get(convId)?.let { db.conversations().update(it.copy(updatedAt = System.currentTimeMillis())) }
    }

    /**
     * Builds the request history: optional system prompt, then the newest
     * messages that fit into the server's character budget.
     */
    private fun buildRequest(server: Server, history: List<Message>): List<ChatMessage> {
        val usable = history.filter { it.content.isNotBlank() && it.role != "system" }
        val budget = server.maxContextChars.takeIf { it > 0 } ?: Int.MAX_VALUE
        val picked = ArrayDeque<ChatMessage>()
        var used = server.systemPrompt.length
        for (m in usable.asReversed()) {
            if (used + m.content.length > budget && picked.isNotEmpty()) break
            picked.addFirst(ChatMessage(m.role, m.content))
            used += m.content.length
        }
        val out = mutableListOf<ChatMessage>()
        if (server.systemPrompt.isNotBlank()) out += ChatMessage("system", server.systemPrompt)
        out += picked
        return out
    }
}
