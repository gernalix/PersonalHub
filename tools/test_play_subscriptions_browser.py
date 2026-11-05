import http.client
import json
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch
from play_subscriptions_browser import Session, archive_url, bounded, handler
from http.server import ThreadingHTTPServer
from io import BytesIO

class BrowserOAuthTest(unittest.TestCase):
    def test_signed_urls_cannot_redirect_tokens_to_unrelated_origins(self):
        for url in ['http://storage.googleapis.com/x','https://storage.googleapis.com.evil.test/x','https://user@storage.googleapis.com/x','https://localhost/x']:
            self.assertFalse(archive_url(url))
        self.assertTrue(archive_url('https://storage.googleapis.com/bucket/file'))
        with self.assertRaises(ValueError): bounded(BytesIO(b'12345'),4)

    def test_rejects_cross_origin_and_wrong_nonce_without_calling_google(self):
        session=Session('public.apps.googleusercontent.com',Path(tempfile.gettempdir()),8765)
        server=ThreadingHTTPServer(('127.0.0.1',0),handler(session))
        thread=threading.Thread(target=server.serve_forever,daemon=True); thread.start()
        try:
            conn=http.client.HTTPConnection('127.0.0.1',server.server_port)
            with patch.object(session,'run') as worker:
                for origin,nonce,expected in [('https://evil.test',session.nonce,403),(session.origin,'wrong',400)]:
                    conn.request('POST','/authorize',json.dumps({'nonce':nonce,'token':'synthetic'}),{'Host':'localhost:8765','Origin':origin,'Content-Type':'application/json'})
                    response=conn.getresponse(); self.assertEqual(expected,response.status); response.read()
                worker.assert_not_called()
            conn.request('GET','/status',headers={'Host':'evil.test'})
            response=conn.getresponse(); self.assertEqual(403,response.status); response.read()
        finally: server.shutdown();server.server_close()

if __name__=='__main__': unittest.main()
