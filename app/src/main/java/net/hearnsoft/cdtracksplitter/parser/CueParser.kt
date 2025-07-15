package net.hearnsoft.cdtracksplitter.parser

import android.content.Context
import android.net.Uri
import net.hearnsoft.cdtracksplitter.model.*
import java.io.BufferedReader
import java.io.InputStreamReader

class CueParser(private val context: Context) {

    fun parse(uri: Uri): CueFile? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
            val reader = BufferedReader(InputStreamReader(inputStream, "UTF-8"))

            val lines = reader.readLines()
            reader.close()

            parseCueContent(lines)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun parseCueContent(lines: List<String>): CueFile {
        var genre: String? = null
        var date: String? = null
        var discId: String? = null
        var comment: String? = null
        var performer: String? = null
        var title: String? = null
        var fileName: String? = null
        var fileType: String? = null
        val tracks = mutableListOf<Track>()

        var currentTrack: MutableTrack? = null

        for (line in lines) {
            val trimmedLine = line.trim()
            if (trimmedLine.isEmpty()) continue

            when {
                trimmedLine.startsWith("REM GENRE") -> {
                    genre = extractQuotedValue(trimmedLine.substring(9).trim())
                }
                trimmedLine.startsWith("REM DATE") -> {
                    date = extractQuotedValue(trimmedLine.substring(8).trim())
                }
                trimmedLine.startsWith("REM DISCID") -> {
                    discId = extractQuotedValue(trimmedLine.substring(10).trim())
                }
                trimmedLine.startsWith("REM COMMENT") -> {
                    comment = extractQuotedValue(trimmedLine.substring(11).trim())
                }
                trimmedLine.startsWith("PERFORMER") && currentTrack == null -> {
                    performer = extractQuotedValue(trimmedLine.substring(9).trim())
                }
                trimmedLine.startsWith("TITLE") && currentTrack == null -> {
                    title = extractQuotedValue(trimmedLine.substring(5).trim())
                }
                trimmedLine.startsWith("FILE") -> {
                    val fileParts = trimmedLine.substring(4).trim().split(" ")
                    fileName = extractQuotedValue(fileParts.dropLast(1).joinToString(" "))
                    fileType = fileParts.last()
                }
                trimmedLine.startsWith("TRACK") -> {
                    // 保存之前的track
                    currentTrack?.let { track ->
                        tracks.add(Track(
                            number = track.number,
                            type = track.type,
                            title = track.title,
                            performer = track.performer,
                            indexes = track.indexes.toList()
                        ))
                    }

                    // 开始新的track
                    val parts = trimmedLine.split(" ")
                    currentTrack = MutableTrack(
                        number = parts[1].toInt(),
                        type = parts[2]
                    )
                }
                trimmedLine.startsWith("TITLE") && currentTrack != null -> {
                    currentTrack.title = extractQuotedValue(trimmedLine.substring(5).trim())
                }
                trimmedLine.startsWith("PERFORMER") && currentTrack != null -> {
                    currentTrack.performer = extractQuotedValue(trimmedLine.substring(9).trim())
                }
                trimmedLine.startsWith("INDEX") && currentTrack != null -> {
                    val parts = trimmedLine.split(" ")
                    val indexNumber = parts[1].toInt()
                    val timePosition = TimePosition.fromString(parts[2])
                    currentTrack.indexes.add(Index(indexNumber, timePosition))
                }
            }
        }

        // 添加最后一个track
        currentTrack?.let { track ->
            tracks.add(Track(
                number = track.number,
                type = track.type,
                title = track.title,
                performer = track.performer,
                indexes = track.indexes.toList()
            ))
        }

        return CueFile(
            genre = genre,
            date = date,
            discId = discId,
            comment = comment,
            performer = performer,
            title = title,
            fileName = fileName,
            fileType = fileType,
            tracks = tracks
        )
    }

    private fun extractQuotedValue(input: String): String {
        return if (input.startsWith("\"") && input.endsWith("\"")) {
            input.substring(1, input.length - 1)
        } else {
            input
        }
    }

    // 用于构建过程中的可变Track
    private data class MutableTrack(
        val number: Int,
        val type: String,
        var title: String? = null,
        var performer: String? = null,
        val indexes: MutableList<Index> = mutableListOf()
    )
}