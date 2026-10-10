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
