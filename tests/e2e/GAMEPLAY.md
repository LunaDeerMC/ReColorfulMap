# Real Paper 26.2 gameplay checks

These tools operate only on disposable localhost test servers. `RCME2E` is a
fixture and observation plugin that grants operator privileges to the offline
player `RCMTester`; never install it on a public server. The build script requires
the ownership marker created by `paper_server.py`.

The client sends real 26.2 login, command, item-frame interaction, attack, inventory
slot and drop packets. The helper prepares blocks/frames, positions the player,
sets physical item conditions, installs test protection listeners and records
real Bukkit events. It never calls ReColorfulMap business methods or fabricates
Bukkit events.

Prerequisites: Node.js 22+, Java 25, a prepared Paper 26.2 server, and the Paper API
JAR. The API JAR is also available under the server's `libraries` after its first
boot. Install the production plugin before the helper.

```sh
bash tests/e2e/client/setup.sh
bash tests/e2e/build-harness.sh /path/to/jdk25 /path/to/paper-api.jar /path/to/disposable/server
python3 tests/e2e/paper_server.py --root /path/to/disposable start
RCM_E2E_SERVER=/path/to/disposable/server node tests/e2e/run-scenarios.js
```

Wait for the server's `Done` message before connecting. The default server path
is `/tmp/recolorfulmap-e2e/server`, default port is `25576`; set `RCM_E2E_PORT` for
another port. The driver serves its own deterministic PNG over a temporary
localhost HTTP port. Protocol dependencies and the additional 26.2 schema are
pinned in `client/`.

The first driver covers generation and scaling, four wall directions × every tile, repeated
whole-art pickup and placement, protected neighbours, cancelled pickup-drop
creation, full inventory, changing the held slot during placement, missing/mixed frame matrices, natural player pickup,
actual fire/despawn/void item removal, and chunk unloading/reloading. It writes
`gameplay-report.json` alongside the server directory, including the production
JAR SHA-256, received map
packets and the final saved wall/inventory checkpoint. The client accumulates
all full and incremental map packets into complete pixel buffers before hashing.

After that driver succeeds, perform a real server stop/start, then run:

```sh
python3 tests/e2e/paper_server.py --root /path/to/disposable stop
python3 tests/e2e/paper_server.py --root /path/to/disposable start
RCM_E2E_SERVER=/path/to/disposable/server node tests/e2e/run-after-restart.js
```

The second driver compares restored item paths, renderers and complete pixel
hashes with the pre-restart checkpoint. It also checks cancelled delivery when
generating into a full inventory, ordinary full-inventory delivery and their actual
failure/success notifications, native item
merging and cancelled despawn followed by actual despawn. Its report is
`restart-extended-report.json`. It leaves an expanded legacy-format fixture for
the offline migration tests: map IDs generated through real Paper APIs, a 2×2
wall, inventory, ender chest, chest, nested shulker, persistent dropped map,
unindexed vanilla maps, and unrelated Unicode metadata/PDC. The fixture manifest
is `plugins/RCME2E/legacy-fixture.json`.

Stop the server before running the migration CLI and independent storage oracle
in `scripts/e2e`. After they succeed, start it and run
`RCM_E2E_SERVER=/path/to/disposable/server node tests/e2e/run-after-migration.js`.
This final driver checks the actual loaded containers and untouched vanilla maps,
the migrated wall's complete pixel hashes, and recovery/replacement through a
real client attack on a non-origin tile. It writes `migration-gameplay-report.json`.
Stop the disposable server again after the checks.

For the additional unrelated-NBT regression used in the recorded run, issue this
console command after creating the fixture and before stopping for migration:

```text
data modify block 104 80 101 Items[{Slot:0b}].components."minecraft:custom_data".unrelated set value {id:"中文🌈",value:42}
```

The storage oracle must take its baseline after this edit.

The expanded fixture reproduces the old data format using Paper APIs; the separate
`scripts/e2e` legacy-upgrade test uses the actual ColorfulMap 2.3 JAR to generate
its source items. Reports identify these two provenance paths separately.

No test helper or protocol dependency is packaged into the production plugin JAR.

## Optimization regressions

The additional optimization driver requires the deterministic test Vault provider:

```sh
bash tests/e2e/vault/build-harness.sh /path/to/jdk25 /path/to/paper-api.jar /path/to/VaultAPI-1.7.jar /path/to/disposable/server
RCM_E2E_REPORT=/path/to/optimization-features.json node tests/e2e/run-optimizations.js before-restart
python3 tests/e2e/paper_server.py --root /path/to/disposable stop
python3 tests/e2e/paper_server.py --root /path/to/disposable start
RCM_E2E_REPORT=/path/to/optimization-features.json node tests/e2e/run-optimizations.js after-restart
```

The provider is named `Vault`, embeds only the public Vault economy API and
registers an `Economy` service through Bukkit's real `ServicesManager`. Production
code performs its usual balance/withdraw/refund calls; the provider writes an
independent transaction log. Its balance is deterministic, and resets on server
startup. Never install this provider on a real server.

The driver temporarily enables a price of 10.5 per tile, saves the previous
configuration under the test helper directory, and restores it at the end of the
second phase. It checks exact MapView ID reuse across repeated whole-wall
retrieval, generation-only charging, failed download/no charge, ambiguous delivery
freezing without duplicate charges or reissue, insufficient-funds recovery, invalid YAML
preservation, live debug reload, all 136 tile canvases over the protocol,
ordinary/glowing-frame support-block destruction with protection/rollback checks,
shulker/bundle/nested-container destruction, cancellation and offline completion.
The server is then stopped and restarted before claiming the offline artwork;
repeated claims must neither duplicate items nor charge again. The next boot
disables charging: the previous pending quote must remain payable while a new
artwork is generated for free. Server state
inspection only reads public configuration getters and Bukkit APIs.

All drivers support `RCM_E2E_REPORT` to preserve earlier reports.
`run-after-migration.js` additionally accepts `RCM_E2E_BASELINE` for the first
phase pixel reference. The Folia smoke uses `FOLIA_E2E_REPORT` for both restart
phases. A recovered entity is identified by its UUID and actual pickup event,
not by a newly allocated map ID: ID reuse is the expected production behavior.

When preserving evidence across a test-observer correction,
`RCM_E2E_RESUME_CONTAINERS=1` resumes only the unfinished container/cancellation/
offline cases from an existing same-JAR report. Prior results, transactions,
messages and the failure reason are kept as report segments. Normal clean runs
do not need this flag.

The deterministic images are checked only after every expected map has a complete
nonblank canvas. Paper normally retries an initially blank lazy-rendered frame
on a later map-update interval; a fixed short delay is not a rendering oracle.
No driver calls `Player.sendMap` to force refreshes.

`run-original-upgrade.js` can additionally reopen the saved world produced by the
actual old ColorfulMap 2.3 plugin (the earlier upgrade fixture), verify all original
PNG hashes and container references, then retrieve/re-place its existing wall
without recreating or clearing that fixture. See the script's environment
variables for its root and report paths.
