# `mobile/aprs-core` Evaluation Against `APRS_AFSK_INTEGRATION.md` & `PROJECT_SPEC.md`

## Verdict: **Yes — Ready at the Wire Boundary (with a few fixes recommended first)**

`mobile/aprs-core` cleanly implements the exact **raw AX.25 UI frame (no FCS) `ByteArray`** handoff boundary required by `javAX25` in [APRS_AFSK_INTEGRATION.md](../APRS_AFSK_INTEGRATION.md) and covers all 6 wire payload types in [PROJECT_SPEC.md](../PROJECT_SPEC.md). You can plug it directly into `sivantoledo.ax25.Packet` / `Afsk1200Modulator` / `Afsk1200MultiDemodulator` right now for JVM loopback testing, though there are **3 high-priority codec bugs** and **2 `javAX25` integration gotchas** that should be addressed before calling it field-ready.

---

## 1. What Already Matches Both Specs

- **Exact `javAX25` TX/RX boundary:**
  - **TX:** [AprsTx.kt](./aprs-core/src/main/kotlin/com/safer247/aprs/AprsTx.kt) outputs `EncodedTxPacket.ax25Bytes` via [Ax25Frame.kt](./aprs-core/src/main/kotlin/com/safer247/aprs/ax25/Ax25Frame.kt) **without** the 2-byte CRC/FCS — ready to pass directly into `sivantoledo.ax25.Packet(byte[])` ([Packet.java](../radio/javAX25/src/sivantoledo/ax25/Packet.java)).
  - **RX:** [AprsRx.kt](./aprs-core/src/main/kotlin/com/safer247/aprs/AprsRx.kt) `decodeAx25(ByteArray)` directly accepts `packet.bytesWithoutCRC()` from `PacketHandler.handlePacket(byte[])`.
- **SAFER 247 Wire Contract (§4.1–4.3):**
  - Default `S247` source, `APRS` destination, empty digipeater path `[]`, `/>` symbol.
  - All 6 payload tags (`BCN`, `QM`, `IR`, `IR|...|IMG`, `IMG`, `IMGEND` with/without `RPT_ID`) and all 5 incident codes (`FRE`, `FLD`, `VEH`, `MED`, `LND`).
  - Timestamped uncompressed position envelope (`/<HHMMSS>hDDMM.mmN/DDDMM.mmE>...`) + `:ALL      :` no-GPS fallback (correctly rejecting GPS-less `Beacon`).
  - Correctly omits `radiochat-aprs` ACK/REJ (`{msgid`), BLE/KISS transports, Mic-E, and APRS-IS third-party unwrapping per the locked fire-and-forget RF broadcast spec.
- **Android `minSdk 26` compatibility:** 100% pure Kotlin/JVM; `java.time.Instant` / `DateTimeFormatter` are natively available on API 26+, and the last recorded test run in `build/test-results/test/TEST-org.example.com.safer247.aprs.AprsRoundTripTest.xml` passed **8/8** tests.

---

## 2. Issues in `mobile/aprs-core` to Fix

### High Priority (Spec / Correctness Bugs)

1. **Unvalidated body (`MAX_BODY_LENGTH = 175`, newlines, non-ASCII) — [AprsTx.kt](./aprs-core/src/main/kotlin/com/safer247/aprs/AprsTx.kt) (lines 34, 111–116)**
   - `MAX_BODY_LENGTH = 175` is declared on line 34 but **never enforced** (violates `PROJECT_SPEC.md` §4.3).
   - No validation/sanitization for printable ASCII (`0x20–0x7E`) or single-line text (marked `TODO` at line 111). Currently, if a body contains `\n` or `\r`, **`AprsPositionParser.uncompressedRegex` fails to match** (drops the packet to `AprsPacket.Other`), and `:ALL      :` fallback silently **truncates** at the first control or non-ASCII character (`AprsMessageCodec.kt:51`).
2. **Coordinate rounding bug at `.999...` degrees — [AprsPositionParser.kt](./aprs-core/src/main/kotlin/com/safer247/aprs/codec/AprsPositionParser.kt) (lines 183–198)**
   - `decimalToDm(7.99999, true)` computes `minutes = 59.9994`, which `String.format("%05.2f", ...)` rounds to **`"0760.00N"`** instead of `"0800.00N"`.
   - Missing range/finite checks (`latitude in -90.0..90.0`, `longitude in -180.0..180.0`).
3. **AX.25 L2 frame validation & bounds — [Ax25Frame.kt](./aprs-core/src/main/kotlin/com/safer247/aprs/ax25/Ax25Frame.kt) (lines 38–100)**
   - `parse()` does **not** verify that the address loop actually ended on an `isLast` (`LSB == 1`) byte, nor does it check `Control == 0x03` and `PID == 0xF0` (`PROJECT_SPEC.md` §4.1). Non-UI AX.25 frames on RF would be misparsed as APRS.
   - `buildUiFrame()` does **not** enforce `digipeaters.size <= 8`. Passing >8 digipeaters overflows `javAX25`'s `Packet.MAX_FRAME_SIZE` (330 bytes = 2 addr + 8 digis + ctrl/pid + 256 info + 2 FCS).

### Medium Priority (Edge Cases & Strictness)

4. **Broken trailing-FCS fallback — [AprsRx.kt](./aprs-core/src/main/kotlin/com/safer247/aprs/AprsRx.kt) (lines 33–44)**
   - `decodeAx25()` claims to tolerate an optional trailing 2-byte FCS, but if a frame *does* include 2 trailing FCS bytes, the first `classifyAx25(ax25Bytes)` still matches `AprsPositionParser`'s trailing `(.*)` and silently appends the 2 raw CRC bytes to the message body instead of stripping them. This will **not** affect `javAX25` (which always emits `bytesWithoutCRC()`), so either remove the misleading fallback or verify CRC-CCITT before stripping (as `Ax25Fcs.kt` does).
5. **Minor spec-strictness gaps:**
   - [AprsRx.kt](./aprs-core/src/main/kotlin/com/safer247/aprs/AprsRx.kt) (line 142) accepts `APRS-1..15` via `substringBefore('-')` (`PROJECT_SPEC.md` §4.1 specifies `APRS` SSID `0` only).
   - [AprsRx.kt](./aprs-core/src/main/kotlin/com/safer247/aprs/AprsRx.kt) (lines 176–194) accepts any `:ADDRESSEE:` on the no-GPS fallback rather than checking `msg.addressee == "ALL"`.
   - [AprsTx.kt](./aprs-core/src/main/kotlin/com/safer247/aprs/AprsTx.kt) (lines 21–26) does not validate `source` / `destination` / `digipeaters` callsign format (1–6 alphanumeric chars, SSID `0–15`).

### Project Hygiene / Packaging

6. **Package & build cleanup:**
   - Rename `package org.example.com.safer247.aprs...` → `com.safer247.aprs...`, set `group = "com.safer247"` in [build.gradle.kts](./aprs-core/build.gradle.kts), and remove template [Main.kt](./aprs-core/src/main/kotlin/Main.kt).
   - Remove the machine-specific `org.gradle.java.home=C:/Users/User/.jdks/openjdk-17.0.2` from [gradle.properties](./aprs-core/gradle.properties) and configure `kotlin { jvmToolchain(17) }` in [build.gradle.kts](./aprs-core/build.gradle.kts) (matching `PROJECT_SPEC.md` §2.1 & §6.1).
   - Clean up stale KDoc (`@param messageId` in [AprsMessageCodec.kt](./aprs-core/src/main/kotlin/com/safer247/aprs/codec/AprsMessageCodec.kt) line 35) and error messages ("must be 1..24 characters" in [AprsTx.kt](./aprs-core/src/main/kotlin/com/safer247/aprs/AprsTx.kt) lines 154, 163).

---

## 3. Critical Gotchas When Integrating `javAX25`

Inspecting the actual sources in `radio/javAX25` alongside both docs reveals three important traps to avoid when wiring them together:

1. **Follow `PROJECT_SPEC.md` over `APRS_AFSK_INTEGRATION.md` on audio parameters:**
   - [APRS_AFSK_INTEGRATION.md](../APRS_AFSK_INTEGRATION.md) shows example defaults of `44_100` Hz, `txDelayUnits = 30` (300 ms), and `ENCODING_PCM_FLOAT`.
   - [PROJECT_SPEC.md](../PROJECT_SPEC.md) (§3) **locks `48,000 Hz`, mono, `PCM16` (`ENCODING_PCM_16BIT`), and `TXDELAY = 60` (600 ms)** so APRS, SSTV PD90, and Direwolf share one zero-resampling stream.
   - `48_000` Hz is natively supported in [Afsk1200Filters.java](../radio/javAX25/src/sivantoledo/ax25/Afsk1200Filters.java) (line 3). Also note `Afsk1200Modulator`'s default `tx_delay = 20` is 200 ms (despite its comment saying 500 ms), so explicitly call `modulator.setTxDelay(60)`.
2. **`Afsk1200MultiDemodulator` duplicate-suppression bug hurts SAFER 247 Retransmit:**
   - In [Afsk1200MultiDemodulator.java](../radio/javAX25/src/sivantoledo/ax25/Afsk1200MultiDemodulator.java) (lines 48–50), the time window (`sample_count <= last_sample_count + max_sample_delay`) is **commented out**:
     ```java
     if (last!=null && d != last_demod && java.util.Arrays.equals(last, bytes))
     ```
   - Because `PROJECT_SPEC.md` §4.5 supports **Retransmit** (and no-GPS `:ALL      :` frames have no timestamp, making retransmitted bytes identical), a retransmitted packet decoded by the opposite demodulator (`0 dB` vs `6 dB`) will be **silently dropped even minutes later**. Re-enable the `sample_count` dedup window (~150–250 ms) when importing `javAX25`.
3. **Minor `javAX25` porting details:**
   - Import only the 12 pure-Java files listed in [APRS_AFSK_INTEGRATION.md](../APRS_AFSK_INTEGRATION.md) §3 (zero `javax.sound` / desktop dependencies).
   - Always call `modulator.txSamplesBuffer` before `modulator.samples` (otherwise `assert(tx_samples != null)` triggers in [Afsk1200Modulator.java](../radio/javAX25/src/sivantoledo/ax25/Afsk1200Modulator.java) line 171).
   - Strip or gate the `System.out`/`System.err` prints in `Packet.statistics()`, `Afsk1200Demodulator`, and `SoundcardConsumer` to avoid log spam on Android.

---

## 4. Recommended Next Steps

1. Fix the 3 high-priority codec bugs + package/toolchain cleanup in `mobile/aprs-core` and expand [AprsRoundTripTest.kt](./aprs-core/src/test/kotlin/com/safer247/aprs/AprsRoundTripTest.kt) (max 175-char / 256-byte bounds, edge coordinates like `7.99999` / `±90` / `±180`, newlines/non-ASCII rejection, digipeater paths, malformed AX.25 frames).
2. Copy the 12 pure-Java `javAX25` files into `src/main/java/sivantoledo/...` (with the `Afsk1200MultiDemodulator` dedup window re-enabled) and build a thin Kotlin `AfskModem` facade configured for **48,000 Hz** and **`txDelay = 60`**.
3. Add a **Tier 2 JVM audio loopback test** (`AprsTx` → `Packet` → `Afsk1200Modulator` → `float[]` / `PCM16` → `Afsk1200MultiDemodulator` → `AprsRx`) + WAV output to cross-verify against Windows Direwolf before wiring Android `AudioRecord`/`AudioTrack` and the SSTV bracket pairing state machine in `:radio-dsp`.
