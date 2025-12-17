"""
Audio Output Module - Fixed for proper streaming
"""

import threading
import platform
from typing import Optional, List
import numpy as np
import sounddevice as sd


class AudioOutput:
    SAMPLE_RATE = 48000
    CHANNELS = 1
    DTYPE = np.int16

    # Larger buffer to handle network jitter - 200ms
    BUFFER_FRAMES = 1024

    def __init__(self):
        self.output_device: Optional[int] = None
        self.stream: Optional[sd.OutputStream] = None
        self.running = False

        # Simple buffer approach - accumulate samples
        self.buffer = np.array([], dtype=np.int16)
        self.buffer_lock = threading.Lock()

        # Target buffer size: ~50ms worth = 2400 samples
        # We want some buffer to handle jitter but not too much for latency
        self.target_buffer = 2400

        # Audio level
        self.current_level: float = 0.0

        # Volume control (0.0 to 2.0, where 1.0 is normal)
        self.volume: float = 1.0

    def list_devices(self) -> List[dict]:
        """List available output devices."""
        devices = []
        try:
            for i, dev in enumerate(sd.query_devices()):
                if dev['max_output_channels'] > 0:
                    devices.append({
                        'id': i,
                        'name': dev['name'],
                        'channels': dev['max_output_channels'],
                        'is_virtual': self._is_virtual_device(dev['name'])
                    })
        except Exception as e:
            print(f"[Audio] Error listing devices: {e}")
        return devices

    def _is_virtual_device(self, name: str) -> bool:
        """Check if device is a virtual audio device."""
        keywords = ['cable', 'virtual', 'vb-audio', 'blackhole', 'soundflower', 'loopback']
        return any(kw in name.lower() for kw in keywords)

    def find_virtual_device(self) -> Optional[int]:
        """Find a virtual audio device."""
        for dev in self.list_devices():
            if dev['is_virtual']:
                return dev['id']
        return None

    def set_output_device(self, device_id: Optional[int]):
        """Set output device."""
        was_running = self.running
        if was_running:
            self.stop()
        self.output_device = device_id
        if was_running:
            self.start()

    def start(self):
        """Start audio output."""
        if self.running:
            return

        with self.buffer_lock:
            self.buffer = np.array([], dtype=np.int16)

        try:
            self.stream = sd.OutputStream(
                device=self.output_device,
                samplerate=self.SAMPLE_RATE,
                channels=self.CHANNELS,
                dtype=self.DTYPE,
                blocksize=self.BUFFER_FRAMES,
                callback=self._callback
            )
            self.stream.start()
            self.running = True

            name = "default"
            if self.output_device is not None:
                name = sd.query_devices(self.output_device)['name']
            print(f"[Audio] Started: {name}")

        except Exception as e:
            print(f"[Audio] Failed: {e}")
            self.running = False

    def stop(self):
        """Stop audio output."""
        self.running = False
        if self.stream:
            try:
                self.stream.stop()
                self.stream.close()
            except:
                pass
            self.stream = None

        with self.buffer_lock:
            self.buffer = np.array([], dtype=np.int16)
        self.current_level = 0.0

    def write(self, audio_data: bytes):
        """Write audio data."""
        if not self.running or len(audio_data) == 0:
            return

        try:
            samples = np.frombuffer(audio_data, dtype=self.DTYPE).copy()

            # Apply volume
            if self.volume != 1.0:
                samples = (samples.astype(np.float32) * self.volume).clip(-32768, 32767).astype(np.int16)

            # Update level
            if len(samples) > 0:
                rms = np.sqrt(np.mean(samples.astype(np.float32) ** 2))
                self.current_level = min(1.0, rms / 10000.0)

            with self.buffer_lock:
                # Add new samples
                self.buffer = np.concatenate([self.buffer, samples])

                # Limit buffer size to prevent latency buildup (max ~150ms)
                max_buffer = 7200
                if len(self.buffer) > max_buffer:
                    # Keep only the newest samples
                    self.buffer = self.buffer[-max_buffer:]

        except Exception as e:
            print(f"[Audio] Write error: {e}")

    def _callback(self, outdata: np.ndarray, frames: int, time_info, status):
        """Audio callback - called by sounddevice."""
        with self.buffer_lock:
            available = len(self.buffer)

            if available >= frames:
                # Have enough data
                outdata[:, 0] = self.buffer[:frames]
                self.buffer = self.buffer[frames:]
            elif available > 0:
                # Have some data, pad with last sample to avoid clicks
                outdata[:available, 0] = self.buffer
                # Repeat last sample or fade to zero
                if available > 0:
                    outdata[available:, 0] = self.buffer[-1]
                else:
                    outdata[available:, 0] = 0
                self.buffer = np.array([], dtype=np.int16)
            else:
                # No data, output silence
                outdata.fill(0)
                self.current_level = 0.0

    def get_level(self) -> float:
        """Get audio level."""
        return self.current_level

    def set_volume(self, volume: float):
        """Set volume (0.0 to 2.0)."""
        self.volume = max(0.0, min(2.0, volume))


def get_platform_instructions() -> str:
    """Get virtual audio setup instructions."""
    system = platform.system()

    if system == 'Windows':
        return """Virtual Audio Setup (Windows):

1. Download VB-Cable from https://vb-audio.com/Cable/
2. Run installer as Administrator
3. Restart PC
4. In Meo Mic, select "CABLE Input"
5. In your app, select "CABLE Output" as mic"""
    elif system == 'Darwin':
        return """Virtual Audio Setup (macOS):

1. Download BlackHole from https://existential.audio/blackhole/
2. Install the 2ch version
3. In Meo Mic, select "BlackHole 2ch"
4. In your app, select "BlackHole 2ch" as mic"""
    else:
        return """Virtual Audio Setup (Linux):

Run: pactl load-module module-null-sink sink_name=MeoMic

Select "MeoMic" in the app.
Use "Monitor of MeoMic" as mic in your app."""
