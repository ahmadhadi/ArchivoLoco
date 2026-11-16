@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.ahmadhadi.archivoloco

import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.widget.VideoView
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
    data class Feed(val folder: String, val index: Int) : Screen
    data class Gallery(val folder: String, val title: String, val index: Int) : Screen
}

@Composable
private fun LibraryApp(startFolder: String?, onExit: () -> Unit) {
    val ctx = LocalContext.current
    var stack by remember {
        mutableStateOf(listOfNotNull<Screen>(Screen.Home, startFolder?.let { Screen.Profile(it) }))
    }
    var profile by remember { mutableStateOf<ArchivedProfile?>(null) }
    fun push(s: Screen) { stack = stack + s }
    fun pop() { if (stack.size > 1) stack = stack.dropLast(1) else onExit() }
    BackHandler { pop() }

    // Load (or reload) the profile whenever we enter one.
    val folder = (stack.last() as? Screen.Profile)?.folder
        ?: (stack.last() as? Screen.Feed)?.folder
        ?: (stack.last() as? Screen.Gallery)?.folder
    LaunchedEffect(folder) {
        if (folder != null && profile?.folder != folder) {
            profile = null
            profile = Library.load(ctx, folder)
        }
    }

    when (val s = stack.last()) {
        Screen.Home -> HomeScreen(onBack = ::pop, onOpen = { push(Screen.Profile(it)) })
        is Screen.Profile -> ProfileScreen(
            profile = profile?.takeIf { it.folder == s.folder },
            title = s.folder,
            onBack = ::pop,
            onOpenPost = { push(Screen.Feed(s.folder, it)) },
            onOpenGallery = { title -> push(Screen.Gallery(s.folder, title, 0)) },
        )
        is Screen.Feed -> profile?.takeIf { it.folder == s.folder }?.let {
            FeedScreen(it, s.index, onBack = ::pop)
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
    LaunchedEffect(Unit) { entries = Library.list(ctx) }
    Scaffold(topBar = { BackBar("Library", onBack) }) { pad ->
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
}

// ------------------------------------------------------------------ Profile grid

@Composable
private fun ProfileScreen(
    profile: ArchivedProfile?,
    title: String,
    onBack: () -> Unit,
    onOpenPost: (Int) -> Unit,
    onOpenGallery: (String) -> Unit,
) {
    Scaffold(topBar = { BackBar(profile?.username ?: title, onBack) }) { pad ->
        if (profile == null) {
            Centered(pad) { Text("Loading archive…") }
            return@Scaffold
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding() + 16.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item(span = { GridItemSpan(3) }) { ProfileHeader(profile, onOpenGallery) }
            if (profile.posts.isEmpty()) {
                item(span = { GridItemSpan(3) }) {
                    Text(
                        "No posts in this archive yet.",
                        modifier = Modifier.padding(32.dp),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            itemsIndexed(profile.posts, key = { _, p -> p.id }) { i, post ->
                Box(Modifier.aspectRatio(1f).clickable { onOpenPost(i) }) {
                    AsyncImage(
                        model = post.media.first().uri,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerHighest),
                    )
                    val badge = when {
                        post.media.size > 1 -> CarouselIcon
                        post.media.first().isVideo -> Icons.Filled.PlayArrow
                        else -> null
                    }
                    badge?.let {
                        Icon(it, null, tint = Color.White, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileHeader(p: ArchivedProfile, onOpenGallery: (String) -> Unit) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(p.picture, 84.dp)
            Spacer(Modifier.width(20.dp))
            Stat(p.posts.size.toString(), "archived", Modifier.weight(1f))
            Stat(p.followers?.let(::compact) ?: "–", "followers", Modifier.weight(1f))
            Stat(p.following?.let(::compact) ?: "–", "following", Modifier.weight(1f))
        }
        if (p.fullName.isNotEmpty()) Text(p.fullName, fontWeight = FontWeight.SemiBold)
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
    HorizontalDivider()
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
private fun FeedScreen(profile: ArchivedProfile, startIndex: Int, onBack: () -> Unit) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = startIndex)
    var commentsFor by remember { mutableStateOf<ArchivedPost?>(null) }
    Scaffold(topBar = { BackBar("Posts", onBack) }) { pad ->
        LazyColumn(state = listState, contentPadding = pad) {
            itemsIndexed(profile.posts, key = { _, p -> p.id }) { _, post ->
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
