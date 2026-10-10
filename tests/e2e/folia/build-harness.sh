#!/usr/bin/env bash
set -euo pipefail
if [[ $# != 4 ]]; then
    echo "Usage: $0 JDK_DIRECTORY PAPER_API_JAR DEPENDENCY_SERVER_DIRECTORY FOLIA_SERVER_DIRECTORY" >&2
    exit 2
fi
jdk_dir="$1"
api_jar="$2"
dependency_server_dir="$3"
server_dir="$4"
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
build_dir="${server_dir}/.rcmfolia-build"
mkdir -p "${build_dir}/classes" "${server_dir}/plugins"
classpath="${api_jar}"
while IFS= read -r dependency; do
    classpath="${classpath}:${dependency}"
done < <(rg --files "${dependency_server_dir}/libraries" | rg '\.jar$')
"${jdk_dir}/bin/javac" -cp "${classpath}" -d "${build_dir}/classes" "${script_dir}/java/RCMFoliaE2E.java"
cp "${script_dir}/java/plugin.yml" "${build_dir}/classes/plugin.yml"
"${jdk_dir}/bin/jar" --create --file "${server_dir}/plugins/RCMFoliaE2E.jar" -C "${build_dir}/classes" .
