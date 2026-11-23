# TV Cast AirPlay

An AirPlay receiver for Android TV / Google TV. Your TV shows up in the iPhone's AirPlay list, and you can send videos and photos to it.

## Get the APK (no computer setup needed)

1. Make a new repository on GitHub and upload everything in this folder, **including the hidden `.github` folder**.
2. Open the repo's **Actions** tab. The "Build APK" job starts by itself and takes about 3–5 minutes.
3. When it's green, open the job and download **TVCast-AirPlay** under "Artifacts". It's a zip with the APK inside.
4. Optional: push a tag like `v1.0` and the APK gets attached to a GitHub Release, so you can download it straight onto the TV.

## Install on the TV

- Install the **Downloader** app on the TV, enable "Install unknown apps" for it, and open the APK link (for example the Release link).
- Or copy the APK over on a USB stick and open it with a file manager.

## Use it

1. Open **TV Cast AirPlay** on the TV. The phone and TV need to be on the same Wi-Fi.
2. On the iPhone, tap the AirPlay icon on a video or photo, then pick the TV.
3. Press **"Allow auto-open in background"** once so videos open even when the app isn't on screen.

## What works and what doesn't

| Feature | Status |
|---|---|
| TV appears in the AirPlay list | ✅ |
| Sending video links (MP4, HLS), with pause, seek and stop from the phone | ✅ in apps that use classic AirPlay video |
| Sending photos | ✅ |
| **iPhone screen mirroring** | ❌ needs Apple's FairPlay encryption, which isn't included |
| Music-only AirPlay (AirPlay audio) | ❌ |
| DRM apps (Netflix, Disney+, Apple TV+) | ❌ they only send to real Apple devices |

## Tech

Kotlin, Media3/ExoPlayer, Android NSD (Bonjour). The receiver code is in `app/src/main/java/com/tvcast/airplay/`.
