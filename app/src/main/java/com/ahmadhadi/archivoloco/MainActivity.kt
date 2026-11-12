@file:OptIn(ExperimentalMaterial3Api::class)

package com.ahmadhadi.archivoloco

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** Username handed over via "Share → ArchivoLoco" from the Instagram app. */
    private val sharedUsername = mutableStateOf<String?>(null)
    private val loginTick = mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleShare(intent)
        setContent {
            ArchivoTheme {
                MainScreen(
                    sharedUsername = sharedUsername.value,
                    onSharedConsumed = { sharedUsername.value = null },
                    loginTick = loginTick.value,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    override fun onResume() {
        super.onResume()
        loginTick.value++ // re-check the session when returning from the login screen
    }

    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        intent.getStringExtra(Intent.EXTRA_TEXT)?.let { parseUsername(it) }?.let { sharedUsername.value = it }
    }
}

fun parseUsername(raw: String): String? {
    val s = raw.trim()
    Regex("instagram\\.com/([A-Za-z0-9._]+)").find(s)?.let { m ->
        val u = m.groupValues[1]
        if (u !in setOf("p", "reel", "reels", "stories", "explore", "accounts")) return u
    }
    val plain = s.removePrefix("@")
    return if (Regex("^[A-Za-z0-9._]{1,30}$").matches(plain)) plain else null
}

@Composable
private fun MainScreen(sharedUsername: String?, onSharedConsumed: () -> Unit, loginTick: Int) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val keyboard = LocalSoftwareKeyboardController.current
    val job by JobBus.state.collectAsState()

    var loggedIn by remember { mutableStateOf(Session.isLoggedIn()) }
    LaunchedEffect(loginTick) { loggedIn = Session.isLoggedIn() }

    var savedMode by rememberSaveable { mutableStateOf(false) }
    var username by rememberSaveable { mutableStateOf("") }
    var usernameError by remember { mutableStateOf(false) }
    var comments by rememberSaveable { mutableStateOf(false) }
    var replies by rememberSaveable { mutableStateOf(false) }
    var unsave by rememberSaveable { mutableStateOf(false) }
    var redownload by rememberSaveable { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }

    LaunchedEffect(sharedUsername) {
        if (sharedUsername != null) {
            savedMode = false
            username = sharedUsername
            onSharedConsumed()
        }
    }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun start() {
        keyboard?.hide()
        if (!loggedIn) {
            scope.launch { snackbar.showSnackbar("Log in to Instagram first") }
            return
        }
        val user = if (savedMode) null else parseUsername(username)
        if (!savedMode && user == null) {
            usernameError = true
            return
        }
        ctx.startForegroundService(
            Intent(ctx, ArchiveService::class.java)
                .putExtra(ArchiveService.EXTRA_SAVED, savedMode)
                .putExtra(ArchiveService.EXTRA_USERNAME, user)
                .putExtra(ArchiveService.EXTRA_COMMENTS, comments)
                .putExtra(ArchiveService.EXTRA_REPLIES, replies)
                .putExtra(ArchiveService.EXTRA_UNSAVE, unsave)
                .putExtra(ArchiveService.EXTRA_REDOWNLOAD, redownload),
        )
    }

    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("ArchivoLoco", fontWeight = FontWeight.SemiBold) },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 4.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Back up Instagram content you have access to.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
                )
            }

            // ---- Account
            item {
                SectionCard {
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        leadingContent = {
                            IconBadge(
                                Icons.Filled.AccountCircle,
                                if (loggedIn) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceContainerHighest,
                                if (loggedIn) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        headlineContent = { Text("Instagram account", fontWeight = FontWeight.Medium) },
                        supportingContent = { Text(if (loggedIn) "Connected" else "Not connected") },
                        trailingContent = {
                            if (loggedIn) {
                                TextButton(onClick = { confirmLogout = true }) { Text("Log out") }
                            } else {
                                FilledTonalButton(onClick = {
                                    ctx.startActivity(Intent(ctx, LoginActivity::class.java))
                                }) { Text("Log in") }
                            }
                        },
                    )
                }
            }

            // ---- Source
            item {
                SectionCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        SectionTitle("Archive")
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            SegmentedButton(
                                selected = !savedMode,
                                onClick = { savedMode = false },
                                shape = SegmentedButtonDefaults.itemShape(0, 2),
                                icon = { SegmentedButtonDefaults.Icon(!savedMode) { Icon(Icons.Filled.Person, null, Modifier.size(18.dp)) } },
                            ) { Text("Profile") }
                            SegmentedButton(
                                selected = savedMode,
                                onClick = { savedMode = true },
                                shape = SegmentedButtonDefaults.itemShape(1, 2),
                                icon = { SegmentedButtonDefaults.Icon(savedMode) { Icon(Icons.Filled.Star, null, Modifier.size(18.dp)) } },
                            ) { Text("Saved posts") }
                        }
                        AnimatedContent(targetState = savedMode, label = "source") { saved ->
                            if (!saved) {
                                OutlinedTextField(
                                    value = username,
                                    onValueChange = { username = it; usernameError = false },
                                    modifier = Modifier.fillMaxWidth(),
                                    label = { Text("Username or profile link") },
                                    leadingIcon = { Text("@", style = MaterialTheme.typography.titleMedium) },
                                    trailingIcon = {
                                        if (username.isNotEmpty()) {
                                            androidx.compose.material3.IconButton(onClick = { username = "" }) {
                                                Icon(Icons.Filled.Close, "Clear")
                                            }
                                        }
                                    },
                                    isError = usernameError,
                                    supportingText = {
                                        Text(
                                            if (usernameError) "Enter a valid username or profile link"
                                            else "Tip: share a profile from Instagram to ArchivoLoco",
                                        )
                                    },
                                    singleLine = true,
                                    shape = MaterialTheme.shapes.small,
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Uri,
                                        imeAction = ImeAction.Go,
                                        autoCorrectEnabled = false,
                                    ),
                                    keyboardActions = KeyboardActions(onGo = { start() }),
                                )
                            } else {
                                Text(
                                    "Everything in your Saved collection, every page.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            // ---- Options
            item {
                SectionCard {
                    Column(Modifier.padding(vertical = 8.dp)) {
                        SectionTitle("Options", Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        OptionRow("Include comments", "Saves a comments file per post", comments) {
                            comments = it
                            if (!it) replies = false
                        }
                        OptionRow("Include replies", "Much slower on busy posts", replies) {
                            replies = it
                            if (it) comments = true
                        }
                        AnimatedVisibility(savedMode, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                            OptionRow("Unsave after archiving", "Removes each post from Saved once it's backed up", unsave) { unsave = it }
                        }
                        OptionRow("Re-download everything", "Ignore history and fetch again", redownload) { redownload = it }
                    }
                }
            }

            // ---- Action
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (job.running) {
                        OutlinedButton(
                            onClick = { ArchiveService.stop(ctx) },
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                        ) {
                            Icon(Icons.Filled.Close, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Stop", style = MaterialTheme.typography.titleMedium)
                        }
                    } else {
                        Button(
                            onClick = { start() },
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                        ) {
                            Icon(Icons.Filled.PlayArrow, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Start archiving", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Info, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Saved to Download/ArchivoLoco",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // ---- Progress
            if (job.title.isNotEmpty()) {
                item { ProgressCard(job) }
            }

            // ---- Activity log
            item {
                SectionTitle("Activity", Modifier.padding(start = 4.dp, top = 8.dp))
            }
            if (job.lines.isEmpty()) {
                item {
                    Text(
                        "Nothing yet. Progress appears here once you start.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            } else {
                item {
                    SectionCard(color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            job.lines.takeLast(150).asReversed().forEach { line ->
                                Text(
                                    line,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            icon = { Icon(Icons.Filled.AccountCircle, null) },
            title = { Text("Log out?") },
            text = { Text("ArchivoLoco will forget your Instagram session. Your archived files stay on the phone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    Session.logOut { loggedIn = false }
                }) { Text("Log out") }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ProgressCard(job: JobState) {
    val s = job.stats
    SectionCard(color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    job.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    if (job.running) "Running" else "Finished",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            if (job.running) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Row(Modifier.fillMaxWidth()) {
                Stat("Posts", s.posts, Modifier.weight(1f))
                Stat("Images", s.images, Modifier.weight(1f))
                Stat("Videos", s.videos, Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth()) {
                Stat(if (s.unsaved > 0) "Unsaved" else "Comments", if (s.unsaved > 0) s.unsaved else s.comments, Modifier.weight(1f))
                Stat("Skipped", s.skipped, Modifier.weight(1f))
                Stat("Failed", s.failed, Modifier.weight(1f), highlight = s.failed > 0)
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: Int, modifier: Modifier, highlight: Boolean = false) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            value.toString(),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (highlight) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
        )
    }
}

@Composable
private fun SectionCard(
    color: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = color),
    ) { content() }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun OptionRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        modifier = Modifier.fillMaxWidth(),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
    )
}

@Composable
private fun IconBadge(icon: ImageVector, bg: Color, fg: Color) {
    Box(
        Modifier.size(44.dp).background(bg, CircleShape),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = fg, modifier = Modifier.size(26.dp)) }
}
