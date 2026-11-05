"""Rebuild assets: uv run --with resvg-py python scripts/export-lucide-icons.py."""
from pathlib import Path
import shutil
import resvg_py

root = Path(__file__).resolve().parent.parent
source = root / 'assets-src/lucide-icons'
target = root / 'lyrics-popup/src/main/resources/assets/relaylyrics/textures/gui/lucide'
target.mkdir(parents=True, exist_ok=True)
for svg in sorted(source.glob('*.svg')):
    image = resvg_py.svg_to_bytes(svg_string=svg.read_text(encoding='utf-8').replace('currentColor', '#ffffff'),
        width=192, height=192, skip_system_fonts=True)
    (target / (svg.stem + '.png')).write_bytes(image)
icons = root / 'mod/src/main/resources/assets/lyrica/textures/gui/lyrics/icons'
for name, filename in [('settings', 'settings-lucide'), ('box', 'world-lyrics'), ('panel-top-open', 'restore-lucide'), ('list-music', 'lyrics')]:
    shutil.copy2(target / (name + '.png'), icons / (filename + '.png'))
license_dir = root / 'lyrics-popup/src/main/resources/assets/relaylyrics/licenses'
license_dir.mkdir(parents=True, exist_ok=True)
shutil.copy2(source / 'LICENSE', license_dir / 'lucide.txt')
print('Exported 13 Lucide icons at 192px; license included.')
