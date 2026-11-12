package com.ahmadhadi.archivoloco

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Stats(
    val posts: Int = 0,
    val images: Int = 0,
    val videos: Int = 0,
    val comments: Int = 0,
    val skipped: Int = 0,
    val failed: Int = 0,
    val unsaved: Int = 0,
) {
    fun summary() = buildString {
        append("Posts $posts · Images $images · Videos $videos")
        if (comments > 0) append(" · Comments $comments")
        append(" · Skipped $skipped")
        if (failed > 0) append(" · Failed $failed")
        if (unsaved > 0) append(" · Unsaved $unsaved")
    }
}

data class JobState(
    val running: Boolean = false,
    val title: String = "",
    val stats: Stats = Stats(),
    val lines: List<String> = emptyList(),
)

/** Process-wide state shared by the service (writer) and the activity (reader). */
object JobBus {
    private const val MAX_LINES = 500
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val _state = MutableStateFlow(JobState())
    val state: StateFlow<JobState> = _state

    fun log(line: String) {
        val stamped = "${fmt.format(Date())}  $line"
        _state.update { it.copy(lines = (it.lines + stamped).takeLast(MAX_LINES)) }
    }

    fun stats(transform: (Stats) -> Stats) = _state.update { it.copy(stats = transform(it.stats)) }

    fun start(title: String) = _state.update { JobState(running = true, title = title, lines = it.lines) }

    fun finish() = _state.update { it.copy(running = false) }
}
