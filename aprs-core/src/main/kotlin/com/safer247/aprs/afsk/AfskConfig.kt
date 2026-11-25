package com.safer247.aprs.afsk

/**
 * Shared Bell 202 AFSK-1200 audio defaults.
 *
 * - Sample rate: 48,000 Hz mono (shared with SSTV PD90 and Direwolf)
 * - PCM format: Signed 16-bit little-endian (PCM16), with FloatArray helpers also provided
 * - TXDELAY: 60 units (60 * 10 ms = 600 ms of 0x7E preamble flags for VOX latch)
 */

// javAX25 - FloatArray
// AudioRecord/AudioTrack - ShortArray
object AfskConfig {
    const val SAMPLE_RATE_HZ: Int = 48_000
    const val TX_DELAY_UNITS: Int = 60 // 1 = 60 ms of 0x7E preamble flags for VOX
    const val TX_TAIL_UNITS: Int = 5 // end flags
    const val TX_MUTE_TAIL_MS: Long = 500L // 500 ms of muting the mic to avoid decoding the TX tail

    internal const val PCM16_MAX: Float = 32767.0f
    internal const val PCM16_INV_SCALE: Float = 1.0f / 32768.0f
}

/**
 * Converts normalized float PCM samples in `[-1.0f, 1.0f]` to signed 16-bit PCM ([ShortArray]).
 * Used during TX to convert the generated float samples by javAX25 into PCM16 for AudioTrack playback.
 */
fun floatToPcm16(samples: FloatArray, count: Int = samples.size): ShortArray {
    require(count in 0..samples.size) {
        "count ($count) must be in 0..${samples.size}."
    }
    val out = ShortArray(count)
    for (i in 0 until count) {
        val clamped = samples[i].coerceIn(-1.0f, 1.0f)
        out[i] = (clamped * AfskConfig.PCM16_MAX).toInt().toShort()
    }
    return out
}

/**
 * Converts signed 16-bit PCM ([ShortArray]) into normalized float samples in `[-1.0f, 1.0f]`.
 *
 * Pass a reusable [dest] buffer in hot RX loops to avoid per-chunk allocations.
 * Used during RX to convert the PCM16 samples from AudioRecord into float samples for javAX25 decoding.
 */
fun pcm16ToFloat(
    samples: ShortArray,
    dest: FloatArray = FloatArray(samples.size),
    count: Int = samples.size
): FloatArray {
    require(count in 0..samples.size) {
        "count ($count) must be in 0..${samples.size}."
    }
    require(dest.size >= count) {
        "dest size (${dest.size}) must be >= count ($count)."
    }
    for (i in 0 until count) {
        dest[i] = samples[i] * AfskConfig.PCM16_INV_SCALE
    }
    return dest
}
