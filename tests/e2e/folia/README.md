# Folia 26.2 smoke test

This separate harness uses entity schedulers and real Minecraft protocol packets. It does not use the Paper harness or `BukkitScheduler`.

Prepare a disposable Folia server, keeping it separate from the Paper test servers:

```sh
python3 tests/e2e/paper_server.py --root /tmp/recolorfulmap-folia-e2e prepare \
  --platform folia --port 25578 --plugin build/libs/ReColorfulMap-4.0-rc.1.jar \
  --vanilla-cache /tmp/recolorfulmap-26.2-server.jar
```

The optional vanilla cache must be the official Minecraft 26.2 server bundle; its checksum is verified. The script downloads and verifies Folia build 7 and Java 25. To reuse an existing test environment, symlink the new root's `downloads/` and `tools/` to that environment before preparing it.

Compile the observer using the downloaded 26.2 API and the dependency jars from a previously started Paper or Folia server:

```sh
bash tests/e2e/folia/build-harness.sh \
  /tmp/recolorfulmap-folia-e2e/jdk /tmp/recolorfulmap-26.2api.jar \
  /tmp/recolorfulmap-e2e/server /tmp/recolorfulmap-folia-e2e/server
python3 tests/e2e/paper_server.py --root /tmp/recolorfulmap-folia-e2e start
```

Install the protocol client as described in [its README](../client/README.md), and serve the test gradient PNG locally. The default image URL is `http://127.0.0.1:18180/gradient.png`; set `FOLIA_IMAGE_URL` to use another colorful PNG. After the server reports `Done`:

```sh
node tests/e2e/folia/smoke.js before-restart
python3 tests/e2e/paper_server.py --root /tmp/recolorfulmap-folia-e2e stop
python3 tests/e2e/paper_server.py --root /tmp/recolorfulmap-folia-e2e start
# Wait for the server to report Done.
node tests/e2e/folia/smoke.js after-restart
python3 tests/e2e/paper_server.py --root /tmp/recolorfulmap-folia-e2e stop
```

The two phases verify login, `/tomap`, the PDC path and PNG, actual client map pixels, success/warning/download-error notifications received by the client, dropped-item pickup, restart restoration, and automatic file deletion after an observed `OUT_OF_WORLD` removal. With the default 256×256 PNG, they also place and retrieve a 2×2 wall twice, retrieve it from a non-origin tile, receive image pixels for every tile, and verify reuse of the thumbnail and tile map IDs. The wall observer uses the owning region thread. Client pixels are reconstructed from both full and incremental map packets before comparing hashes. The report records the exact plugin SHA-256 and requires both phases to use that artifact. The test leaves its report at `/tmp/recolorfulmap-folia-e2e/results.json` and preserves the server logs. `FOLIA_E2E_ROOT` can select a different root for the client; `FOLIA_E2E_REPORT` can preserve an earlier report by selecting another report path for both phases.

The Folia smoke does not repeat the Paper suite's complete wall orientation/click-position matrix, protection, migration, or all destruction causes.
