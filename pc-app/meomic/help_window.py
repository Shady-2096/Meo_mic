"""
Help Window

Shows setup instructions for virtual audio devices.
"""

import tkinter as tk
from tkinter import ttk, scrolledtext


def show_help_window(instructions: str):
    """Show a window with setup instructions."""
    window = tk.Tk()
    window.title("Meo Mic - Virtual Audio Setup")
    window.resizable(True, True)

    # Center on screen
    window_width = 500
    window_height = 350
    screen_width = window.winfo_screenwidth()
    screen_height = window.winfo_screenheight()
    x = (screen_width - window_width) // 2
    y = (screen_height - window_height) // 2
    window.geometry(f"{window_width}x{window_height}+{x}+{y}")

    # Keep on top
    window.attributes('-topmost', True)

    # Main frame
    frame = ttk.Frame(window, padding=15)
    frame.pack(fill=tk.BOTH, expand=True)

    # Title
    title = ttk.Label(frame, text="Virtual Audio Setup", font=('Segoe UI', 14, 'bold'))
    title.pack(pady=(0, 10))

    # Instructions text
    text = scrolledtext.ScrolledText(
        frame,
        wrap=tk.WORD,
        font=('Consolas', 10),
        height=15,
        width=55
    )
    text.pack(fill=tk.BOTH, expand=True, pady=10)
    text.insert(tk.END, instructions.strip())
    text.configure(state='disabled')

    # Close button
    close_btn = ttk.Button(frame, text="Close", command=window.destroy)
    close_btn.pack(pady=10)

    # Close on Escape
    window.bind('<Escape>', lambda e: window.destroy())

    window.mainloop()


if __name__ == "__main__":
    from .audio_output import get_platform_instructions
    show_help_window(get_platform_instructions())
