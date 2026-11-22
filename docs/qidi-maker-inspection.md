# QIDI Maker v1.1.3 (Android) — inspection notes

Reference notes only. They come from static inspection of `QIDI_Maker_P_qdt.intl.qidimaker.com_V_v1.1.3.apk`, done for interoperability. No QIDI code or assets are copied into Replicator Ops.

## Basics

| Item | Value |
|---|---|
| Package | `qdt.intl.qidimaker.com` |
| Version | 1.1.3 (code 10) |
| Min / target SDK | 32 (Android 12L) / 37 |
| UI | Kotlin + Jetpack Compose, one activity (`com.qidi.maker.MainActivity`), Navigation 3 style "NavKey" screens |
| Obfuscation | R8, with all app classes renamed except `App`, `MainActivity`, `QIDIDatabase` and the push service |
| Package format | **Split APK.** This is the base only; native libraries (P2P engine) live in a separate `config.<abi>` split, so this file alone won't install. |

## Libraries spotted

- **Ktor** (HTTP client) for the cloud REST API
- **HiveMQ MQTT client** (over netty + RxJava) for cloud MQTT, including MQTT-over-WebSocket
- **Peergine** P2P connect library (`com.peergine.connect.android.pgJniConnect`), used for remote printer and camera access through QIDI's relay
- **Media3 / ExoPlayer** with RTSP and HLS support (camera and video guides)
- **CameraX + ML Kit barcode scanning**, for scanning the printer's QR code when binding
- **Room** (local database), **Firebase** (Analytics, Crashlytics, Cloud Messaging push), Lottie, a PDF viewer
- Google advertising-ID permissions (`AD_ID`, `ACCESS_ADSERVICES_*`)

## Permissions

Internet, network state, **local network**, Wi-Fi multicast (for SSDP discovery), camera (QR scan), notifications, wake lock, plus Firebase/Play install-referrer and ad-ID permissions.

## How it talks to printers

There are three paths, picked automatically (a `fallbackToMqtt` flag and `WsStateDecision` / `LeaseDecision` types suggest local first, cloud second):

1. **Local network.** It finds printers by **SSDP** (multicast `239.255.255.250`, search target `urn:qiditech-com:device:3dprinter:1`), then talks to **Moonraker** directly over its `/websocket` JSON-RPC.
   - Moonraker methods seen: `printer.objects.subscribe`, `printer.gcode.script`, `printer.print.pause/resume/cancel`, `server.extensions.request`, `server.files.delete_batch_file`
   - QIDI-specific HTTP endpoint: `/api/qidiclient/files/list`
   - Thumbnails come from `/server/files/gcodes/.thumbs/…`, and plate previews from `/server/files/.temp/plate_…`
2. **Cloud MQTT.** It gets a licence from `auth/v1/mqtt/auth/connect/license`, then exchanges `PrinterMQTTData`, `CommandAck` and `DeviceHeartbeat` messages with the printer through QIDI's broker.
3. **P2P relay (Peergine).** This is used for remote camera and file transfer (`FileBegin/FileChunk/FileEnd`) when neither of the others works.

**Cloud API base:** `https://api.qidimaker.com/`, with three groups of endpoints: `auth/v1/…`, `backend/v1/…` and `community/v1/…`.

## Printer data it reads (Klipper objects)

Extruder temp/target, bed, chamber (`heater_generic chamber`), chamber circulation fan, extruder cooler, toolhead, `print_stats`, `display_status`, filament switch sensor, and QIDI Box (AMS) state: `boxInserted0-3`, `filamentInserted0-3`, box heater/target temp, RFID, auto filament loading.

## Printer actions (cloud REST names, mirrored by local G-code)

- Print: start, start sliced model, pause/resume/cancel (`set/print/control`), reprint
- Temperatures: extruder, heater bed, chamber
- Fans: cooling (part) fan, auxiliary fan, chamber fan, extruder cooler switch
- Motion: X / Y / Z jog, return to safe home
- Case light
- Filament: load, unload, eject (`LOAD_FILAMENT_CONTROL SLOT=`, `EJECT_FILAMENT_CONTROL SLOT=`), RFID read, auto-fill, read on boot, read on insert, drying
- Detection: "noodle" (spaghetti) detection on/off and sensitivity (`SAVE_VARIABLE VARIABLE=enable_noodle_detection`, `noodle_sensitivity_level`), foreign-matter / pre-print model check (`enable_pre_print_model_check`)
- Options: timelapse, silent mode
- Files: list, delete in batches, upload (`UploadPrepare`), print history and details

## App features beyond printer control

Account (email/phone/password/SMS code login), model library and community (like, favourite, follow, comments, reports), sliced-model printing from the cloud, print queue, messages and notification settings, service tickets ("work orders"), beginner guides (PDFs and videos, some on Bilibili/YouTube), app update check.

## What this means for Replicator Ops

- The Q2C runs Klipper + Moonraker, so the **local path is all we need**: Moonraker's WebSocket JSON-RPC plus its HTTP file and webcam endpoints. No QIDI account, cloud, MQTT or P2P.
- Remote access works through **Tailscale** instead of QIDI's relay.
- Later additions we could copy from this list: SSDP discovery with QIDI's search target, QIDI Box filament slots, noodle-detection toggles, case light, timelapse.
- Left out on purpose: account/community/cloud features, analytics and ads.
