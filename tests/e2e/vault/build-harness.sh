#!/usr/bin/env bash
set -euo pipefail
if [[ $# != 4 ]]; then
  echo "Usage: $0 JDK_DIRECTORY PAPER_API_JAR VAULT_API_JAR DISPOSABLE_SERVER_DIRECTORY" >&2
  exit 2
fi
jdk_dir="$1"
api_jar="$2"
vault_api="$3"
server_dir="$4"
if [[ ! -f "${server_dir}/.recolorfulmap-e2e.json" ]]; then
  echo 'Refusing to deploy the test-only Vault provider outside a marked disposable E2E server.' >&2
  exit 2
fi
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
build_dir="${server_dir}/.rcmvault-build"
mkdir -p "${build_dir}/classes" "${server_dir}/plugins"
classpath="${api_jar}:${vault_api}"
while IFS= read -r dependency; do
  classpath="${classpath}:${dependency}"
done < <(rg --files "${server_dir}/libraries" | rg '\.jar$')
"${jdk_dir}/bin/javac" -cp "${classpath}" -d "${build_dir}/classes" "${script_dir}/java/RCMTestVault.java"
(cd "${build_dir}/classes" && "${jdk_dir}/bin/jar" --extract --file "${vault_api}" net/milkbowl/vault/economy)
cp "${script_dir}/java/plugin.yml" "${build_dir}/classes/plugin.yml"
"${jdk_dir}/bin/jar" --create --file "${server_dir}/plugins/Vault.jar" -C "${build_dir}/classes" .
