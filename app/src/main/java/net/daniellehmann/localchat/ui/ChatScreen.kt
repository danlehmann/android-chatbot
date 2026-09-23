package net.daniellehmann.localchat.ui

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.core.content.FileProvider
import java.io.File
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.daniellehmann.localchat.ChatViewModel
import net.daniellehmann.localchat.Prefs
import net.daniellehmann.localchat.Streaming
import net.daniellehmann.localchat.api.ModelInfo
import net.daniellehmann.localchat.data.Conversation
import net.daniellehmann.localchat.data.ImageStore
import net.daniellehmann.localchat.data.Message
import net.daniellehmann.localchat.data.Server

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel, onOpenServers: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val servers by vm.servers.collectAsState()
    val conversations by vm.conversations.collectAsState()
    val current by vm.currentConversation.collectAsState()
    val currentId by vm.currentId.collectAsState()
    val messages by vm.messages.collectAsState()
    val streaming by vm.streaming.collectAsState()
    val models by vm.models.collectAsState()
    val notice by vm.notice.collectAsState()

    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // Server/model selection: the conversation's own if it has one, else the last used.
    var pickedServerId by remember { mutableStateOf(prefs.lastServerId) }
    var pickedModel by remember { mutableStateOf(prefs.lastModel) }
    LaunchedEffect(current?.id, current?.serverId, current?.model) {
        current?.let { c ->
            c.serverId?.let { pickedServerId = it }
            if (c.model.isNotBlank()) pickedModel = c.model
        }
    }
    val server = servers.firstOrNull { it.id == pickedServerId } ?: servers.firstOrNull()
    val model = pickedModel.ifBlank { server?.defaultModel.orEmpty() }
    LaunchedEffect(server?.id) {
        server?.let { s ->
            prefs.lastServerId = s.id
            if (models[s.id] == null) vm.fetchModels(s)
        }
    }

    LaunchedEffect(notice) {
        notice?.let { snackbar.showSnackbar(it); vm.clearNotice() }
    }

    var pendingDelete by remember { mutableStateOf<Conversation?>(null) }
    val attachments by vm.attachments.collectAsState()
    var viewing by remember { mutableStateOf<File?>(null) }

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { vm.attach(it) }
    }
    // Survives process death while the camera app is in front.
    var cameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val file = cameraPath?.let(::File)
        cameraPath = null
        if (file != null) {
            if (ok && file.length() > 0) vm.attach(Uri.fromFile(file), deleteAfter = file) else file.delete()
        }
    }
    val launchCamera = {
        val file = vm.images.newCameraFile()
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        cameraPath = file.path
        try {
            takePhoto.launch(uri)
        } catch (e: ActivityNotFoundException) {
            cameraPath = null
            vm.notify("No camera app available")
        }
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet {
                Spacer(Modifier.height(12.dp))
                NavigationDrawerItem(
                    label = { Text("New chat") },
                    icon = { Icon(Icons.Default.Add, null) },
                    selected = currentId == null,
                    onClick = { vm.newConversation(); scope.launch { drawer.close() } },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                NavigationDrawerItem(
                    label = { Text("Servers") },
                    icon = { Icon(Icons.Default.Settings, null) },
                    selected = false,
                    onClick = { scope.launch { drawer.close() }; onOpenServers() },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                LazyColumn {
                    items(conversations, key = { it.id }) { c ->
                        NavigationDrawerItem(
                            label = { Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            selected = c.id == currentId,
                            onClick = { vm.select(c.id); scope.launch { drawer.close() } },
                            badge = {
                                IconButton(onClick = { pendingDelete = c }) {
                                    Icon(Icons.Default.Delete, "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                }
            }
        },
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Default.Menu, "Menu") }
                    },
                    title = {
                        val serverModels = server?.let { models[it.id] }.orEmpty()
                        val limit = server?.contextTokens?.takeIf { it > 0 }
                            ?: serverModels.firstOrNull { it.id == model }?.contextLength
                        val used = current?.let { it.promptTokens + it.completionTokens } ?: 0
                        ServerModelPicker(
                            servers = servers,
                            server = server,
                            model = model,
                            models = serverModels.map { it.id },
                            contextUsed = used,
                            contextLimit = limit,
                            enabled = streaming == null,
                            onServer = { s -> pickedServerId = s.id; pickedModel = s.defaultModel; prefs.lastModel = s.defaultModel },
                            onModel = { m ->
                                pickedModel = m; prefs.lastModel = m
                                if (current != null) vm.setConversationModel(m)
                            },
                        )
                    },
                )
            },
            bottomBar = {
                InputBar(
                    enabled = server != null && model.isNotBlank(),
                    streaming = streaming != null,
                    attachments = attachments.map { vm.images.file(it) },
                    onSend = { text -> server?.let { vm.send(text, it, model) } },
                    onStop = { vm.stop() },
                    onPickPhoto = {
                        pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    onTakePhoto = launchCamera,
                    onRemoveAttachment = { f -> vm.removeAttachment(f.name) },
                    onOpenImage = { viewing = it },
                )
            },
        ) { padding ->
            if (server == null) {
                Column(Modifier.padding(padding).padding(24.dp)) {
                    Text("No server configured", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onOpenServers) { Text("Add a server") }
                }
            } else {
                MessageList(
                    messages = messages,
                    streaming = streaming,
                    images = vm.images,
                    onOpenImage = { viewing = it },
                    modifier = Modifier.padding(padding).fillMaxSize(),
                    onRegenerate = { vm.regenerate(server, model) },
                    onDelete = { vm.deleteMessage(it) },
                    onEdit = { m, text -> vm.editAndResend(m, text, server, model) },
                )
            }
        }
    }

    viewing?.let { f -> FullImageDialog(f, onDismiss = { viewing = null }) }

    pendingDelete?.let { c ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete chat?") },
            text = { Text(c.title) },
            confirmButton = { TextButton(onClick = { vm.deleteConversation(c.id); pendingDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ServerModelPicker(
    servers: List<Server>,
    server: Server?,
    model: String,
    models: List<String>,
    contextUsed: Int,
    contextLimit: Int?,
    enabled: Boolean,
    onServer: (Server) -> Unit,
    onModel: (String) -> Unit,
) {
    val ctx = when {
        contextLimit != null && contextUsed > 0 -> "${fmtTokens(contextUsed)} / ${fmtTokens(contextLimit)}"
        contextLimit != null -> "ctx ${fmtTokens(contextLimit)}"
        contextUsed > 0 -> "${fmtTokens(contextUsed)} used"
        else -> ""
    }
    var serverMenu by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    Column {
        Box {
            Text(
                server?.name ?: "No server",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.clickable(enabled = enabled && servers.size > 1) { serverMenu = true },
            )
            DropdownMenu(expanded = serverMenu, onDismissRequest = { serverMenu = false }) {
                servers.forEach { s ->
                    DropdownMenuItem(text = { Text(s.name) }, onClick = { onServer(s); serverMenu = false })
                }
            }
        }
        Box {
            // Model name gives way to the context counter, which must stay readable.
            Row(Modifier.clickable(enabled = enabled) { modelMenu = true }) {
                Text(
                    model.ifBlank { "Pick a model" }.substringAfterLast('/'),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (ctx.isNotEmpty()) {
                    Text(
                        "  \u00b7  $ctx",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
            DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                if (models.isEmpty()) {
                    DropdownMenuItem(text = { Text("No models listed; set one on the server") }, onClick = { modelMenu = false })
                }
                models.forEach { m ->
                    DropdownMenuItem(text = { Text(m) }, onClick = { onModel(m); modelMenu = false })
                }
            }
        }
    }
}

private fun fmtTokens(n: Int): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000f)
    n >= 10_000 -> "${n / 1000}k"
    n >= 1_000 -> "%.1fk".format(n / 1000f)
    else -> n.toString()
}

@Composable
private fun InputBar(
    enabled: Boolean,
    streaming: Boolean,
    attachments: List<File>,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onPickPhoto: () -> Unit,
    onTakePhoto: () -> Unit,
    onRemoveAttachment: (File) -> Unit,
    onOpenImage: (File) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var attachMenu by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        if (attachments.isNotEmpty()) {
            Row(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                attachments.forEach { f ->
                    RemovableThumbnail(f, onOpen = { onOpenImage(f) }, onRemove = { onRemoveAttachment(f) })
                }
            }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Box {
                IconButton(onClick = { attachMenu = true }, enabled = enabled && !streaming) {
                    Icon(Icons.Default.AddPhotoAlternate, "Attach image")
                }
                DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Photo library") },
                        leadingIcon = { Icon(Icons.Default.PhotoLibrary, null) },
                        onClick = { attachMenu = false; onPickPhoto() },
                    )
                    DropdownMenuItem(
                        text = { Text("Camera") },
                        leadingIcon = { Icon(Icons.Default.PhotoCamera, null) },
                        onClick = { attachMenu = false; onTakePhoto() },
                    )
                }
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Message") },
                maxLines = 6,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            if (streaming) {
                FilledIconButton(onClick = onStop) { Icon(Icons.Default.Stop, "Stop") }
            } else {
                FilledIconButton(
                    onClick = { onSend(text); text = "" },
                    enabled = enabled && (text.isNotBlank() || attachments.isNotEmpty()),
                ) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
            }
        }
    }
}

@Composable
private fun MessageList(
    messages: List<Message>,
    streaming: Streaming?,
    images: ImageStore,
    onOpenImage: (File) -> Unit,
    modifier: Modifier,
    onRegenerate: () -> Unit,
    onDelete: (Message) -> Unit,
    onEdit: (Message, String) -> Unit,
) {
    val listState = rememberLazyListState()
    // Follow the stream unless the user scrolled up.
    val atBottom by remember {
        androidx.compose.runtime.derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull() ?: return@derivedStateOf true
            // Last item visible and its bottom edge within a little slack of the viewport end.
            last.index >= info.totalItemsCount - 1 && last.offset + last.size <= info.viewportEndOffset + 160
        }
    }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }
    LaunchedEffect(streaming?.content?.length, streaming?.reasoning?.length) {
        if (streaming != null && atBottom && messages.isNotEmpty()) {
            listState.scrollToItem(messages.size - 1, scrollOffset = Int.MAX_VALUE)
        }
    }

    var editing by remember { mutableStateOf<Message?>(null) }

    LazyColumn(state = listState, modifier = modifier, contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)) {
        items(messages, key = { it.id }) { m ->
            val live = streaming?.takeIf { it.messageId == m.id }
            MessageRow(
                message = m,
                liveContent = live?.content,
                liveReasoning = live?.reasoning,
                isLast = m.id == messages.last().id,
                generating = live != null,
                imageFiles = m.imageList.map { images.file(it) },
                onOpenImage = onOpenImage,
                onRegenerate = onRegenerate,
                onDelete = { onDelete(m) },
                onEdit = { editing = m },
            )
        }
    }

    editing?.let { m ->
        var text by remember(m.id) { mutableStateOf(m.content) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Edit and resend") },
            text = { OutlinedTextField(text, { text = it }, maxLines = 10, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { onEdit(m, text); editing = null }) { Text("Send") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageRow(
    message: Message,
    liveContent: String?,
    liveReasoning: String?,
    isLast: Boolean,
    generating: Boolean,
    imageFiles: List<File>,
    onOpenImage: (File) -> Unit,
    onRegenerate: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var menu by remember { mutableStateOf(false) }
    val content = liveContent ?: message.content
    val reasoning = liveReasoning ?: message.reasoning
    val isUser = message.role == "user"

    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Column(
            Modifier
                .then(if (isUser) Modifier.widthIn(max = 320.dp) else Modifier.fillMaxWidth())
                .clip(RoundedCornerShape(16.dp))
                .background(
                    if (isUser) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surface,
                )
                .combinedClickable(onClick = {}, onLongClick = { menu = true })
                .padding(horizontal = if (isUser) 14.dp else 4.dp, vertical = if (isUser) 10.dp else 4.dp),
        ) {
            if (reasoning.isNotBlank()) ReasoningSection(reasoning, generating && content.isEmpty())
            if (imageFiles.isNotEmpty()) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(bottom = if (content.isNotEmpty()) 8.dp else 0.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    imageFiles.forEach { f -> Thumbnail(f, if (imageFiles.size == 1) 200.dp else 120.dp) { onOpenImage(f) } }
                }
            }
            if (isUser) {
                if (content.isNotEmpty()) Text(content, style = MaterialTheme.typography.bodyLarge)
            } else if (content.isNotEmpty()) {
                if (generating) StreamingMarkdown(content) else MarkdownText(content)
            } else if (generating && reasoning.isBlank()) {
                CircularProgressIndicator(Modifier.padding(8.dp).width(20.dp).height(20.dp), strokeWidth = 2.dp)
            }
            if (message.error.isNotBlank()) {
                Text(
                    message.error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (isLast && !generating) TextButton(onClick = onRegenerate) { Text("Retry") }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Copy") }, onClick = { clipboard.setText(AnnotatedString(content)); menu = false })
            if (isUser) DropdownMenuItem(text = { Text("Edit & resend") }, onClick = { onEdit(); menu = false })
            if (!isUser && isLast && !generating) DropdownMenuItem(text = { Text("Regenerate") }, onClick = { onRegenerate(); menu = false })
            if (!generating) DropdownMenuItem(text = { Text("Delete") }, onClick = { onDelete(); menu = false })
        }
    }
}

@Composable
private fun ReasoningSection(reasoning: String, thinking: Boolean) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.padding(bottom = 6.dp)) {
        Row(
            Modifier.clickable { open = !open },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (thinking) CircularProgressIndicator(Modifier.width(14.dp).height(14.dp), strokeWidth = 2.dp)
            Text(
                if (thinking) "Thinking…" else if (open) "Hide reasoning" else "Show reasoning",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AnimatedVisibility(open) {
            Text(
                reasoning,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
