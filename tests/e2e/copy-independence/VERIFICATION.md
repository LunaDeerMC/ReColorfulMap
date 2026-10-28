# 5.0.1 command-copy verification

Completed on 2026-10-28, using the actual DeerFoliaPlus 26.1.2 `72d5f22` core,
Java 25, an isolated new world and a localhost protocol client.

Candidate: `ReColorfulMap-5.0.1.jar`

SHA-256: `29bcec0f50fb62f3ab09c212f128f9dd2b6a070d2f058605fd323dbb48a1849b`

The runner completed both startup phases, both clients and both normal server
shutdowns with exit code 0. The before-restart report contains 14 successful
checks; the after-restart report contains one. The separate disconnect check
also confirmed no new artwork directories remained.

| Actual runtime action | Verified result |
| --- | --- |
| Workbench result click and cartography Shift-click | Image-map cloning refused, original input materials unchanged |
| `/mapmanage copy` with a full inventory | Original unchanged; no image copy or directory added |
| Change held slot while copy I/O is pending | Copy cancelled; original retained; no new directory remains |
| Disconnect while copy I/O is pending | Reconnection retains original; no new directory remains |
| Copy whole 2×2 artwork with `/mapmanage copy` | Original retained; new UUID, map ID and independent files; PNG hashes and client pixels match |
| Naturally despawn copied whole artwork | Only copy's directory removed; original file remains |
| Kill original dropped map with fire | Original's directory removed; copy remains and displays matching pixels |
| Naturally despawn legacy artwork without ownership marker | Every PNG remains byte-for-byte unchanged |
| Copy tile `1_0.png` | New independent 1×1 artwork with its own `0_0.png`, UUID and map ID; bytes match source tile |
| Send original tile into the void | Only that tile removed; sibling tiles and independent copy survive; copy pixels match |
| Naturally despawn independent 1×1 copied tile | Last tile removes its directory; original artwork's sibling tiles survive |
| Normal stop and restart | Remaining tile restores identical stored path, map ID and client pixel hash |

The fixture sets up inventories and accelerates real dropped-item destruction.
It observes actual `EntityRemoveEvent` causes rather than directly calling the
plugin's destruction handler. Commands and inventory clicks come through the
real network client. A test-only two-second storage-monitor delay makes the
hand-change and disconnect cancellation cases deterministic.

After intentionally deleting one tile from the original 2×2 artwork, restart
logged `Incomplete tile matrix` while attempting to read its whole-artwork
metadata. The surviving tile still reloaded and rendered correctly. The isolated
offline server also logged a failed Mojang public-key request; this did not affect
the test connection. No copy-service or destruction-handler exception occurred.

Creative duplication and arbitrary third-party plugin item cloning are outside
the supported copy feature and were not treated as passing scenarios. This
validation does not claim to inventory or separate pre-existing shared copies.

Local evidence is stored under
`/home/agent/.local/state/mcpc/recolorfulmap-copy-e2e/`: `result.json`,
`before-report.json`, `after-report.json`, `disconnect-report.json`, client logs
and server logs. No production server, player inventory or artwork was used.
