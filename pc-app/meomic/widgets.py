"""
Meo Mic - custom widgets.

The waveform is the centre of this app. Everything else on screen is a setting
you touch once; the waveform is the only thing that answers the question you
opened the window to ask - "can they hear me?"

It has been a segmented dBFS meter and then a single level bar. The ballistics
underneath have never changed, because instant attack and timed release are
what make a level readable at a glance. What changed is that it now keeps the
last two seconds on screen instead of only the present instant: a flat line
means silence and a moving one means your voice is arriving, which nobody has
to be taught to read.

Matches the Mac app's waveform deliberately - same sample count, same
ballistics, same fade toward the older edge.
"""

from __future__ import annotations

import math
import tkinter as tk
from typing import Optional

import customtkinter as ctk

from . import theme as t


class Waveform(ctk.CTkFrame):
    """A rolling level history with meter ballistics and a plain-English caption.

    Feed it dBFS with :meth:`set_db`; it handles its own animation.
    """

    MIN_DB = -60.0
    MAX_DB = 0.0

    RELEASE_DB_PER_SEC = 26.0     # how fast the level falls once you stop talking
    FRAME_MS = 33                 # ~30 fps

    BAR_W = 3
    GAP = 3
    HEIGHT = 46
    FLOOR_PX = 3                  # silence is a row of dots, not an empty box
    FADE = 0.55                   # how far the oldest bar sinks into the ground

    def __init__(self, parent, width: int = 320, **kwargs):
        super().__init__(parent, fg_color="transparent", **kwargs)

        self._width = width
        self._connected = False
        self._level_db = self.MIN_DB
        self._target_db = self.MIN_DB
        self._running = False
        self._ramp: list = []
        self._ramp_key = None

        # A raw Tk canvas is the one thing in this window CustomTkinter does
        # not scale for us, so every measurement here is converted by hand.
        # Left in points, the meter draws at half width on a 200% display,
        # which looks like a bug in the audio rather than in the geometry.
        self._scale = self._widget_scaling()
        self._px_w = self._px(width)
        self._px_h = self._px(self.HEIGHT)
        self._bar = max(1, self._px(self.BAR_W))
        self._pitch = self._bar + max(1, self._px(self.GAP))

        # Derived from the width rather than fixed, so the bars fill the panel
        # exactly instead of leaving a dead strip at the right.
        self._count = max(24, int(self._px_w // self._pitch))
        self._samples = [0.0] * self._count

        ground = t.resolve(t.WINDOW)
        self.canvas = tk.Canvas(
            self, width=self._px_w, height=self._px_h, bg=ground,
            highlightthickness=0, bd=0,
        )
        self.canvas.pack(fill="x")

        # One line per sample, created once and then only moved. Recreating
        # canvas items 30 times a second is what makes Tk animations stutter.
        # Round caps turn each one into a capsule; Tk has no rounded rectangle,
        # and a grid of hard-edged bars is the look this window is trying to
        # get away from.
        self._bars = []
        mid = self._px_h / 2
        for index in range(self._count):
            x = index * self._pitch + self._bar / 2
            self._bars.append(
                self.canvas.create_line(
                    x, mid, x, mid,
                    width=self._bar, capstyle=tk.ROUND,
                    fill=t.resolve(t.TEXT_TERTIARY),
                )
            )

        self.caption = ctk.CTkLabel(
            self,
            text="No sound yet",
            font=t.font("label", 12),
            text_color=t.TEXT_SECONDARY,
            anchor="w",
        )
        self.caption.pack(fill="x", pady=(t.SM + 2, 0))

    # -- geometry ---------------------------------------------------------- #

    def _widget_scaling(self) -> float:
        try:
            return ctk.ScalingTracker.get_widget_scaling(self) or 1.0
        except Exception:
            return 1.0

    def _px(self, value: float) -> int:
        """Points to real canvas pixels."""
        return max(1, int(round(value * self._scale)))

    # -- appearance -------------------------------------------------------- #

    def refresh_theme(self):
        """Re-resolve colours after the appearance mode changes."""
        self.canvas.configure(bg=t.resolve(t.WINDOW))
        self._ramp_key = None
        self._redraw()

    def _bar_color(self):
        if not self._connected:
            return t.TEXT_TERTIARY
        if self._level_db >= -3:
            return t.WARN
        return t.ACCENT

    def _bar_ramp(self) -> list:
        """One colour per column, fading the older end into the ground.

        The fade is what makes a static screenshot read as time moving left,
        and it is cached because blending 60 colours per frame is not free.
        """
        color = self._bar_color()
        if self._ramp_key != color:
            span = max(1, self._count - 1)
            self._ramp = [
                t.mix(color, t.WINDOW, self.FADE * (1 - index / span))
                for index in range(self._count)
            ]
            self._ramp_key = color
        return self._ramp

    def _caption_for(self, db: float) -> tuple:
        """What the level means, in the words of someone about to join a call."""
        if not self._connected:
            return "No sound yet", t.TEXT_SECONDARY
        if db < -50:
            return "Very quiet - say something", t.TEXT_SECONDARY
        if db < -30:
            return "A little quiet", t.TEXT_SECONDARY
        if db < -6:
            return "Sounds good", t.TEXT_SECONDARY
        if db < -3:
            return "Getting loud", t.TEXT_SECONDARY
        return "Too loud - turn it down on your phone", t.WARN

    # -- animation --------------------------------------------------------- #

    def start(self):
        if not self._running:
            self._running = True
            self._tick()

    def stop(self):
        self._running = False

    def set_db(self, db: float):
        """Feed the waveform a dBFS value. Safe to call from any thread."""
        self._target_db = max(self.MIN_DB, min(self.MAX_DB, db))

    def set_connected(self, connected: bool):
        self._connected = connected
        if not connected:
            self._target_db = self.MIN_DB
            self._level_db = self.MIN_DB
        self._redraw()

    def _tick(self):
        if not self._running:
            return

        step = self.FRAME_MS / 1000.0

        # Attack is instant, release is timed. That asymmetry is what makes a
        # level readable: you catch every transient, but it does not flicker on
        # every syllable.
        if self._target_db >= self._level_db:
            self._level_db = self._target_db
        else:
            self._level_db = max(
                self._target_db,
                self._level_db - self.RELEASE_DB_PER_SEC * step,
            )

        span = self.MAX_DB - self.MIN_DB
        sample = (self._level_db - self.MIN_DB) / span if self._connected else 0.0

        self._samples.pop(0)
        self._samples.append(max(0.0, min(1.0, sample)))

        self._redraw()

        try:
            self.after(self.FRAME_MS, self._tick)
        except Exception:
            self._running = False

    def _redraw(self):
        ramp = self._bar_ramp()
        mid = self._px_h / 2
        # The caps add half a stroke at each end, so the tallest bar has to
        # stop short of the frame or it clips.
        usable = self._px_h - self._bar
        floor = self._px(self.FLOOR_PX)

        for index, item in enumerate(self._bars):
            height = max(floor, self._samples[index] * usable)
            x = index * self._pitch + self._bar / 2
            self.canvas.coords(item, x, mid - height / 2, x, mid + height / 2)
            self.canvas.itemconfigure(item, fill=ramp[index])

        text, text_color = self._caption_for(self._level_db)
        if self.caption.cget("text") != text:
            self.caption.configure(text=text, text_color=text_color)

    def resize(self, width: int):
        self._width = width
        self._px_w = self._px(width)
        self.canvas.configure(width=self._px_w)

    @property
    def level_db(self) -> float:
        return self._level_db


# Older call sites imported the level bar under its previous name.
VoiceBar = Waveform


class StatusGlyph(ctk.CTkFrame):
    """The connection state as a ring with a dot in it.

    It breathes while the app is waiting and settles once a phone is on the
    line. That is the whole point of it: a static icon cannot tell you the
    difference between listening and hung, and this window's first job is to
    say which one it is doing.
    """

    SIZE = 34
    DOT = 10
    PERIOD_MS = 2200
    FRAME_MS = 50

    def __init__(self, parent, **kwargs):
        super().__init__(
            parent,
            width=self.SIZE,
            height=self.SIZE,
            fg_color="transparent",
            **kwargs,
        )
        self.pack_propagate(False)
        self.grid_propagate(False)

        self._live = False
        self._phase = 0.0
        self._job = None

        self.ring = ctk.CTkFrame(
            self,
            width=self.SIZE,
            height=self.SIZE,
            corner_radius=self.SIZE // 2,
            border_width=1,
            fg_color=t.ACCENT_QUIET,
            border_color=t.ACCENT_RING,
        )
        self.ring.place(relx=0.5, rely=0.5, anchor="center")

        self.dot = ctk.CTkFrame(
            self.ring,
            width=self.DOT,
            height=self.DOT,
            corner_radius=self.DOT // 2,
            fg_color=t.ACCENT,
        )
        self.dot.place(relx=0.5, rely=0.5, anchor="center")

        self._pulse()

    def set_live(self, live: bool):
        self._live = live
        self._phase = 0.0
        self._paint(1.0)

    def _paint(self, amount: float):
        """*amount* runs 0..1 across the breath, dimmest to brightest."""
        base = t.LIVE if self._live else t.ACCENT
        ground = t.WINDOW
        # Live sits brighter and moves less: a steady light reads as connected,
        # a moving one reads as still trying.
        low, high = (0.86, 0.76) if self._live else (0.94, 0.86)
        fill = t.mix(base, ground, low - (low - high) * amount)
        ring = t.mix(base, ground, 0.58 - 0.16 * amount)
        self.ring.configure(fg_color=fill, border_color=ring)
        self.dot.configure(fg_color=t.resolve(base))

    def _pulse(self):
        self._phase += self.FRAME_MS / self.PERIOD_MS
        # A sine, not a sawtooth: the turn at each end is what keeps it from
        # looking like a blinking error light.
        self._paint(0.5 - 0.5 * math.cos(self._phase * 2 * math.pi))
        try:
            self._job = self.after(self.FRAME_MS, self._pulse)
        except Exception:
            self._job = None

    def stop(self):
        if self._job is not None:
            try:
                self.after_cancel(self._job)
            except Exception:
                pass
            self._job = None


# Older call sites imported the dot under its previous name.
StatusDot = StatusGlyph


def card(parent) -> ctk.CTkFrame:
    """An inset grouped container - the only box in the window."""
    return ctk.CTkFrame(
        parent,
        fg_color=t.CARD,
        corner_radius=t.RADIUS_LG,
        border_width=1,
        border_color=t.BORDER,
    )


def separator(parent) -> ctk.CTkFrame:
    """A row separator inside a grouped card."""
    return ctk.CTkFrame(parent, height=1, fg_color=t.SEPARATOR, corner_radius=0)


def micro_label(parent, text: str) -> ctk.CTkLabel:
    """The small caps header on a card.

    Uppercase with the letters pushed apart: at 10px it is the cheapest way to
    mark a header as a header without spending a font size on it.
    """
    return ctk.CTkLabel(
        parent,
        text=" ".join(text.upper()),
        font=t.font("micro", 10, "bold"),
        text_color=t.TEXT_TERTIARY,
        anchor="w",
    )


def field_label(parent, text: str) -> ctk.CTkLabel:
    """A quiet, sentence-case label."""
    return ctk.CTkLabel(
        parent,
        text=text,
        font=t.font("label", 12),
        text_color=t.TEXT_TERTIARY,
        anchor="w",
    )


def hairline(parent, color=None) -> ctk.CTkFrame:
    """A one-pixel rule."""
    return ctk.CTkFrame(parent, height=1, fg_color=color or t.BORDER, corner_radius=0)


def inline_note(parent, text: str, tint=None, wraplength: Optional[int] = None) -> ctk.CTkLabel:
    """An advisory line, in the flow, where the problem is."""
    return ctk.CTkLabel(
        parent,
        text=text,
        font=t.font("label", 12),
        text_color=tint or t.TEXT_SECONDARY,
        anchor="w",
        justify="left",
        wraplength=wraplength or 300,
    )
