package com.safer247.aprs.afsk

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.safer247.aprs.AprsTx
import com.safer247.aprs.protocol.EncodedTxPacket
import com.safer247.aprs.protocol.GeoPoint
import com.safer247.aprs.protocol.Payload
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant

/**
 * Coroutine-based Android [AudioTrack] AFSK-1200 transmitter.
 *
 * - 48,000 Hz mono `ENCODING_PCM_16BIT` (with optional `ENCODING_PCM_FLOAT` method)
 * - `txDelayUnits = 60` (600 ms of `0x7E` flags for VOX keying)
 * - Serialized single-consumer half-duplex TX via [Mutex]
 * - Optional half-duplex RX mute callback + VOX settle tail ([AfskConfig.TX_MUTE_TAIL_MS])
 *
 * =========================================================
 * play() ... head reaches end ... postDrainMs ... stop()/release() ... muteTailMs ... unmute RX
 * =========================================================
 */
class AfskAudioTransmitter(
    val sampleRate: Int = AfskConfig.SAMPLE_RATE_HZ,
    txDelayUnits: Int = AfskConfig.TX_DELAY_UNITS,
    txTailUnits: Int = AfskConfig.TX_TAIL_UNITS,
    private val aprsTx: AprsTx = AprsTx(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val audioUsage: Int = AudioAttributes.USAGE_MEDIA, // tells Android to use the media volume stream for playback
    private val audioContentType: Int = AudioAttributes.CONTENT_TYPE_SONIFICATION, // tells Android this is a signal tone, not music or speech
    private val playbackMarginMs: Long = 250L,  // safety margin to ensure AudioTrack playback completes before executing track.stop() since track.play() waits 10-30 ms to start outputting audio
    private val postDrainMs: Long = 40L // additional duration that the
) {
    private val modulator = AfskModulator(
        sampleRate = sampleRate,
        txDelayUnits = txDelayUnits,
        txTailUnits = txTailUnits
    )

    // Creates a Mutex lock to ensure one packet plays at a time.
    private val txMutex = Mutex()

    /** Updates the VOX preamble length in 10 ms units (e.g., `60` = 600 ms). */
    fun setTxDelay(units: Int) = modulator.setTxDelay(units)

    /** Updates the trailer length in 10 ms units (`0` = default 2 trailing `0x7E` flags). */
    fun setTxTail(units: Int) = modulator.setTxTail(units)

    /**
     * Encodes a [Payload] with [aprsTx] and transmits it as 1200-baud AFSK PCM16 audio.
     *
     * @param setRxMuted Optional callback invoked with `true` before TX starts and `false` after
     *   playback + [muteTailMs] VOX settle delay (e.g. `{ receiver.mutedForTx = it }`).
     * @return The [EncodedTxPacket] that was modulated and played.
     */
    suspend fun transmitPayload(
        payload: Payload,
        location: GeoPoint? = null,
        timestamp: Instant = Instant.now(),
        setRxMuted: ((Boolean) -> Unit)? = null, // for the AudioReceiver later on
        muteTailMs: Long = AfskConfig.TX_MUTE_TAIL_MS
    ): EncodedTxPacket {
        val encoded = aprsTx.encodePacket(
            payload = payload,
            location = location,
            timestamp = timestamp
        )
        transmitPacket(
            encoded = encoded,
            setRxMuted = setRxMuted,
            muteTailMs = muteTailMs
        )
        return encoded
    }

    /** Modulates and transmits an [EncodedTxPacket] as mono PCM16 audio via [AudioTrack]. */
    suspend fun transmitPacket(
        encoded: EncodedTxPacket,
        setRxMuted: ((Boolean) -> Unit)? = null,
        muteTailMs: Long = AfskConfig.TX_MUTE_TAIL_MS
    ) {
        transmitAx25(
            ax25WithoutCrc = encoded.ax25Bytes,
            setRxMuted = setRxMuted,
            muteTailMs = muteTailMs
        )
    }

    /**
     * Encodes a plain APRS addressed message (`S247>APRS::ALL      :<message>`) without
     * SAFER 247 tag framing and plays it as mono 48 kHz PCM16 AFSK audio via [AudioTrack].
     *
     * @return The [EncodedTxPacket] that was modulated and played.
     */
    suspend fun transmitPlainMessage(
        message: String,
        addressee: String = AprsTx.FALLBACK_ADDRESSEE,
        setRxMuted: ((Boolean) -> Unit)? = null,
        muteTailMs: Long = AfskConfig.TX_MUTE_TAIL_MS
    ): EncodedTxPacket {
        val encoded = aprsTx.encodePlainMessage(
            message = message,
            addressee = addressee
        )
        transmitPacket(
            encoded = encoded,
            setRxMuted = setRxMuted,
            muteTailMs = muteTailMs
        )
        return encoded
    }

    /**
     * Modulates a raw AX.25 UI frame (without CRC/FCS) and plays it as mono `ENCODING_PCM_16BIT`
     * audio via Android [AudioTrack].
     */
    suspend fun transmitAx25(
        ax25WithoutCrc: ByteArray,
        setRxMuted: ((Boolean) -> Unit)? = null,
        muteTailMs: Long = AfskConfig.TX_MUTE_TAIL_MS
    ) = txMutex.withLock { // waits until no other transmission (previous APRS TX or SSTV TX) is using the speaker
        // Uses Dispatcher.IO so modulation and AudioTrack never touch the UI thread
        withContext(dispatcher) {
            val pcm16 = modulator.modulateToPcm16(ax25WithoutCrc)
            withHalfDuplexMute(setRxMuted, muteTailMs) {
                playPcm16(pcm16)
            }
        }
    }

    /**
     * Modulates a raw AX.25 UI frame (without CRC/FCS) and plays it as mono `ENCODING_PCM_FLOAT`
     * audio via Android [AudioTrack].
     */
    suspend fun transmitAx25Float(
        ax25WithoutCrc: ByteArray,
        setRxMuted: ((Boolean) -> Unit)? = null,
        muteTailMs: Long = AfskConfig.TX_MUTE_TAIL_MS
    ) = txMutex.withLock {
        withContext(dispatcher) {
            val pcmFloat = modulator.modulateToFloat(ax25WithoutCrc)
            withHalfDuplexMute(setRxMuted, muteTailMs) {
                playPcmFloat(pcmFloat)
            }
        }
    }

    /**
     *
     * @param setRxMuted A function to set the receive mute state.
     * Ex: {sstvReceiver.mutedForTx = true} to mute the receiver during transmission and unmute after.
     * @param muteTailMs The duration to keep the receiver muted after transmission.
     * @param block The block of code to execute while the receiver is muted.
     */
    private suspend inline fun withHalfDuplexMute(
        noinline setRxMuted: ((Boolean) -> Unit)?,
        muteTailMs: Long,
        block: () -> Unit
    ) {
        setRxMuted?.invoke(true)
        // block which in this case is playpcm16 or playpcmfloat can be cancelled if user decides to cancel transmission, but we still want to unmute the receiver after the mute tail delay, so we use NonCancellable context for that.
        try {
            block()
        } finally {
            if (setRxMuted != null) {
                // NonCancellable tells the coroutine that this is still active (even if it was cancelled higher up) and that it should still run the code inside (unmute the mic)
                withContext(NonCancellable) {
                    if (muteTailMs > 0L) {
                        // mutes the mic for a short duration after the transmission to allow the VOX to release before the receiver listens again
                        delay(muteTailMs)
                    }
                    setRxMuted.invoke(false)
                }
            }
        }
    }

    /**
     * Plays a sequence of PCM 16-bit samples.
     */
    private suspend fun playPcm16(samples: ShortArray) {
        val minBufBytes = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(2)

        val minSamples = (minBufBytes + 1) / 2
        val bufferSamples = if (samples.size < minSamples) samples.copyOf(minSamples) else samples
        val bufferBytes = bufferSamples.size * 2

        // Build a static AudioTrack for 16-bit PCM playback (reserve hardware resources)
        val track = buildStaticTrack(
            encoding = AudioFormat.ENCODING_PCM_16BIT,
            bufferSizeBytes = bufferBytes
        )
        try {
            // Write the PCM16 samples to the AudioTrack buffer
            val written = track.write(bufferSamples, 0, bufferSamples.size, AudioTrack.WRITE_BLOCKING)
            check(written >= 0) { "AudioTrack.write(ShortArray) failed with error code $written." }

            // Start playback in the speaker and wait for the duration of the audio plus a safety margin to ensure playback completes before stopping the track
            track.play()
            val durationMs = (bufferSamples.size * 1000L) / sampleRate
            awaitPlaybackComplete(track, bufferSamples.size, durationMs)
        } finally {
            android.util.Log.d("AfskTx", "head=${track.playbackHeadPosition} size=${bufferSamples.size}")
            // Stop playback and release the AudioTrack resources, ignoring any exceptions during stop
            runCatching { track.stop() }
            track.release()
        }
    }

    /**
     * Plays a sequence of PCM float samples.
     */
    private suspend fun playPcmFloat(samples: FloatArray) {
        val minBufBytes = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT
        ).coerceAtLeast(4)

        val minSamples = (minBufBytes + 3) / 4
        val bufferSamples = if (samples.size < minSamples) samples.copyOf(minSamples) else samples
        val bufferBytes = bufferSamples.size * 4


        val track = buildStaticTrack(
            encoding = AudioFormat.ENCODING_PCM_FLOAT,
            bufferSizeBytes = bufferBytes
        )
        try {
            val written = track.write(bufferSamples, 0, bufferSamples.size, AudioTrack.WRITE_BLOCKING)
            check(written >= 0) { "AudioTrack.write(FloatArray) failed with error code $written." }
            track.play()
            val durationMs = (bufferSamples.size * 1000L) / sampleRate
            awaitPlaybackComplete(track, bufferSamples.size, durationMs)
        } finally {
            android.util.Log.d("AfskTx", "head=${track.playbackHeadPosition} size=${bufferSamples.size}")
            runCatching { track.stop() }
            track.release()
        }
    }

    private suspend fun awaitPlaybackComplete(track: AudioTrack, totalFrames: Int, durationMs: Long) {
        val timeoutMs = durationMs + playbackMarginMs
        withTimeoutOrNull(timeoutMs) {
            // if the playback head position is less than the total frames, it means the audio is still playing, so we wait for a short duration and check again until it completes or timeout occurs
            while (track.playbackHeadPosition < totalFrames) {
                // pauses the code until audio is done playing or timeout occurs
                delay(10L)
            }
            delay(postDrainMs)
        }
    }

    /***
     * Reserves hardware resources for a static [AudioTrack] with the given [encoding] and [bufferSizeBytes].
     */
    private fun buildStaticTrack(encoding: Int, bufferSizeBytes: Int): AudioTrack {
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(audioUsage)
                    .setContentType(audioContentType)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(encoding)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSizeBytes)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        // In MODE_STATIC, a newly built AudioTrack starts in STATE_NO_STATIC_DATA until write() loads the buffer.
        // AudioTrack states: STATE_UNINITIALIZED = 0 (actual failure), STATE_INITIALIZED = 1 (ready to play), STATE_NO_STATIC_DATA = 2 (waiting for track.write() to write audio data)
        check(track.state != AudioTrack.STATE_UNINITIALIZED) {
            // this code runs if the condition above is false!
            val state = track.state
            track.release()
            "Failed to initialize AudioTrack (state=$state, sampleRate=$sampleRate, encoding=$encoding, bufferBytes=$bufferSizeBytes)."
        }
        return track
    }
}
