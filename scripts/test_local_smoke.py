"""Verify the local smoke client never forwards credentials across redirects."""
import importlib.util
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import threading
import unittest

spec = importlib.util.spec_from_file_location(
    "local_smoke", Path(__file__).with_name("local-smoke.py")
)
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)


class SmokeRedirectTest(unittest.TestCase):
    def test_redirects_never_forward_bearer_to_another_origin(self):
        received_tokens = []

        class Destination(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_GET(self):
                received_tokens.append(self.headers.get("Authorization"))
                self.send_response(200)
                self.end_headers()

        destination = ThreadingHTTPServer(("127.0.0.1", 0), Destination)
        redirect_status = [302]

        class Redirector(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_GET(self):
                self.send_response(redirect_status[0])
                self.send_header(
                    "Location", f"http://127.0.0.1:{destination.server_port}/capture"
                )
                self.end_headers()

        origin = ThreadingHTTPServer(("127.0.0.1", 0), Redirector)
        threads = [
            threading.Thread(target=server.serve_forever, daemon=True)
            for server in (origin, destination)
        ]
        for thread in threads:
            thread.start()
        try:
            client = smoke.Client(f"http://127.0.0.1:{origin.server_port}")
            for redirect_status[0] in (301, 302, 303, 307, 308):
                with self.subTest(status=redirect_status[0]):
                    with self.assertRaises(smoke.SmokeFailure):
                        client.request("GET", "/redirect", token="SYNTHETIC_BEARER")
            self.assertEqual(received_tokens, [])
        finally:
            for server in (origin, destination):
                server.shutdown()
                server.server_close()
            for thread in threads:
                thread.join(timeout=5)


if __name__ == "__main__":
    unittest.main()
