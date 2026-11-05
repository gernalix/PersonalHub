#!/usr/bin/env python3
"""Ephemeral loopback GIS token flow. No OAuth secrets, tokens or signed URLs on disk/logs."""
import argparse
import http.server
import json
import os
from pathlib import Path
import re
import secrets
import threading
import time
import urllib.request
import urllib.error
from urllib.parse import urlsplit

SCOPE = 'https://www.googleapis.com/auth/dataportability.play.subscriptions'
LIMIT = 16 * 1024 * 1024


def bounded(stream, limit=LIMIT):
    data = stream.read(limit + 1)
    if len(data) > limit:
        raise ValueError('Response too large')
    return data


def archive_url(value):
    parsed = urlsplit(value)
    return (parsed.scheme == 'https' and not parsed.username and parsed.port in (None, 443)
            and (parsed.hostname == 'storage.googleapis.com' or
                 (parsed.hostname or '').endswith('.storage.googleapis.com')))


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


class Session:
    def __init__(self, client_id, output, port):
        self.client_id, self.output = client_id, output
        self.origin = f'http://localhost:{port}'
        self.nonce = secrets.token_urlsafe(32)
        self.status = {'state': 'ready'}
        self.lock = threading.Lock()
        self.job = None
        self.opener = urllib.request.build_opener(NoRedirect)

    def api(self, token, path, data=None):
        req = urllib.request.Request('https://dataportability.googleapis.com/v1/' + path,
              data=None if data is None else json.dumps(data).encode(),
              headers={'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json'})
        with self.opener.open(req, timeout=45) as response:
            return json.loads(bounded(response, 1024 * 1024))

    def run(self, token):
        try:
            with self.lock:
                if self.job is None:
                    result = self.api(token, 'portabilityArchive:initiate', {'resources': ['play.subscriptions']})
                    self.job = result['archiveJobId']
                    if not re.fullmatch(r'[A-Za-z0-9_-]{1,200}', self.job):
                        raise ValueError('Invalid job')
                self.status = {'state': 'pending'}
            # Google archives can take days. Keep the job for reauthorization without repeating initiation.
            deadline = time.monotonic() + 3300
            while time.monotonic() < deadline:
                result = self.api(token, f'archiveJobs/{self.job}/portabilityArchiveState')
                state = result['state']
                if state == 'COMPLETE':
                    urls = result['urls']
                    if not 1 <= len(urls) <= 20 or not all(archive_url(u) for u in urls):
                        raise ValueError('Invalid archive origin')
                    self.output.mkdir(mode=0o700, parents=True, exist_ok=True)
                    for i, url in enumerate(urls):
                        with self.opener.open(url, timeout=90) as response:
                            content = bounded(response)
                        if not content.startswith(b'PK'):
                            raise ValueError('Expected ZIP archive')
                        path = self.output / f'play-subscriptions-{i+1:03d}.zip'
                        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
                        with os.fdopen(fd, 'wb') as stream:
                            stream.write(content)
                    self.status = {'state': 'complete', 'files': len(urls)}
                    return
                if state != 'IN_PROGRESS':
                    self.status = {'state': 'failed'}
                    return
                time.sleep(15)
            self.status = {'state': 'reauthorize'}
        except urllib.error.HTTPError as error:
            self.status = {'state': 'reauthorize' if error.code == 401 else 'failed', 'http_status': error.code}
        except Exception:
            self.status = {'state': 'failed'}
        finally:
            token = None

    def page(self):
        config = json.dumps({'clientId': self.client_id, 'scope': SCOPE, 'nonce': self.nonce})
        return '''<!doctype html><html lang="it"><meta charset="utf-8"><title>PersonalHub · Google Play</title>
<style>body{font:18px system-ui;max-width:700px;margin:60px auto;padding:20px}button{font:inherit;padding:14px;background:#176b42;color:white;border:0;border-radius:8px}p{line-height:1.6}</style>
<h1>PersonalHub · Abbonamenti Google Play</h1>
<p>Autorizza l'esportazione delle sole sottoscrizioni. Il token resta nella memoria di questa sessione; vengono salvati soltanto gli archivi dei dati da importare in Soldi.</p>
<button id="connect" disabled>Autorizza Google Play</button><p id="status">Caricamento accesso Google…</p>
<script>const cfg=CONFIG;
function init(){const client=google.accounts.oauth2.initTokenClient({client_id:cfg.clientId,scope:cfg.scope,include_granted_scopes:false,
callback:async r=>{if(!r.access_token || !google.accounts.oauth2.hasGrantedAllScopes(r,cfg.scope)){statusEl.textContent='Autorizzazione non completata';return;}
const token=r.access_token; r.access_token=null;
await fetch('/authorize',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({nonce:cfg.nonce,token})});},
error_callback:()=>{statusEl.textContent='Finestra Google chiusa o non disponibile';}});
document.getElementById('connect').disabled=false;statusEl.textContent='Pronto per l’autorizzazione';
document.getElementById('connect').onclick=()=>client.requestAccessToken({scope:cfg.scope,include_granted_scopes:false,prompt:''});}
const statusEl=document.getElementById('status');
setInterval(async()=>{const r=await fetch('/status');const s=await r.json();if(s.state==='pending')statusEl.textContent='Google sta preparando l’archivio…';
if(s.state==='complete')statusEl.textContent='Esportazione completata: '+s.files+' archivio/i pronti per Soldi';
if(s.state==='failed')statusEl.textContent='Esportazione non completata'+(s.http_status?' (HTTP '+s.http_status+')':'');
if(s.state==='reauthorize')statusEl.textContent='Autorizza di nuovo per continuare lo stesso export';},3000);
</script><script src="https://accounts.google.com/gsi/client" onload="init()" async defer></script></html>'''.replace('CONFIG', config).encode()


def handler(session):
    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def reply(self, status, data, content_type='application/json'):
            self.send_response(status)
            self.send_header('Content-Type', content_type)
            self.send_header('Cache-Control', 'no-store')
            self.send_header('X-Content-Type-Options', 'nosniff')
            self.send_header('X-Frame-Options', 'DENY')
            self.send_header('Referrer-Policy', 'no-referrer')
            self.send_header('Cross-Origin-Opener-Policy', 'same-origin-allow-popups')
            self.send_header('Content-Length', str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def valid_host(self):
            return self.headers.get('Host') == urlsplit(session.origin).netloc

        def do_GET(self):
            if not self.valid_host():
                return self.reply(403, b'{}')
            if self.path == '/':
                return self.reply(200, session.page(), 'text/html; charset=utf-8')
            if self.path == '/status':
                return self.reply(200, json.dumps(session.status).encode())
            self.reply(404, b'{}')

        def do_POST(self):
            if (not self.valid_host() or self.path != '/authorize' or
                self.headers.get('Origin') != session.origin or
                self.headers.get('Content-Type') != 'application/json'):
                return self.reply(403, b'{}')
            try:
                length = int(self.headers.get('Content-Length', '0'))
                if not 0 < length < 16384:
                    raise ValueError()
                data = json.loads(self.rfile.read(length))
                if not secrets.compare_digest(data.get('nonce', ''), session.nonce):
                    raise ValueError()
                token = data['token']
                if not isinstance(token, str) or not token or any(c.isspace() for c in token):
                    raise ValueError()
                with session.lock:
                    if session.status['state'] in ('pending', 'complete'):
                        return self.reply(409, b'{}')
                    session.status = {'state': 'pending'}
                threading.Thread(target=session.run, args=(token,), daemon=True).start()
                self.reply(202, b'{}')
            except Exception:
                self.reply(400, b'{}')
    return Handler


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--client-id', required=True, help='Public web OAuth client ID, never a client secret')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--port', type=int, default=18765)
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9_-]+\.apps\.googleusercontent\.com', args.client_id):
        parser.error('Invalid public client ID')
    session = Session(args.client_id, args.output, args.port)
    server = http.server.ThreadingHTTPServer(('127.0.0.1', args.port), handler(session))
    print(f'PersonalHub OAuth: {session.origin}', flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == '__main__':
    main()
