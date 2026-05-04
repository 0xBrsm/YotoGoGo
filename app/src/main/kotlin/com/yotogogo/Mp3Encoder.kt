package com.yotogogo

import java.io.OutputStream

class Mp3Encoder(sampleRate: Int, channels: Int, bitrateKbps: Int = 128) : AutoCloseable {

    private val handle: Long
    private val samplesPerPass: Int
    private val channels: Int = channels
    private val buf = ArrayDeque<Short>()

    init {
        handle = nativeCreate(sampleRate, channels, bitrateKbps)
        if (handle == 0L) throw IllegalStateException("shine_initialise failed")
        samplesPerPass = nativeSamplesPerPass(handle)
    }

    fun feed(pcm: ShortArray, out: OutputStream) {
        for (s in pcm) buf.addLast(s)
        val chunkSize = samplesPerPass * channels
        while (buf.size >= chunkSize) {
            val chunk = ShortArray(chunkSize) { buf.removeFirst() }
            nativeEncode(handle, chunk)?.let { out.write(it) }
        }
    }

    fun flush(out: OutputStream) {
        val chunkSize = samplesPerPass * channels
        if (buf.isNotEmpty()) {
            val padded = ShortArray(chunkSize)
            buf.forEachIndexed { i, s -> padded[i] = s }
            buf.clear()
            nativeEncode(handle, padded)?.let { out.write(it) }
        }
        nativeFlush(handle)?.let { out.write(it) }
    }

    override fun close() = nativeClose(handle)

    private external fun nativeCreate(sampleRate: Int, channels: Int, bitrate: Int): Long
    private external fun nativeEncode(handle: Long, pcm: ShortArray): ByteArray?
    private external fun nativeFlush(handle: Long): ByteArray?
    private external fun nativeClose(handle: Long)
    private external fun nativeSamplesPerPass(handle: Long): Int

    companion object {
        init { System.loadLibrary("shine_bridge") }
    }
}
