package com.safer247.aprs

import com.safer247.aprs.afsk.AfskConfig
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes mono signed 16-bit little-endian PCM ([ShortArray]) into a standard RIFF/WAVE file
 * (44-byte header + raw PCM16 samples) for Direwolf (`atest.exe`) cross-verification.
 */
fun writePcm16Wav(
    file: File,
    samples: ShortArray,
    sampleRate: Int = AfskConfig.SAMPLE_RATE_HZ
) {
    val dataBytes = samples.size * 2
    val buffer = ByteBuffer
        .allocate(44 + dataBytes)
        .order(ByteOrder.LITTLE_ENDIAN)

    // 44-byte RIFF/WAVE PCM header
    buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
    buffer.putInt(36 + dataBytes)
    buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
    buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
    buffer.putInt(16)                // PCM fmt chunk size
    buffer.putShort(1)               // audio format = 1 (uncompressed PCM)
    buffer.putShort(1)               // channels = 1 (mono)
    buffer.putInt(sampleRate)        // sample rate (48,000 Hz)
    buffer.putInt(sampleRate * 2)    // byte rate = sampleRate * channels * 2
    buffer.putShort(2)               // block align = channels * 2
    buffer.putShort(16)              // bits per sample = 16
    buffer.put("data".toByteArray(Charsets.US_ASCII))
    buffer.putInt(dataBytes)

    // Raw PCM16 audio samples
    for (s in samples) {
        buffer.putShort(s)
    }

    file.parentFile?.mkdirs()
    file.writeBytes(buffer.array())
}
