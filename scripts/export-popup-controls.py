"""Build the popup's prefiltered icon atlas with Pillow."""
from pathlib import Path

from PIL import Image

ICON_DIRECTORY = Path(__file__).resolve().parents[1] / "mod/src/main/resources/assets/lyrica/textures/gui/lyrics/icons"
# Match ATLAS_ICONS in PopupIcons. Transparent gutters isolate neighbouring icons.
ICONS = (
    "heart", "heart-outline", "previous", "play", "pause", "next", "shuffle",
    "repeat", "close", "settings-lucide", "world-lyrics", "lyrics", "restore-lucide",
    "romanization-on", "romanization-off", "translation", "volume-muted",
    "volume-speaker", "volume-low", "volume-high",
)
COLUMNS = 7
GUTTER = 2
SIZES = (16, 32, 64)

rows = (len(ICONS) + COLUMNS - 1) // COLUMNS
strides = [size + GUTTER * 2 for size in SIZES]
atlas = Image.new("RGBA", (strides[-1] * COLUMNS, sum(strides) * rows), (255, 255, 255, 0))
top = 0
for size, stride in zip(SIZES, strides):
    for index, name in enumerate(ICONS):
        with Image.open(ICON_DIRECTORY / f"{name}.png") as source:
            coverage = source.convert("RGBA").getchannel("A").resize((size, size), Image.Resampling.LANCZOS)
        icon = Image.new("RGBA", (size, size), "white")
        icon.putalpha(coverage)
        atlas.paste(icon, (index % COLUMNS * stride + GUTTER, top + index // COLUMNS * stride + GUTTER))
    top += stride * rows
atlas.save(ICON_DIRECTORY / "popup-controls.png", optimize=True)
