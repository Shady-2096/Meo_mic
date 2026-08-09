"""
Meo Mic - main window.

A compact utility panel, built to match the Mac app: one status line, a live
waveform, and a single grouped card holding the two things anyone ever sets.

Two shapes. Waiting: the address sits in a field you can copy or scan, and
there is no waveform, because a meter with nothing to meter is noise. Live: the
waveform replaces it. The audio route is not diagrammed anywhere - it only
matters when it is broken, and then it is one line under the card.

The window is exactly as tall as whichever shape is on screen. It measures
itself rather than carrying a table of hard-coded heights, because every time
that table drifted from the layout the result was a strip of dead grey above
the footer.
"""

from __future__ import annotations

import math
import os
import sys
from typing import Callable, List, Optional

import customtkinter as ctk

from . import theme as t
from .widgets import StatusGlyph, Waveform, card, hairline, micro_label, separator

# Follows the Windows light/dark setting, the same way the Mac app follows the
# system appearance. Read once at startup; switching themes needs a restart.
ctk.set_appearance_mode("System")

WIDTH = 420
CONTENT = WIDTH - 2 * t.PAD
MIN_HEIGHT = 300
ROW_H = 44          # a settings row, tall enough to hit without aiming


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

        # Devices
        self.devices: List[dict] = []
        self.selected_device: Optional[int] = None
        self._device_ids: dict = {}     # menu label -> device id
        self._pending_devices: Optional[tuple] = None
        self._pending_connection_info: Optional[tuple] = None

        # Widgets
        self.status_dot: Optional[StatusGlyph] = None
        self.status_headline: Optional[ctk.CTkLabel] = None
        self.status_detail: Optional[ctk.CTkLabel] = None
        self.voice: Optional[Waveform] = None
        self.voice_block: Optional[ctk.CTkFrame] = None
        self.pairing_card: Optional[ctk.CTkFrame] = None
        self.address_label: Optional[ctk.CTkLabel] = None
        self.port_label: Optional[ctk.CTkLabel] = None
        self.device_menu: Optional[ctk.CTkOptionMenu] = None
        self.device_note: Optional[ctk.CTkLabel] = None
        self.copy_btn: Optional[ctk.CTkButton] = None
        self.qr_btn: Optional[ctk.CTkButton] = None
        self.volume_slider: Optional[ctk.CTkSlider] = None
        self.volume_label: Optional[ctk.CTkLabel] = None

        self._qr_frame: Optional[ctk.CTkFrame] = None
        self._qr_visible = False
        self._qr_image = None
        self._height: Optional[int] = None
        # Which of the two shapes is on screen. Tracked explicitly rather than
        # read back with winfo_ismapped(), which reports False until the
        # geometry manager has run - so at startup both would show.
        self._shape: Optional[str] = None

    # ------------------------------------------------------------------ #
    # Construction
    # ------------------------------------------------------------------ #

    def create_window(self):
        self.root = ctk.CTk(fg_color=t.WINDOW)
        self.root.title("Meo Mic")
        self.root.geometry(f"{WIDTH}x{MIN_HEIGHT}")
        self.root.resizable(False, False)

        self._set_icon()

        self.root.protocol("WM_DELETE_WINDOW", self._on_close)

        self._build_footer()   # packed to the bottom first
        self._build_status()
        self._build_voice()
        self._build_pairing()
        self._build_settings()

        self.voice.start()
        self._apply_pending_data()
        self._do_update_status(self.is_connected, self.client_ip)
        self._centre()

    def _centre(self):
        height = self._content_height()
        scale = self._scaling()
        x = int((self.root.winfo_screenwidth() / scale - WIDTH) // 2)
        y = int((self.root.winfo_screenheight() / scale - height) // 2)
        self.root.geometry(f"{WIDTH}x{height}+{max(0, x)}+{max(0, y)}")

    # -- status ---------------------------------------------------------- #

    def _build_status(self):
        block = ctk.CTkFrame(self.root, fg_color="transparent")
        block.pack(fill="x", padx=t.PAD, pady=(t.XL, 0))

        self.status_dot = StatusGlyph(block)
        self.status_dot.pack(side="left", padx=(0, t.MD + 2))

        text_col = ctk.CTkFrame(block, fg_color="transparent")
        text_col.pack(side="left", fill="x", expand=True)

        self.status_headline = ctk.CTkLabel(
            text_col,
            text="Waiting for your phone",
            font=t.font("title", 20, "bold"),
            text_color=t.TEXT,
            anchor="w",
        )
        self.status_headline.pack(fill="x")

        self.status_detail = ctk.CTkLabel(
            text_col,
            text="Open Meo Mic on your phone - it'll find this PC.",
            font=t.font("body", 12),
            text_color=t.TEXT_SECONDARY,
            anchor="w",
            justify="left",
            wraplength=CONTENT - StatusGlyph.SIZE - t.MD - 2,
        )
        self.status_detail.pack(fill="x", pady=(2, 0))

    # -- waveform -------------------------------------------------------- #

    def _build_voice(self):
        self.voice_block = ctk.CTkFrame(self.root, fg_color="transparent")
        self.voice_block.pack(fill="x", padx=t.PAD, pady=(t.LG, 0))
        self.voice = Waveform(self.voice_block, width=CONTENT)
        self.voice.pack(fill="x")

    # -- pairing --------------------------------------------------------- #

    def _build_pairing(self):
        """The address as a field, not a headline. Reading it off the screen
        into a phone is the actual task."""
        self.pairing_card = ctk.CTkFrame(self.root, fg_color="transparent")
        self.pairing_card.pack(fill="x", padx=t.PAD, pady=(t.LG, 0))

        field = card(self.pairing_card)
        field.pack(fill="x")

        inner = ctk.CTkFrame(field, fg_color="transparent")
        inner.pack(fill="x", padx=t.MD + 2, pady=t.MD)

        micro_label(inner, "On this network").pack(fill="x", pady=(0, 5))

        line = ctk.CTkFrame(inner, fg_color="transparent")
        line.pack(fill="x")

        self.address_label = ctk.CTkLabel(
            line,
            text="Looking for your network...",
            font=t.font("address", 17, "bold"),
            text_color=t.TEXT,
            anchor="w",
        )
        self.address_label.pack(side="left")

        # The port is the same every time and nobody reads it twice; it is kept
        # because you have to type it, and dimmed because it is not the part
        # you are looking for.
        self.port_label = ctk.CTkLabel(
            line,
            text="",
            font=t.font("address", 17),
            text_color=t.TEXT_TERTIARY,
            anchor="w",
        )
        self.port_label.pack(side="left")

        self.qr_btn = self._quiet_button(line, "QR", self._toggle_qr, width=44)
        self.qr_btn.pack(side="right")

        self.copy_btn = self._tinted_button(line, "Copy", self._copy_ip, width=62)
        self.copy_btn.pack(side="right", padx=(0, t.SM - 2))

        ctk.CTkLabel(
            self.pairing_card,
            text="Or tap Search for PC on your phone.",
            font=t.font("label", 12),
            text_color=t.TEXT_TERTIARY,
            anchor="w",
        ).pack(fill="x", padx=t.XS, pady=(t.SM + 2, 0))

        self._qr_frame = ctk.CTkFrame(self.pairing_card, fg_color="transparent")

    # -- settings -------------------------------------------------------- #

    def _build_settings(self):
        block = ctk.CTkFrame(self.root, fg_color="transparent")
        block.pack(fill="x", padx=t.PAD, pady=(t.GUTTER, 0))

        group = card(block)
        group.pack(fill="x")

        output_row = self._settings_row(group)

        ctk.CTkLabel(
            output_row,
            text="Output",
            font=t.font("body", 13),
            text_color=t.TEXT,
            anchor="w",
        ).pack(side="left")

        self.device_menu = ctk.CTkOptionMenu(
            output_row,
            values=["No devices found"],
            width=214,
            height=30,
            corner_radius=t.RADIUS,
            font=t.font("body", 12),
            dropdown_font=t.font("body", 12),
            fg_color=t.CONTROL,
            button_color=t.CONTROL,
            button_hover_color=t.CONTROL_HOVER,
            text_color=t.TEXT,
            dropdown_fg_color=t.CARD,
            dropdown_hover_color=t.ACCENT_SOFT,
            dropdown_text_color=t.TEXT,
            anchor="w",
            command=self._on_device_selected,
        )
        self.device_menu.pack(side="right")

        separator(group).pack(fill="x", padx=t.MD + 2)

        volume_row = self._settings_row(group)

        ctk.CTkLabel(
            volume_row,
            text="Volume",
            font=t.font("body", 13),
            text_color=t.TEXT,
            anchor="w",
        ).pack(side="left")

        self.volume_label = ctk.CTkLabel(
            volume_row,
            text="100%",
            font=t.font("label", 12),
            text_color=t.TEXT_SECONDARY,
            width=40,
            anchor="e",
        )
        self.volume_label.pack(side="right")

        self.volume_slider = ctk.CTkSlider(
            volume_row,
            from_=0,
            to=200,
            number_of_steps=200,
            width=166,
            height=16,
            corner_radius=8,
            border_width=5,          # thins the groove without thinning the knob
            button_length=0,
            button_corner_radius=8,
            fg_color=t.TRACK,
            border_color=t.CARD,
            progress_color=t.ACCENT,
            button_color=t.ACCENT,
            button_hover_color=t.ACCENT_HOVER,
            command=self._on_volume_changed,
        )
        self.volume_slider.pack(side="right", padx=(0, t.MD))
        self.volume_slider.set(100)

        self.device_note = ctk.CTkLabel(
            block,
            text="",
            font=t.font("label", 12),
            text_color=t.TEXT_TERTIARY,
            anchor="w",
            justify="left",
            wraplength=CONTENT - t.SM,
        )
        self.device_note.pack(fill="x", padx=t.XS, pady=(t.SM + 2, 0))

    def _settings_row(self, parent) -> ctk.CTkFrame:
        """A fixed-height row inside the grouped card.

        Fixed, so the two rows are the same height whatever ends up in them -
        a dropdown and a slider have no reason to agree on their own.
        """
        row = ctk.CTkFrame(parent, fg_color="transparent", height=ROW_H)
        row.pack(fill="x", padx=t.MD + 2)
        row.pack_propagate(False)
        return row

    # -- footer ---------------------------------------------------------- #

    def _build_footer(self):
        footer = ctk.CTkFrame(self.root, fg_color="transparent")
        footer.pack(side="bottom", fill="x", padx=t.PAD - t.SM, pady=(t.MD, t.MD + 2))

        self._link_button(footer, "Audio setup", self._on_show_setup).pack(side="left")
        self._link_button(footer, "Quit", self._on_close).pack(side="right")

        # The rule floats a clear gap below the last block, so it reads as the
        # edge of the window's chrome rather than as another card border.
        hairline(self.root, t.SEPARATOR).pack(
            side="bottom", fill="x", padx=t.PAD, pady=(t.XL, 0)
        )

    def _tinted_button(self, parent, text: str, command, width: int = 62) -> ctk.CTkButton:
        """The one action in the window worth pointing at."""
        return ctk.CTkButton(
            parent,
            text=text,
            width=width,
            height=30,
            corner_radius=t.RADIUS,
            font=t.font("label", 12),
            fg_color=t.ACCENT_SOFT,
            hover_color=t.ACCENT_SOFT_HOVER,
            text_color=t.ACCENT,
            command=command,
        )

    def _quiet_button(self, parent, text: str, command, width: int = 52) -> ctk.CTkButton:
        """A secondary action: an outline, so it reads as a button without
        competing with the tinted one beside it."""
        return ctk.CTkButton(
            parent,
            text=text,
            width=width,
            height=30,
            corner_radius=t.RADIUS,
            font=t.font("label", 12),
            fg_color="transparent",
            hover_color=t.CONTROL_HOVER,
            border_width=1,
            border_color=t.BORDER,
            text_color=t.TEXT_SECONDARY,
            command=command,
        )

    def _link_button(self, parent, text: str, command) -> ctk.CTkButton:
        return ctk.CTkButton(
            parent,
            text=text,
            width=1,
            height=28,
            corner_radius=t.RADIUS_SM,
            font=t.font("label", 12),
            fg_color="transparent",
            hover_color=t.CARD_HOVER,
            text_color=t.TEXT_SECONDARY,
            command=command,
        )

    # ------------------------------------------------------------------ #
    # Window shape
    # ------------------------------------------------------------------ #

    def _scaling(self) -> float:
        """The display scale CustomTkinter is drawing at.

        Everything measured off a Tk widget comes back in real pixels, while
        ``geometry()`` on a CTk window takes the unscaled kind and multiplies
        it back up. Mixing the two on a 200% display asks for a window twice
        the height of the screen, which Windows then silently clamps - which is
        exactly the dead grey strip this measuring was meant to remove.
        """
        try:
            return ctk.ScalingTracker.get_window_scaling(self.root) or 1.0
        except Exception:
            return 1.0

    def _content_height(self) -> int:
        """How tall the window has to be for everything packed into it."""
        self.root.update_idletasks()
        wanted = math.ceil(self.root.winfo_reqheight() / self._scaling())
        return max(MIN_HEIGHT, wanted)

    def _resize_window(self):
        """The window is as tall as the state needs, and no taller."""
        if not self.root:
            return
        height = self._content_height()
        if height != self._height:
            self._height = height
            self.root.geometry(f"{WIDTH}x{height}")

    # ------------------------------------------------------------------ #
    # QR
    # ------------------------------------------------------------------ #

    def _toggle_qr(self):
        if self._qr_visible:
            self._qr_frame.pack_forget()
            for child in self._qr_frame.winfo_children():
                child.destroy()
            self.qr_btn.configure(text="QR")
            self._qr_visible = False
            self._resize_window()
            return

        image = self._render_qr()
        if image is None:
            self.device_note.configure(
                text="No address yet, so there is nothing to scan.", text_color=t.WARN
            )
            return

        holder = ctk.CTkFrame(self._qr_frame, fg_color="#FFFFFF", corner_radius=t.RADIUS)
        holder.pack(anchor="w")
        ctk.CTkLabel(holder, image=image, text="").pack(padx=12, pady=12)

        ctk.CTkLabel(
            self._qr_frame,
            text="Tap Scan QR Code on your phone.",
            font=t.font("label", 12),
            text_color=t.TEXT_TERTIARY,
            anchor="w",
        ).pack(fill="x", padx=t.XS, pady=(t.SM, 0))

        self._qr_frame.pack(fill="x", pady=(t.MD, 0))
        self.qr_btn.configure(text="Hide")
        self._qr_visible = True
        self._resize_window()

    def _render_qr(self):
        if not self.local_ip:
            return None
        try:
            import qrcode
            from PIL import Image

            qr = qrcode.QRCode(version=1, border=0, box_size=4,
                               error_correction=qrcode.constants.ERROR_CORRECT_M)
            qr.add_data(f"meomic://{self.local_ip}:{self.port}")
            qr.make(fit=True)

            # Always black on white, in both appearances: a scanner needs the
            # contrast, and a QR that fails to read is not a design decision
            # worth having.
            img = qr.make_image(fill_color="#000000", back_color="#FFFFFF").convert("RGB")
            img = img.resize((132, 132), Image.Resampling.NEAREST)

            self._qr_image = ctk.CTkImage(light_image=img, dark_image=img, size=(132, 132))
            return self._qr_image
        except Exception:
            return None

    # ------------------------------------------------------------------ #
    # Events
    # ------------------------------------------------------------------ #

    def _copy_ip(self):
        if self.local_ip and self.root:
            self.root.clipboard_clear()
            self.root.clipboard_append(f"{self.local_ip}:{self.port}")
            self.copy_btn.configure(text="Copied")
            self.root.after(1400, lambda: self.copy_btn.configure(text="Copy"))

    def _on_device_selected(self, choice: str):
        device_id = self._device_ids.get(choice)
        if device_id is None:
            return
        self.selected_device = device_id
        self._update_device_note()
        self._update_status_text()
        if self.on_device_change:
            self.on_device_change(device_id)

    def _on_volume_changed(self, value: float):
        if self.volume_label:
            self.volume_label.configure(text=f"{int(value)}%")
        if self.on_volume_change:
            self.on_volume_change(value / 100.0)

    def _on_close(self):
        self.running = False
        if self.voice:
            self.voice.stop()
        if self.status_dot:
            self.status_dot.stop()
        if self.on_quit:
            self.on_quit()
        if self.root:
            self.root.quit()
            self.root.destroy()

    def _on_show_setup(self):
        if self.on_show_setup:
            self.on_show_setup()

    def _set_icon(self):
        try:
            candidates = [
                os.path.join(os.path.dirname(sys.executable), "icon.ico"),
                os.path.join(os.path.dirname(__file__), "..", "icon.ico"),
                os.path.join(os.path.dirname(__file__), "icon.ico"),
                "icon.ico",
            ]
            for path in candidates:
                if os.path.exists(path):
                    self.root.iconbitmap(path)
                    return
        except Exception:
            pass

    # ------------------------------------------------------------------ #
    # Public API
    # ------------------------------------------------------------------ #

    def _apply_pending_data(self):
        if self._pending_connection_info:
            ip, port = self._pending_connection_info
            self._do_set_connection_info(ip, port)
            self._pending_connection_info = None

        if self._pending_devices:
            devices, selected = self._pending_devices
            self._do_set_devices(devices, selected)
            self._pending_devices = None

    def set_connection_info(self, ip: str, port: int):
        self.local_ip = ip
        self.port = port
        if self.root and self.address_label:
            self.root.after(0, lambda: self._do_set_connection_info(ip, port))
        else:
            self._pending_connection_info = (ip, port)

    def _do_set_connection_info(self, ip: str, port: int):
        # These handlers own the state they render. Letting the caller set it
        # first means any path that does not go through the public setter
        # renders against stale values.
        self.local_ip = ip
        self.port = port
        if self.address_label:
            self.address_label.configure(text=ip)
        if self.port_label:
            self.port_label.configure(text=f":{port}")
        self._update_status_text()
        self._resize_window()

    # -- device names ---------------------------------------------------- #

    @staticmethod
    def _short_name(name: str) -> str:
        """The part of a device name a person would say out loud.

        Windows device names carry the driver's own parenthetical - "CABLE In
        16ch (VB-Audio Virtual Cable)" - which is the half that never fits and
        never helps. Dropping it is the difference between a row that reads and
        a row that gets sliced off mid-word.
        """
        head = name.split("(")[0].strip(" -") or name.strip()
        return head if len(head) <= 30 else head[:29].rstrip() + "…"

    def _device_label(self, dev: dict) -> str:
        return self._short_name(dev["name"])

    def set_devices(self, devices: List[dict], selected: Optional[int] = None):
        self.devices = devices
        self.selected_device = selected
        if self.root and self.device_menu:
            self.root.after(0, lambda: self._do_set_devices(devices, selected))
        else:
            self._pending_devices = (devices, selected)

    def _do_set_devices(self, devices: List[dict], selected: Optional[int]):
        self.devices = devices
        self.selected_device = selected
        if not self.device_menu:
            return

        # Shortening names can collide - two "Speakers" from different drivers
        # is normal on Windows - so the menu keeps its own label-to-id map and
        # numbers any duplicates rather than silently routing to the first one.
        names, selected_name, seen = [], None, {}
        self._device_ids = {}
        for dev in devices:
            label = self._device_label(dev)
            seen[label] = seen.get(label, 0) + 1
            if seen[label] > 1:
                label = f"{label} ({seen[label]})"
            names.append(label)
            self._device_ids[label] = dev["id"]
            if dev["id"] == selected:
                selected_name = label

        if names:
            self.device_menu.configure(values=names)
            self.device_menu.set(selected_name or names[0])
        else:
            self.device_menu.configure(values=["No devices found"])
            self.device_menu.set("No devices found")

        self._update_device_note()
        self._update_status_text()
        self._resize_window()

    def _current_device(self) -> Optional[dict]:
        return next((d for d in self.devices if d["id"] == self.selected_device), None)

    def _update_device_note(self):
        """Say what this choice means, in the user's words - not the driver's.

        Silent when the route is fine: the window should not narrate success.
        """
        if not self.device_note:
            return

        current = self._current_device()

        if current is None:
            self.device_note.configure(
                text="Choose a virtual audio device so call apps can hear you.",
                text_color=t.WARN,
            )
        elif current["is_virtual"]:
            self.device_note.configure(
                text=f"Pick {self._short_name(current['name'])} as your microphone "
                     f"in Discord, Zoom or Meet.",
                text_color=t.TEXT_TERTIARY,
            )
        else:
            self.device_note.configure(
                text=f"{self._short_name(current['name'])} plays out loud - "
                     f"apps can't use it as a mic.",
                text_color=t.WARN,
            )

    def update_status(self, connected: bool, client_ip: Optional[str] = None):
        self.is_connected = connected
        self.client_ip = client_ip
        if self.root:
            self.root.after(0, lambda: self._do_update_status(connected, client_ip))

    def _do_update_status(self, connected: bool, client_ip: Optional[str]):
        self.is_connected = connected
        self.client_ip = client_ip
        if self.status_dot:
            self.status_dot.set_live(connected)
        if self.voice:
            self.voice.set_connected(connected)

        # Waiting asks a question the address field answers; live does not.
        # And a waveform with nothing to meter is noise, so it goes the other
        # way: exactly one of the two is on screen at any time.
        wanted = "live" if connected else "waiting"
        if self.pairing_card and self.voice_block and self._shape != wanted:
            self.pairing_card.pack_forget()
            self.voice_block.pack_forget()
            shown = self.voice_block if connected else self.pairing_card
            shown.pack(fill="x", padx=t.PAD, pady=(t.LG, 0), after=self._status_anchor())
            self._shape = wanted

        self._update_status_text()
        self._resize_window()

    def _status_anchor(self):
        """Both shapes sit directly under the status line."""
        return self.status_dot.master if self.status_dot else None

    def _update_status_text(self):
        if not self.status_headline or not self.status_detail:
            return

        if self.is_connected:
            self.status_headline.configure(text="Your phone is live")
            device = self._current_device()
            where = self.client_ip or "your phone"
            if device and device["is_virtual"]:
                detail = f"From {where} · {self._short_name(device['name'])}"
            else:
                detail = f"From {where}"
            self.status_detail.configure(text=detail, text_color=t.TEXT_SECONDARY)
        else:
            self.status_headline.configure(text="Waiting for your phone")
            if self.local_ip:
                detail = "Open Meo Mic on your phone - it'll find this PC."
            else:
                detail = "Connect this PC to Wi-Fi first."
            self.status_detail.configure(text=detail, text_color=t.TEXT_SECONDARY)

    def update_level(self, level: float):
        """Legacy 0..1 RMS input, kept for callers that still use it."""
        self.audio_level = level
        db = 20.0 * math.log10(max(level * 10000.0 / 32768.0, 1e-6))
        self.update_level_db(db)

    def update_level_db(self, db: float):
        """Feed the waveform peak dBFS."""
        if self.voice:
            self.voice.set_db(db)

    def run(self):
        self.create_window()
        self.running = True
        self.root.mainloop()

    def stop(self):
        self.running = False
        if self.voice:
            self.voice.stop()
        if self.status_dot:
            self.status_dot.stop()
        if self.root:
            try:
                self.root.quit()
            except Exception:
                pass
