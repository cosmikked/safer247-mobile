# Mobile TODO & Open Items

## FOR TOMORROW
- [ ] **Verify Layer 2 (Android `AudioTrack` Playback):**
  - Increase `playbackMarginMs` (e.g., `50L` → `200L`/`250L`) + `TX_TAIL_UNITS` (`0` → `5` = 50 ms / ~8 closing `0x7E` flags) and wait until `track.playbackHeadPosition >= bufferSamples.size` before calling `track.stop()`/`track.release()`.
  - Goal: Eliminate `AudioTrack` cutting off the packet tail; confirm whether live Direwolf decode rate jumps from ~50% to ~95–100%.
- [ ] **Implement Python KISS TCP for web** (`pyham_kiss` + `pyham_ax25` + SAFER 247 APRS L3 codec connected to Direwolf KISS TCP `:8001`).

---

## Location Reference (Uncompressed APRS Check)
- Expected location: `latitude = 7.9186`, `longitude = 125.3286`
- Decoded location in Direwolf: `0755.12N/12519.72E` *(uncompressed APRS `DDMM.mmN/DDDMM.mmE` format; converts back to `7.918667° N, 125.328667° E`, ~18.5 m precision)*

---

## Deferred / TBD Items (from `PROJECT_SPEC.md`)

The following items are **explicitly on hold** and must be finalized in a follow-up decision before implementing those specific modules:

1. **Mobile Location Beaconing RX Behavior:** Whether enabling the 5-minute location beacon toggle uses **listen-between-beacons** (keep RX active during the ~298 s idle intervals, muting only ~1.5 s per beacon burst) or **strict exclusive beacon mode** (disabling RX while beaconing is on, as currently drafted in `capstone-doc.md` L28 & L236).
2. **Command Center Image Enhancement Pipeline & Controls:** The **manual-only** trigger requirement (no automatic enhancement on receive) is locked; however, the exact classical processing pipeline (e.g., OpenCV Median/Bilateral denoise → LAB CLAHE → Unsharp Mask vs. Pillow pipeline), operator preset/slider controls, and Chapter 4 quality metric hooks (PSNR/SSIM) are TBD.
3. **Command Center PostGIS Barangay Auto-Resolution & Existing Web Features Spec Alignment:**
   - Automatic `barangay_id` resolution via PostGIS `ST_Contains(barangays.boundary, point)` when incoming GPS incident reports are persisted.
   - Formal capstone specification coverage for the already-implemented **Operator Authentication** (call sign + Argon2 password login) and **Offline Local XYZ Map Tiles** (`/api/maps/tiles/{z}/{x}/{y}.png`).

---

## To Be Considered (from `PROJECT_SPEC.md`)

- Consider using compressed APRS position format (Base91) instead of uncompressed `DDMM.mmN/DDDMM.mmE`.
- Consider `<RPT_ID>` collision/duplicate handling across different device senders (e.g., what happens if two devices independently generate the same report ID, and whether uniqueness should be scoped to `(sender, rpt_id)` or include a device/timestamp component).
- Consider how to handle multi-line user text input in Quick Messages and Incident Reports (e.g., auto-flattening `\r\n`/`\n` to spaces in `AprsTx`, escaping `\n` on the wire with matching RX unescaping across mobile & web, or keeping strict single-line printable ASCII rejection).
