package com.yotogogo

import java.io.OutputStream

class Mp3Encoder(sampleRate: Int, channels: Int, bitrateKbps: Int = 128) : AutoCloseable {

    private val handle: Long
    val samplesPerPass: Int
    private val chunkSize: Int
    private val channels: Int = channels
    private var leftover = ShortArray(0)

    init {
        handle = nativeCreate(sampleRate, channels, bitrateKbps)
        if (handle == 0L) throw IllegalStateException("shine_initialise failed")
        samplesPerPass = nativeSamplesPerPass(handle)
        chunkSize = samplesPerPass * channels
    }

    fun feed(pcm: ShortArray, out: OutputStream) {
        val input = if (leftover.isEmpty()) pcm else leftover + pcm
        var offset = 0
        while (offset + chunkSize <= input.size) {
            nativeEncode(handle, input, offset, chunkSize)?.let { out.write(it) }
            offset += chunkSize
        }
        leftover = if (offset < input.size) input.copyOfRange(offset, input.size) else ShortArray(0)
    }

    fun flush(out: OutputStream) {
        if (leftover.isNotEmpty()) {
            val padded = ShortArray(chunkSize)
            leftover.copyInto(padded)
            nativeEncode(handle, padded, 0, chunkSize)?.let { out.write(it) }
            leftover = ShortArray(0)
        }
        nativeFlush(handle)?.let { out.write(it) }
    }

    override fun close() = nativeClose(handle)

    private external fun nativeCreate(sampleRate: Int, channels: Int, bitrate: Int): Long
    private external fun nativeEncode(handle: Long, pcm: ShortArray, offset: Int, length: Int): ByteArray?
    private external fun nativeFlush(handle: Long): ByteArray?
    private external fun nativeClose(handle: Long)
    private external fun nativeSamplesPerPass(handle: Long): Int

    companion object {
        init { System.loadLibrary("shine_bridge") }
    }
}
