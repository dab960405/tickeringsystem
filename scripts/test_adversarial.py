"""Check the probe harness's local-only boundary and credential-free reporting."""
import importlib.util
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import sys
import threading
import unittest

spec = importlib.util.spec_from_file_location("adversarial", Path(__file__).with_name("adversarial.py"))
audit = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = audit
spec.loader.exec_module(audit)


class HarnessTests(unittest.TestCase):
    def test_only_loopback_origins_are_accepted(self):
        for url in ("http://localhost:8080", "http://127.0.0.1:5173/", "http://[::1]:8080"):
            self.assertEqual(audit.local_url(url), url.rstrip("/"))
        for url in ("https://example.com", "http://192.168.1.10", "http://localhost.example.com",
                    "http://user:password@localhost", "http://localhost/api", "http://localhost?token=secret",
                    "http://localhost#fragment", "file:///tmp/data", "http://localhost:0"):
            with self.subTest(url=url), self.assertRaises(ValueError):
                audit.local_url(url)

    def test_redirect_is_not_followed_and_trace_has_no_credentials(self):
        hits = []
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass
            def do_POST(self):
                hits.append(self.path)
                self.rfile.read(int(self.headers.get("Content-Length", "0")))
                self.send_response(307)
                self.send_header("Location", "/unexpected")
                self.send_header("Content-Length", "0")
                self.end_headers()
            def do_GET(self):
                hits.append(self.path)
                self.send_response(200)
                self.send_header("Content-Length", "0")
                self.end_headers()
        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            trace = []
            client = audit.Client(f"http://127.0.0.1:{server.server_port}", trace, threading.Lock())
            response = client.request("POST", "/probe", token="DO_NOT_LOG_TOKEN", body={"password": "DO_NOT_LOG_PASSWORD"},
                                      headers={"X-Signature": "DO_NOT_LOG_SIGNATURE"})
            self.assertEqual(response.status, 307)
            self.assertEqual(hits, ["/probe"])
            self.assertNotIn("DO_NOT_LOG", json.dumps(trace))
            self.assertEqual(set(trace[0]), {"target", "method", "path", "status", "ms"})
            with self.assertRaises(ValueError):
                client.request("GET", "//external.example/")
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=5)


if __name__ == "__main__":
    unittest.main()
