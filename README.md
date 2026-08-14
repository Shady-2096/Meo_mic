(Disclaimer: I made this project to solve a specific problem I was facing. Which is, not finding a decent app to use my phone as a mic for my pc. Most apps that I found were either paid, complicated to use, or had ads every damn hour. So even tho I don't have much coding experience, I built this tool with massive help from claude code. So if you feel like the code quality is messy, please feel free to make a pull request)

# Meo Mic

Use your Android phone as a wireless microphone for Windows or macOS. Simple,
lightweight, and free.

<img width="400" height="750" alt="Screenshot 2025-12-17 145040" src="https://github.com/user-attachments/assets/29f88143-1b3b-415a-bfc8-e41f3878204b" />


## Features

- **Real-time audio streaming** over WiFi
- **Auto-discovery** - Phone finds your computer automatically on the same network
- **QR pairing** - Scan the code on screen instead of typing an IP address
- **Volume control** - Adjust input volume on both phone and PC (0-200%)
- **Mute button** - Quick mute/unmute from your phone
- **Low latency** - Optimized UDP streaming with latency display
- **Camera (in development)** - Service-owned CameraX capture with lens
  switching, zoom, torch and screen-off lifecycle, plus the phone's side of the
  transport: pinned-TLS pairing, a versioned JSON control plane, mDNS
  advertising, and a LAN-only WebRTC sender. **There is still no desktop app to
  receive it**, so no end-to-end video path exists yet. See
  `CAMERA_BUILD_PLAN.md` for what is verified and what is not.
- **Modern UI** - Beautiful Catpuccin-themed dark interface
- **Open source** - Free forever

## Download

### PC App (Windows)
Download `MeoMic-Windows.zip` from [Releases](../../releases)

### Mac App

Download `MeoMic-macOS.zip` from [Releases](../../releases). Requires macOS 14
or later.

The Mac app is **not notarized** — notarization needs a paid Apple Developer
account, and this project has none. macOS will refuse to open it the first
time. That is expected, and here is how to get past it:

1. Unzip and drag **Meo Mic** to your Applications folder
2. Open it once. macOS blocks it and shows a warning — close that
3. Go to **System Settings → Privacy & Security**, scroll down, and click
   **Open Anyway** next to Meo Mic
4. Confirm. macOS remembers the choice; later launches are normal

On macOS 14 you can instead right-click the app and choose **Open**.

Every release lists the app's SHA-256. To check your download matches:

```bash
shasum -a 256 MeoMic-macOS.zip
```

Nervous about running an unsigned app? [Build it from source](#macos-app-swift) —
it takes one command.

### Android App
Download `MeoMic.apk` from [Releases](../../releases)

## Screenshots

| Android App | 
|-------------|
|<img src="https://github.com/user-attachments/assets/714a0e75-f48d-40b5-96be-c27ffd640eb1" width="400">
## Quick Start

### Step 1: Install the Apps

**Windows:**
1. Extract `MeoMic-Windows.zip`
2. Run `MeoMic.exe` from the extracted folder
3. (Optional) Create a desktop shortcut to `MeoMic.exe`

**Mac:**
1. Extract `MeoMic-macOS.zip` and drag **Meo Mic** to Applications
2. Approve it once in System Settings → Privacy & Security (see
   [Mac App](#mac-app) above — the app is not notarized)
3. Open it

**Android:**
1. Download and install `MeoMic.apk`
2. Allow installation from unknown sources if prompted
3. Grant microphone permission when asked

### Step 2: Install VB-Cable (Windows) — one click

Windows has no built-in way for an app to appear as a microphone, so Meo Mic needs
a virtual audio driver. On first run, the setup wizard offers to do this for you:

1. Click **Install VB-Cable**
2. Approve the Windows administrator prompt
3. Click **Install Driver** in VB-Audio's installer
4. Click **Restart now** when Meo Mic offers it

Meo Mic downloads the driver pack straight from `vb-audio.com`, verifies its
Authenticode signature before running anything, and launches VB-Audio's own
installer unmodified. Nothing is bundled or repackaged.

Prefer to do it yourself? The wizard's **Install manually instead** section has
the same steps, or grab it from [vb-audio.com/Cable](https://vb-audio.com/Cable/).

> VB-CABLE is donationware by VB-Audio (Vincent Burel). If you find it useful,
> consider [donating to them](https://vb-audio.com/Cable/) — they make it possible.

### Step 2 (Mac): Install BlackHole — one click

macOS has the same gap, so Meo Mic offers the same one-click route. On first
run the setup sheet appears by itself:

1. Click **Install BlackHole**
2. Approve the administrator prompt in Apple's installer
3. Meo Mic selects **BlackHole 2ch** automatically when it appears

Meo Mic downloads the package straight from `existential.audio`, verifies
Apple's signature on it and checks that Gatekeeper accepts it, then hands it to
Apple's own Installer — which is what asks for your password. Meo Mic never
sees it, and nothing is bundled or repackaged. If any check fails, the install
stops and the manual steps open instead.

> BlackHole is free, open-source software by
> [Existential Audio](https://existential.audio/blackhole/).

### Step 3: Connect

1. Make sure both devices are on the **same WiFi network**
2. Open Meo Mic on your computer and on your phone
3. On the phone, connect one of three ways:
   - Tap **"Search for PC"** — your computer should appear by itself
   - Tap **"Scan QR Code"**, then **Show QR** on the computer, and point the
     phone at it
   - Tap **"Enter IP Manually"** and type the address the computer shows
4. You should see "Connected" on both apps

The QR code and the manual field accept the same thing, so
`192.168.1.100`, `192.168.1.100:48888` and `meomic://192.168.1.100:48888` all
work.

### Step 4: Configure Audio Output

In the desktop app, pick where Meo Mic sends your phone's voice:

| | Select in Meo Mic |
|---|---|
| Windows | **CABLE Input (VB-Audio Virtual Cable)** |
| macOS | **BlackHole 2ch** |

### Step 5: Use in Your Apps

In Discord, Zoom, Teams, OBS, etc., go to audio/microphone settings and choose:

| | Select as your microphone |
|---|---|
| Windows | **CABLE Output (VB-Audio Virtual Cable)** |
| macOS | **BlackHole 2ch** |

Meo Mic writes into the virtual device; your call app reads out of it.

## Controls

### Android App
| Control | Function |
|---------|----------|
| Mute Button (green/red) | Toggle microphone mute |
| Volume Slider | Adjust input volume (0-200%) |
| Scan QR Code | Pair by scanning the code on your computer |
| Camera Button | Open the in-development local camera capture probe |
| Disconnect Button | End the connection |

### Desktop App
| Control | Function |
|---------|----------|
| Device Dropdown | Select audio output device |
| Volume Slider | Adjust output volume (0-200%) |
| Audio Setup | Open the setup wizard (VB-Cable on Windows, BlackHole on macOS) |

## Building from Source

### PC App (Python)

```bash
cd pc-app
pip install -r requirements.txt
python main.py
```

To build executable:
```bash
build_windows.bat
```
The app will be in `dist\MeoMic\MeoMic.exe`

### macOS App (Swift)

Xcode 16 or newer is recommended:

```bash
./scripts/build-app.sh release
open build/MeoMic.app
```

The script builds a native Swift executable, assembles a real app bundle,
declares local-network and Bonjour access, generates the app icon, and applies
an ad-hoc signature.

To build the zip that goes on a release:

```bash
./scripts/package-release.sh
```

It prints the version and SHA-256 to paste into the release notes. The app is
ad-hoc signed rather than Developer ID signed and notarized, because that needs
a paid Apple Developer account — so users approve it once in System Settings on
first launch, as described under [Mac App](#mac-app).

To run the tests:

```bash
swift test
```

To exercise the UDP protocol without an Android phone:

```bash
./scripts/send-test-audio.py --host 127.0.0.1 --seconds 3
```

### Android App

In Android Studio:

1. Open the `android-app` folder
2. Sync Gradle files
3. Build → Generate Signed Bundle / APK → APK
4. Create/select a keystore
5. Build release APK

Or from a terminal, with `ANDROID_HOME` pointing at your SDK:

```bash
cd android-app
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew lintDebug
```

## Technical Details

| Specification | Value |
|---------------|-------|
| Protocol | Custom UDP packets |
| Audio Format | 48kHz, 16-bit, Mono PCM |
| Port | 48888 |
| Discovery | mDNS/Zeroconf (`_meomic._udp.local.`) |

## Requirements

### Windows
- Windows 10 or later
- VB-Cable virtual audio driver (one-click install in the app)

### macOS
- macOS 14 or later
- BlackHole 2ch virtual audio device (one-click install in the app)
- Local network permission when macOS asks

### Android
- Android 7.0 (API 24) or higher
- Android 10 or higher for the in-development camera path
- Microphone permission
- Camera permission if you scan a QR code or explicitly start camera capture
- Same WiFi network as your computer

## Troubleshooting

### No audio in Discord/Zoom/etc.
1. Make sure you selected **"CABLE Input"** in the Meo Mic PC app
2. Make sure you selected **"CABLE Output"** as microphone in Discord/Zoom
3. Check that the audio level bar moves when you speak

### Phone shows "Connected" but PC doesn't
- Check Windows Firewall - allow Meo Mic through
- Try disabling VPN
- Restart both apps

### High latency
- Move closer to your WiFi router
- Use 5GHz WiFi instead of 2.4GHz
- Close bandwidth-heavy apps

### PC app takes long to start
- Make sure you're using the folder version (not single .exe)
- Extract the entire ZIP before running

### "CABLE Input" not showing
- Make sure VB-Cable is installed (VB-Cable Setup → **Install VB-Cable**)
- Restart your PC after installation — the device does not appear until you do
- Check Device Manager for VB-Audio device

### macOS: "Meo Mic cannot be opened" / "Apple could not verify"
This is Gatekeeper, not a problem with the download. The app is not notarized
because notarization requires a paid Apple Developer account.
- Open **System Settings → Privacy & Security**, scroll down, click
  **Open Anyway** next to Meo Mic, and confirm
- On macOS 14, right-click the app and choose **Open** instead
- Compare `shasum -a 256 MeoMic-macOS.zip` against the release notes if you
  want to confirm the download first
- Or [build it from source](#macos-app-swift) and skip the question entirely

### macOS: "BlackHole 2ch" not showing
- Open **Audio Setup** in Meo Mic and click **Install BlackHole**
- If the install refuses with a signature or Gatekeeper error, that is
  deliberate — Meo Mic will not run a package it cannot verify. Use
  **Install manually instead** and get it from
  [existential.audio](https://existential.audio/blackhole/)
- Already installed? Click **Re-check**; no restart is needed on macOS

### Scanning the QR code does nothing
- Grant the camera permission when the scanner asks. It is only requested when
  you open the scanner, never at startup
- The scanner only accepts Meo Mic addresses. If it says the code is not one,
  you are pointing at a different QR code
- No camera, or the permission denied? **Enter IP Manually** works the same way

### macOS: phone cannot find the Mac
- Approve the local network prompt (**System Settings → Privacy & Security →
  Local Network → Meo Mic**)
- macOS firewall: allow incoming connections for Meo Mic

### One-click install failed
- **"Signature could not be verified"** — Meo Mic refuses to run an installer it
  can't verify. Use the wizard's **Install manually instead** steps.
- **"Administrator permission was declined"** — VB-Cable installs a driver, so
  Windows requires elevation. Click **Try again** and approve the prompt.
- **Download failed** — check your connection or a corporate proxy/firewall, then
  fall back to the manual steps.

## License

MIT License - Free to use and modify

## Contributing

Contributions welcome! Feel free to open issues or pull requests.

---

Made with love using Python, Kotlin, and Jetpack Compose
