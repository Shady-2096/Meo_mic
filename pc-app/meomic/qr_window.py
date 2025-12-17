"""
QR Code Window

Displays a QR code that the phone app can scan to connect.
Uses tkinter for a simple, cross-platform window.
"""

import tkinter as tk
from tkinter import ttk
import io
from PIL import Image, ImageTk
import qrcode


def generate_qr_code(ip: str, port: int, size: int = 256) -> Image.Image:
    """Generate a QR code image for the connection info."""
    # Format: meomic://IP:PORT
    data = f"meomic://{ip}:{port}"

    qr = qrcode.QRCode(
        version=1,
        error_correction=qrcode.constants.ERROR_CORRECT_L,
        box_size=10,
        border=2,
    )
    qr.add_data(data)
    qr.make(fit=True)

    img = qr.make_image(fill_color="black", back_color="white")

    # Resize to desired size
    img = img.resize((size, size), Image.Resampling.NEAREST)

    return img


def show_qr_window(ip: str, port: int):
    """Show a window with the QR code and connection info."""
    # Create window
    window = tk.Tk()
    window.title("Meo Mic - Connect")
    window.resizable(False, False)

    # Center on screen
    window_width = 320
    window_height = 420
    screen_width = window.winfo_screenwidth()
    screen_height = window.winfo_screenheight()
    x = (screen_width - window_width) // 2
    y = (screen_height - window_height) // 2
    window.geometry(f"{window_width}x{window_height}+{x}+{y}")

    # Keep on top
    window.attributes('-topmost', True)

    # Style
    style = ttk.Style()
    style.configure('Title.TLabel', font=('Segoe UI', 16, 'bold'))
    style.configure('Info.TLabel', font=('Segoe UI', 11))
    style.configure('IP.TLabel', font=('Consolas', 14, 'bold'))

    # Main frame
    frame = ttk.Frame(window, padding=20)
    frame.pack(fill=tk.BOTH, expand=True)

    # Title
    title = ttk.Label(frame, text="Scan with Meo Mic App", style='Title.TLabel')
    title.pack(pady=(0, 15))

    # QR Code
    qr_image = generate_qr_code(ip, port, size=220)
    qr_photo = ImageTk.PhotoImage(qr_image)

    qr_label = ttk.Label(frame, image=qr_photo)
    qr_label.image = qr_photo  # Keep a reference
    qr_label.pack(pady=10)

    # Or text
    or_label = ttk.Label(frame, text="or enter manually:", style='Info.TLabel')
    or_label.pack(pady=(15, 5))

    # IP Address
    ip_text = f"{ip}:{port}"
    ip_label = ttk.Label(frame, text=ip_text, style='IP.TLabel')
    ip_label.pack(pady=5)

    # Copy button
    def copy_to_clipboard():
        window.clipboard_clear()
        window.clipboard_append(ip_text)
        copy_btn.configure(text="Copied!")
        window.after(1500, lambda: copy_btn.configure(text="Copy"))

    copy_btn = ttk.Button(frame, text="Copy", command=copy_to_clipboard)
    copy_btn.pack(pady=10)

    # Close on Escape
    window.bind('<Escape>', lambda e: window.destroy())

    # Run
    window.mainloop()


if __name__ == "__main__":
    # Test
    show_qr_window("192.168.1.100", 48888)
