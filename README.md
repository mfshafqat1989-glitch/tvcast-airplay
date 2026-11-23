# TV Cast AirPlay

AirPlay receiver for Android TV, Google TV and Android phones. Your TV shows up in the iPhone's **Screen Mirroring** and AirPlay lists.

**Download the latest APK:**
https://github.com/mfshafqat1989-glitch/tvcast-airplay/releases/latest/download/TVCast-AirPlay.apk

## What it does

| Feature | Status |
|---|---|
| iPhone / iPad / Mac **screen mirroring** (H.264, plus H.265 on devices that support it) | ✅ |
| Mirroring audio (AAC-ELD) | ✅ |
| AirPlay music (Apple Music, Spotify AirPlay and so on, ALAC/AAC) | ✅ |
| Volume control from the iPhone | ✅ |
| Auto-update from GitHub Releases | ✅ |
| "Cast video link" mode (YouTube and similar apps that send a link instead of mirroring) | ❌ not yet |
| DRM apps (Netflix, Disney+, Apple TV+) | ❌ they only play on real Apple devices |

## Use it

1. Install the APK. If an older version (v1.x) is installed, uninstall it first, because v2 is signed with a new key.
2. Open **TV Cast AirPlay**. The phone and the TV must be on the same Wi-Fi.
3. Press **"Allow auto-open in background"** once, so mirroring pops up even when the app isn't open.
4. On the iPhone, open Control Centre → **Screen Mirroring** and pick the TV.

## Updates

The app checks GitHub when it opens and every 6 hours. With **Auto-update** on, it downloads new versions by itself. Android always asks you to tap **Install** to confirm. The first time, you'll also be asked to allow this app to install updates.

Every push to `main` builds a new APK automatically (GitHub Actions) and publishes it as the latest release.

## How it's built

- `app/src/main/cpp/uxplay`: the AirPlay engine from [UxPlay](https://github.com/FDH2/UxPlay) (GPLv3), including its built-in mDNS responder
- `app/src/main/cpp/libplist`: [libplist](https://github.com/libimobiledevice/libplist) (LGPL 2.1)
- `app/src/main/cpp/alac`: Apple's [ALAC decoder](https://github.com/macosforge/alac) (Apache 2.0)
- OpenSSL 3 (libcrypto) is compiled for Android in CI by `scripts/build-openssl.sh`
- Kotlin: hardware decoding with MediaCodec, audio with AudioTrack

Because it includes UxPlay, this app is licensed under **GPLv3** (see `LICENSE`).

The signing key in `keystore/` is public on purpose. It only keeps updates installing over each other, so don't reuse it for anything else.
