# Independent-copy runtime regression

Run only on a disposable localhost server. `CopyProbe` grants privileges to its
test account and clears fixture inventories. Never install it in production.

The fixture plugin prepares image-map inputs, records real inventory state and
observes entity removal causes. `client.js` sends actual Minecraft 26.1 protocol
commands and window clicks. Copies use `/mapmanage copy`; workbench/cartography
clicks must be refused without consuming materials. Creative-mode copying is
outside the supported feature and is not presented as a passing copy scenario.
The fixture does not directly invoke the copy command or copy service.

Prepare a separate DeerFoliaPlus/Folia 26.1.2 server on `127.0.0.1:25695` with
Java 25 and two complete 2×2 PNG artwork fixtures. Compile `java/CopyProbe.java`
against the candidate plugin JAR and that server's API/libraries, package its `plugin.yml`, and create
`plugins/CopyProbe/fixture.properties` with
`original=d7204b5e-f969-4eac-a317-33dd3b9c6c11` and
`tiles=24a8c7fb-0f89-48d4-8015-432e22d805c8`.
The fixture directories use the same `.independent-artwork` ownership marker as
newly generated artwork; keep their pristine copies under `<root>/fixtures` for
repeat runs. Configure a flat, isolated world with no external
database or messaging connections. Accept the Minecraft EULA only when authorized.

Install `minecraft-protocol` and `prismarine-nbt` into a separate test directory.
Set `COPY_PROBE_NODE_MODULES` to its `node_modules` path, then run:

```bash
python3 tests/e2e/copy-independence/run.py \
  --root /path/to/disposable-copy-test \
  --plugin build/libs/ReColorfulMap-5.0.1.jar \
  --java /path/to/java25/bin/java
```

The driver checks whole-artwork and loose-tile command copies, independent paths
and map IDs, PNG bytes and client pixels, actual despawn/fire/void deletion,
preservation of legacy artwork without an independent ownership marker,
full-inventory rejection, cancelled copies and image restoration after a normal
restart. For cancellation tests, the fixture deliberately holds the storage
monitor for two seconds while the real client changes its held slot or disconnects.
This controlled delay makes the asynchronous boundary observable; it is test-only
fault injection, not normal server behavior.
The report records the exact plugin SHA and each completed assertion; a tool
description or fixture setup alone is not a passing runtime result.

See [the recorded 5.0.1 run](VERIFICATION.md) for the tested artifact and results.
