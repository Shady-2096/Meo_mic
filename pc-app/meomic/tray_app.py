"""
System Tray Application

Provides a minimal system tray icon with:
- Connection status indicator
- QR code for easy phone connection
- Device selection menu
- Quick access to settings
"""

import io
import os
import sys
import threading
import webbrowser
from typing import Optional, Callable
from PIL import Image, ImageDraw
import pystray
from pystray import MenuItem as Item

from .audio_output import AudioOutput, get_platform_instructions


class TrayApp:
    def __init__(self):
        self.icon: Optional[pystray.Icon] = None
        self.is_connected = False
        self.client_ip: Optional[str] = None
        self.local_ip: Optional[str] = None
        self.port: int = 48888

        self.audio_output: Optional[AudioOutput] = None

        # Callbacks
        self.on_quit: Optional[Callable] = None
        self.on_device_change: Optional[Callable[[int], None]] = None

        # Cache the loaded icon
        self._icon_image: Optional[Image.Image] = None
        self._load_icon()

    def _load_icon(self):
        """Load the icon from file."""
        icon_paths = [
            os.path.join(os.path.dirname(sys.executable), 'icon.ico'),
            os.path.join(os.path.dirname(sys.executable), '_internal', 'icon.ico'),  # PyInstaller 6.x
            os.path.join(os.path.dirname(__file__), '..', 'icon.ico'),
            os.path.join(os.path.dirname(__file__), 'icon.ico'),
            'icon.ico'
        ]

        for icon_path in icon_paths:
            if os.path.exists(icon_path):
                try:
                    self._icon_image = Image.open(icon_path)
                    # Resize to 64x64 for tray
                    self._icon_image = self._icon_image.resize((64, 64), Image.Resampling.LANCZOS)
                    return
                except Exception:
                    pass

        # Fallback: create a simple icon if file not found
        self._icon_image = self._create_fallback_icon()

    def _create_fallback_icon(self) -> Image.Image:
        """Create a fallback icon if file not found."""
        size = 64
        img = Image.new('RGBA', (size, size), (0, 0, 0, 0))
        draw = ImageDraw.Draw(img)
        draw.ellipse([4, 4, size - 4, size - 4], fill='#666666')
        draw.rounded_rectangle([24, 14, 40, 38], radius=6, fill='white')
        draw.arc([18, 28, 46, 48], start=0, end=180, fill='white', width=3)
        draw.line([32, 48, 32, 54], fill='white', width=3)
        draw.line([24, 54, 40, 54], fill='white', width=3)
        return img

    def create_icon_image(self, connected: bool = False) -> Image.Image:
        """Get the tray icon image."""
        if self._icon_image:
            return self._icon_image.copy()
        return self._create_fallback_icon()

    def update_icon(self, connected: bool, client_ip: Optional[str] = None):
        """Update the tray icon based on connection status."""
        self.is_connected = connected
        self.client_ip = client_ip

        if self.icon:
            self.icon.icon = self.create_icon_image(connected)
            if connected:
                self.icon.title = f"Meo Mic - Connected ({client_ip})"
            else:
                self.icon.title = f"Meo Mic - Waiting for connection\n{self.local_ip}:{self.port}"

    def set_local_ip(self, ip: str, port: int):
        """Set the local IP and port for display."""
        self.local_ip = ip
        self.port = port
        if self.icon and not self.is_connected:
            self.icon.title = f"Meo Mic - Waiting for connection\n{ip}:{port}"

    def _create_menu(self):
        """Create the tray menu."""
        def get_status(item):
            if self.is_connected:
                return f"Connected: {self.client_ip}"
            return f"Waiting... ({self.local_ip}:{self.port})"

        def get_devices_submenu():
            """Generate device selection submenu."""
            items = []
            if self.audio_output:
                devices = self.audio_output.list_devices()
                current = self.audio_output.output_device

                for dev in devices:
                    name = dev['name']
                    if dev['is_virtual']:
                        name = f"* {name}"  # Mark virtual devices

                    def make_handler(device_id):
                        return lambda: self._select_device(device_id)

                    items.append(Item(
                        name,
                        make_handler(dev['id']),
                        checked=lambda item, d=dev['id']: d == current
                    ))

            if not items:
                items.append(Item("No devices found", None, enabled=False))

            return items

        return pystray.Menu(
            Item(get_status, None, enabled=False),
            pystray.Menu.SEPARATOR,
            Item("Show QR Code", self._show_qr_code),
            Item("Copy IP Address", self._copy_ip),
            pystray.Menu.SEPARATOR,
            Item("Output Device", pystray.Menu(get_devices_submenu)),
            Item("Virtual Audio Setup", self._show_setup_help),
            pystray.Menu.SEPARATOR,
            Item("Quit", self._quit),
        )

    def _select_device(self, device_id: int):
        """Handle device selection."""
        if self.on_device_change:
            self.on_device_change(device_id)

    def _show_qr_code(self):
        """Show QR code in a window."""
        if self.local_ip:
            from .qr_window import show_qr_window
            show_qr_window(self.local_ip, self.port)

    def _copy_ip(self):
        """Copy IP address to clipboard."""
        if self.local_ip:
            try:
                import subprocess
                import platform

                text = f"{self.local_ip}:{self.port}"

                if platform.system() == 'Windows':
                    subprocess.run(['clip'], input=text.encode(), check=True)
                elif platform.system() == 'Darwin':
                    subprocess.run(['pbcopy'], input=text.encode(), check=True)
                else:
                    subprocess.run(['xclip', '-selection', 'clipboard'],
                                   input=text.encode(), check=True)
                print(f"[Tray] Copied to clipboard: {text}")
            except Exception as e:
                print(f"[Tray] Failed to copy: {e}")

    def _show_setup_help(self):
        """Show virtual audio setup instructions."""
        instructions = get_platform_instructions()
        print(instructions)

        # Also try to open in a simple window or browser
        try:
            from .help_window import show_help_window
            show_help_window(instructions)
        except Exception:
            print(instructions)

    def _quit(self):
        """Quit the application."""
        if self.on_quit:
            self.on_quit()
        if self.icon:
            self.icon.stop()

    def run(self):
        """Run the tray application (blocking)."""
        self.icon = pystray.Icon(
            "MeoMic",
            self.create_icon_image(False),
            "Meo Mic - Starting...",
            menu=self._create_menu()
        )
        self.icon.run()

    def run_detached(self):
        """Run the tray application in a separate thread."""
        thread = threading.Thread(target=self.run, daemon=True)
        thread.start()
        return thread

    def stop(self):
        """Stop the tray application."""
        if self.icon:
            self.icon.stop()
