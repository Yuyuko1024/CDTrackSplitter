package net.hearnsoft.cdtracksplitter.model

data class TrackItem(
    val number: Int,
    val title: String,
    val artist: String? = null,
    val startTimeMs: Long,
    val indexTime: String
)