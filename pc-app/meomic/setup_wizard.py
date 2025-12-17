"""
Meo Mic - First-Run Setup Wizard

Helps users install and understand VB-Cable virtual audio driver.
"""

import customtkinter as ctk
import webbrowser
from typing import Optional, Callable, List
import sounddevice as sd


class SetupWizard:
    """Setup wizard to guide users through VB-Cable installation."""

    VB_CABLE_URL = "https://vb-audio.com/Cable/"

    def __init__(self):
        self.window: Optional[ctk.CTkToplevel] = None
        self.on_complete: Optional[Callable] = None
        self.on_skip: Optional[Callable] = None
        self.status_label: Optional[ctk.CTkLabel] = None
        self.continue_btn: Optional[ctk.CTkButton] = None

    @staticmethod
    def find_virtual_devices() -> List[dict]:
        """Find virtual audio devices."""
        virtual_devices = []
        keywords = ['cable', 'virtual', 'vb-audio', 'blackhole', 'soundflower', 'loopback']

        try:
            for i, dev in enumerate(sd.query_devices()):
                if dev['max_output_channels'] > 0:
                    name_lower = dev['name'].lower()
                    if any(kw in name_lower for kw in keywords):
                        virtual_devices.append({
                            'id': i,
                            'name': dev['name'],
                            'channels': dev['max_output_channels']
                        })
        except Exception:
            pass

        return virtual_devices

    @staticmethod
    def needs_setup() -> bool:
        """Check if setup wizard should be shown."""
        return len(SetupWizard.find_virtual_devices()) == 0

    def _create_step(self, parent, number: str, title: str, details: str) -> ctk.CTkFrame:
        """Create a step frame with number, title, and details."""
        frame = ctk.CTkFrame(parent, fg_color="transparent")

        # Header row
        header = ctk.CTkFrame(frame, fg_color="transparent")
        header.pack(fill="x")

        num_label = ctk.CTkLabel(
            header,
            text=number,
            font=ctk.CTkFont(size=14, weight="bold"),
            width=28,
            height=28,
            corner_radius=14,
            fg_color="#3B8ED0"
        )
        num_label.pack(side="left", padx=(0, 10))

        title_label = ctk.CTkLabel(
            header,
            text=title,
            font=ctk.CTkFont(size=14, weight="bold"),
            anchor="w"
        )
        title_label.pack(side="left", fill="x", expand=True)

        # Details
        if details:
            details_label = ctk.CTkLabel(
                frame,
                text=details,
                font=ctk.CTkFont(size=12),
                text_color="gray",
                anchor="w",
                justify="left"
            )
            details_label.pack(fill="x", padx=(38, 0), pady=(2, 0))

        return frame

    def show(self, parent: ctk.CTk):
        """Show the setup wizard window."""
        self.window = ctk.CTkToplevel(parent)
        self.window.title("Meo Mic Setup")
        self.window.geometry("520x720")
        self.window.resizable(False, False)
        self.window.transient(parent)
        self.window.grab_set()

        # Center on parent
        self.window.update_idletasks()
        x = parent.winfo_x() + (parent.winfo_width() - 520) // 2
        y = parent.winfo_y() + (parent.winfo_height() - 720) // 2
        self.window.geometry(f"520x720+{x}+{y}")

        # Main container with scrolling
        main = ctk.CTkFrame(self.window, fg_color="transparent")
        main.pack(fill="both", expand=True, padx=25, pady=20)

        # Title
        title = ctk.CTkLabel(
            main,
            text="Virtual Audio Setup",
            font=ctk.CTkFont(size=24, weight="bold")
        )
        title.pack(pady=(0, 3))

        subtitle = ctk.CTkLabel(
            main,
            text="One-time setup to use your phone as a PC microphone",
            font=ctk.CTkFont(size=12),
            text_color="gray"
        )
        subtitle.pack(pady=(0, 15))

        # Scrollable content
        scroll_frame = ctk.CTkScrollableFrame(main, fg_color="transparent", height=480)
        scroll_frame.pack(fill="both", expand=True)

        # Why needed card
        why_card = ctk.CTkFrame(scroll_frame, corner_radius=10)
        why_card.pack(fill="x", pady=(0, 15))

        why_title = ctk.CTkLabel(
            why_card,
            text="Why is this needed?",
            font=ctk.CTkFont(size=13, weight="bold")
        )
        why_title.pack(pady=(12, 5), padx=15, anchor="w")

        why_text = ctk.CTkLabel(
            why_card,
            text="VB-Cable creates a virtual audio device that acts as a bridge.\n"
                 "Meo Mic sends audio to this virtual device, and apps like\n"
                 "Discord, Zoom, or games can use it as a microphone input.",
            font=ctk.CTkFont(size=12),
            text_color="gray",
            justify="left"
        )
        why_text.pack(pady=(0, 12), padx=15, anchor="w")

        # Installation Steps
        steps_title = ctk.CTkLabel(
            scroll_frame,
            text="Installation Steps",
            font=ctk.CTkFont(size=16, weight="bold")
        )
        steps_title.pack(fill="x", pady=(5, 10), anchor="w")

        # Step 1
        step1 = self._create_step(
            scroll_frame, "1",
            "Download VB-Cable",
            "Click the button below to open the download page"
        )
        step1.pack(fill="x", pady=5)

        download_btn = ctk.CTkButton(
            scroll_frame,
            text="Download VB-Cable (Free)",
            width=220,
            height=38,
            corner_radius=8,
            command=self._open_download
        )
        download_btn.pack(pady=(5, 10))

        # Step 2
        step2 = self._create_step(
            scroll_frame, "2",
            "Extract the ZIP file",
            "Right-click the downloaded file → Extract All"
        )
        step2.pack(fill="x", pady=5)

        # Step 3 - Important!
        step3 = self._create_step(
            scroll_frame, "3",
            "Run the correct installer",
            ""
        )
        step3.pack(fill="x", pady=5)

        # Installer info box
        installer_card = ctk.CTkFrame(scroll_frame, corner_radius=8, fg_color="#2D3748")
        installer_card.pack(fill="x", padx=(38, 0), pady=5)

        installer_text = ctk.CTkLabel(
            installer_card,
            text="For 64-bit Windows (most PCs):\n"
                 "   Run  VBCABLE_Setup_x64.exe\n\n"
                 "For 32-bit Windows:\n"
                 "   Run  VBCABLE_Setup.exe",
            font=ctk.CTkFont(size=12, family="Consolas"),
            justify="left",
            anchor="w"
        )
        installer_text.pack(pady=10, padx=12, anchor="w")

        # Step 4
        step4 = self._create_step(
            scroll_frame, "4",
            "Run as Administrator",
            "Right-click installer → 'Run as administrator' → Install"
        )
        step4.pack(fill="x", pady=5)

        # Step 5
        step5 = self._create_step(
            scroll_frame, "5",
            "Restart your PC",
            "Required for Windows to detect the new audio device"
        )
        step5.pack(fill="x", pady=5)

        # After Installation
        after_title = ctk.CTkLabel(
            scroll_frame,
            text="After Installation",
            font=ctk.CTkFont(size=16, weight="bold")
        )
        after_title.pack(fill="x", pady=(15, 10), anchor="w")

        # How it works card
        how_card = ctk.CTkFrame(scroll_frame, corner_radius=10, fg_color="#1E3A2F")
        how_card.pack(fill="x", pady=5)

        how_title = ctk.CTkLabel(
            how_card,
            text="How to use Meo Mic with VB-Cable",
            font=ctk.CTkFont(size=13, weight="bold"),
            text_color="#4ADE80"
        )
        how_title.pack(pady=(12, 8), padx=15, anchor="w")

        # Meo Mic setting
        ezmic_frame = ctk.CTkFrame(how_card, fg_color="transparent")
        ezmic_frame.pack(fill="x", padx=15, pady=2)

        ctk.CTkLabel(
            ezmic_frame,
            text="In Meo Mic:",
            font=ctk.CTkFont(size=12, weight="bold"),
            width=100,
            anchor="w"
        ).pack(side="left")

        ctk.CTkLabel(
            ezmic_frame,
            text="Select \"CABLE Input\" as Output Device",
            font=ctk.CTkFont(size=12),
            text_color="#A0AEC0"
        ).pack(side="left")

        # App setting
        app_frame = ctk.CTkFrame(how_card, fg_color="transparent")
        app_frame.pack(fill="x", padx=15, pady=2)

        ctk.CTkLabel(
            app_frame,
            text="In Discord/Zoom:",
            font=ctk.CTkFont(size=12, weight="bold"),
            width=100,
            anchor="w"
        ).pack(side="left")

        ctk.CTkLabel(
            app_frame,
            text="Select \"CABLE Output\" as Microphone",
            font=ctk.CTkFont(size=12),
            text_color="#A0AEC0"
        ).pack(side="left")

        # Visual flow
        flow_label = ctk.CTkLabel(
            how_card,
            text="Phone → Meo Mic → CABLE Input → CABLE Output → Discord",
            font=ctk.CTkFont(size=11),
            text_color="#68D391"
        )
        flow_label.pack(pady=(10, 12))

        # Confusing naming note
        naming_card = ctk.CTkFrame(scroll_frame, corner_radius=10, fg_color="#3D2E1E")
        naming_card.pack(fill="x", pady=10)

        naming_title = ctk.CTkLabel(
            naming_card,
            text="Why are the names confusing?",
            font=ctk.CTkFont(size=12, weight="bold"),
            text_color="#F6AD55"
        )
        naming_title.pack(pady=(10, 5), padx=15, anchor="w")

        naming_text = ctk.CTkLabel(
            naming_card,
            text="• CABLE Input appears in OUTPUT devices\n"
                 "   (because you output/send audio TO the cable)\n\n"
                 "• CABLE Output appears in INPUT devices\n"
                 "   (because apps read/input audio FROM the cable)",
            font=ctk.CTkFont(size=11),
            text_color="#CBD5E0",
            justify="left",
            anchor="w"
        )
        naming_text.pack(pady=(0, 10), padx=15, anchor="w")

        # Status and buttons (outside scroll)
        bottom_frame = ctk.CTkFrame(main, fg_color="transparent")
        bottom_frame.pack(fill="x", pady=(10, 0))

        self.status_label = ctk.CTkLabel(
            bottom_frame,
            text="",
            font=ctk.CTkFont(size=12)
        )
        self.status_label.pack(pady=(0, 8))

        btn_frame = ctk.CTkFrame(bottom_frame, fg_color="transparent")
        btn_frame.pack(fill="x")

        skip_btn = ctk.CTkButton(
            btn_frame,
            text="Skip for now",
            width=110,
            height=36,
            corner_radius=8,
            fg_color="#444",
            hover_color="#555",
            command=self._on_skip
        )
        skip_btn.pack(side="left")

        recheck_btn = ctk.CTkButton(
            btn_frame,
            text="Re-check",
            width=90,
            height=36,
            corner_radius=8,
            fg_color="#444",
            hover_color="#555",
            command=self._recheck
        )
        recheck_btn.pack(side="left", padx=10)

        self.continue_btn = ctk.CTkButton(
            btn_frame,
            text="Continue",
            width=110,
            height=36,
            corner_radius=8,
            state="disabled",
            command=self._on_continue
        )
        self.continue_btn.pack(side="right")

        # Initial check
        self._recheck()

    def _open_download(self):
        """Open VB-Cable download page."""
        webbrowser.open(self.VB_CABLE_URL)
        self.status_label.configure(
            text="Opening download page in browser...",
            text_color="gray"
        )

    def _recheck(self):
        """Re-check for virtual audio devices."""
        devices = self.find_virtual_devices()

        if devices:
            device_names = ", ".join(d['name'][:30] for d in devices[:2])
            self.status_label.configure(
                text=f"Found: {device_names}",
                text_color="#4ADE80"
            )
            self.continue_btn.configure(state="normal")
        else:
            self.status_label.configure(
                text="No virtual audio device detected yet",
                text_color="#F87171"
            )
            self.continue_btn.configure(state="disabled")

    def _on_skip(self):
        """Handle skip button."""
        if self.window:
            self.window.destroy()
        if self.on_skip:
            self.on_skip()

    def _on_continue(self):
        """Handle continue button."""
        if self.window:
            self.window.destroy()
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
