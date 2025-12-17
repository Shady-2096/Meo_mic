"""
Meo Mic - Phone to PC Microphone

Main application that coordinates all components.
"""

import signal
import sys
import threading
import time
from typing import Optional

from .audio_receiver import UdpAudioReceiver
from .audio_output import AudioOutput
from .service_broadcaster import ServiceBroadcaster
from .main_window import MainWindow
from .setup_wizard import SetupWizard


class MeoMicApp:
    PORT = 48888

    def __init__(self):
        self.receiver = UdpAudioReceiver(port=self.PORT)
        self.audio_output = AudioOutput()
        self.broadcaster = ServiceBroadcaster(port=self.PORT)
        self.window = MainWindow()

        self.running = False
        self._level_thread: Optional[threading.Thread] = None
        self._setup_shown = False

        # Wire up callbacks
        self.receiver.on_audio_data = self._on_audio_data
        self.receiver.on_client_connected = self._on_client_connected
        self.receiver.on_client_disconnected = self._on_client_disconnected

        self.window.on_quit = self.stop
        self.window.on_device_change = self._on_device_change
        self.window.on_show_setup = self._show_setup_wizard
        self.window.on_volume_change = self._on_volume_change

    def _on_audio_data(self, data: bytes):
        """Handle received audio data."""
        self.audio_output.write(data)

    def _on_client_connected(self, client_ip: str):
        """Handle client connection."""
        print(f"[Meo Mic] Client connected: {client_ip}")
        self.window.update_status(True, client_ip)
        self.audio_output.start()

    def _on_client_disconnected(self):
        """Handle client disconnection."""
        print("[Meo Mic] Client disconnected")
        self.window.update_status(False)
        self.audio_output.stop()

    def _on_device_change(self, device_id: int):
        """Handle output device change."""
        self.audio_output.set_output_device(device_id)
        print(f"[Meo Mic] Output device changed to: {device_id}")

    def _on_volume_change(self, volume: float):
        """Handle volume change."""
        self.audio_output.set_volume(volume)

    def _level_update_loop(self):
        """Background thread to update audio level in UI."""
        while self.running:
            level = self.audio_output.get_level()
            self.window.update_level(level)
            time.sleep(0.05)

    def start(self):
        """Start all components."""
        print("=" * 50)
        print("  Meo Mic - Phone to PC Microphone")
        print("=" * 50)

        self.running = True

        # Start receiver
        self.receiver.start()

        # Start mDNS broadcaster
        self.broadcaster.start()

        # Get connection info
        local_ip = self.broadcaster.get_local_ip()
        if local_ip:
            print(f"\nReady! Connect from phone to: {local_ip}:{self.PORT}\n")
        else:
            local_ip = "127.0.0.1"
            print("\nWarning: Could not determine local IP\n")

        # Set connection info (will be applied when window opens)
        self.window.set_connection_info(local_ip, self.PORT)

        # Get devices and set them (will be applied when window opens)
        devices = self.audio_output.list_devices()
        virtual_device = self.audio_output.find_virtual_device()

        if virtual_device is not None:
            self.audio_output.set_output_device(virtual_device)
            self.window.set_devices(devices, virtual_device)
            print(f"Auto-selected virtual device (ID: {virtual_device})")
        else:
            self.window.set_devices(devices, None)
            print("No virtual audio device found.")
            print("Install VB-Cable to use as a virtual microphone.")

        # Start level update thread
        self._level_thread = threading.Thread(target=self._level_update_loop, daemon=True)
        self._level_thread.start()

    def _refresh_devices(self):
        """Refresh device list after setup wizard completes."""
        devices = self.audio_output.list_devices()
        virtual_device = self.audio_output.find_virtual_device()

        if virtual_device is not None:
            self.audio_output.set_output_device(virtual_device)
            self.window.set_devices(devices, virtual_device)
            print(f"Auto-selected virtual device (ID: {virtual_device})")
        else:
            self.window.set_devices(devices, None)

    def _show_setup_if_needed(self):
        """Show setup wizard if no virtual audio device found."""
        if self._setup_shown:
            return

        if SetupWizard.needs_setup():
            self._setup_shown = True
            wizard = SetupWizard()
            wizard.on_complete = self._refresh_devices
            wizard.on_skip = lambda: None
            wizard.show(self.window.root)
    def _show_setup_wizard(self):
        """Show setup wizard from button click."""
        wizard = SetupWizard()
        wizard.on_complete = self._refresh_devices
        wizard.on_skip = lambda: None
        wizard.show(self.window.root)

    def run(self):
        """Run the application (blocking)."""
        self.start()

        # Create window first
        self.window.create_window()
        self.window.running = True

        # Show setup wizard if needed (after window exists)
        self._show_setup_if_needed()

        try:
            self.window.root.mainloop()
        except KeyboardInterrupt:
            pass
        finally:
            self.stop()

    def stop(self):
        """Stop all components."""
        if not self.running:
            return

        print("\nShutting down...")
        self.running = False

        self.audio_output.stop()
        self.receiver.stop()
        self.broadcaster.stop()

        print("Goodbye!")

        # Force exit
        import os
        os._exit(0)


def main():
    """Entry point."""
    app = MeoMicApp()

    def signal_handler(sig, frame):
        print("\nInterrupted")
        app.stop()
        sys.exit(0)

    signal.signal(signal.SIGINT, signal_handler)

    app.run()


if __name__ == "__main__":
    main()
