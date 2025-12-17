"""Generate a simple Meo Mic icon."""
from PIL import Image, ImageDraw

def create_icon():
    # Create images at multiple sizes for .ico
    sizes = [16, 32, 48, 64, 128, 256]
    images = []

    for size in sizes:
        # Create image with transparent background
        img = Image.new('RGBA', (size, size), (0, 0, 0, 0))
        draw = ImageDraw.Draw(img)

        # Catpuccin colors
        mauve = (203, 166, 247)  # Primary purple
        base = (30, 30, 46)      # Dark background
        crust = (17, 17, 27)     # Darker accent

        # Draw circular background
        padding = size // 8
        draw.ellipse(
            [padding, padding, size - padding, size - padding],
            fill=mauve
        )

        # Draw microphone body (rectangle with rounded top)
        mic_width = size // 4
        mic_height = size // 2.5
        mic_x = (size - mic_width) // 2
        mic_y = size // 4

        # Mic head (rounded rectangle)
        draw.rounded_rectangle(
            [mic_x, mic_y, mic_x + mic_width, mic_y + mic_height],
            radius=mic_width // 2,
            fill=crust
        )

        # Mic stand (arc)
        stand_top = mic_y + mic_height - size // 10
        stand_width = mic_width + size // 6
        stand_x = (size - stand_width) // 2
        draw.arc(
            [stand_x, stand_top, stand_x + stand_width, stand_top + size // 4],
            start=0, end=180,
            fill=crust,
            width=max(2, size // 16)
        )

        # Mic base (vertical line)
        base_x = size // 2
        base_top = stand_top + size // 8
        base_bottom = size - padding - size // 8
        draw.line(
            [(base_x, base_top), (base_x, base_bottom)],
            fill=crust,
            width=max(2, size // 16)
        )

        # Mic base (horizontal line)
        base_width = size // 5
        draw.line(
            [(base_x - base_width // 2, base_bottom), (base_x + base_width // 2, base_bottom)],
            fill=crust,
            width=max(2, size // 16)
        )

        images.append(img)

    # Save as .ico
    images[0].save(
        'icon.ico',
        format='ICO',
        sizes=[(s, s) for s in sizes],
        append_images=images[1:]
    )
    print("Created icon.ico")

    # Also save a PNG for other uses
    images[-1].save('icon.png', format='PNG')
    print("Created icon.png")

if __name__ == "__main__":
    create_icon()
