#!/usr/bin/env bash
set -euo pipefail
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
venv_dir="${RECOLORFULMAP_MIGRATION_VENV:-${XDG_CACHE_HOME:-${HOME}/.cache}/recolorfulmap-migration-venv}"
if [[ ! -x "${venv_dir}/bin/python" ]]; then
    python3 -m venv "${venv_dir}"
fi
"${venv_dir}/bin/python" -m pip --disable-pip-version-check install --quiet -r "${script_dir}/requirements.txt"
exec "${venv_dir}/bin/python" "${script_dir}/migrate_colorfulmap.py" "$@"
