# Minecraft 26.2 actual network test client

Requires Node.js 22+ (verified runtime 24.19.0).

```sh
bash setup.sh
node bot.js
```

`setup.sh` uses package-lock.json for npm dependencies and pins the Minecraft 26.2
schema to upstream commit `4005723728fe00435209ebdd2234e9fb1184a184` (PR 1333).
The archive SHA-256 is checked before extracting the schema and generating
the minecraft-data index. Published minecraft-data 3.117.0 only has complete
26.1 schemas; 26.2's protocol ID is 776 and DataVersion is 4903.

Defaults: offline RCMTester, 127.0.0.1:25576; `PORT=... node bot.js` overrides port.

CLI accepts one JSON action per line:

```json
{"command":"rcme2e state"}
{"interact":123}
{"attack":123}
{"hotbar":0}
{"dropStack":true}
{"state":true}
{"close":true}
```

Importable API:

```js
const { connect } = require('./bot')
const bot = await connect()
bot.command('rcme2e state')
bot.interact(123)
bot.attack(123)
bot.dropStack()
bot.close()
```

The exposed `client` is minecraft-protocol's real socket client. `ready` completes
when server teleport is acknowledged and the client's position/player_loaded
packets have been sent. `maps` records actual map packets received from server.
The caller can listen on `map`, `chat`, `inventory`, `teleport`, `packet`, `end`
and `clientError`, or register more packet listeners through `client`.

26.2 uses a separate `attack` packet. Its `use_entity` packet contains target,
hand, local hit location and sneaking, with no legacy action/mouse field.

The client has no pathfinding or physics. Server harness must position the
player in reach and keep flight allowed if suspended in air.

Verified against a real Paper 26.2 server on port 25576: offline login and
configuration completed, initial teleport was acknowledged, 29 registry-data
packets and 81 chunk packets decoded, player inventory/health updates arrived,
and three keep-alive exchanges completed without protocol decoding errors.
The probe then disconnected normally. Gameplay actions have been serialized
against the 26.2 schema and are ready for the separate scenario harness.
