package net.hearnsoft.cdtracksplitter.model

data class CueFile(
    val genre: String? = null,
    val date: String? = null,
    val discId: String? = null,
    val comment: String? = null,
    val performer: String? = null,
    val title: String? = null,
    val fileName: String? = null,
    val fileType: String? = null,
    val tracks: List<Track> = emptyList()
)

data class Track(
    val number: Int,
    val type: String,
    val title: String? = null,
    val performer: String? = null,
    val indexes: List<Index> = emptyList()
) {
    // 获取主要索引（通常是INDEX 01）
    val mainIndex: Index?
        get() = indexes.find { it.number == 1 } ?: indexes.firstOrNull()

    // 获取预间隙索引（INDEX 00）
    val pregapIndex: Index?
        get() = indexes.find { it.number == 0 }
}

data class Index(
    val number: Int,
    val time: TimePosition
)

data class TimePosition(
    val minutes: Int,
    val seconds: Int,
    val frames: Int
) {
    // 转换为毫秒
    fun toMilliseconds(): Long {
        return (minutes * 60 + seconds) * 1000L + (frames * 1000L / 75)
    }

    // 转换为秒（用于FFmpeg）
    fun toSeconds(): Double {
        return minutes * 60.0 + seconds + frames / 75.0
    }

    override fun toString(): String {
        return String.format("%02d:%02d:%02d", minutes, seconds, frames)
    }

    companion object {
        fun fromString(timeString: String): TimePosition {
            val parts = timeString.split(":")
            return TimePosition(
                minutes = parts[0].toInt(),
                seconds = parts[1].toInt(),
                frames = parts[2].toInt()
            )
        }
    }
}