#!/usr/bin/env bash
set -euo pipefail
if [[ $# != 3 ]]; then
    echo "Usage: $0 JDK_DIRECTORY PAPER_API_JAR DISPOSABLE_SERVER_DIRECTORY" >&2
    exit 2
fi
jdk_dir="$1"
api_jar="$2"
server_dir="$3"
if [[ ! -f "${server_dir}/.recolorfulmap-e2e.json" ]]; then
    echo "Refusing to deploy the test-only auto-op plugin outside a marked disposable E2E server." >&2
    exit 2
fi
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
build_dir="${server_dir}/.rcme2e-build"
mkdir -p "${build_dir}/classes" "${server_dir}/plugins"
classpath="${api_jar}"
while IFS= read -r dependency; do
    classpath="${classpath}:${dependency}"
done < <(rg --files "${server_dir}/libraries" | rg '\.jar$')
"${jdk_dir}/bin/javac" -cp "${classpath}" -d "${build_dir}/classes" "${script_dir}/java/RCME2E.java"
cp "${script_dir}/java/plugin.yml" "${build_dir}/classes/plugin.yml"
"${jdk_dir}/bin/jar" --create --file "${server_dir}/plugins/RCME2E.jar" -C "${build_dir}/classes" .
