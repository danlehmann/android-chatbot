package net.daniellehmann.localchat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.KeyboardOptions as FoundationKeyboardOptions
import androidx.compose.ui.unit.dp
import net.daniellehmann.localchat.ChatViewModel
import net.daniellehmann.localchat.data.Server

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServersScreen(vm: ChatViewModel, onBack: () -> Unit, onEdit: (Long) -> Unit) {
    val servers by vm.servers.collectAsState()
    var pendingDelete by remember { mutableStateOf<Server?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Servers") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { onEdit(0L) }) { Icon(Icons.Default.Add, "Add server") }
        },
    ) { padding ->
        if (servers.isEmpty()) {
            Column(Modifier.padding(padding).padding(24.dp)) {
                Text("No servers yet.", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Start your SSH tunnel, then add a server pointing at the local " +
                        "forwarded port, e.g. http://127.0.0.1:8000/v1",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            LazyColumn(Modifier.padding(padding)) {
                items(servers, key = { it.id }) { s ->
                    ListItem(
                        headlineContent = { Text(s.name) },
                        supportingContent = { Text(s.baseUrl + if (s.defaultModel.isNotBlank()) "  ·  ${s.defaultModel}" else "") },
                        trailingContent = {
                            IconButton(onClick = { pendingDelete = s }) { Icon(Icons.Default.Delete, "Delete") }
                        },
                        modifier = Modifier.clickable { onEdit(s.id) },
                    )
                }
            }
        }
    }

    pendingDelete?.let { s ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete ${s.name}?") },
            text = { Text("Conversations that used it are kept but lose their server.") },
            confirmButton = { TextButton(onClick = { vm.deleteServer(s); pendingDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerEditScreen(vm: ChatViewModel, serverId: Long, onDone: () -> Unit) {
    val servers by vm.servers.collectAsState()
    val existing = servers.firstOrNull { it.id == serverId }
    val models by vm.models.collectAsState()

    var name by remember(existing) { mutableStateOf(existing?.name ?: "") }
    var baseUrl by remember(existing) { mutableStateOf(existing?.baseUrl ?: "http://127.0.0.1:8000/v1") }
    var apiKey by remember(existing) { mutableStateOf(existing?.apiKey ?: "") }
    var model by remember(existing) { mutableStateOf(existing?.defaultModel ?: "") }
    var systemPrompt by remember(existing) { mutableStateOf(existing?.systemPrompt ?: "") }
    var maxChars by remember(existing) { mutableStateOf((existing?.maxContextChars ?: 60_000).toString()) }
    var temperature by remember(existing) { mutableStateOf(existing?.temperature ?: 0.7f) }
    var contextTokens by remember(existing) { mutableStateOf((existing?.contextTokens ?: 0).toString()) }
    var modelMenu by remember { mutableStateOf(false) }

    val draft = Server(
        id = existing?.id ?: 0L,
        name = name.trim().ifBlank { "Server" },
        baseUrl = baseUrl.trim(),
        apiKey = apiKey.trim(),
        defaultModel = model.trim(),
        systemPrompt = systemPrompt,
        maxContextChars = maxChars.toIntOrNull() ?: 0,
        temperature = temperature,
        contextTokens = contextTokens.toIntOrNull() ?: 0,
    )
    val fetched = models[draft.id].orEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "Add server" else "Edit server") },
                navigationIcon = {
                    IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                baseUrl, { baseUrl = it },
                label = { Text("Base URL (ending in /v1)") },
                singleLine = true,
                keyboardOptions = FoundationKeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                apiKey, { apiKey = it },
                label = { Text("API key (optional)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedTextField(model, { model = it }, label = { Text("Default model") }, singleLine = true, modifier = Modifier.weight(1f))
                Spacer(Modifier.padding(4.dp))
                Column {
                    OutlinedButton(
                        onClick = { vm.fetchModels(draft); modelMenu = true },
                        enabled = draft.baseUrl.startsWith("http"),
                    ) { Text("Fetch") }
                    DropdownMenu(expanded = modelMenu && fetched.isNotEmpty(), onDismissRequest = { modelMenu = false }) {
                        fetched.forEach { m ->
                            DropdownMenuItem(
                                text = { Text(m.id + (m.contextLength?.let { "  ($it ctx)" } ?: "")) },
                                onClick = { model = m.id; modelMenu = false },
                            )
                        }
                    }
                }
            }
            OutlinedTextField(
                systemPrompt, { systemPrompt = it },
                label = { Text("System prompt (optional)") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                maxChars, { maxChars = it.filter(Char::isDigit) },
                label = { Text("History budget in characters (0 = unlimited)") },
                singleLine = true,
                keyboardOptions = FoundationKeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                contextTokens, { contextTokens = it.filter(Char::isDigit) },
                label = { Text("Context window in tokens (0 = as reported by server)") },
                singleLine = true,
                keyboardOptions = FoundationKeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Temperature: ${"%.2f".format(temperature)}", style = MaterialTheme.typography.bodyMedium)
            Slider(value = temperature, onValueChange = { temperature = it }, valueRange = 0f..2f)
            Button(
                onClick = { vm.saveServer(draft); onDone() },
                enabled = draft.baseUrl.startsWith("http"),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save") }
            Spacer(Modifier.height(24.dp))
        }
    }
}
