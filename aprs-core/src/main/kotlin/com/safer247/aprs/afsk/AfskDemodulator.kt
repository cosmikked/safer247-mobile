package com.safer247.aprs.afsk

import sivantoledo.ax25.Afsk1200MultiDemodulator
import sivantoledo.ax25.PacketHandler
import kotlin.collections.isNotEmpty

/**
 * Platform-agnostic Bell 202 AFSK-1200 demodulator wrapping `javAX25`'s [Afsk1200MultiDemodulator]
 * (parallel 0 dB flat + 6 dB de-emphasized demodulators).
 *
 * Feed incoming mono PCM16 ([feedPcm16]) or float ([feedFloat]) chunks; valid CRC-checked
 * AX.25 frames (without trailing CRC/FCS) are delivered to [onAx25Frame].
 */
class AfskDemodulator(
    val sampleRate: Int = AfskConfig.SAMPLE_RATE_HZ,
    private val onAx25Frame: (ByteArray) -> Unit
) {
    private val demodulator = Afsk1200MultiDemodulator(
        sampleRate,
        PacketHandler { ax25WithoutCrc ->
            if (ax25WithoutCrc != null && ax25WithoutCrc.isNotEmpty()) {
                onAx25Frame(ax25WithoutCrc)
            }
        }
    )

    private var scratchFloatBuf = FloatArray(0)

    /** True when either the 0 dB or 6 dB demodulator detects an active AFSK carrier (DCD). */
    val isChannelBusy: Boolean
        get() = demodulator.dcd()

    /** Recent peak audio input level reported by `javAX25` (`0..100`). */
    val peakLevel: Int
        get() = demodulator.peak()

    /** Feeds mono signed 16-bit PCM samples into the demodulator. */
    fun feedPcm16(samples: ShortArray, count: Int = samples.size) {
        require(count in 0..samples.size) {
            "count ($count) must be in 0..${samples.size}."
        }
        if (count == 0) return
        if (scratchFloatBuf.size < count) {
            scratchFloatBuf = FloatArray(count)
        }
        pcm16ToFloat(samples, scratchFloatBuf, count)
        demodulator.addSamples(scratchFloatBuf, count)
    }

    /** Feeds mono normalized float PCM samples (`[-1.0f, 1.0f]`) into the demodulator. */
    fun feedFloat(samples: FloatArray, count: Int = samples.size) {
        require(count in 0..samples.size) {
            "count ($count) must be in 0..${samples.size}."
        }
        if (count == 0) return
        demodulator.addSamples(samples, count)
    }
}