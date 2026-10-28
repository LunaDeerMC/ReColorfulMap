"""Run only in a separately prepared, disposable local CopyProbe server directory."""
from pathlib import Path
import argparse, hashlib, json, queue, shutil, socket, subprocess, threading, time

parser = argparse.ArgumentParser()
parser.add_argument('--root', type=Path, required=True)
parser.add_argument('--plugin', type=Path, required=True)
parser.add_argument('--java', type=Path, required=True)
args = parser.parse_args()
root = args.root.resolve()
server = root / 'server'
assert (server / 'plugins/CopyProbe/fixture.properties').is_file(), 'Disposable fixture is required'
assert 'server-ip=127.0.0.1' in (server / 'server.properties').read_text()
assert 'server-port=25695' in (server / 'server.properties').read_text()
assert args.plugin.is_file()
with socket.socket() as port:
    port.bind(('127.0.0.1', 25695))
plugin = server / 'plugins/ReColorfulMap.jar'
shutil.copy2(args.plugin, plugin)
for fixture in (root / 'fixtures').iterdir():
    shutil.copytree(fixture, server / 'plugins/ReColorfulMap/maps' / fixture.name, dirs_exist_ok=True)
legacy = server / 'plugins/ReColorfulMap/maps/8aa62018-68c2-4b20-92b2-704f85c335ab'
shutil.copytree(root / 'fixtures/d7204b5e-f969-4eac-a317-33dd3b9c6c11', legacy, dirs_exist_ok=True)
(legacy / '.independent-artwork').unlink(missing_ok=True)
digest = hashlib.file_digest(plugin.open('rb'), 'sha256').hexdigest()
result = {'plugin': str(args.plugin), 'sha256': digest, 'root': str(root), 'port': 25695, 'phases': []}
driver = Path(__file__).resolve().parent / 'client.js'

for phase in ['before', 'after']:
    (server / 'plugins/CopyProbe/state.json').unlink(missing_ok=True)
    lines = queue.Queue()
    process = subprocess.Popen([str(args.java), '--enable-native-access=ALL-UNNAMED',
        '-Dterminal.jline=false', '-Dterminal.ansi=false', '-XX:ActiveProcessorCount=4',
        '-Xms256m', '-Xmx1024m', '-jar', 'server.jar', '--nogui'], cwd=server,
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
    (root / 'process.json').write_text(json.dumps({'pid': process.pid, 'phase': phase, 'root': str(server)}))
    def read_output():
        with (root / (phase + '-server.log')).open('w') as log:
            for line in process.stdout:
                log.write(line); log.flush(); lines.put(line)
            lines.put(None)
    reader = threading.Thread(target=read_output, daemon=True)
    reader.start()
    ready = False
    client = None
    try:
        deadline = time.monotonic() + 180
        while time.monotonic() < deadline:
            try: line = lines.get(timeout=.5)
            except queue.Empty: continue
            if line is None: break
            if 'Done (' in line:
                ready = True
                print('SERVER_READY ' + phase, flush=True)
                break
        assert ready, 'Disposable server did not become ready'
        if phase == 'before':
            disconnected = subprocess.run(['node', str(driver.parent / 'disconnect.js'), str(root)],
                capture_output=True, text=True, timeout=40)
            (root / 'disconnect-client.log').write_text(disconnected.stdout + disconnected.stderr)
            assert disconnected.returncode == 0, 'Disconnect probe failed'
            time.sleep(3)
            disconnect_report = json.loads((root / 'disconnect-report.json').read_text())
            assert sorted(path.name for path in (server / 'plugins/ReColorfulMap/maps').iterdir()) == disconnect_report['directories'], 'Cancelled copy left extra files'
            result['disconnectCancellationFilesVerified'] = True
        client = subprocess.run(['node', str(driver), str(root), phase], capture_output=True, text=True, timeout=300)
        (root / (phase + '-client.log')).write_text(client.stdout + client.stderr)
        print('CLIENT_DONE', phase, client.returncode, client.stdout[-1800:], client.stderr[-1800:], flush=True)
    finally:
        if process.poll() is None:
            process.stdin.write('stop\n'); process.stdin.flush()
            try: process.wait(timeout=90)
            except subprocess.TimeoutExpired:
                process.terminate()
                try: process.wait(timeout=10)
                except subprocess.TimeoutExpired: process.kill(); process.wait()
        reader.join(timeout=5)
        row = {'phase': phase, 'ready': ready, 'serverExit': process.returncode,
               'clientExit': None if client is None else client.returncode}
        result['phases'].append(row)
        (root / 'result.json').write_text(json.dumps(result, indent=2))
        print('SERVER_STOPPED', json.dumps(row), flush=True)
    if client is None or client.returncode != 0:
        raise SystemExit(1)
print(json.dumps(result), flush=True)
