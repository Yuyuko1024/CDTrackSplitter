package net.hearnsoft.cdtracksplitter.metadata

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.extractor.metadata.flac.PictureFrame
import androidx.media3.extractor.metadata.id3.ApicFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import net.hearnsoft.cdtracksplitter.model.CueFile
import net.hearnsoft.cdtracksplitter.model.Track
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer

class MetadataUpdater(private val context: Context) {

    companion object {
        private const val TAG = "MetadataUpdater"
    }

    /**
     * 更新FLAC文件的metadata和封面
     */
    fun updateFlacMetadata(
        audioFileUri: Uri,
        track: Track,
        cueFile: CueFile,
        coverImageUri: Uri?
    ): Boolean {
        return try {
            // 复制文件到临时位置进行处理
            val tempFile = createTempFile(audioFileUri)

            val success = updateFlacFile(tempFile, track, cueFile, coverImageUri)

            if (success) {
                // 将更新后的文件复制回原位置
                copyTempFileBack(tempFile, audioFileUri)
            }

            // 清理临时文件
            tempFile.delete()

            success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update metadata for track ${track.number}", e)
            false
        }
    }

    private fun updateFlacFile(
        file: File,
        track: Track,
        cueFile: CueFile,
        coverImageUri: Uri?
    ): Boolean {
        return try {
            val flacMetadataProcessor = FlacMetadataProcessor(file)

            // 构建新的metadata
            val metadataMap = buildMetadataMap(track, cueFile)

            // 更新VORBIS_COMMENT块
            flacMetadataProcessor.updateVorbisComment(metadataMap)

            // 更新PICTURE块（封面）
            coverImageUri?.let { uri ->
                val coverData = readCoverImageData(uri)
                if (coverData != null) {
                    flacMetadataProcessor.updatePicture(coverData)
                }
            }

            // 保存更改
            flacMetadataProcessor.save()

            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process FLAC file", e)
            false
        }
    }

    private fun buildMetadataMap(track: Track, cueFile: CueFile): Map<String, String> {
        val metadata = mutableMapOf<String, String>()

        // 只添加CUE文件中存在的metadata
        track.title?.let { metadata["TITLE"] = it }
        track.performer?.let { metadata["ARTIST"] = it }
        cueFile.title?.let { metadata["ALBUM"] = it }
        cueFile.performer?.let { metadata["ALBUMARTIST"] = it }
        cueFile.date?.let { metadata["DATE"] = it }
        cueFile.genre?.let { metadata["GENRE"] = it }

        // 轨道号信息总是添加
        metadata["TRACKNUMBER"] = track.number.toString()
        metadata["TRACKTOTAL"] = cueFile.tracks.size.toString()

        return metadata
    }

    private fun readCoverImageData(uri: Uri): ByteArray? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                inputStream.readBytes()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read cover image", e)
            null
        }
    }

    private fun createTempFile(uri: Uri): File {
        val tempFile = File.createTempFile("metadata_update", ".flac", context.cacheDir)

        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(tempFile).use { output ->
                input.copyTo(output)
            }
        }

        return tempFile
    }

    private fun copyTempFileBack(tempFile: File, targetUri: Uri) {
        context.contentResolver.openOutputStream(targetUri)?.use { output ->
            FileInputStream(tempFile).use { input ->
                input.copyTo(output)
            }
        }
    }
}

/**
 * FLAC文件metadata处理器
 */
private class FlacMetadataProcessor(private val file: File) {

    companion object {
        private const val TAG = "FlacMetadataProcessor"
    }

    private val FLAC_SIGNATURE = "fLaC".toByteArray()
    private val VORBIS_COMMENT_TYPE = 4
    private val PICTURE_TYPE = 6

    private data class MetadataBlock(
        val type: Int,
        val isLast: Boolean,
        val size: Int,
        val data: ByteArray,
        val position: Long
    )

    private val metadataBlocks = mutableListOf<MetadataBlock>()
    private var streamInfoBlock: MetadataBlock? = null

    init {
        parseFlacFile()
    }

    private fun parseFlacFile() {
        RandomAccessFile(file, "r").use { raf ->
            // 验证FLAC签名
            val signature = ByteArray(4)
            raf.read(signature)
            if (!signature.contentEquals(FLAC_SIGNATURE)) {
                throw IllegalArgumentException("Not a valid FLAC file")
            }

            // 读取metadata块
            var isLast = false
            while (!isLast) {
                val position = raf.filePointer
                val header = raf.readInt()

                isLast = (header and 0x80000000.toInt()) != 0
                val blockType = (header shr 24) and 0x7F
                val blockSize = header and 0x00FFFFFF

                val blockData = ByteArray(blockSize)
                raf.read(blockData)

                val block = MetadataBlock(blockType, isLast, blockSize, blockData, position)

                when (blockType) {
                    0 -> streamInfoBlock = block // STREAMINFO
                    VORBIS_COMMENT_TYPE, PICTURE_TYPE -> metadataBlocks.add(block)
                }
            }
        }
    }

    fun updateVorbisComment(newMetadata: Map<String, String>) {
        // 查找现有的VORBIS_COMMENT块
        val existingCommentIndex = metadataBlocks.indexOfFirst { it.type == VORBIS_COMMENT_TYPE }

        if (existingCommentIndex >= 0) {
            // 解析现有注释
            val existingComments = parseVorbisComment(metadataBlocks[existingCommentIndex].data)

            // 只更新CUE文件中提供的字段，保留其他字段
            val updatedComments = existingComments.toMutableMap()
            newMetadata.forEach { (key, value) ->
                updatedComments[key.uppercase()] = value
            }

            // 构建新的VORBIS_COMMENT块
            val newCommentData = buildVorbisComment(updatedComments)
            val newBlock = MetadataBlock(
                VORBIS_COMMENT_TYPE,
                metadataBlocks[existingCommentIndex].isLast,
                newCommentData.size,
                newCommentData,
                metadataBlocks[existingCommentIndex].position
            )

            metadataBlocks[existingCommentIndex] = newBlock
        } else {
            // 创建新的VORBIS_COMMENT块
            val commentData = buildVorbisComment(newMetadata)
            val newBlock = MetadataBlock(
                VORBIS_COMMENT_TYPE,
                false,
                commentData.size,
                commentData,
                -1
            )
            metadataBlocks.add(newBlock)
        }
    }

    fun updatePicture(imageData: ByteArray) {
        // 移除现有的PICTURE块
        metadataBlocks.removeAll { it.type == PICTURE_TYPE }

        // 创建新的PICTURE块
        val pictureData = buildPictureBlock(imageData)
        val pictureBlock = MetadataBlock(
            PICTURE_TYPE,
            false,
            pictureData.size,
            pictureData,
            -1
        )

        metadataBlocks.add(pictureBlock)
    }

    private fun parseVorbisComment(data: ByteArray): Map<String, String> {
        val comments = mutableMapOf<String, String>()
        val buffer = ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN)

        try {
            // 跳过vendor string
            val vendorLength = buffer.int
            buffer.position(buffer.position() + vendorLength)

            // 读取comment数量
            val commentCount = buffer.int

            // 读取每个comment
            repeat(commentCount) {
                val commentLength = buffer.int
                val commentBytes = ByteArray(commentLength)
                buffer.get(commentBytes)

                val comment = String(commentBytes, Charsets.UTF_8)
                val equalIndex = comment.indexOf('=')
                if (equalIndex > 0) {
                    val key = comment.substring(0, equalIndex).uppercase()
                    val value = comment.substring(equalIndex + 1)
                    comments[key] = value
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse existing VORBIS_COMMENT", e)
        }

        return comments
    }

    private fun buildVorbisComment(comments: Map<String, String>): ByteArray {
        val vendor = "CDTrackSplitter"
        val vendorBytes = vendor.toByteArray(Charsets.UTF_8)

        val commentStrings = comments.map { (key, value) ->
            "$key=$value".toByteArray(Charsets.UTF_8)
        }

        val totalSize = 4 + vendorBytes.size + 4 + commentStrings.sumOf { 4 + it.size }
        val buffer = ByteBuffer.allocate(totalSize).order(java.nio.ByteOrder.LITTLE_ENDIAN)

        // Vendor string
        buffer.putInt(vendorBytes.size)
        buffer.put(vendorBytes)

        // Comment count
        buffer.putInt(commentStrings.size)

        // Comments
        commentStrings.forEach { commentBytes ->
            buffer.putInt(commentBytes.size)
            buffer.put(commentBytes)
        }

        return buffer.array()
    }

    private fun buildPictureBlock(imageData: ByteArray): ByteArray {
        val mimeType = "image/jpeg"
        val description = ""

        val mimeTypeBytes = mimeType.toByteArray(Charsets.UTF_8)
        val descriptionBytes = description.toByteArray(Charsets.UTF_8)

        val totalSize = 32 + mimeTypeBytes.size + descriptionBytes.size + imageData.size
        val buffer = ByteBuffer.allocate(totalSize).order(java.nio.ByteOrder.BIG_ENDIAN)

        // Picture type (3 = Cover (front))
        buffer.putInt(3)

        // MIME type
        buffer.putInt(mimeTypeBytes.size)
        buffer.put(mimeTypeBytes)

        // Description
        buffer.putInt(descriptionBytes.size)
        buffer.put(descriptionBytes)

        // Width, Height, Color depth, Number of colors (all 0 for now)
        buffer.putInt(0) // Width
        buffer.putInt(0) // Height
        buffer.putInt(0) // Color depth
        buffer.putInt(0) // Number of colors

        // Picture data
        buffer.putInt(imageData.size)
        buffer.put(imageData)

        return buffer.array()
    }

    fun save() {
        val tempFile = File(file.parentFile, "${file.name}.tmp")

        RandomAccessFile(file, "r").use { input ->
            RandomAccessFile(tempFile, "rw").use { output ->
                // 写入FLAC签名
                output.write(FLAC_SIGNATURE)

                // 写入STREAMINFO块
                streamInfoBlock?.let { block ->
                    writeMetadataBlockHeader(output, 0, false, block.size)
                    output.write(block.data)
                }

                // 写入其他metadata块
                metadataBlocks.forEachIndexed { index, block ->
                    val isLast = index == metadataBlocks.size - 1
                    writeMetadataBlockHeader(output, block.type, isLast, block.size)
                    output.write(block.data)
                }

                // 跳过原文件的所有metadata块，直接复制音频数据
                input.seek(findAudioDataStart())
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                }
            }
        }

        // 替换原文件
        file.delete()
        tempFile.renameTo(file)
    }

    private fun writeMetadataBlockHeader(output: RandomAccessFile, type: Int, isLast: Boolean, size: Int) {
        var header = (type shl 24) or (size and 0x00FFFFFF)
        if (isLast) {
            header = header or 0x80000000.toInt()
        }
        output.writeInt(header)
    }

    private fun findAudioDataStart(): Long {
        var position = 4L // Skip FLAC signature

        RandomAccessFile(file, "r").use { raf ->
            raf.seek(position)

            var isLast = false
            while (!isLast) {
                val header = raf.readInt()
                isLast = (header and 0x80000000.toInt()) != 0
                val blockSize = header and 0x00FFFFFF

                position = raf.filePointer + blockSize
                raf.seek(position)
            }
        }

        return position
    }
}