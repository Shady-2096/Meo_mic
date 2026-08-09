"""
Meo Mic - design tokens for the Windows app.

Every colour is a ``(light, dark)`` pair, which is what CustomTkinter widgets
take directly, and the app runs in ``System`` appearance mode so it follows the
Windows light/dark setting. That mirrors the Mac app's move to AppKit semantic
colours: a hand-rolled dark-only palette is the most reliable tell that an app
is not really a native app.

Values follow Windows 11's layering model rather than Catppuccin, which the
Android app still uses. Sharing a product does not mean sharing a palette
across operating systems that disagree about what a window looks like.

The grounds are deliberately further apart than Windows' own defaults. A
utility panel this small has only two or three surfaces in it, and when they
sit within a few percent of each other the whole window reads as one flat grey
slab. Separation is what makes a card look like a card.

Raw ``tkinter`` widgets - the waveform canvas - cannot take a pair, so pass
them through :func:`resolve` first.
"""

from __future__ import annotations

import sys
from typing import Sequence

Color = tuple  # (light, dark)

# --------------------------------------------------------------------------- #
# Colour
# --------------------------------------------------------------------------- #

# Grounds and layers: the window sits lowest, cards sit one layer above it,
# controls one above that. Each step is a visible step.
WINDOW = ("#F4F5F7", "#17171B")
CARD = ("#FFFFFF", "#212127")
CARD_HOVER = ("#F7F8FA", "#26262D")
BORDER = ("#E3E4E9", "#2E2E36")
SEPARATOR = ("#ECEDF1", "#2A2A31")

# A control has to be a step away from the card it sits on, not the window.
# White-on-white in light mode leaves a dropdown with nothing but its own
# chevron to prove it is a control at all.
CONTROL = ("#F1F2F6", "#2B2B33")
CONTROL_HOVER = ("#E7E9EF", "#35353E")
TRACK = ("#E6E7EC", "#33333B")      # slider grooves, empty meters

TEXT = ("#16161A", "#F5F5F8")
TEXT_SECONDARY = ("#5A5A64", "#A3A3AE")
TEXT_TERTIARY = ("#7C7C88", "#75757F")

# Windows' own status colours, each side of the pair chosen to clear 4.5:1 on
# its own ground.
LIVE = ("#0F7B0F", "#5FD07A")
WARN = ("#8F5300", "#FFC24B")
HOT = ("#9A5B00", "#FFB959")
ERROR = ("#C42B1C", "#FF99A4")


def _windows_accent() -> Color:
    """The user's own Windows accent colour, or the system default.

    Windows stores it as an ABGR DWORD. Reading it is the closest equivalent to
    the Mac app taking ``Color.accentColor``, and it is what makes the window
    look like it belongs to this particular desktop rather than to us.
    """
    default = ("#0067C0", "#4CC2FF")
    if sys.platform != "win32":
        return default
    try:
        import winreg

        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, r"Software\Microsoft\Windows\DWM") as key:
            raw, _ = winreg.QueryValueEx(key, "ColorizationColor")
        red = (raw >> 16) & 0xFF
        green = (raw >> 8) & 0xFF
        blue = raw & 0xFF
        # The stored colour is tuned for a dark ground; darken it for light
        # mode so text on top of it keeps its contrast.
        light = "#%02x%02x%02x" % (int(red * 0.72), int(green * 0.72), int(blue * 0.72))
        dark = "#%02x%02x%02x" % (red, green, blue)
        return (light, dark)
    except Exception:
        return default


ACCENT = _windows_accent()


# --------------------------------------------------------------------------- #
# Blending
# --------------------------------------------------------------------------- #
#
# Tk has no alpha channel, so anything that would be a translucent tint has to
# be precomputed against the ground it sits on. These helpers are how the
# window gets tinted fills without fake transparency.

def resolve(color, mode: str | None = None) -> str:
    """Flatten a ``(light, dark)`` pair to one hex string.

    Needed wherever a raw ``tkinter`` widget is involved, since only
    CustomTkinter understands pairs.
    """
    if not isinstance(color, (tuple, list)):
        return color
    if mode is None:
        try:
            import customtkinter as ctk

            mode = ctk.get_appearance_mode()
        except Exception:
            mode = "Dark"
    return color[1] if str(mode).lower() == "dark" else color[0]


def _mix_hex(a_hex: str, b_hex: str, amount: float) -> str:
    a = tuple(int(a_hex[i:i + 2], 16) for i in (1, 3, 5))
    b = tuple(int(b_hex[i:i + 2], 16) for i in (1, 3, 5))
    return "#%02x%02x%02x" % tuple(round(x + (y - x) * amount) for x, y in zip(a, b))


def mix(color_a, color_b, amount: float) -> str:
    """Blend two colours down to one hex string, for raw Tk widgets."""
    amount = max(0.0, min(1.0, amount))
    return _mix_hex(resolve(color_a), resolve(color_b), amount)


def blend(color_a, color_b, amount: float) -> Color:
    """Blend two colours a side at a time, keeping the ``(light, dark)`` pair.

    Use this over :func:`mix` for anything a CustomTkinter widget will hold, so
    the tint survives the user switching appearance.
    """
    amount = max(0.0, min(1.0, amount))
    pair_a = color_a if isinstance(color_a, (tuple, list)) else (color_a, color_a)
    pair_b = color_b if isinstance(color_b, (tuple, list)) else (color_b, color_b)
    return tuple(_mix_hex(pair_a[i], pair_b[i], amount) for i in (0, 1))


def dim(color, amount: float = 0.7) -> str:
    """Push a colour towards the window ground."""
    return mix(color, WINDOW, amount)


# Accent tints, precomputed against the surfaces they actually sit on. The soft
# fills are what let the accent appear more than once without the window
# turning into a toy.
ACCENT_HOVER = blend(ACCENT, TEXT, 0.12)
ACCENT_SOFT = blend(ACCENT, CARD, 0.86)          # tinted fill on a card
ACCENT_SOFT_HOVER = blend(ACCENT, CARD, 0.78)
ACCENT_QUIET = blend(ACCENT, WINDOW, 0.88)       # tinted fill on the window
ACCENT_RING = blend(ACCENT, WINDOW, 0.62)        # hairline rings and rules
ON_ACCENT = ("#FFFFFF", "#0B0B0E")               # text sitting on solid accent

LIVE_SOFT = blend(LIVE, WINDOW, 0.86)
LIVE_RING = blend(LIVE, WINDOW, 0.60)
WARN_SOFT = blend(WARN, CARD, 0.88)

# Legacy role names. The setup wizard still speaks in these; they map onto the
# roles above rather than defining new colours.
CRUST = WINDOW
MANTLE = WINDOW
BASE = CARD
SURFACE = CARD_HOVER
SURFACE0 = CARD_HOVER
SURFACE1 = CONTROL_HOVER
SURFACE2 = BORDER
SUBTEXT = TEXT_SECONDARY
OVERLAY = TEXT_TERTIARY
MAUVE = ACCENT
LAVENDER = ACCENT_HOVER
GREEN = LIVE
YELLOW = WARN
PEACH = HOT
RED = ERROR
LINE = BORDER


# --------------------------------------------------------------------------- #
# Type
# --------------------------------------------------------------------------- #
#
# One family: whatever Windows uses for its own interface. A utility window
# does not need a display face, and monospace here would be a costume.
#
#   TITLE   20 bold     the one status line
#   ADDRESS 17 bold     the address while waiting
#   BODY    13 regular  row labels, supporting sentences
#   LABEL   12 regular  captions, buttons, footer
#   MICRO   10 bold     the uppercase card headers
#
# The scale is deliberately short. Four sizes is enough to build a hierarchy
# in a window this small, and a fifth only blurs the four that were working.

_UI_STACK = (
    "Segoe UI Variable Display",  # Windows 11
    "Segoe UI Variable Text",
    "Segoe UI",                   # Windows 10
    "SF Pro Text",                # macOS, when running from source
    "Helvetica Neue",
    "DejaVu Sans",
)

_resolved: dict = {}


def _resolve_family(stack: Sequence[str], fallback: str) -> str:
    """Return the first installed family in *stack*."""
    key = id(stack)
    if key in _resolved:
        return _resolved[key]

    chosen = fallback
    try:
        from tkinter import font as tkfont

        available = {name.lower() for name in tkfont.families()}
        for family in stack:
            if family.lower() in available:
                chosen = family
                break
    except Exception:
        pass

    _resolved[key] = chosen
    return chosen


def ui_family() -> str:
    return _resolve_family(_UI_STACK, "Segoe UI" if sys.platform == "win32" else "Helvetica")


# Kept so older call sites keep resolving; every role is the same family.
def display() -> str:
    return ui_family()


def body() -> str:
    return ui_family()


def font(role: str = "body", size: int = 13, weight: str = "normal"):
    """Build a CTkFont. *role* is accepted for call-site readability only."""
    import customtkinter as ctk

    return ctk.CTkFont(family=ui_family(), size=size, weight=weight)


# --------------------------------------------------------------------------- #
# Spacing - a 4px base grid
# --------------------------------------------------------------------------- #

XS = 4
SM = 8
MD = 12
LG = 18
XL = 24

PAD = 22        # window side gutter
GUTTER = 14     # between stacked blocks
ROW_PAD = 12    # inside a card row, vertically
RADIUS = 8      # controls
RADIUS_LG = 12  # cards - Windows 11 rounds its own surfaces this far
RADIUS_SM = 6   # small buttons
