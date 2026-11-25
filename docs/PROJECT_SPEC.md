# SAFER 247 — Locked Project Specification Document

> **Project:** SAFER 247: A Radio Frequency-Based Emergency Messaging System  
> **Target Deployment:** Local Disaster Risk Reduction and Management Office (LDRRMO), San Fernando, Bukidnon  
> **Status:** Core Architecture, Wire Protocol & Stack Locked (see [Section 8](#8-deferred--tbd-items) for explicitly deferred items)  
> **Related Documents:** [capstone-doc.md](./capstone-doc.md) · [web/PROJECT_CONTEXT.md](./web/PROJECT_CONTEXT.md)

---

## 1. System Overview & Locked Scope

SAFER 247 enables offline emergency communication over analog VHF/UHF radio frequency (RF) bands when cellular networks and internet connectivity are unavailable. It consists of two applications communicating over a shared half-duplex RF channel (simplex or voice repeater):

| Component | Target Platform | Locked Functional Scope |
| --- | --- | --- |
| **Field Responder Mobile App** (`mobile/`) | Android (`minSdk 26`, `targetSdk 35`, Kotlin + Jetpack Compose + Java DSP) | Offline-first **Messaging Module** (All Messages + Incident Reports tabs; Quick Messages with text/GPS or SSTV PD90 photo; Incident Reports with type, description, GPS, timestamp, optional PD90 photo; search & type/date filters; editable call sign; Resend/Retransmit; background screen-off RF reception) and **Location Beaconing** toggle (5-min interval; RX-during-beacon behavior TBD). **No mobile map view or mobile image enhancement.** |
| **Command Center Web App** (`web/`) | Windows PC (FastAPI + PostgreSQL/PostGIS + React/Vite + Managed Direwolf) | **Messaging Module** (APRS TX+RX for text + auto HQ coordinates + optional Location Pin override; **SSTV PD90 receive-only**; search & filters; Resend/Retransmit; incident report sub-channel; image-to-report Link/Unlink override), **Dashboard** (stats, timelines, distributions), **Map View** (Markers, Responder Tracking, Heatmap), **Reports** (records table, filters, CSV/XLSX/PDF export), and **Image Enhancement** (manual-only trigger; classical pipeline details TBD). |

### Locked Security & Regulatory Posture
- **Plaintext Broadcast Only:** All RF transmissions are unencrypted, open-broadcast packets with **no encryption and no cryptographic hashing/shared-secret reconstruction**. This eliminates the earlier contradiction in `capstone-doc.md`, maximizes APRS payload budget, and maintains regulatory compliance for amateur/open VHF-UHF RF channels.
- **Honest Delivery Model (Fire-and-Forget Broadcast):** No RF delivery acknowledgments (ACKs) are transmitted. Outgoing transmissions track local execution states only (`queued` → `preparing` → `sending` → `audio_complete` / `failed` / `canceled`). Users can **Resend** failed transmissions or **Retransmit** any previously sent message without recomposing.

---

## 2. Verified Dependency & Compatibility Audit

All eight upstream open-source codebases were verified directly against their GitHub repositories.

### 2.1 Field Responder Android App (`mobile/`)

| Upstream Repo | Language & Activity | License | Upstream `minSdk` | Role in SAFER 247 | Verified Integration Boundary |
| --- | --- | --- | --- | --- | --- |
| [`sivantoledo/javAX25`](https://github.com/sivantoledo/javAX25) | Java (2012) | **GPL-2.0-or-later** (file headers; no root `LICENSE`) | Desktop JVM (API 1+ pure Java DSP) | 1200-baud Bell 202 AFSK modem (`Afsk1200Modulator`, `Afsk1200MultiDemodulator`, `Afsk1200Filters`) + AX.25 L2 HDLC/CRC (`Packet.java`, `PacketHandler`) | **Drop** desktop `javax.sound.sampled` & serial classes (`Soundcard`, `SoundcardInterface`, `SerialTransmitController`). Keep **only** `sivantoledo.ax25.*` + pure-Java `SoundcardConsumer`/`SoundcardProducer`. Natively supports **48,000 Hz** (and 44,100 Hz). Contains **no** APRS L3 parser. |
| [`lalmonte/radiochat-aprs`](https://github.com/lalmonte/radiochat-aprs) | **100% Kotlin** (2026) | **GPL-3.0-or-later** | `31` (full BLE app) | Pure-Kotlin AX.25 & APRS Layer 3 codecs (`Ax25Frame.kt`, `Ax25Fcs.kt`, `AprsPacketParser.kt`, `AprsMessageCodec.kt`, `AprsPositionParser.kt`) | Extract **only** the pure-Kotlin `data/aprs/*` parser/codec logic (zero Android/BLE dependencies → runs cleanly on `minSdk 26`). Consumes `javAX25` raw AX.25 `byte[]` directly (already tolerates optional 2-byte trailing FCS). |
| [`olgamiller/SSTVEncoder2`](https://github.com/olgamiller/SSTVEncoder2) | Java (2026) | **Apache-2.0** | `23` (Android 6.0) | **SSTV PD90 Encoder** (`Modes/PD90.java`, `Modes/PD.java`, `Modes/Mode.java`, `Modes/ImageFormats/*`, `IMode`, `IOutput`) + `TextOverlay/` reference | Decouple from its Activity/UI classes. Core encoder uses `android.graphics.Bitmap` + custom `IOutput` streaming 48 kHz mono PCM16 to `AudioTrack`. |
| [`xdsopl/robot36`](https://github.com/xdsopl/robot36) | Java (2026) | **0BSD** | `24` (Android 7.0) | **SSTV PD90 Decoder** (`PaulDon.java`, `Decoder.java`, `BaseMode.java`, pure-Java DSP filters & `PixelBuffer`) | Decouple from its UI & `AudioPlaybackCaptureSource`. Core DSP has **zero Android API dependencies** and accepts 48,000 Hz PCM streams directly. |

**Mobile Compatibility Verdict:**
- **Target Android Baseline:** `minSdk = 26` (Android 8.0 Oreo; hard DSP floor is `24`), `compileSdk = 35`, `targetSdk = 35`, JVM/Kotlin 17.
- **Kotlin + Java Interop:** Fully supported and recommended. Keep adapted Java DSP packages in a dedicated `:radio-dsp` Android library module (Java 8/11 source compatibility) wrapped behind Kotlin Coroutines/`Flow` interfaces; build the app shell, UI, and persistence in modern Kotlin + Jetpack Compose + Room.

### 2.2 Command Center Web Backend (`web/`)

| Upstream Component | Language / Runtime | License | Role in SAFER 247 | Verified Integration Notes |
| --- | --- | --- | --- | --- |
| **Dire Wolf 1.8.1** (`direwolf.exe`) | Native C binary (Windows) | **GPL-2.0-or-later** | Real-time Bell 202 1200-baud AFSK TNC daemon (RX via `ADEVICE0 UDP:7355`, TX via Windows output device, KISS TCP `:8001`) | Bundled binary already tested on Windows; auto-spawned & supervised by the Python radio worker. |
| [`mfncooper/pyham_kiss`](https://github.com/mfncooper/pyham_kiss) | Python `>=3.7` | **MIT** | TCP/IP KISS client (`kiss`) communicating with Direwolf `:8001` | Pure Python, compatible with Python 3.11–3.13. |
| [`mfncooper/pyham_ax25`](https://github.com/mfncooper/pyham_ax25) | Python `>=3.7` | **MIT** | AX.25 Layer 2 frame pack/unpack (`ax25`) | Pure Python, compatible with Python 3.11–3.13. Handles **AX.25 L2 only** — paired with a lightweight SAFER 247 Python APRS L3 codec matching Android's Kotlin parser 1-to-1. |
| [`bucknova/Open-SSTV`](https://github.com/bucknova/Open-SSTV) | Python `>=3.11` | **GPL-3.0-or-later** | Headless streaming **PD90 SSTV decoder** (`open_sstv.core.decoder`, `Mode.PD_90`) | Installed/adapted headless (`--no-deps`, omitting PySide6/GUI). Used for live RX decoding only. |
| [`dnet/pySSTV`](https://github.com/dnet/pySSTV) | Python 3 | **MIT** | **Test-only** PD90 SSTV waveform generator (`pysstv.color.PD90`) | Not used for live Command Center TX (Web is SSTV receive-only); used exclusively for deterministic test-fixture generation and round-trip CI verification. |

### 2.3 Combined Licensing Compliance
All selected licenses (**GPL-2.0-or-later**, **GPL-3.0-or-later**, **Apache-2.0**, **MIT**, **0BSD**) are legally compatible when combined under **GPL-3.0-or-later**. Any distributed build of the Android APK or Command Center radio service incorporating GPLv3/GPLv2+ components must retain upstream copyright/license notices and comply with GPL-3.0 source availability obligations.

---

## 3. Shared Audio & RF Physical Layer Specification

| Parameter | Locked Value | Notes |
| --- | --- | --- |
| **Shared PCM Format** | **48,000 Hz, Mono (1 ch), Signed 16-bit Little-Endian (`PCM16`)** | Supported natively by `sounddevice`, Direwolf, `Open-SSTV`, `pySSTV`, Android `AudioRecord`/`AudioTrack`, `javAX25`, `SSTVEncoder2`, and `robot36` — zero resampling needed. |
| **APRS Modulation** | Bell 202 AFSK, 1200 baud (1200 Hz Mark / 2200 Hz Space), NRZI, HDLC + CRC-CCITT (FCS) | Standard VHF/UHF packet radio. |
| **SSTV Mode** | **PD90** — VIS Code `99` (`0x63`), resolution **320 × 256 px** (128 two-row super-lines), scan duration **89.989 s**, total frame **~90.899 s** (including 910 ms VIS header) | Web receive-only; Mobile TX + RX. |
| **Radio Interfacing** | Acoustic coupling **or** direct audio cable (K1-to-TRRS on Android; USB audio interface / 3.5 mm interface on Windows PC) | Direct audio cable is strongly recommended for reliable VOX. |
| **TX Keying Method** | **VOX** on both Mobile and Command Center (Direwolf hardware PTT via `CM108`/COM RTS remains a config-only option in `direwolf.conf`) | Radios must have **TOT (Time-Out Timer) ≥ 120 s or Disabled** to avoid truncating 91 s PD90 transmissions. |
| **VOX Lead-In Timing** | **APRS:** `TXDELAY` configurable, default **600 ms** (`TXDELAY 60` in Direwolf; matching flag delay in `Afsk1200Modulator`). **SSTV PD90:** prepend **450 ms of 1900 Hz** VOX wake-up tone directly before the standard 300 ms 1900 Hz leader tone. | Ensures radio VOX + repeater carrier fully latch before frame data or the 1200 Hz VIS break begins. |
| **Half-Duplex & Channel Busy** | Strict single-channel half-duplex. RX ingestion is muted during local TX + ~500 ms VOX hang-time settle delay. Mobile checks `javAX25.dcd()`, active `robot36` decode, and mic RMS before starting TX and warns the user if the channel is busy. | Prevents self-decoding/echo and reduces on-air collisions. |

---

## 4. Shared Wire Protocol Contract (Web ↔ Mobile)

Both the Python backend (`pyham_ax25` + SAFER 247 APRS L3 codec) and the Android app (`javAX25` + `radiochat-aprs` + SAFER 247 envelope parser) MUST implement this exact contract.

### 4.1 AX.25 Layer 2 Envelope
- **Frame Type:** AX.25 UI Frame (`Control = 0x03`, `PID = 0xF0` No Layer 3).
- **Destination Address:** `APRS` (SSID `0`) — standard APRS tocall (parsers accept `APRS` only).
- **Source Address (Generic AX.25 Station ID):** Valid 1–6 char uppercase alphanumeric AX.25 call sign + optional SSID `0–15`:
  - Default station ID: `S247` (no SSID required; configurable station call sign + optional SSID `0–15`)
  - *Rationale:* Allows arbitrary human-readable DRRMO display names (e.g., `Responder Alpha`, `Command Center`) in the UI while keeping 100% valid AX.25 headers.
- **Digipeater Path (`via`):** Configurable; default empty `[]` for simplex/FM voice repeaters, or `["WIDE1-1"]` when APRS digipeaters are used.
- **Maximum Info Field Length:** **≤ 256 bytes** (printable ASCII `0x20–0x7E`, single-line, no CR/LF).

### 4.2 APRS Layer 3 Unified Timestamped Position Envelope
Every message with a valid GPS fix (or Command Center configured HQ/Pin coordinates) uses a **single standard APRS Timestamped Position Report**:

```text
/<HHMMSS>h<DDMM.mm><N|S>/<DDDMM.mm><E|W>><SAFER247_TAG><BODY>
```

- **Timestamp:** UTC `HHMMSS` followed by `h` (e.g., `/064215h`).
- **Coordinates:** Standard uncompressed APRS degrees & decimal minutes (`DDMM.mmN/DDDMM.mmE`, ~18.5 m resolution). Example for San Fernando, Bukidnon (`7.9186° N, 125.3286° E`): `0755.12N/12519.72E`.
- **Symbol:** Primary table `/` + code `>` (vehicle/responder) or `-` (HQ); parsers accept any valid APRS symbol char.
- **No-GPS Fallback (Indoors / No Fix):** If GPS is unavailable when sending a Quick Message or Incident Report, fall back to a standard APRS Addressed Message so transmission is never blocked:

```text
:ALL      :<SAFER247_TAG><BODY>
```

### 4.3 SAFER 247 Tag & Payload Grammar

**Field Rules:**
- `<SENDER>`: Human-readable display call sign, **1–24 printable ASCII chars**, excluding `|`, `[`, `]`, `\r`, `\n`.
- `<TYPE>`: 3-character Incident Type code mapped cleanly on both platforms:
  - `FRE` → Fire
  - `FLD` → Flood
  - `VEH` → Vehicular Accident
  - `MED` → Medical
  - `LND` → Landslide
- `<RPT_ID>`: Compact unique report identifier, locked to **max 12 chars** (default generator emits 10-char `RPT-` + 6 uppercase alphanumeric chars, e.g., `RPT-4A9K2M`), generated by the originating mobile device.
- `<BODY>`: Printable ASCII text (`0x20–0x7E`, single-line, no CR/LF), locked to **max 175 characters** across all payload types (worst-case AX.25 `info` field = 27 B GPS prefix + 50 B max `[IR|...|IMG]` tag + 175 B body = **252 bytes ≤ 256 bytes** — no multi-packet fragmentation required).

| # | Message Kind | Exact Comment / Payload Format | Example Complete APRS Info Field |
| --- | --- | --- | --- |
| 1 | **Location Beacon** *(5-min)* | `[BCN\|<SENDER>]` | `/064000h0755.12N/12519.72E>[BCN\|Responder Alpha]` |
| 2 | **Quick Message** *(Text + GPS)* | `[QM\|<SENDER>]<TEXT>` | `/064215h0755.12N/12519.72E>[QM\|Command Center]Copy that, ambulance en route.` |
| 3 | **Incident Report** *(No Photo)* | `[IR\|<SENDER>\|<TYPE>\|<RPT_ID>]<DESC>` | `/064300h0755.12N/12519.72E>[IR\|Responder Alpha\|FRE\|RPT-4A9K2M]Residential fire near public market.` |
| 4 | **Incident Report + Photo** *(Start Bracket)* | `[IR\|<SENDER>\|<TYPE>\|<RPT_ID>\|IMG]<DESC>` | `/064300h0755.12N/12519.72E>[IR\|Responder Alpha\|FRE\|RPT-4A9K2M\|IMG]Residential fire near market.` |
| 5 | **Quick Message Photo** *(Start Bracket)* | `[IMG\|<SENDER>]` *(optional caption after `]`)* | `/064500h0755.12N/12519.72E>[IMG\|Responder Alpha]Bridge flood water level` |
| 6 | **SSTV End Bracket** *(Trailer)* | `[IMGEND\|<SENDER>]` or `[IMGEND\|<SENDER>\|<RPT_ID>]` | `/064635h0755.12N/12519.72E>[IMGEND\|Responder Alpha\|RPT-4A9K2M]` |

### 4.4 SSTV PD90 Visual Overlay & Start/End Bracket Pairing Protocol

#### Transmitting Side (Android Mobile App)
1. **Pre-Transmission Image Processing:** Resize/crop image to **320 × 256 px** and burn a high-contrast text overlay bar containing:
   - Quick Message Photo: `<SENDER> | <LAT>, <LNG> | <HH:MM UTC>`
   - Incident Report Photo: `<SENDER> | <RPT_ID> | <LAT>, <LNG>`
2. **Sequenced Half-Duplex Broadcast (~94 s total):**
   - `t = 0.0 s`: Transmit APRS Start Bracket (`[IR|...|IMG]` or `[IMG|...]`, ~0.8–1.2 s including VOX `TXDELAY`).
   - `t ≈ 1.2–3.2 s`: **2.0 s pause** (allows VOX/repeater turnaround & clean APRS decode completion).
   - `t ≈ 3.2–94.5 s`: Transmit 450 ms 1900 Hz VOX wake-up tone + **PD90 SSTV frame** (~90.9 s).
   - `t ≈ 94.5–95.5 s`: **1.0 s pause**.
   - `t ≈ 95.5–96.5 s`: Transmit APRS End Bracket (`[IMGEND|<SENDER>]` or `[IMGEND|<SENDER>|<RPT_ID>]`, ~0.8 s).

#### Receiving Side (Command Center Web & Co-Responder Android)
1. **Session Tracking State Machine:**
   - When an `[IR|...|IMG]` or `[IMG|...]` start bracket arrives, create a pending image session `{sender, rpt_id?, lat, lng, caption?, received_at}` (and create the `IncidentReport` immediately in `awaiting_image` status for `[IR|...|IMG]`).
   - When the PD90 decoder emits `ImageStarted` (VIS `0x63`), record `vis_started_at`.
   - When PD90 emits `ImageComplete` (320 × 256 PNG), wait up to **12 seconds** (non-blocking display update) for an optional `[IMGEND]` trailer packet.
2. **Unambiguous Auto-Pairing Rule:**
   - **High-Confidence Match (Auto-Link):** Auto-associate the decoded image to the sender / `RPT_ID` if **either**:
     - An `[IMGEND]` trailer arrives within 12 s after `ImageComplete` matching an active start bracket that arrived within 25 s before `vis_started_at`, **OR**
     - Exactly **ONE** pending start bracket existed within 25 s before `vis_started_at` and no conflicting start bracket arrived before `ImageComplete`.
   - **Ambiguous / Missing Brackets (Safe Fallback):** If **zero** matching start brackets exist (e.g., RF static dropped APRS) or **2+ conflicting** sessions overlap without a clean start+end match, **never discard the image and never guess** — store and display it in Messaging as `SSTV-UNKNOWN (Unlinked)` with its burned-in visual overlay visible.
   - **Operator Control (Command Center):** Operators can **manually Link** any unlinked image to an Incident Report, and can **Unlink or Reassign** any auto-paired image (recorded in the audit log).

### 4.5 Local Transmission States & Resend Behavior
- **States:** `queued` → `preparing` → `sending` → `audio_complete` (displayed as *Sent / Audio Complete*) | `failed` | `canceled`.
- **Resend / Retransmit:**
  - Any `failed` message exposes a prominent **Resend** button that re-queues the exact stored payload/image without recomposing.
  - Any `audio_complete` message exposes a secondary **Retransmit** action so users can re-air it if teammates request a repeat.

---

## 5. Command Center Web Application Architecture (`web/`)

### 5.1 Rebuild Boundary
- **Keep:** Existing FastAPI app shell, PostgreSQL/PostGIS database schema ([models.py](file:///c:/Projects/capstone2026/safer247/web/backend/app/db/models.py) & Alembic migrations), and React/Vite operational UI (Dashboard, Map View, Reports + CSV/XLSX/PDF export, Messaging, Settings).
- **Replace from Scratch:** Remove [web/backend/app/radio_lab/](file:///c:/Projects/capstone2026/safer247/web/backend/app/radio_lab) (the old `gen_packets.exe`/`atest.exe` WAV CLI wrappers, `aprslib` parser, base64 fragmenter, and automatic `ImageOps.autocontrast`) and build a clean new radio subsystem (e.g., `web/backend/app/radio/`).

### 5.2 Command Center Audio & Process Architecture

```text
                       ┌───────────────────────────────────────────────────────┐
                       │          Python Radio Worker Process (Windows)        │
                       │                                                       │
Windows Mic (48kHz) ──▶│  Audio Listener Thread (sounddevice, mono PCM16)      │
(Muted during TX)      │     ├──▶ UDP 127.0.0.1:7355 ──▶ Managed direwolf.exe  │
                       │     │         (optional pause while PD90 VIS active)  │
                       │     └──▶ Open-SSTV PD90 Stream Decoder (VIS 0x63)     │
                       │               └──▶ Original 320x256 PNG + Pairing     │
                       │                                                       │
                       │  Managed direwolf.exe (auto-spawned + supervised)     │
                       │     ├── ADEVICE0 UDP:7355 <Windows Output Device>     │
                       │     ├── MODEM 1200 · TXDELAY 60 · KISSPORT 8001       │
                       │     └── KISS TCP :8001 ◀──▶ pyham_kiss + pyham_ax25   │
                       │                             + SAFER 247 APRS L3 Codec │
                       │                                                       │
                       │  PostgreSQL Durable Queue & Persistence Repository    │
                       └──────────────────────────┬────────────────────────────┘
                                                  │
                                          FastAPI + React UI
```

### 5.3 Key Command Center Engineering Rules
1. **Managed Direwolf Supervision:**
   - The Python radio worker generates a runtime `direwolf.conf` from `.env`/Settings (`ADEVICE0 UDP:7355 <output_device>`, `ACHANNELS 1`, `ARATE 48000`, `CHANNEL 0`, `MYCALL S247-0`, `MODEM 1200`, `TXDELAY 60`, `KISSPORT 8001`) and spawns/supervises `direwolf.exe` as a child process with clean shutdown on API exit.
   - An optional config flag (`RADIO_DIREWOLF_EXTERNAL=true`) allows attaching to a manually started Direwolf instance (`host:port`) for field debugging.
2. **Exclusive Audio Ownership:**
   - **Input (Mic):** Owned exclusively by Python's single `sounddevice` capture thread (48 kHz mono PCM16, 20–50 ms chunks). Supports software WAV injection into the exact same pipeline for deterministic automated tests.
   - **Output (Speaker/Radio TX):** Owned exclusively by **Direwolf** (since Command Center is **APRS TX+RX** and **SSTV receive-only**). Zero output contention!
   - **Half-Duplex Mute:** When Python sends an outgoing APRS frame via `pyham_kiss` to Direwolf, it briefly suppresses forwarding mic PCM chunks to Direwolf/SSTV for the estimated frame airtime + VOX tail (~1.5–2.5 s) to avoid acoustic loopback self-reception.
3. **Composer Scope (Web Messaging):**
   - Supports sending APRS Quick Messages (text + auto-embedded Command Center HQ coordinates + optional **Location Pin** coordinate override). No outgoing image upload button (SSTV is receive-only on Web).
4. **Immutable Original Images:**
   - Decoded PD90 PNGs are written once with exclusive creation (`xb`) into original media storage. **No automatic image enhancement runs on receive.**

---

## 6. Field Responder Android Application Architecture (`mobile/`)

### 6.1 Platform & Module Structure
- **Android SDK:** `minSdk = 26` (Android 8.0 Oreo), `compileSdk = 35`, `targetSdk = 35`, Java/Kotlin 17.
- **Gradle Multi-Module Layout:**
  - `:app` — Modern **Kotlin**, **Jetpack Compose (Material 3)**, Navigation Compose, ViewModels (`StateFlow`), **Room** (local SQLite DB for messages, incident reports, local image paths, TX attempts), DataStore (call sign & radio settings), FusedLocationProvider / Android `LocationManager` (offline GPS without Play Services dependency fallback!).
  - `:radio-dsp` — Dedicated DSP & protocol library module containing:
    - Java DSP packages adapted from `sivantoledo/javAX25` (`sivantoledo.ax25.*` only — no `javax.sound`), `olgamiller/SSTVEncoder2` (`Modes/PD90`, `Modes/PD`, `Modes/Mode`, `ImageFormats`, `TextOverlay`), and `xdsopl/robot36` (`Decoder`, `PaulDon`, DSP filters, `PixelBuffer`).
    - Pure-Kotlin AX.25/APRS codecs adapted from `lalmonte/radiochat-aprs` (`Ax25Frame`, `Ax25Fcs`, `AprsPacketParser`, `AprsMessageCodec`, `AprsPositionParser`) + SAFER 247 Tag Encoder/Decoder + Start/End Bracket Pairing State Machine.
    - Clean Kotlin Coroutine/`Flow` facade exposing `startListening()`, `incomingEvents: Flow<RadioEvent>`, `transmitAprs(...)`, `transmitSstvPd90(...)`, and `isChannelBusy(): Boolean`.

### 6.2 Background RF Engine (`RadioForegroundService`)
- Runs as a persistent Android **`ForegroundService`** declared with `android:foregroundServiceType="microphone|location"` (compliant with Android 14+ / API 34+ restrictions; started while the app is in foreground, displaying a persistent "SAFER 247 RF Listening Active" notification).
- Uses a single **`AudioRecord`** stream (**48,000 Hz, mono, `ENCODING_PCM_16BIT`**, `AudioSource.UNPROCESSED` with fallback to `MIC` to avoid Android AGC/noise-suppression distorting AFSK/SSTV tones!) reading ~20–40 ms frames and fanning out in parallel to:
  1. `Afsk1200MultiDemodulator` (`javAX25`) → raw AX.25 `byte[]` → `radiochat-aprs` + SAFER 247 parser.
  2. `Decoder` (`robot36`) → VIS `0x63` PD90 decode → 320 × 256 `Bitmap` saved to app storage + bracket pairing engine.
- **TX Path:** Serialized single-consumer coroutine TX queue using **`AudioTrack`** (48,000 Hz, mono, PCM16). Pauses RX fan-out during playback + ~500 ms VOX settle tail; checks channel-busy state before sending and prompts user if another signal is active.

### 6.3 Mobile Functional Screens (Strictly Per `capstone-doc.md`)
1. **Broadcast Channel Screen:** Segmented tabs (`All` / `Incident Reports`), chronological chat/report feed with burned-in PD90 image previews, search bar, Incident Type filter, Date filter, local TX status badges, and Resend/Retransmit buttons.
2. **Quick Message & Incident Report Composers:**
   - Quick Message: text (auto GPS + timestamp + call sign) and/or Camera/Gallery image (auto-cropped/resized to 320 × 256 with burned-in visual overlay → `[IMG]` + PD90 + `[IMGEND]`).
   - Incident Report form: Incident Type selector (`Fire`, `Flood`, `Vehicular Accident`, `Medical`, `Landslide`), description, optional Camera/Gallery image, auto-attached call sign + GPS + timestamp.
3. **Settings & Beacon Controls:** Editable responder call sign (1–24 chars), VOX `TXDELAY` / SSTV wake-up lead-in tuning, audio source/volume guidance, foreground listener status, and manual **Location Beaconing** On/Off toggle (5-min interval; RX behavior TBD).

---

## 7. Cross-Platform Verification & Interoperability Test Matrix

Before field deployment, every radio layer must pass four progressive verification tiers:

| Tier | Scope | Required Passing Checks |
| --- | --- | --- |
| **1. Protocol Golden Vectors** | Python (`pyham_ax25` + APRS L3) ↔ Kotlin (`radiochat-aprs` + SAFER 247) | Shared JSON test vectors proving byte-identical AX.25 frame & APRS payload encode/decode across all 6 tag types (including GPS & no-GPS fallback, edge coordinates, 24-char sender names, max 256-byte bounds). |
| **2. Software Audio Loopback** | Cross-codec WAV tests (`Direwolf` ↔ `javAX25`; `SSTVEncoder2` ↔ `Open-SSTV`; `pySSTV` ↔ `robot36`) | 48 kHz mono PCM16 WAVs generated by Android `javAX25` decode cleanly in Windows Direwolf (and vice versa); PD90 WAVs generated by Android `SSTVEncoder2` (including 450 ms VOX wake-up tone + visual overlay) decode to 320 × 256 in Python `Open-SSTV`, and `pySSTV` PD90 WAVs decode cleanly in Android `robot36`. |
| **3. Bracket & Pairing State Machine** | Simulated full 96 s sequences | Clean `[IR\|...\|IMG]` → PD90 → `[IMGEND]` auto-links; dropped `[IMGEND]` with unambiguous single pending report still links; dropped both brackets falls back safely to Unlinked; overlapping dual reports refuse to auto-link and fall back to manual operator Link. |
| **4. Bench RF & Hardware VOX Tests** | Physical handheld radios + K1-TRRS / USB audio + Acoustic coupling | Verify VOX trigger reliability (`TXDELAY` 500–700 ms, SSTV wake tone), TOT ≥ 120 s survival across 91 s PD90, audio level calibration (no clipping/over-deviation), Android screen-off `ForegroundService` decode survival, and repeater/simplex range evaluation. |

---

## 8. Deferred / TBD Items

The following items are **explicitly on hold** and must be finalized in a follow-up decision before implementing those specific modules:

1. **Mobile Location Beaconing RX Behavior:** Whether enabling the 5-minute location beacon toggle uses **listen-between-beacons** (keep RX active during the ~298 s idle intervals, muting only ~1.5 s per beacon burst) or **strict exclusive beacon mode** (disabling RX while beaconing is on, as currently drafted in `capstone-doc.md` L28 & L236).
2. **Command Center Image Enhancement Pipeline & Controls:** The **manual-only** trigger requirement (no automatic enhancement on receive) is locked; however, the exact classical processing pipeline (e.g., OpenCV Median/Bilateral denoise → LAB CLAHE → Unsharp Mask vs. Pillow pipeline), operator preset/slider controls, and Chapter 4 quality metric hooks (PSNR/SSIM) are TBD.
3. **Command Center PostGIS Barangay Auto-Resolution & Existing Web Features Spec Alignment:**
   - Automatic `barangay_id` resolution via PostGIS `ST_Contains(barangays.boundary, point)` when incoming GPS incident reports are persisted.
   - Formal capstone specification coverage for the already-implemented **Operator Authentication** (call sign + Argon2 password login) and **Offline Local XYZ Map Tiles** (`/api/maps/tiles/{z}/{x}/{y}.png`).

### To Be Considered
- Consider using compressed APRS position format (Base91) instead of uncompressed `DDMM.mmN/DDDMM.mmE`.
- Consider `<RPT_ID>` collision/duplicate handling across different device senders (e.g., what happens if two devices independently generate the same report ID, and whether uniqueness should be scoped to `(sender, rpt_id)` or include a device/timestamp component).
- Consider how to handle multi-line user text input in Quick Messages and Incident Reports (e.g., auto-flattening `\r\n`/`\n` to spaces in `AprsTx`, escaping `\n` on the wire with matching RX unescaping across mobile & web, or keeping strict single-line printable ASCII rejection).
