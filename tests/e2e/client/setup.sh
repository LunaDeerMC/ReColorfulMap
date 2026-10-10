#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
npm ci --ignore-scripts
schema_commit=4005723728fe00435209ebdd2234e9fb1184a184
schema_archive=minecraft-data-26.2.tar.gz
if [[ ! -f "$schema_archive" ]]; then
  curl -fsSL "https://codeload.github.com/0xmthan/minecraft-data/tar.gz/${schema_commit}" -o "$schema_archive"
fi
printf '%s  %s\n' f4c07331d0c29eb376985c897f986056ea12d9253034481c533772f8b98a84e9 "$schema_archive" | sha256sum --check
rm -rf node_modules/minecraft-data/minecraft-data
mkdir -p node_modules/minecraft-data/minecraft-data
tar -xzf "$schema_archive" --strip-components=1 -C node_modules/minecraft-data/minecraft-data
node node_modules/minecraft-data/bin/generate_data.js
node -e "console.log(require('minecraft-data')('26.2').version)"
