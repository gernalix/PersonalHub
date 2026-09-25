importScripts('pyodide/pyodide.js');

let pyodide = null;
let ready = null;

function log(line) {
  self.postMessage({type: 'log', line: String(line)});
}

async function fetchBytes(url) {
  const response = await fetch(url, {cache: 'no-store'});
  if (!response.ok) throw new Error(`Failed local asset: ${url} (${response.status})`);
  return new Uint8Array(await response.arrayBuffer());
}

async function fetchText(url) {
  const response = await fetch(url, {cache: 'no-store'});
  if (!response.ok) throw new Error(`Failed local asset: ${url} (${response.status})`);
  return response.text();
}

async function sha256Hex(bytes) {
  const digest = await crypto.subtle.digest('SHA-256', bytes);
  return Array.from(new Uint8Array(digest), b => b.toString(16).padStart(2, '0')).join('');
}
async function writeVerified(path, url, expectedHash) {
  const bytes = await fetchBytes(url);
  const actual = await sha256Hex(bytes);
  if (actual !== expectedHash) throw new Error(`Asset checksum mismatch: ${url}`);
  const parent = path.substring(0, path.lastIndexOf('/'));
  if (parent) pyodide.FS.mkdirTree(parent);
  pyodide.FS.writeFile(path, bytes);
}

async function installVendoredWheels(manifest) {
  const wheelPaths = [];
  for (const wheel of manifest.datasette.wheels) {
    const relative = `wheels/${wheel.file}`;
    const path = `/wheels/${wheel.file}`;
    await writeVerified(path, relative, wheel.sha256);
    wheelPaths.push(path);
  }
  pyodide.globals.set('ph_wheel_paths_json', JSON.stringify(wheelPaths));
  await pyodide.runPythonAsync(`
import importlib, json, pathlib, zipfile
site_packages = pathlib.Path('/lib/python3.12/site-packages')
for wheel_path in json.loads(ph_wheel_paths_json):
    with zipfile.ZipFile(wheel_path) as wheel:
        wheel.extractall(site_packages)
importlib.invalidate_caches()
`);
}

async function installProjector(manifest) {
  for (const item of manifest.projector.files) {
    const path = '/' + item.file;
    await writeVerified(path, item.file, item.sha256);
  }
}
async function preparePresentation(snapshotUrl) {
  const snapshotLocation = new URL(snapshotUrl, self.location.href);
  if (snapshotLocation.origin !== self.location.origin || !snapshotLocation.pathname.startsWith('/snapshot/')) {
    throw new Error('Detached snapshot must come from the local /snapshot/ origin');
  }
  const manifest = JSON.parse(await fetchText('vendor-manifest.json'));
  log('Loading Pyodide 0.27.2 from app assets');
  pyodide = await loadPyodide({indexURL: 'pyodide/'});
  await pyodide.loadPackage(manifest.pyodide.load_packages, {messageCallback: log});
  log('Installing vendored Datasette wheels');
  await installVendoredWheels(manifest);
  await installProjector(manifest);

  log('Copying detached PersonalHub snapshot');
  const snapshot = await fetchBytes(snapshotLocation.href);
  pyodide.FS.writeFile('/personalhub.db', snapshot);
  pyodide.globals.set('ph_snapshot_bytes', snapshot.length);

  log('Building read-only relational presentation');
  await pyodide.runPythonAsync(`
import json, sqlite3, sys
from pathlib import Path
sys.path.insert(0, '/projector')
from scripts.personalhub_local_envelope import build_local_envelope
from scripts.personalhub_projection import project, configure

build_local_envelope('/personalhub.db', '/personalhub_envelope.db')
project('/personalhub_envelope.db', '/personalhub_read.db', '/projector/schema.json', reconcile=True)
Path('/datasette.yaml').write_text('{}')
Path('/metadata.json').write_text('{}')
configure('/datasette.yaml', '/metadata.json', '/projector/schema.json')
`);
}
async function startDatasette() {
  await pyodide.runPythonAsync(`
import json, sqlite3
from datasette.app import Datasette

with sqlite3.connect('/personalhub_read.db') as db:
    db.execute('PRAGMA wal_checkpoint(TRUNCATE)')
    db.execute('PRAGMA journal_mode=DELETE')
    if list(db.execute('PRAGMA foreign_key_check')):
        raise RuntimeError('Offline presentation foreign-key check failed')
    if db.execute('PRAGMA quick_check').fetchone()[0] != 'ok':
        raise RuntimeError('Offline presentation quick_check failed')

metadata = json.loads(open('/metadata.json').read())
ds = Datasette(
    immutables=['/personalhub_read.db'],
    settings={'num_sql_threads': 0},
    metadata=metadata,
)
await ds.invoke_startup()
`);
  log('Offline Data Explorer ready');
}

async function renderPath(path) {
  pyodide.globals.set('ph_request_path', path || '/');
  const response = await pyodide.runPythonAsync(`
response = await ds.client.get(ph_request_path, follow_redirects=True)
[response.status_code, response.headers.get('content-type') or 'text/plain', response.text]
`);
  const [status, contentType, body] = response.toJs();
  self.postMessage({status, contentType, text: body});
}
self.onmessage = async (event) => {
  const data = event.data || {};
  try {
    if (data.type === 'startup') {
      if (!data.snapshotUrl) throw new Error('Missing detached snapshot URL');
      ready = (async () => {
        await preparePresentation(data.snapshotUrl);
        await startDatasette();
      })();
      await ready;
      await renderPath(data.initialPath || '/');
      return;
    }
    if (data.type === 'path') {
      if (!ready) throw new Error('Offline Data Explorer is not initialized');
      await ready;
      await renderPath(data.path || '/');
    }
  } catch (error) {
    self.postMessage({error: error && error.stack ? error.stack : String(error)});
  }
};
