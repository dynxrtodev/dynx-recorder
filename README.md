# Screen Recorder (Android, Kotlin)

Aplikasi perekam layar Android dengan dukungan **audio internal + mikrofon sekaligus**,
kualitas sampai 1440p, pause/resume, dan tombol kontrol mengambang.

## Fitur

| Fitur | Keterangan |
|---|---|
| Resolusi | 480p / 720p / 1080p / 1440p / native layar |
| Frame rate | 24 / 30 / 48 / 60 fps |
| Bitrate | Otomatis (≈0.12 bpp) atau manual 1–60 Mbps |
| Audio internal | Lewat `AudioPlaybackCapture` (Android 10+) |
| Mikrofon | `AudioRecord` dengan sumber `VOICE_RECOGNITION` |
| Mic + internal | Di-mix real-time jadi satu track AAC stereo 48 kHz |
| Gain terpisah | Slider volume mic dan internal (0–3×) |
| Pause / resume | Timestamp digeser, jadi tidak ada frame beku di hasil |
| Hitung mundur | 0 / 3 / 5 / 10 detik sebelum mulai |
| Batas durasi | Otomatis stop setelah 5 / 15 / 30 menit |
| Tombol mengambang | Pause & stop tanpa membuka notification shade |
| Kontrol notifikasi | Pause / resume / stop dari notifikasi |
| Simpan ke galeri | `Movies/ScreenRecorder/` via MediaStore |
| Bagikan | Langsung dari notifikasi setelah selesai |

## Arsitektur

```
MediaProjection
   ├─ VirtualDisplay ──► Surface ──► VideoEncoder (H.264/AVC) ──┐
   │                                                            ├─► MuxerController ──► MP4
   ├─ AudioPlaybackCapture ──┐                                  │
   │                         ├─► mixer PCM ──► AudioEncoder ─────┘
   └─ (mic) AudioRecord ─────┘                 (AAC-LC stereo)
```

Kenapa `MediaCodec` dan bukan `MediaRecorder`: `MediaRecorder` hanya menerima
**satu** audio source, jadi mustahil merekam mic dan audio internal bersamaan.
Dengan pipeline manual, dua `AudioRecord` dibaca paralel, di-mix di domain `Int`
lalu di-clamp ke `Short` (mencegah wrap-around yang terdengar sebagai bunyi pecah),
baru di-encode ke AAC.

### File penting

| File | Peran |
|---|---|
| `recorder/ScreenRecorder.kt` | Orkestrator: projection, virtual display, encoder, muxer |
| `recorder/VideoEncoder.kt` | Surface → H.264, thread drain sendiri |
| `recorder/AudioEncoder.kt` | Mixing 2 sumber PCM → AAC |
| `recorder/PcmSource.kt` | Abstraksi mic / playback-capture, normalisasi ke stereo |
| `recorder/MuxerController.kt` | `MediaMuxer` thread-safe, tunggu semua track siap |
| `recorder/RecordingClock.kt` | Geser timestamp supaya pause tidak jadi frame beku |
| `recorder/RecordingStore.kt` | Tulis ke cache lalu publish ke MediaStore |
| `service/ScreenRecordService.kt` | Foreground service `mediaProjection` + notifikasi |
| `service/FloatingControlService.kt` | Overlay tombol pause/stop, bisa di-drag |

## Build

Butuh JDK 17 dan Android SDK (compileSdk 34).

```bash
# Buka folder ini di Android Studio (Hedgehog+), atau dari terminal:
./gradlew assembleDebug
./gradlew installDebug
```

Gradle wrapper JAR tidak disertakan. Hasilkan dengan:

```bash
gradle wrapper --gradle-version 8.9
```

atau cukup buka project di Android Studio — IDE akan mengunduh wrapper sendiri.

## Izin

| Izin | Kenapa |
|---|---|
| `RECORD_AUDIO` | Mikrofon, dan di banyak perangkat juga diperlukan untuk playback capture |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | Wajib Android 14+ |
| `POST_NOTIFICATIONS` | Notifikasi kontrol (Android 13+) |
| `SYSTEM_ALERT_WINDOW` | Tombol mengambang (opsional, diminta manual dari UI) |

## Batasan yang perlu diketahui

- **Audio internal butuh Android 10 (API 29).** Di bawah itu, mode internal
  otomatis turun ke mikrofon saja.
- **Aplikasi bisa memblokir perekaman audionya.** Playback capture hanya
  menangkap stream dengan `AudioAttributes` usage MEDIA / GAME / UNKNOWN dan
  yang tidak men-set `setAllowedCapturePolicy(ALLOW_CAPTURE_BY_NONE)`.
  Banyak layanan streaming berlisensi memblokirnya — bagian itu terekam senyap.
  Ini batasan OS, bukan bug yang bisa diakali dari sisi aplikasi.
- **Rotasi layar saat merekam** tidak mengubah ukuran `VirtualDisplay`, jadi
  hasilnya akan ber-letterbox. Menangani ini butuh me-restart encoder.
- **1440p 60fps** melebihi kemampuan encoder hardware di banyak perangkat
  menengah; bila gagal, turunkan resolusi atau fps.
