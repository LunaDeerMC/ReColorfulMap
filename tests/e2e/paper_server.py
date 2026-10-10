#!/usr/bin/env python3
"""Prepare and control a disposable, localhost-only Paper 26.2 server for E2E tests."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import time
import urllib.request
import uuid


PAPER_URL = (
    "https://fill-data.papermc.io/v1/objects/"
    "bf1dcf627364f8a631ab15250d240eb5a8e89faaac523b391e1c61367fce9ff1/paper-26.2-133.jar"
)
PAPER_SHA256 = "bf1dcf627364f8a631ab15250d240eb5a8e89faaac523b391e1c61367fce9ff1"
FOLIA_URL = (
    "https://fill-data.papermc.io/v1/objects/"
    "128a634192261cd38bb4a5dc54075018a0f896fd6c6f529e37dca6e99e32b3b3/folia-26.2-7.jar"
)
FOLIA_SHA256 = "128a634192261cd38bb4a5dc54075018a0f896fd6c6f529e37dca6e99e32b3b3"
JAVA_URL = (
    "https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.4.1%2B1/"
    "OpenJDK25U-jdk_x64_linux_hotspot_25.0.4.1_1.tar.gz"
)
JAVA_SHA256 = "dbb698396d478e7fa2b1e50f4103324b2a99b90569ee27c33f2261f9215cf41e"
JAVA_DIRECTORY = "jdk-25.0.4.1+1"
VANILLA_SHA256 = "cdacdfb25898de5e4b4b0e5ddcc2722f77067e46605709c2d886c000ebb63ec5"
MARKER = ".recolorfulmap-e2e.json"


def require_owned_server(root: Path, *, prepare: bool = False) -> None:
    server = root / "server"
    marker = server / MARKER
    if not server.exists():
        if not prepare:
            raise RuntimeError("Run prepare before controlling the E2E server")
        server.mkdir(parents=True)
    if not marker.exists():
        if not prepare or any(server.iterdir()):
            raise RuntimeError(f"Refusing to use a directory without an E2E ownership marker: {server}")
        marker.write_text(json.dumps({"tool": "recolorfulmap-e2e", "version": 1,
                                      "id": str(uuid.uuid4())}) + "\n", encoding="utf-8")
    if marker.is_symlink() or not marker.is_file():
        raise RuntimeError("The E2E ownership marker must be a regular file")
    ownership = json.loads(marker.read_text(encoding="utf-8"))
    if ownership.get("tool") != "recolorfulmap-e2e" or ownership.get("version") != 1:
        raise RuntimeError("Unrecognized E2E ownership marker")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for data in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(data)
    return digest.hexdigest()


def download(url: str, path: Path, checksum: str) -> None:
    if path.exists() and sha256(path) == checksum:
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".part")
    request = urllib.request.Request(url, headers={"User-Agent": "ReColorfulMap-E2E/1.0"})
    with urllib.request.urlopen(request, timeout=60) as response, temporary.open("wb") as output:
        shutil.copyfileobj(response, output)
    if sha256(temporary) != checksum:
        raise RuntimeError(f"Checksum mismatch: {path.name}")
    temporary.replace(path)


def server_pid(root: Path) -> int | None:
    path = root / "server/server.pid"
    if not path.exists():
        return None
    pid = int(path.read_text().strip())
    if pid <= 1:
        raise RuntimeError("Invalid E2E server PID")
    try:
        os.kill(pid, 0)
        process = Path(f"/proc/{pid}")
        if (process / "stat").read_text().split(") ", 1)[1].startswith("Z "):
            return None
        argv = (process / "cmdline").read_bytes().split(b"\0")
        if ((process / "cwd").resolve(strict=True) != (root / "server").resolve()
                or (process / "exe").resolve(strict=True) != (root / "jdk/bin/java").resolve()
                or b"-jar" not in argv
                or argv[argv.index(b"-jar") + 1] != b"paper.jar"):
            raise RuntimeError("The saved PID belongs to a different process; refusing to control it")
    except (ProcessLookupError, FileNotFoundError):
        return None
    return pid


def prepare(args: argparse.Namespace) -> None:
    root = args.root
    require_owned_server(root, prepare=True)
    if server_pid(root):
        raise RuntimeError("Stop the disposable server before preparing or replacing plugin files")
    downloads = root / "downloads"
    if args.platform == "folia":
        server_artifact = downloads / "folia-26.2-7.jar"
        download(FOLIA_URL, server_artifact, FOLIA_SHA256)
    else:
        server_artifact = downloads / "paper-26.2-133.jar"
        download(PAPER_URL, server_artifact, PAPER_SHA256)
    java = root / "tools" / JAVA_DIRECTORY
    if not (java / "bin/java").exists():
        download(JAVA_URL, downloads / "jdk25.tar.gz", JAVA_SHA256)
        with tarfile.open(downloads / "jdk25.tar.gz", "r:gz") as archive:
            archive.extractall(root / "tools", filter="data")
    if not (root / "jdk").exists():
        (root / "jdk").symlink_to(java, target_is_directory=True)
    server = root / "server"
    server.mkdir(parents=True, exist_ok=True)
    # Keep a stable launch filename so the same process checks work for either platform.
    shutil.copy2(server_artifact, server / "paper.jar")
    if args.vanilla_cache:
        if sha256(args.vanilla_cache) != VANILLA_SHA256:
            raise RuntimeError("The supplied vanilla cache is not Minecraft 26.2")
        (server / "cache").mkdir(exist_ok=True)
        shutil.copy2(args.vanilla_cache, server / "cache/mojang_26.2.jar")
    (server / "plugins/bStats").mkdir(parents=True, exist_ok=True)
    (server / "plugins/bStats/config.yml").write_text("enabled: false\n", encoding="utf-8")
    if args.plugin:
        shutil.copy2(args.plugin, server / "plugins/ReColorfulMap.jar")
    (server / "config").mkdir(exist_ok=True)
    if not (server / "config/paper-global.yml").exists():
        (server / "config/paper-global.yml").write_text(
            "_version: 31\nspark:\n  enabled: false\n  enable-immediately: false\n", encoding="utf-8"
        )
    (server / "eula.txt").write_text("# Disposable server for the authorized E2E test.\neula=true\n")
    if not (server / "server.properties").exists():
        properties = {
            "server-ip": "127.0.0.1",
            "server-port": args.port,
            "online-mode": "false",
            "enforce-secure-profile": "false",
            "enable-rcon": "false",
            "enable-query": "false",
            "level-name": "world",
            "level-type": "minecraft:flat",
            "generator-settings": json.dumps({"biome": "minecraft:plains", "layers": [
                {"block": "minecraft:bedrock", "height": 1},
                {"block": "minecraft:stone", "height": 2},
                {"block": "minecraft:grass_block", "height": 1},
            ]}, separators=(",", ":")),
            "generate-structures": "false",
            "spawn-protection": 0,
            "view-distance": 3,
            "simulation-distance": 3,
            "max-players": 10,
            "difficulty": "peaceful",
            "pause-when-empty-seconds": -1,
            "motd": "ReColorfulMap disposable E2E server",
        }
        (server / "server.properties").write_text(
            "".join(f"{key}={value}\n" for key, value in properties.items()), encoding="utf-8"
        )
    print(f"Prepared {server}")


def start(args: argparse.Namespace) -> None:
    root = args.root
    require_owned_server(root)
    if (pid := server_pid(root)):
        raise RuntimeError(f"Server is already running with PID {pid}")
    server = root / "server"
    properties = dict(line.split("=", 1) for line in (server / "server.properties").read_text().splitlines()
                      if "=" in line and not line.lstrip().startswith(("#", "!")))
    if properties.get("server-ip") != "127.0.0.1" or properties.get("online-mode") != "false":
        raise RuntimeError("The E2E server must be localhost-only and in offline mode")
    fifo = server / "control.fifo"
    fifo.unlink(missing_ok=True)
    os.mkfifo(fifo, mode=0o600)
    descriptor = os.open(fifo, os.O_RDWR)
    command = [str(root / "jdk/bin/java"), "-Xms512m", "-Xmx2g", "-Djava.awt.headless=true",
               "-Dterminal.jline=false", "-Dterminal.ansi=false", "-jar", "paper.jar", "--nogui"]
    logfile = server / "console.log"
    with logfile.open("ab", buffering=0) as output:
        output.write(f"\n=== E2E start {time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime())} ===\n".encode())
        process = subprocess.Popen(command, stdin=descriptor, stdout=output, stderr=subprocess.STDOUT,
                                   cwd=server, start_new_session=True)
    os.close(descriptor)
    (server / "server.pid").write_text(str(process.pid) + "\n")
    print(f"Started PID {process.pid}; log={logfile}; console={fifo}")


def send_command(root: Path, command: str) -> None:
    require_owned_server(root)
    if not server_pid(root):
        raise RuntimeError("Server is not running")
    if "\n" in command or "\r" in command:
        raise ValueError("Send one console command per invocation")
    descriptor = os.open(root / "server/control.fifo", os.O_WRONLY | os.O_NONBLOCK)
    try:
        os.write(descriptor, (command + "\n").encode("utf-8"))
    finally:
        os.close(descriptor)


def stop(args: argparse.Namespace) -> None:
    require_owned_server(args.root)
    if not server_pid(args.root):
        print("Server is already stopped")
        return
    send_command(args.root, "stop")
    deadline = time.monotonic() + 55
    while server_pid(args.root) and time.monotonic() < deadline:
        time.sleep(0.25)
    if server_pid(args.root):
        raise RuntimeError("Server has not finished stopping; inspect console.log before retrying")
    print("Server stopped cleanly")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path("/tmp/recolorfulmap-e2e"))
    commands = parser.add_subparsers(dest="action", required=True)
    setup = commands.add_parser("prepare")
    setup.add_argument("--plugin", type=Path)
    setup.add_argument("--vanilla-cache", type=Path)
    setup.add_argument("--port", type=int, default=25576)
    setup.add_argument("--platform", choices=("paper", "folia"), default="paper")
    commands.add_parser("start")
    commands.add_parser("stop")
    commands.add_parser("status")
    console = commands.add_parser("command")
    console.add_argument("text")
    args = parser.parse_args()
    args.root = args.root.resolve()
    if args.action == "prepare":
        prepare(args)
    elif args.action == "start":
        start(args)
    elif args.action == "stop":
        stop(args)
    elif args.action == "status":
        require_owned_server(args.root)
        print(json.dumps({"pid": server_pid(args.root), "server": str(args.root / "server")}))
    else:
        send_command(args.root, args.text)


if __name__ == "__main__":
    main()
