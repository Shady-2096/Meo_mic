# Meo Mic

Use your Android phone as a wireless microphone for your PC. Simple, lightweight, and free.

!(<img width="400" height="750" alt="Screenshot 2025-12-17 145040" src="https://github.com/user-attachments/assets/29f88143-1b3b-415a-bfc8-e41f3878204b" />)


## Features

- **Real-time audio streaming** over WiFi
- **Auto-discovery** - Phone finds PC automatically on the same network
- **Volume control** - Adjust input volume on both phone and PC (0-200%)
- **Mute button** - Quick mute/unmute from your phone
- **Low latency** - Optimized UDP streaming with latency display
- **Modern UI** - Beautiful Catpuccin-themed dark interface
- **Open source** - Free forever

## Download

### PC App (Windows)
Download `MeoMic-Windows.zip` from [Releases](../../releases)

### Android App
Download `MeoMic.apk` from [Releases](../../releases)

## Screenshots

| Android App | PC App |
|-------------|--------|
| ![Android](screenshots/android.png) | ![PC](screenshots/pc.png) |

## Quick Start

### Step 1: Install VB-Cable (Windows)

VB-Cable is a free virtual audio driver that lets other apps use your phone as a microphone.

1. Download from [vb-audio.com/Cable](https://vb-audio.com/Cable/)
2. Extract the ZIP file
3. **Right-click** `VBCABLE_Setup_x64.exe` → **Run as administrator**
4. Click "Install Driver" and wait for completion
5. **Restart your PC**

### Step 2: Install the Apps

**PC:**
1. Extract `MeoMic-Windows.zip`
2. Run `MeoMic.exe` from the extracted folder
3. (Optional) Create a desktop shortcut to `MeoMic.exe`

**Android:**
1. Download and install `MeoMic.apk`
2. Allow installation from unknown sources if prompted
3. Grant microphone permission when asked

### Step 3: Connect

1. Make sure both devices are on the **same WiFi network**
2. Open Meo Mic on your PC - note the IP address shown
3. Open Meo Mic on your phone
4. Tap **"Search for PC"** or enter the IP address manually
5. You should see "Connected" on both apps

### Step 4: Configure Audio Output

In the PC app:
- Select **"CABLE Input (VB-Audio Virtual Cable)"** from the dropdown
- This sends audio TO the virtual cable

### Step 5: Use in Your Apps

In Discord, Zoom, Teams, OBS, etc.:
- Go to audio/microphone settings
- Select **"CABLE Output (VB-Audio Virtual Cable)"** as your microphone
- This receives audio FROM the virtual cable

## Controls

### Android App
| Control | Function |
|---------|----------|
| Mute Button (green/red) | Toggle microphone mute |
| Volume Slider | Adjust input volume (0-200%) |
| Disconnect Button | End the connection |

### PC App
| Control | Function |
|---------|----------|
| Device Dropdown | Select audio output device |
| Volume Slider | Adjust output volume (0-200%) |
| VB-Cable Setup | Open setup wizard |

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

### Android App

1. Open `android-app` folder in Android Studio
2. Sync Gradle files
3. Build → Generate Signed Bundle / APK → APK
4. Create/select a keystore
5. Build release APK

## Technical Details

| Specification | Value |
|---------------|-------|
| Protocol | Custom UDP packets |
| Audio Format | 48kHz, 16-bit, Mono PCM |
| Port | 48888 |
| Discovery | mDNS/Zeroconf (`_meomic._udp.local.`) |

## Requirements

### PC
- Windows 10 or later
- VB-Cable virtual audio driver

### Android
- Android 7.0 (API 24) or higher
- Microphone permission
- Same WiFi network as PC

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
- Make sure VB-Cable is installed
- Restart your PC after installation
- Check Device Manager for VB-Audio device

## License

MIT License - Free to use and modify

## Contributing

Contributions welcome! Feel free to open issues or pull requests.

---

Made with love using Python, Kotlin, and Jetpack Compose
