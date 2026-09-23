package net.daniellehmann.localchat

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import net.daniellehmann.localchat.ui.ChatScreen
import net.daniellehmann.localchat.ui.ServerEditScreen
import net.daniellehmann.localchat.ui.ServersScreen
import net.daniellehmann.localchat.ui.theme.LocalChatTheme

/** Tiny hand-rolled navigation: a screen enum plus an optional server id. */
private enum class Screen { Chat, Servers, EditServer }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LocalChatTheme {
                val vm: ChatViewModel = viewModel()
                var screen by rememberSaveable { mutableStateOf(Screen.Chat) }
                var editId by rememberSaveable { mutableStateOf(0L) }

                BackHandler(enabled = screen != Screen.Chat) {
                    screen = if (screen == Screen.EditServer) Screen.Servers else Screen.Chat
                }

                when (screen) {
                    Screen.Chat -> ChatScreen(vm, onOpenServers = { screen = Screen.Servers })
                    Screen.Servers -> ServersScreen(
                        vm,
                        onBack = { screen = Screen.Chat },
                        onEdit = { id -> editId = id; screen = Screen.EditServer },
                    )
                    Screen.EditServer -> ServerEditScreen(
                        vm,
                        serverId = editId,
                        onDone = { screen = Screen.Servers },
                    )
                }
            }
        }
    }
}
