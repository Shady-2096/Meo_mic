"""
Meo Mic - Modern Main Window GUI
"""

import customtkinter as ctk
import threading
import os
import sys
from typing import Optional, Callable, List

# Set appearance
ctk.set_appearance_mode("dark")
ctk.set_default_color_theme("blue")


class MainWindow:
    def __init__(self):
        self.root: Optional[ctk.CTk] = None
        self.running = False

        # State
        self.is_connected = False
        self.client_ip: Optional[str] = None
        self.local_ip: Optional[str] = None
        self.port: int = 48888
        self.audio_level: float = 0.0

        # Callbacks
        self.on_device_change: Optional[Callable[[int], None]] = None
        self.on_volume_change: Optional[Callable[[float], None]] = None
        self.on_quit: Optional[Callable] = None
        self.on_show_setup: Optional[Callable] = None

        # Devices - stored for later application
        self.devices: List[dict] = []
        self.selected_device: Optional[int] = None
        self._pending_devices: Optional[tuple] = None
        self._pending_connection_info: Optional[tuple] = None

        # UI elements
        self.status_label: Optional[ctk.CTkLabel] = None
        self.status_dot: Optional[ctk.CTkLabel] = None
        self.ip_label: Optional[ctk.CTkLabel] = None
        self.level_bar: Optional[ctk.CTkProgressBar] = None
        self.device_menu: Optional[ctk.CTkOptionMenu] = None
        self.copy_btn: Optional[ctk.CTkButton] = None
        self.volume_slider: Optional[ctk.CTkSlider] = None
        self.volume_label: Optional[ctk.CTkLabel] = None



    def create_window(self):
        """Create the main window."""
        self.root = ctk.CTk()
        self.root.title("Meo Mic")
        self.root.geometry("380x620")
        self.root.resizable(False, False)

        # Set window icon
        self._set_icon()

        # Center on screen
        self.root.update_idletasks()
        x = (self.root.winfo_screenwidth() - 380) // 2
        y = (self.root.winfo_screenheight() - 620) // 2
        self.root.geometry(f"380x620+{x}+{y}")

        # Handle close
        self.root.protocol("WM_DELETE_WINDOW", self._on_close)

        # Main container
        main = ctk.CTkFrame(self.root, fg_color="transparent")
        main.pack(fill="both", expand=True, padx=25, pady=25)

        # ===== Header =====
        header = ctk.CTkFrame(main, fg_color="transparent")
        header.pack(fill="x", pady=(0, 20))

        title = ctk.CTkLabel(
            header,
            text="Meo Mic",
            font=ctk.CTkFont(size=32, weight="bold")
        )
        title.pack()

        subtitle = ctk.CTkLabel(
            header,
            text="Phone → PC Microphone",
            font=ctk.CTkFont(size=13),
            text_color="gray"
        )
        subtitle.pack()

        # ===== Status Card =====
        status_card = ctk.CTkFrame(main, corner_radius=12)
        status_card.pack(fill="x", pady=10)

        status_inner = ctk.CTkFrame(status_card, fg_color="transparent")
        status_inner.pack(pady=15, padx=15)

        self.status_dot = ctk.CTkLabel(
            status_inner,
            text="●",
            font=ctk.CTkFont(size=16),
            text_color="gray"
        )
        self.status_dot.pack(side="left", padx=(0, 10))

        self.status_label = ctk.CTkLabel(
            status_inner,
            text="Waiting for connection...",
            font=ctk.CTkFont(size=14)
        )
        self.status_label.pack(side="left")

        # ===== IP Address =====
        ip_section = ctk.CTkFrame(main, fg_color="transparent")
        ip_section.pack(fill="x", pady=10)

        self.ip_label = ctk.CTkLabel(
            ip_section,
            text="Loading...",
            font=ctk.CTkFont(size=18, weight="bold")
        )
        self.ip_label.pack()

        self.copy_btn = ctk.CTkButton(
            ip_section,
            text="Copy IP",
            width=100,
            height=32,
            corner_radius=8,
            command=self._copy_ip
        )
        self.copy_btn.pack(pady=10)

        # ===== Audio Level =====
        level_section = ctk.CTkFrame(main, fg_color="transparent")
        level_section.pack(fill="x", pady=15)

        level_label = ctk.CTkLabel(
            level_section,
            text="Audio Level",
            font=ctk.CTkFont(size=12),
            text_color="gray"
        )
        level_label.pack()

        self.level_bar = ctk.CTkProgressBar(
            level_section,
            width=300,
            height=12,
            corner_radius=6
        )
        self.level_bar.pack(pady=8)
        self.level_bar.set(0)

        # ===== Volume Control =====
        volume_section = ctk.CTkFrame(main, fg_color="transparent")
        volume_section.pack(fill="x", pady=10)

        volume_header = ctk.CTkFrame(volume_section, fg_color="transparent")
        volume_header.pack(fill="x")

        volume_title = ctk.CTkLabel(
            volume_header,
            text="Volume",
            font=ctk.CTkFont(size=12),
            text_color="gray"
        )
        volume_title.pack(side="left")

        self.volume_label = ctk.CTkLabel(
            volume_header,
            text="100%",
            font=ctk.CTkFont(size=12),
            text_color="gray"
        )
        self.volume_label.pack(side="right")

        self.volume_slider = ctk.CTkSlider(
            volume_section,
            from_=0,
            to=200,
            number_of_steps=200,
            width=300,
            height=18,
            command=self._on_volume_changed
        )
        self.volume_slider.pack(pady=8)
        self.volume_slider.set(100)

        # ===== Device Selection =====
        device_section = ctk.CTkFrame(main, fg_color="transparent")
        device_section.pack(fill="x", pady=10)

        device_label = ctk.CTkLabel(
            device_section,
            text="Output Device",
            font=ctk.CTkFont(size=12),
            text_color="gray"
        )
        device_label.pack(anchor="w")

        self.device_menu = ctk.CTkOptionMenu(
            device_section,
            values=["No devices found"],
            width=320,
            height=35,
            corner_radius=8,
            command=self._on_device_selected
        )
        self.device_menu.pack(fill="x", pady=5)

        # ===== Bottom Buttons =====
        bottom_frame = ctk.CTkFrame(main, fg_color="transparent")
        bottom_frame.pack(side="bottom", fill="x", pady=10)

        quit_btn = ctk.CTkButton(
            bottom_frame,
            text="Quit",
            width=100,
            height=36,
            corner_radius=8,
            fg_color="#444",
            hover_color="#555",
            command=self._on_close
        )
        quit_btn.pack(side="left")

        setup_btn = ctk.CTkButton(
            bottom_frame,
            text="VB-Cable Setup",
            width=120,
            height=36,
            corner_radius=8,
            fg_color="transparent",
            hover_color="#333",
            border_width=1,
            border_color="#555",
            command=self._on_show_setup
        )
        setup_btn.pack(side="right")

        # Apply any pending data
        self._apply_pending_data()

    def _apply_pending_data(self):
        """Apply data that was set before window was created."""
        if self._pending_connection_info:
            ip, port = self._pending_connection_info
            self._do_set_connection_info(ip, port)
            self._pending_connection_info = None

        if self._pending_devices:
            devices, selected = self._pending_devices
            self._do_set_devices(devices, selected)
            self._pending_devices = None

    def _copy_ip(self):
        """Copy IP to clipboard."""
        if self.local_ip and self.root:
            text = f"{self.local_ip}:{self.port}"
            self.root.clipboard_clear()
            self.root.clipboard_append(text)
            self.copy_btn.configure(text="Copied!")
            self.root.after(1000, lambda: self.copy_btn.configure(text="Copy IP"))

    def _on_device_selected(self, choice: str):
        """Handle device selection."""
        if self.on_device_change and self.devices:
            for dev in self.devices:
                name = dev['name']
                if dev['is_virtual']:
                    name = f"★ {name}"
                if name == choice:
                    self.selected_device = dev['id']
                    self.on_device_change(dev['id'])
                    break

    def _on_close(self):
        """Handle window close."""
        self.running = False
        if self.on_quit:
            self.on_quit()
        if self.root:
            self.root.quit()
            self.root.destroy()

    def _on_show_setup(self):
        """Handle setup button click."""
        if self.on_show_setup:
            self.on_show_setup()

    def _set_icon(self):
        """Set the window icon."""
        try:
            # Try to find icon in various locations
            icon_paths = [
                os.path.join(os.path.dirname(sys.executable), 'icon.ico'),
                os.path.join(os.path.dirname(__file__), '..', 'icon.ico'),
                os.path.join(os.path.dirname(__file__), 'icon.ico'),
                'icon.ico'
            ]

            for icon_path in icon_paths:
                if os.path.exists(icon_path):
                    self.root.iconbitmap(icon_path)
                    return

        except Exception:
            pass  # Icon is optional, continue without it

    def _on_volume_changed(self, value: float):
        """Handle volume slider change."""
        volume = value / 100.0  # Convert 0-200 to 0.0-2.0
        if self.volume_label:
            self.volume_label.configure(text=f"{int(value)}%")
        if self.on_volume_change:
            self.on_volume_change(volume)

    def set_connection_info(self, ip: str, port: int):
        """Set connection info (thread-safe)."""
        self.local_ip = ip
        self.port = port

        if self.root and self.ip_label:
            # Use after() for thread safety
            self.root.after(0, lambda: self._do_set_connection_info(ip, port))
        else:
            self._pending_connection_info = (ip, port)

    def _do_set_connection_info(self, ip: str, port: int):
        """Actually update the UI (must be called from main thread)."""
        if self.ip_label:
            self.ip_label.configure(text=f"{ip}:{port}")

    def set_devices(self, devices: List[dict], selected: Optional[int] = None):
        """Set devices list (thread-safe)."""
        self.devices = devices
        self.selected_device = selected

        if self.root and self.device_menu:
            self.root.after(0, lambda: self._do_set_devices(devices, selected))
        else:
            self._pending_devices = (devices, selected)

    def _do_set_devices(self, devices: List[dict], selected: Optional[int]):
        """Actually update device menu (must be called from main thread)."""
        if not self.device_menu:
            return

        names = []
        selected_name = None

        for dev in devices:
            name = dev['name']
            if dev['is_virtual']:
                name = f"★ {name}"
            names.append(name)
            if dev['id'] == selected:
                selected_name = name

        if names:
            self.device_menu.configure(values=names)
            if selected_name:
                self.device_menu.set(selected_name)
            else:
                self.device_menu.set(names[0])
        else:
            self.device_menu.configure(values=["No devices found"])
            self.device_menu.set("No devices found")

    def update_status(self, connected: bool, client_ip: Optional[str] = None):
        """Update connection status (thread-safe)."""
        self.is_connected = connected
        self.client_ip = client_ip

        if self.root:
            self.root.after(0, lambda: self._do_update_status(connected, client_ip))

    def _do_update_status(self, connected: bool, client_ip: Optional[str]):
        """Actually update status (must be called from main thread)."""
        if self.status_dot and self.status_label:
            if connected:
                self.status_dot.configure(text_color="#4ADE80")
                self.status_label.configure(text=f"Connected: {client_ip}")
            else:
                self.status_dot.configure(text_color="gray")
                self.status_label.configure(text="Waiting for connection...")

    def update_level(self, level: float):
        """Update audio level (thread-safe)."""
        self.audio_level = level
        if self.root and self.level_bar:
            self.root.after(0, lambda: self.level_bar.set(min(1.0, level)))

    def run(self):
        """Run the window."""
        self.create_window()
        self.running = True
        self.root.mainloop()

    def stop(self):
        """Stop the window."""
        self.running = False
        if self.root:
            try:
                self.root.quit()
            except:
                pass
