package com.yotogogo

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.ByteOrder

object Transcoder {

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

        val mime       = format.getString(MediaFormat.KEY_MIME)!!
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels   = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val buffered = BufferedOutputStream(output, 65536)
        Mp3Encoder(sampleRate, channels, bitrateKbps).use { encoder ->
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
                if (outIdx >= 0) {
                    val outBuf = codec.getOutputBuffer(outIdx)!!
                    if (info.size > 0) {
                        outBuf.position(info.offset)
                        outBuf.limit(info.offset + info.size)
                        val pcmBytes = ByteArray(info.size)
                        outBuf.get(pcmBytes)
                        val shorts = pcmBytes.toShortArray()
                        encoder.feed(shorts, shorts.size / channels, buffered)
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) eos = true
                }
            }

            encoder.flush(buffered)
        }
        buffered.flush()

        codec.stop()
        codec.release()
        extractor.release()
    }

    private fun ByteArray.toShortArray(): ShortArray {
        val shorts = ShortArray(size / 2)
        val buf = java.nio.ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        buf.get(shorts)
        return shorts
    }
}
