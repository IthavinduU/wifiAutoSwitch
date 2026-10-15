package app.wifiautoswitch.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.wifiautoswitch.AppState
import app.wifiautoswitch.SettingsRepository

enum class Screen { HOME, DEVICES, CHANNELS, SPEED, OUTAGES }

/** Five screens, no navigation library: a single saved state value decides which one is shown. */
@Composable
fun AppRoot(repo: SettingsRepository) {
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    val requested by AppState.requestedScreen.collectAsStateWithLifecycle()
    LaunchedEffect(requested) {
        requested?.let { name ->
            Screen.values().firstOrNull { it.name == name }?.let { screen = it }
            AppState.requestedScreen.value = null
        }
    }
    val back = { screen = Screen.HOME }
    when (screen) {
        Screen.HOME -> HomeScreen(repo, onOpenTool = { screen = it })
        Screen.DEVICES -> DevicesScreen(repo, onBack = back)
        Screen.CHANNELS -> ChannelsScreen(repo, onBack = back)
        Screen.SPEED -> SpeedScreen(repo, onBack = back)
        Screen.OUTAGES -> OutagesScreen(repo, onBack = back)
    }
}

/** Shared layout for the tool screens: back button, title, scrolling content. */
@Composable
fun ToolScaffold(
    title: String,
    onBack: () -> Unit,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(onBack = onBack)
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextButton(onClick = onBack) { Text("‹ Back") }
            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            content()
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Rounded card used by the tool screens. */
@Composable
fun ToolCard(
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
    }
}
