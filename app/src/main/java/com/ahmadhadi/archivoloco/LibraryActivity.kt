@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.ahmadhadi.archivoloco

import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.widget.VideoView
import android.widget.Toast
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.Coil
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

/** Offline, Instagram-style viewer for everything ArchivoLoco has saved. Reads local files only. */
class LibraryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Coil.setImageLoader(
            ImageLoader.Builder(this)
                .components { add(VideoFrameDecoder.Factory()) }
                .crossfade(true)
                .build(),
        )
        val start = intent.getStringExtra(EXTRA_FOLDER)
        setContent { ArchivoTheme { LibraryApp(start) { finish() } } }
    }

    companion object {
        const val EXTRA_FOLDER = "folder"
    }
}

private sealed interface Screen {
    data object Home : Screen
    data class Profile(val folder: String) : Screen
    data class Feed(val folder: String, val index: Int, val reels: Boolean = false) : Screen
    data class Gallery(val folder: String, val title: String, val index: Int) : Screen
}

@Composable
private fun LibraryApp(startFolder: String?, onExit: () -> Unit) {
    val ctx = LocalContext.current
    var stack by remember {
        mutableStateOf(listOfNotNull<Screen>(Screen.Home, startFolder?.let { Screen.Profile(it) }))
    }
    var profile by remember { mutableStateOf<ArchivedProfile?>(null) }
    var reloadKey by remember { mutableStateOf(0) }
    fun push(s: Screen) { stack = stack + s }
    fun pop() { if (stack.size > 1) stack = stack.dropLast(1) else onExit() }
    BackHandler { pop() }

    // Load (or reload) the profile whenever we enter one.
    val folder = (stack.last() as? Screen.Profile)?.folder
        ?: (stack.last() as? Screen.Feed)?.folder
        ?: (stack.last() as? Screen.Gallery)?.folder
    LaunchedEffect(folder, reloadKey) {
        if (folder != null && (profile?.folder != folder || reloadKey > 0)) {
            if (profile?.folder != folder) profile = null
            profile = Library.load(ctx, folder)
        }
    }

    when (val s = stack.last()) {
        Screen.Home -> HomeScreen(onBack = ::pop, onOpen = { push(Screen.Profile(it)) })
        is Screen.Profile -> ProfileScreen(
            profile = profile?.takeIf { it.folder == s.folder },
            title = s.folder,
            onBack = ::pop,
            onOpenPost = { index, reels -> push(Screen.Feed(s.folder, index, reels)) },
            onOpenGallery = { title -> push(Screen.Gallery(s.folder, title, 0)) },
            onRefresh = { reloadKey++ },
            onDeleted = {
                profile = null
                stack = listOf(Screen.Home)
            },
        )
        is Screen.Feed -> profile?.takeIf { it.folder == s.folder }?.let {
            FeedScreen(it, if (s.reels) it.posts.filter { p -> p.isReel } else it.posts, s.index, onBack = ::pop)
        }
        is Screen.Gallery -> profile?.takeIf { it.folder == s.folder }?.let { p ->
            val media = if (s.title == "Stories") p.stories else p.highlights
            GalleryScreen(s.title, media, onBack = ::pop)
        }
    }
}

// ------------------------------------------------------------------ Home

@Composable
private fun HomeScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    var entries by remember { mutableStateOf<List<LibraryEntry>?>(null) }
    var reload by remember { mutableStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var confirmAll by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(reload) { entries = Library.list(ctx) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Library", fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (!entries.isNullOrEmpty()) {
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Delete all archives…", color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                    onClick = { menu = false; confirmAll = true },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { pad ->
        val list = entries
        when {
            list == null -> Centered(pad) { Text("Loading…") }
            list.isEmpty() -> Centered(pad) {
                Text(
                    "Nothing archived yet.\nArchive a profile and it shows up here.",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> LazyColumn(contentPadding = pad) {
                item {
                    Text(
                        "Archives are private to ArchivoLoco - they don't appear in your gallery. " +
                            "Open a profile and tap Save to phone to export it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                items(list) { e ->
                    ListItem(
                        modifier = Modifier.clickable { onOpen(e.folder) },
                        leadingContent = { Avatar(e.picture, 52.dp) },
                        headlineContent = { Text(if (e.folder == "saved") "Saved posts" else "@${e.folder}", fontWeight = FontWeight.SemiBold) },
                        supportingContent = { Text("${e.postCount} posts · ${e.fileCount} files") },
                    )
                }
            }
        }
    }
    if (confirmAll) {
        ConfirmDelete(
            title = "Delete all archives?",
            text = "Removes every archived profile from ArchivoLoco, any copies you saved to the phone, " +
                "and the download history. This can't be undone.",
            onConfirm = {
                confirmAll = false
                scope.launch {
                    Library.deleteAll(ctx)
                    Toast.makeText(ctx, "All archives deleted", Toast.LENGTH_SHORT).show()
                    reload++
                }
            },
            onDismiss = { confirmAll = false },
        )
    }
}

// ------------------------------------------------------------------ Profile grid

@Composable
private fun ProfileScreen(
    profile: ArchivedProfile?,
    title: String,
    onBack: () -> Unit,
    onOpenPost: (index: Int, reels: Boolean) -> Unit,
    onOpenGallery: (String) -> Unit,
    onRefresh: () -> Unit,
    onDeleted: () -> Unit,
) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(0) }
    val username = profile?.username ?: title
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(username, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = {
                        clipboard.setText(AnnotatedString(username))
                        Toast.makeText(ctx, "Copied @$username", Toast.LENGTH_SHORT).show()
                    }) { Icon(CopyIcon, "Copy username") }
                    if (exporting) {
                        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        }
                    } else {
                        IconButton(enabled = profile != null, onClick = {
                            exporting = true
                            scope.launch {
                                val n = runCatching { Library.exportToPhone(ctx, title) }
                                exporting = false
                                Toast.makeText(
                                    ctx,
                                    n.fold(
                                        { if (it == 0) "Already saved to Download/ArchivoLoco/$title" else "Saved $it files to Download/ArchivoLoco/$title" },
                                        { "Couldn't save: ${it.message}" },
                                    ),
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }) { Icon(SaveToPhoneIcon, "Save to phone (gallery)") }
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Refresh") },
                                leadingIcon = { Icon(Icons.Filled.Refresh, null) },
                                onClick = { menu = false; onRefresh() },
                            )
                            DropdownMenuItem(
                                text = { Text("Delete profile…", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                onClick = { menu = false; confirmDelete = true },
                            )
                        }
                    }
                },
            )
        },
    ) { pad ->
        if (profile == null) {
            Centered(pad) { Text("Loading archive…") }
            return@Scaffold
        }
        val reels = profile.posts.filter { it.isReel }
        val shown = if (tab == 1) reels else profile.posts
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding() + 16.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item(span = { GridItemSpan(3) }) {
                Column {
                    ProfileHeader(profile, onOpenGallery) {
                        clipboard.setText(AnnotatedString(username))
                        Toast.makeText(ctx, "Copied @$username", Toast.LENGTH_SHORT).show()
                    }
                    PrimaryTabRow(selectedTabIndex = tab) {
                        Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Posts · ${profile.posts.size}") })
                        Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Reels · ${reels.size}") })
                    }
                    Spacer(Modifier.height(2.dp))
                }
            }
            if (shown.isEmpty()) {
                item(span = { GridItemSpan(3) }) {
                    Text(
                        if (tab == 1) "No reels in this archive yet." else "No posts in this archive yet.",
                        modifier = Modifier.padding(32.dp),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            itemsIndexed(shown, key = { _, p -> p.id }) { i, post ->
                Box(
                    Modifier.aspectRatio(if (tab == 1) 9f / 16f else 1f)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                        .clickable { onOpenPost(i, tab == 1) },
                ) {
                    val first = post.media.firstOrNull()
                    if (first == null) {
                        Text(
                            "Media\nmissing",
                            modifier = Modifier.align(Alignment.Center),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        AsyncImage(
                            model = first.uri,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    val badge = when {
                        post.media.size > 1 -> CarouselIcon
                        first?.isVideo == true -> Icons.Filled.PlayArrow
                        else -> null
                    }
                    badge?.let {
                        Icon(it, null, tint = Color.White, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp))
                    }
                    if (tab == 1) {
                        post.views?.let { v ->
                            Text(
                                "▶ ${compact(v)}",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
    if (confirmDelete) {
        ConfirmDelete(
            title = "Delete @$username?",
            text = "Removes this profile's archive from ArchivoLoco, any copy saved to Download/ArchivoLoco/$title, " +
                "and its download history. This can't be undone.",
            onConfirm = {
                confirmDelete = false
                scope.launch {
                    Library.delete(ctx, title)
                    Toast.makeText(ctx, "Deleted @$username", Toast.LENGTH_SHORT).show()
                    onDeleted()
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun ProfileHeader(p: ArchivedProfile, onOpenGallery: (String) -> Unit, onCopy: () -> Unit) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(p.picture, 84.dp)
            Spacer(Modifier.width(20.dp))
            Stat(p.posts.size.toString(), "archived", Modifier.weight(1f))
            Stat(p.followers?.let(::compact) ?: "–", "followers", Modifier.weight(1f))
            Stat(p.following?.let(::compact) ?: "–", "following", Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                if (p.fullName.isNotEmpty()) Text(p.fullName, fontWeight = FontWeight.SemiBold)
                Text(
                    "@${p.username}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onCopy) { Icon(CopyIcon, "Copy username", Modifier.size(20.dp)) }
        }
        if (p.bio.isNotEmpty()) Text(p.bio, style = MaterialTheme.typography.bodyMedium)
        p.postCountOnInstagram?.let {
            Text(
                "Archived ${p.posts.size} of $it posts on Instagram",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (p.stories.isNotEmpty() || p.highlights.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (p.stories.isNotEmpty()) AssistChip(onClick = { onOpenGallery("Stories") }, label = { Text("Stories · ${p.stories.size}") })
                if (p.highlights.isNotEmpty()) AssistChip(onClick = { onOpenGallery("Highlights") }, label = { Text("Highlights · ${p.highlights.size}") })
            }
        }
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

// ------------------------------------------------------------------ Feed + post

@Composable
private fun FeedScreen(profile: ArchivedProfile, posts: List<ArchivedPost>, startIndex: Int, onBack: () -> Unit) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = startIndex)
    var commentsFor by remember { mutableStateOf<ArchivedPost?>(null) }
    Scaffold(topBar = { BackBar("Posts", onBack) }) { pad ->
        LazyColumn(state = listState, contentPadding = pad) {
            itemsIndexed(posts, key = { _, p -> p.id }) { _, post ->
                PostCard(profile, post, onComments = { commentsFor = post })
                Spacer(Modifier.height(12.dp))
            }
        }
    }
    commentsFor?.let { post -> CommentsSheet(post) { commentsFor = null } }
}

@Composable
private fun PostCard(profile: ArchivedProfile, post: ArchivedPost, onComments: () -> Unit) {
    Column {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(profile.picture, 34.dp)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(profile.username, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                post.location?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        MediaPager(post.media)
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val stats = listOfNotNull(
                post.likes?.let { "${NumberFormat.getInstance().format(it)} likes" },
                post.views?.let { "${NumberFormat.getInstance().format(it)} views" },
            )
            if (stats.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Favorite, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(6.dp))
                    Text(stats.joinToString(" · "), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (post.caption.isNotEmpty()) ExpandableCaption(profile.username, post.caption)
            if (post.commentCount > 0 || !post.comments.isNullOrEmpty()) {
                Text(
                    if (post.comments == null) "${post.commentCount} comments (not archived)"
                    else "View all ${maxOf(post.commentCount, post.comments.size)} comments",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.clickable(onClick = onComments).padding(vertical = 2.dp),
                )
                post.comments?.take(2)?.forEach { c -> CommentLine(c.username, c.text) }
            }
            Text(
                DateFormat.getDateInstance(DateFormat.LONG).format(Date(post.takenAt * 1000)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ExpandableCaption(username: String, caption: String) {
    var expanded by remember { mutableStateOf(false) }
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(username) }
            append("  ")
            append(caption)
        },
        style = MaterialTheme.typography.bodyMedium,
        maxLines = if (expanded) Int.MAX_VALUE else 3,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.clickable { expanded = !expanded },
    )
}

@Composable
private fun CommentLine(username: String, text: String) {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(username) }
            append("  ")
            append(text)
        },
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun MediaPager(media: List<Media>) {
    if (media.isEmpty()) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "This post's media wasn't downloaded.\nArchive the profile again to retry.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    val first = media.first()
    // Instagram crops feed media between 4:5 portrait and 1.91:1 landscape.
    val ratio = if (first.width > 0 && first.height > 0) {
        (first.width.toFloat() / first.height).coerceIn(0.8f, 1.91f)
    } else 1f
    val pager = rememberPagerState(pageCount = { media.size })
    Box(Modifier.fillMaxWidth().aspectRatio(ratio).background(Color.Black)) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
            MediaView(media[page], active = pager.currentPage == page, fit = ContentScale.Crop)
        }
        if (media.size > 1) {
            Text(
                "${pager.currentPage + 1}/${media.size}",
                color = Color.White,
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.TopEnd).padding(10.dp)
                    .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
            Row(
                Modifier.align(Alignment.BottomCenter).padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                repeat(media.size) { i ->
                    Box(
                        Modifier.size(6.dp).clip(CircleShape)
                            .background(if (i == pager.currentPage) Color.White else Color.White.copy(alpha = 0.4f)),
                    )
                }
            }
        }
    }
}

/** A photo, or a video that shows its first frame and plays (looping) when tapped. */
@Composable
private fun MediaView(m: Media, active: Boolean, fit: ContentScale) {
    var playing by remember(m.uri) { mutableStateOf(false) }
    LaunchedEffect(active) { if (!active) playing = false }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (m.isVideo && playing) {
            AndroidView(
                modifier = Modifier.fillMaxSize().clickable { playing = false },
                factory = { c ->
                    VideoView(c).apply {
                        setVideoURI(m.uri)
                        setOnPreparedListener { it.isLooping = true; start() }
                    }
                },
                onRelease = { it.stopPlayback() },
            )
        } else {
            AsyncImage(
                model = m.uri,
                contentDescription = null,
                contentScale = fit,
                modifier = Modifier.fillMaxSize().then(if (m.isVideo) Modifier.clickable { playing = true } else Modifier),
            )
            if (m.isVideo) {
                Icon(
                    Icons.Filled.PlayArrow, "Play",
                    tint = Color.White,
                    modifier = Modifier.size(64.dp).background(Color.Black.copy(alpha = 0.45f), CircleShape).padding(8.dp),
                )
            }
        }
    }
}

@Composable
private fun CommentsSheet(post: ArchivedPost, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text(
            "Comments",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            textAlign = TextAlign.Center,
        )
        HorizontalDivider()
        val comments = post.comments
        if (comments.isNullOrEmpty()) {
            Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Comments weren't archived for this post", fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Instagram only shows comments to logged-in visitors. Log in, turn on " +
                        "\"Include comments\" and archive this profile again - existing photos aren't re-downloaded.",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                items(comments) { c ->
                    CommentRow(c, indent = 0)
                    c.replies.forEach { r -> CommentRow(r, indent = 1) }
                }
            }
        }
    }
}

@Composable
private fun CommentRow(c: Comment, indent: Int) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp + 44.dp * indent, end = 16.dp, top = 10.dp, bottom = 6.dp)) {
        Box(
            Modifier.size(if (indent == 0) 34.dp else 26.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                c.username.take(1).uppercase(),
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row {
                Text(c.username, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
                if (c.createdAt > 0) {
                    Text(
                        "  " + DateUtils.getRelativeTimeSpanString(c.createdAt * 1000),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(c.text, style = MaterialTheme.typography.bodyMedium)
        }
        if (c.likes > 0) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Favorite, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(compact(c.likes), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

// ------------------------------------------------------------------ Stories / highlights

@Composable
private fun GalleryScreen(title: String, media: List<Media>, onBack: () -> Unit) {
    val pager = rememberPagerState(pageCount = { media.size })
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
            MediaView(media[page], active = pager.currentPage == page, fit = ContentScale.Fit)
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 36.dp, start = 4.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("${pager.currentPage + 1}/${media.size}", color = Color.White)
        }
    }
}

// ------------------------------------------------------------------ Shared bits

@Composable
private fun BackBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
    )
}

@Composable
private fun Avatar(uri: Uri?, size: androidx.compose.ui.unit.Dp) {
    if (uri != null) {
        AsyncImage(
            model = uri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest),
        )
    } else {
        Icon(Icons.Filled.AccountCircle, null, Modifier.size(size), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Centered(pad: PaddingValues, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(pad).padding(24.dp), contentAlignment = Alignment.Center) { content() }
}

private fun compact(n: Int): String = when {
    n >= 1_000_000 -> String.format("%.1fM", n / 1_000_000f).replace(".0M", "M")
    n >= 10_000 -> "${n / 1000}K"
    n >= 1_000 -> String.format("%.1fK", n / 1000f).replace(".0K", "K")
    else -> n.toString()
}

/** Two stacked squares, like Instagram's multi-photo badge (drawn here; no icon pack needed). */
private val CarouselIcon: ImageVector = ImageVector.Builder("carousel", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = SolidColor(Color.White)) {
        moveTo(8f, 3f); horizontalLineTo(21f); verticalLineTo(16f); horizontalLineTo(19f)
        verticalLineTo(5f); horizontalLineTo(8f); close()
        moveTo(3f, 8f); horizontalLineTo(16f); verticalLineTo(21f); horizontalLineTo(3f); close()
    }
}.build()

/** Two overlapping rounded sheets - a "copy" glyph (drawn here; the core icon set has none). */
private val CopyIcon: ImageVector = ImageVector.Builder("copy", 24.dp, 24.dp, 24f, 24f).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(16f, 1f); horizontalLineTo(4f); curveTo(2.9f, 1f, 2f, 1.9f, 2f, 3f); verticalLineTo(17f)
        horizontalLineTo(4f); verticalLineTo(3f); horizontalLineTo(16f); close()
        moveTo(19f, 5f); horizontalLineTo(8f); curveTo(6.9f, 5f, 6f, 5.9f, 6f, 7f); verticalLineTo(21f)
        curveTo(6f, 22.1f, 6.9f, 23f, 8f, 23f); horizontalLineTo(19f); curveTo(20.1f, 23f, 21f, 22.1f, 21f, 21f)
        verticalLineTo(7f); curveTo(21f, 5.9f, 20.1f, 5f, 19f, 5f); close()
        moveTo(19f, 21f); horizontalLineTo(8f); verticalLineTo(7f); horizontalLineTo(19f); close()
    }
}.build()

@Composable
private fun ConfirmDelete(title: String, text: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A phone with a down-arrow: "save to phone". Drawn here; the core icon set has none. */
private val SaveToPhoneIcon: ImageVector = ImageVector.Builder("save_to_phone", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(
        pathData = androidx.compose.ui.graphics.vector.PathParser().parsePathString(
            // phone outline
            "M17,1H7C5.9,1 5,1.9 5,3v18c0,1.1 0.9,2 2,2h10c1.1,0 2,-0.9 2,-2V3C19,1.9 18.1,1 17,1z" +
                "M17,19H7V5h10V19z" +
                // arrow into the screen
                "M11,7h2v5.17l1.59,-1.58L16,12l-4,4l-4,-4l1.41,-1.41L11,12.17z",
        ).toNodes(),
        fill = SolidColor(Color.Black),
    )
}.build()
