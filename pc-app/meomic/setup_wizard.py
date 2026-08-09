"""
Meo Mic - First-Run Setup Wizard

One-click VB-Cable installation, with the manual steps kept as a fallback.

Styled from the same tokens as the main window, and for the same reason: this
is the second window most people ever see, and a setup screen that looks like
it came from a different app is the fastest way to make an install feel
unofficial.
"""

import math
import os
import threading
import webbrowser
from typing import Callable, List, Optional

import customtkinter as ctk

from . import theme as t
from . import vbcable
from .widgets import card as make_card

ACCENT = t.ACCENT
GREEN = t.LIVE
RED = t.ERROR
AMBER = t.WARN
MUTED = t.TEXT_TERTIARY

WIDTH = 520
# Window gutter, then the scroll area's own track, then the card's padding.
# Miss the scrollbar out of this sum and every paragraph runs a word past the
# right edge of its card.
BODY_WRAP = WIDTH - 2 * 25 - 24 - 2 * 16


class SetupWizard:
    """Setup wizard to guide users through VB-Cable installation."""

    VB_CABLE_URL = vbcable.DOWNLOAD_PAGE_URL

    def __init__(self):
        self.window: Optional[ctk.CTkToplevel] = None
        self.on_complete: Optional[Callable] = None
        self.on_skip: Optional[Callable] = None

        self.status_label: Optional[ctk.CTkLabel] = None
        self.continue_btn: Optional[ctk.CTkButton] = None
        self.install_btn: Optional[ctk.CTkButton] = None
        self.restart_btn: Optional[ctk.CTkButton] = None
        self.progress_bar: Optional[ctk.CTkProgressBar] = None
        self.progress_label: Optional[ctk.CTkLabel] = None
        self.manual_frame: Optional[ctk.CTkFrame] = None
        self.manual_toggle: Optional[ctk.CTkButton] = None
        self.scroll: Optional[ctk.CTkScrollableFrame] = None
        self._blocks: List[ctk.CTkBaseClass] = []

        self._manual_visible = False
        self._installing = False
        self._cancel = threading.Event()

    # ------------------------------------------------------------------ #
    # Detection helpers (kept for callers outside this module)
    # ------------------------------------------------------------------ #

    @staticmethod
    def find_virtual_devices() -> List[dict]:
        """Find virtual audio devices."""
        return vbcable.find_virtual_output_devices()

    @staticmethod
    def needs_setup() -> bool:
        """Check if setup wizard should be shown."""
        return len(SetupWizard.find_virtual_devices()) == 0

    # ------------------------------------------------------------------ #
    # Window
    # ------------------------------------------------------------------ #

    def show(self, parent: ctk.CTk):
        """Show the setup wizard window."""
        self.window = ctk.CTkToplevel(parent, fg_color=t.WINDOW)
        self.window.title("Meo Mic - Audio setup")
        self.window.geometry(f"{WIDTH}x600")
        self.window.resizable(False, False)
        self.window.transient(parent)
        self.window.protocol("WM_DELETE_WINDOW", self._on_skip)
        self._set_icon()

        main = ctk.CTkFrame(self.window, fg_color="transparent")
        main.pack(fill="both", expand=True, padx=25, pady=(22, 20))

        ctk.CTkLabel(
            main,
            text="Make your phone the microphone",
            font=t.font("title", 20, "bold"),
            text_color=t.TEXT,
            anchor="w",
        ).pack(fill="x")

        ctk.CTkLabel(
            main,
            text="One-time setup. Takes about a minute.",
            font=t.font("body", 12),
            text_color=t.TEXT_SECONDARY,
            anchor="w",
        ).pack(fill="x", pady=(3, 18))

        # The scrollbar is only ever needed once the manual steps are open, so
        # it is tinted down to a hairline rather than sitting there as a grey
        # slab beside content that already fits.
        self.scroll = ctk.CTkScrollableFrame(
            main,
            fg_color="transparent",
            height=420,
            scrollbar_button_color=t.BORDER,
            scrollbar_button_hover_color=t.CONTROL_HOVER,
        )
        self.scroll.pack(fill="both", expand=True)

        # The action first, the explanation under it. A display scaled to 200%
        # leaves so little height that something has to fall below the fold,
        # and it should be the paragraph nobody reads twice - not the button
        # this window exists to offer.
        self._build_install_card(self.scroll)
        self._build_why_card(self.scroll)
        self._build_manual_section(self.scroll)

        self._build_bottom_bar(main)

        self._refresh_state()
        self._fit(parent)
        self.window.grab_set()

    # -- geometry -------------------------------------------------------- #

    def _scaling(self) -> float:
        try:
            return ctk.ScalingTracker.get_window_scaling(self.window) or 1.0
        except Exception:
            return 1.0

    def _fit(self, parent):
        """Size the window to the collapsed content, then centre it on *parent*.

        The scrolling area is set to exactly what is in it, so the wizard opens
        without a strip of empty grey under the last card; opening the manual
        steps scrolls inside that area instead of growing the window past the
        bottom of the screen.
        """
        win = self.window
        win.update_idletasks()
        scale = self._scaling()

        def points(widget) -> int:
            return math.ceil(widget.winfo_reqheight() / scale)

        screen = win.winfo_screenheight() / scale
        # Everything that is not the scrolling area, measured rather than
        # guessed at - a display scaled to 200% leaves barely 500 points of
        # height to spend, and a guess that is 30 out costs a whole card.
        chrome = points(win) - self.scroll.cget("height")

        # Where each card ends, so the visible area can stop at one of them.
        edges, running = [], 0
        for block in self._blocks:
            running += points(block) + 15
            edges.append(running)

        room = int(screen * 0.90) - chrome
        if edges[-1] <= room:
            height = edges[-1]
        else:
            # Not everything fits. Cut at the last card edge that does, so the
            # boundary reads as the end of a section rather than as a heading
            # sliced through the middle.
            fits = [edge for edge in edges if edge <= room]
            height = fits[-1] if fits else room
        self.scroll.configure(height=max(200, height))

        win.update_idletasks()
        height = min(points(win), int(screen * 0.92))

        x = parent.winfo_x() + (parent.winfo_width() - WIDTH) // 2
        y = parent.winfo_y() + (parent.winfo_height() - height) // 2
        win.geometry(f"{WIDTH}x{height}+{max(0, x)}+{max(0, y)}")

    def _set_icon(self):
        """A Toplevel does not inherit the app icon, and a stray blue default
        in the taskbar is the tell that this window came from somewhere else."""
        try:
            here = os.path.dirname(__file__)
            for path in (os.path.join(here, "..", "icon.ico"),
                         os.path.join(here, "icon.ico"),
                         "icon.ico"):
                if os.path.exists(path):
                    self.window.after(200, lambda p=path: self.window.iconbitmap(p))
                    return
        except Exception:
            pass

    def _card_title(self, parent, text: str):
        return ctk.CTkLabel(
            parent,
            text=text,
            font=t.font("title", 15, "bold"),
            text_color=t.TEXT,
            anchor="w",
        )

    def _card_body(self, parent, text: str, color=None, size: int = 12):
        """Body copy set by wraplength, not by hand-placed newlines.

        The old copy carried its own line breaks, which meant every wording
        change was also a typesetting change - and any of them being one word
        too long left a ragged half-line in the middle of the card.
        """
        return ctk.CTkLabel(
            parent,
            text=text,
            font=t.font("body", size),
            text_color=color or t.TEXT_SECONDARY,
            justify="left",
            anchor="w",
            wraplength=BODY_WRAP,
        )

    def _build_why_card(self, parent):
        card = make_card(parent)
        card.pack(fill="x", pady=(0, 15))
        self._blocks.append(card)

        self._card_title(card, "Why is this needed?").pack(pady=(14, 6), padx=16, fill="x")
        self._card_body(
            card,
            "Windows has no built-in way for an app to appear as a microphone. "
            "VB-Cable adds a virtual audio device: Meo Mic plays your phone into "
            "it, and Discord, Zoom or Meet pick it up as a normal mic.",
        ).pack(pady=(0, 14), padx=16, fill="x")

    def _build_install_card(self, parent):
        card = make_card(parent)
        card.pack(fill="x", pady=(0, 15))
        self._blocks.append(card)

        self._card_title(card, "Install VB-Cable").pack(pady=(16, 6), padx=16, fill="x")

        if vbcable.can_auto_install():
            blurb = ("Meo Mic downloads the official installer (about 1.4 MB), checks "
                     "its signature and runs it. Windows will ask for permission.")
        else:
            blurb = ("Automatic installation is available on Windows only. On this "
                     "platform, install a virtual audio device manually.")

        self._card_body(card, blurb).pack(pady=(0, 14), padx=16, fill="x")

        self.install_btn = self._primary_button(
            card, "Install VB-Cable", self._start_install, width=200
        )
        self.install_btn.pack(pady=(0, 10), padx=16, anchor="w")

        self.restart_btn = self._primary_button(
            card, "Restart now", self._restart_now, width=200
        )
        # Shown only once an install is waiting on a reboot.

        self.progress_bar = ctk.CTkProgressBar(
            card, height=5, corner_radius=3,
            fg_color=t.TRACK, progress_color=t.ACCENT,
        )
        self.progress_bar.set(0)

        self.progress_label = self._card_body(card, "", size=11)

        # The credit and the link are one line, not a paragraph and a link. Both
        # need to be here; neither is what anyone opened this window to read.
        self._card_body(
            card,
            "VB-CABLE is donationware by VB-Audio (Vincent Burel), not bundled or "
            "modified by Meo Mic. You accept their terms in their own installer.",
            color=t.TEXT_TERTIARY,
            size=11,
        ).pack(pady=(2, 3), padx=16, fill="x")

        link = ctk.CTkLabel(
            card,
            text="vb-audio.com/Cable",
            font=t.font("body", 11),
            text_color=t.ACCENT,
            cursor="hand2",
            anchor="w",
        )
        link.pack(pady=(0, 14), padx=16, fill="x")
        link.bind("<Button-1>", lambda _event: self._open_download())

    def _build_manual_section(self, parent):
        self.manual_toggle = ctk.CTkButton(
            parent,
            text="Install manually instead  ▸",
            height=32,
            corner_radius=t.RADIUS,
            font=t.font("body", 12),
            fg_color="transparent",
            hover_color=t.CARD_HOVER,
            anchor="w",
            text_color=t.TEXT_SECONDARY,
            command=self._toggle_manual
        )
        self.manual_toggle.pack(fill="x", pady=(0, 5))
        self._blocks.append(self.manual_toggle)

        self.manual_frame = make_card(parent)

        steps = [
            ("1", "Download VB-Cable", "Opens vb-audio.com/Cable in your browser"),
            ("2", "Extract the ZIP file", "Right-click the downloaded file → Extract All"),
            ("3", "Run the right installer",
             "64-bit Windows: VBCABLE_Setup_x64.exe\n32-bit Windows: VBCABLE_Setup.exe"),
            ("4", "Run as Administrator",
             "Right-click the installer → 'Run as administrator' → Install Driver"),
            ("5", "Restart your PC", "Required before Windows shows the new device"),
        ]

        for number, title, detail in steps:
            self._create_step(self.manual_frame, number, title, detail).pack(
                fill="x", padx=16, pady=7
            )

        self._secondary_button(
            self.manual_frame, "Open download page", self._open_download, width=190
        ).pack(pady=(4, 16), padx=16, anchor="w")

    def _create_step(self, parent, number: str, title: str, details: str) -> ctk.CTkFrame:
        """Create a step frame with number, title, and details."""
        frame = ctk.CTkFrame(parent, fg_color="transparent")

        header = ctk.CTkFrame(frame, fg_color="transparent")
        header.pack(fill="x")

        # A tinted counter, not a solid accent chip: five saturated dots down
        # the side of a card is a lot of colour for something you read once.
        ctk.CTkLabel(
            header,
            text=number,
            font=t.font("body", 12, "bold"),
            width=26,
            height=26,
            corner_radius=13,
            fg_color=t.ACCENT_SOFT,
            text_color=t.ACCENT,
        ).pack(side="left", padx=(0, 10))

        ctk.CTkLabel(
            header,
            text=title,
            font=t.font("body", 13, "bold"),
            text_color=t.TEXT,
            anchor="w"
        ).pack(side="left", fill="x", expand=True)

        if details:
            ctk.CTkLabel(
                frame,
                text=details,
                font=t.font("body", 12),
                text_color=t.TEXT_SECONDARY,
                anchor="w",
                justify="left",
                wraplength=BODY_WRAP - 36,
            ).pack(fill="x", padx=(36, 0), pady=(3, 0))

        return frame

    def _primary_button(self, parent, text: str, command, width: int = 120) -> ctk.CTkButton:
        """The action this window exists for. Exactly one per view."""
        return ctk.CTkButton(
            parent,
            text=text,
            width=width,
            height=36,
            corner_radius=t.RADIUS,
            font=t.font("body", 13, "bold"),
            fg_color=t.ACCENT,
            hover_color=t.ACCENT_HOVER,
            text_color=t.ON_ACCENT,
            text_color_disabled=t.TEXT_TERTIARY,
            command=command,
        )

    def _secondary_button(self, parent, text: str, command, width: int = 110) -> ctk.CTkButton:
        return ctk.CTkButton(
            parent,
            text=text,
            width=width,
            height=36,
            corner_radius=t.RADIUS,
            font=t.font("body", 12),
            fg_color="transparent",
            border_width=1,
            border_color=t.BORDER,
            text_color=t.TEXT_SECONDARY,
            hover_color=t.CONTROL_HOVER,
            command=command,
        )

    def _build_bottom_bar(self, parent):
        bottom = ctk.CTkFrame(parent, fg_color="transparent")
        bottom.pack(fill="x", pady=(14, 0))

        self.status_label = ctk.CTkLabel(
            bottom,
            text="",
            font=t.font("body", 12),
            anchor="w",
            justify="left",
            wraplength=WIDTH - 50,
        )
        self.status_label.pack(fill="x", pady=(0, 12))

        buttons = ctk.CTkFrame(bottom, fg_color="transparent")
        buttons.pack(fill="x")

        self._secondary_button(
            buttons, "Skip for now", self._on_skip, width=104
        ).pack(side="left")

        self._secondary_button(
            buttons, "Re-check", self._recheck, width=88
        ).pack(side="left", padx=8)

        self.continue_btn = self._primary_button(
            buttons, "Continue", self._on_continue, width=112
        )
        self.continue_btn.configure(state="disabled")
        self.continue_btn.pack(side="right")

    # ------------------------------------------------------------------ #
    # Install flow
    # ------------------------------------------------------------------ #

    def _start_install(self):
        if self._installing:
            return

        self._installing = True
        self._cancel.clear()

        self.install_btn.configure(state="disabled", text="Installing...")
        self.restart_btn.pack_forget()
        self.progress_bar.pack(fill="x", padx=16, pady=(0, 6))
        self.progress_bar.set(0)
        self.progress_label.pack(fill="x", padx=16, pady=(0, 8))
        self.progress_label.configure(text="Starting...", text_color=t.SUBTEXT)
        self._set_status("", MUTED)

        threading.Thread(target=self._install_worker, daemon=True).start()

    def _install_worker(self):
        try:
            status = vbcable.install(self._report_progress, self._cancel)
            self._ui(lambda: self._install_succeeded(status))
        except vbcable.InstallCancelled as exc:
            self._ui(lambda: self._install_failed(str(exc), warn_only=True))
        except vbcable.InstallError as exc:
            self._ui(lambda: self._install_failed(str(exc)))
        except Exception as exc:  # noqa: BLE001 - never kill the wizard
            self._ui(lambda: self._install_failed(f"Unexpected error: {exc}"))

    def _report_progress(self, message: str, fraction: Optional[float]):
        def update():
            if not self.progress_label:
                return
            self.progress_label.configure(text=message, text_color=t.SUBTEXT)
            if fraction is None:
                self.progress_bar.configure(mode="indeterminate")
                self.progress_bar.start()
            else:
                self.progress_bar.stop()
                self.progress_bar.configure(mode="determinate")
                self.progress_bar.set(max(0.0, min(1.0, fraction)))

        self._ui(update)

    def _install_succeeded(self, status: "vbcable.CableStatus"):
        self._installing = False
        self.progress_bar.stop()
        self.progress_bar.pack_forget()
        self.install_btn.configure(state="normal", text="Install VB-Cable")

        if status.installed:
            self.progress_label.configure(
                text="VB-Cable is installed and ready.",
                text_color=GREEN
            )
            self.install_btn.pack_forget()
            self._refresh_state()
            return

        # Installed on disk, endpoints appear after a reboot. Normal outcome.
        self.install_btn.pack_forget()
        self.progress_label.configure(
            text="VB-Cable installed. Restart Windows to finish — the microphone "
                 "won't appear until you do.",
            text_color=AMBER
        )
        self.restart_btn.pack(pady=(0, 10), padx=16, anchor="w")
        self._set_status("Restart required to finish setup", AMBER)

    def _install_failed(self, message: str, warn_only: bool = False):
        self._installing = False
        self.progress_bar.stop()
        self.progress_bar.pack_forget()
        self.install_btn.configure(state="normal", text="Try again")
        self.progress_label.configure(text=message, text_color=AMBER if warn_only else RED)

        if not warn_only and not self._manual_visible:
            self._toggle_manual()

    def _restart_now(self):
        if vbcable.restart_windows(delay_seconds=10):
            self._set_status("Restarting in 10 seconds...", AMBER)
            self.restart_btn.configure(state="disabled", text="Restarting...")
        else:
            self._set_status("Could not trigger a restart — please restart manually.", RED)

    # ------------------------------------------------------------------ #
    # State
    # ------------------------------------------------------------------ #

    def _refresh_state(self):
        """Sync buttons and status text with what's actually installed."""
        status = vbcable.detect()
        devices = self.find_virtual_devices()

        if devices:
            # The driver's own parenthetical - "(VB-Audio Virtual Cable)" - is
            # the half that never fits, and slicing at a fixed 30 characters cut
            # it mid-word with the bracket left open.
            names = ", ".join(
                d["name"].split("(")[0].strip(" -") or d["name"] for d in devices[:2]
            )
            self._set_status(f"Ready — found {names}", GREEN)
            self.continue_btn.configure(state="normal")
            if self.install_btn and not self._installing:
                self.install_btn.configure(text="Reinstall VB-Cable")
            return

        self.continue_btn.configure(state="disabled")

        if status.reboot_pending:
            self._set_status("VB-Cable installed — restart Windows to finish", AMBER)
            if self.restart_btn and not self.restart_btn.winfo_ismapped():
                self.install_btn.pack_forget()
                self.restart_btn.pack(pady=(0, 10), padx=16, anchor="w")
        else:
            self._set_status("No virtual audio device detected yet", RED)

        if not vbcable.can_auto_install() and self.install_btn:
            self.install_btn.configure(state="disabled")

    def _recheck(self):
        """Re-check for virtual audio devices."""
        vbcable.refresh_device_list()
        self._refresh_state()

    def _set_status(self, text: str, color: str):
        if self.status_label:
            self.status_label.configure(text=text, text_color=color)

    def _toggle_manual(self):
        self._manual_visible = not self._manual_visible
        if self._manual_visible:
            self.manual_frame.pack(fill="x", pady=(0, 10))
            self.manual_toggle.configure(text="Install manually instead  ▾")
        else:
            self.manual_frame.pack_forget()
            self.manual_toggle.configure(text="Install manually instead  ▸")

    def _ui(self, func: Callable):
        """Run *func* on the Tk main thread."""
        if self.window is not None:
            try:
                self.window.after(0, func)
            except Exception:
                pass

    def _open_download(self):
        """Open VB-Cable download page."""
        webbrowser.open(self.VB_CABLE_URL)

    # ------------------------------------------------------------------ #
    # Exit
    # ------------------------------------------------------------------ #

    def _close(self):
        self._cancel.set()
        if self.window:
            self.window.destroy()
            self.window = None

    def _on_skip(self):
        """Handle skip button."""
        self._close()
        if self.on_skip:
            self.on_skip()

    def _on_continue(self):
        """Handle continue button."""
        self._close()
        if self.on_complete:
            self.on_complete()


def check_and_show_setup(parent: ctk.CTk, on_complete: Callable = None, on_skip: Callable = None) -> bool:
    """
    Check if setup is needed and show wizard if so.
    Returns True if setup wizard was shown, False if not needed.
    """
    if SetupWizard.needs_setup():
        wizard = SetupWizard()
        wizard.on_complete = on_complete
        wizard.on_skip = on_skip
        wizard.show(parent)
        return True
    return False
