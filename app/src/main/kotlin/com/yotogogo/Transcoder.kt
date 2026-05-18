package com.yotogogo

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

object Transcoder {
    private const val TAG = "Transcoder"

    fun toMp3(input: File, output: OutputStream, bitrateKbps: Int = 128) {
        val extractor = MediaExtractor()
        extractor.setDataSource(input.absolutePath)

        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            if (fmt.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                trackIndex = i
                format = fmt
                break
            }
        }
        requireNotNull(format) { "No audio track found in $input" }
        extractor.selectTrack(trackIndex)

        val mime = format.getString(MediaFormat.KEY_MIME)!!

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        try {
            val buffered = BufferedOutputStream(output, 65536)
            var pcmFormat: PcmFormat? = null
            var encoder: Mp3Encoder? = null
            try {
                val info = MediaCodec.BufferInfo()
                var eos = false

                while (!eos) {
                    val inIdx = codec.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        val inBuf = codec.getInputBuffer(inIdx)!!
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        } else {
                            codec.queueInputBuffer(inIdx, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }

                    val outIdx = codec.dequeueOutputBuffer(info, 10_000L)
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val activeFormat = codec.outputFormat.toPcmFormat()
                            pcmFormat = activeFormat
                            Log.d(
                                TAG,
                                "Decoder output format for ${input.name}: " +
                                    "${activeFormat.channels}ch @ ${activeFormat.sampleRate} Hz, " +
                                    "${pcmEncodingName(activeFormat.pcmEncoding)}"
                            )
                        }

                        outIdx >= 0 -> {
                            val outBuf = codec.getOutputBuffer(outIdx)!!
                            if (info.size > 0) {
                                val activeFormat = pcmFormat ?: codec.outputFormat.toPcmFormat().also {
                                    pcmFormat = it
                                    Log.d(
                                        TAG,
                                        "Using decoder output format for ${input.name}: " +
                                            "${it.channels}ch @ ${it.sampleRate} Hz, " +
                                            "${pcmEncodingName(it.pcmEncoding)}"
                                    )
                                }
                                val activeEncoder = encoder ?: Mp3Encoder(
                                    activeFormat.sampleRate,
                                    activeFormat.channels,
                                    bitrateKbps
                                ).also { encoder = it }
                                val shorts = outBuf.toShortArray(info, activeFormat.pcmEncoding)
                                activeEncoder.feed(shorts, shorts.size / activeFormat.channels, buffered)
                            }
                            codec.releaseOutputBuffer(outIdx, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos = true
                        }
                    }
                }

                val activeEncoder = requireNotNull(encoder) { "Decoder produced no PCM output for $input" }
                activeEncoder.flush(buffered)
            } finally {
                encoder?.close()
            }
            buffered.flush()
        } finally {
            runCatching { codec.stop() }
            codec.release()
            extractor.release()
        }
    }

    private data class PcmFormat(
        val sampleRate: Int,
        val channels: Int,
        val pcmEncoding: Int,
    )

    private fun MediaFormat.toPcmFormat(): PcmFormat {
        val sampleRate = getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        require(channels in 1..2) { "Unsupported channel count $channels" }

        val pcmEncoding = if (containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            getInteger(MediaFormat.KEY_PCM_ENCODING)
        } else {
            AudioFormat.ENCODING_PCM_16BIT
        }
        require(
            pcmEncoding == AudioFormat.ENCODING_PCM_16BIT ||
                pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT
        ) {
            "Unsupported PCM encoding ${pcmEncodingName(pcmEncoding)}"
        }

        return PcmFormat(
            sampleRate = sampleRate,
            channels = channels,
            pcmEncoding = pcmEncoding,
        )
    }

    private fun ByteBuffer.toShortArray(info: MediaCodec.BufferInfo, pcmEncoding: Int): ShortArray {
        val data = duplicate()
        data.position(info.offset)
        data.limit(info.offset + info.size)
        data.order(ByteOrder.nativeOrder())

        return when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_16BIT -> {
                val shorts = data.asShortBuffer()
                ShortArray(shorts.remaining()).also { shorts.get(it) }
            }

            AudioFormat.ENCODING_PCM_FLOAT -> {
                val floats = data.asFloatBuffer()
                ShortArray(floats.remaining()) { index ->
                    val sample = floats.get(index).coerceIn(-1f, 1f)
                    (sample * Short.MAX_VALUE).roundToInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                        .toShort()
                }
            }

            else -> error("Unsupported PCM encoding ${pcmEncodingName(pcmEncoding)}")
        }
    }

    private fun pcmEncodingName(pcmEncoding: Int): String =
        when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_16BIT -> "PCM_16BIT"
            AudioFormat.ENCODING_PCM_FLOAT -> "PCM_FLOAT"
            else -> "PCM($pcmEncoding)"
        }
}
