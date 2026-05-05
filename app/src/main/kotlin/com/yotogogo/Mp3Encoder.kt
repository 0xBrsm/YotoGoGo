package com.yotogogo

import java.io.OutputStream

class Mp3Encoder(sampleRate: Int, channels: Int, bitrateKbps: Int = 128) : AutoCloseable {

    private val handle: Long

    init {
        handle = nativeCreate(sampleRate, channels, bitrateKbps)
        if (handle == 0L) throw IllegalStateException("lame_init_params failed")
    }

    fun feed(pcm: ShortArray, samplesPerChannel: Int, out: OutputStream) {
        nativeEncode(handle, pcm, samplesPerChannel)?.let { out.write(it) }
    }

    fun flush(out: OutputStream) {
        nativeFlush(handle)?.let { out.write(it) }
    }

    override fun close() = nativeClose(handle)

    private external fun nativeCreate(sampleRate: Int, channels: Int, bitrate: Int): Long
    private external fun nativeEncode(handle: Long, pcm: ShortArray, samplesPerChannel: Int): ByteArray?
    private external fun nativeFlush(handle: Long): ByteArray?
    private external fun nativeClose(handle: Long)

    companion object {
        init { System.loadLibrary("lame_bridge") }
    }
}
