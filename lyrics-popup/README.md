# Relay Lyrics

`lyrics-popup` is the Minecraft 1.21.1 standalone build and
`lyrics-popup-1.21.11` is the Minecraft 1.21.11 standalone build. They contain
only Relay's Spotify/Spicetify media connection, synchronized-lyrics lookup,
cover-art background and HUD popup. The phone, chat, calls, gallery, camera and
server protocol are not included.

Install Fabric API, Mod Menu and the matching `relaylyrics` JAR. The existing
Relay Spicetify extension remains compatible. Open **Mods > Relay Lyrics >
Configure** to open a categorized Minecraft options screen with a live popup
preview above the Done button. While an inventory or container is open, the
popup is drawn above it with the configured enhanced effects and dynamic
artwork background. Click the popup to select it, drag it to move it, use its
larger lower-right handle to resize it, or press the gear shown below it to open
the same configuration screen. Player and creative inventories also show the
album cover and track title to the left of the inventory panel. Inventory GUI
bounds are solid obstacles while moving or resizing the popup. The popup also
has a true edge-only border that can be disabled under **Appearance**. The
lyrics popup and the separate cover/track/control card can each be disabled
independently under **General**, without hiding the normal in-world HUD popup.

The standalone mod uses the ID `relaylyrics` and stores preferences in
`config/relaylyrics.json`. Relay continues to use `fandroid` and
`config/fandroid.json`. If both JARs are installed, Relay Lyrics disables its
renderer and bridge so Relay's built-in popup remains the single active copy.

Optional compatibility is included for Minecraft Cursor/Cursors Extended,
Mouse Tweaks, EMI, REI, Iris, Sodium, ImmediatelyFast, Controlify, Xaero's
Minimap and AppleSkin. EMI/REI panels become solid placement obstacles inside
inventories, cursor mods receive pointer/drag/resize states, and render/HUD mods
use safe drawing and reserved HUD regions. None of them is a required dependency.
