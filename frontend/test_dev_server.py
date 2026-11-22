import hashlib
import hmac
from http.client import HTTPConnection
from http.server import ThreadingHTTPServer
import json
import os
import threading
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from dev_server import Handler, simulate_payment

ORDER = "00000000-0000-0000-0000-000000000001"


class GatewayTest(unittest.TestCase):
    def setUp(self):
        self.environment = patch.dict(os.environ, {"PAYMENT_WEBHOOK_SECRET": "test-local-key"})
        self.environment.start()
        self.addCleanup(self.environment.stop)

    def test_requires_session_before_reading_or_signing(self):
        with patch("dev_server.upstream") as upstream:
            self.assertEqual(simulate_payment(ORDER, "capture", None)[0], 401)
            upstream.assert_not_called()

    def test_cannot_pay_someone_elses_order(self):
        with patch("dev_server.upstream", return_value=(404, b"{}", "application/json")) as upstream:
            self.assertEqual(simulate_payment(ORDER, "capture", "Bearer other-user")[0], 404)
            self.assertEqual(upstream.call_count, 1)

    def test_signs_server_amount_and_reuses_identity_on_retry(self):
        received = []

        def api(method, path, body=None, authorization=None, extra_headers=None):
            if method == "GET":
                self.assertEqual(authorization, "Bearer owner")
                return 200, b'{"status":"PENDING_PAYMENT","refundStatus":"NONE","totalAmount":45.00,"currency":"USD"}', "application/json"
            received.append(body)
            payload = json.loads(body)
            self.assertEqual(payload["amount"], 45)
            self.assertEqual(payload["currency"], "USD")
            expected = hmac.new(b"test-local-key", body, hashlib.sha256).hexdigest()
            self.assertEqual(extra_headers["X-Signature"], expected)
            return 204, b"", "application/json"

        with patch("dev_server.upstream", side_effect=api):
            for _ in range(2):
                self.assertEqual(simulate_payment(ORDER, "capture", "Bearer owner")[0], 200)
        self.assertEqual(received[0], received[1])

    def test_refund_requires_request_first(self):
        with patch("dev_server.upstream", return_value=(200, b'{"status":"PAID","refundStatus":"NONE"}', "application/json")) as upstream:
            self.assertEqual(simulate_payment(ORDER, "refund", "Bearer owner")[0], 409)
            self.assertEqual(upstream.call_count, 1)

    def test_unknown_action_cannot_reach_backend(self):
        with patch("dev_server.upstream") as upstream:
            self.assertEqual(simulate_payment(ORDER, "arbitrary", "Bearer owner")[0], 400)
            upstream.assert_not_called()


class LocalServerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.thread.join()

    def request(self, method, path, headers=None, body=None):
        connection = HTTPConnection("127.0.0.1", self.server.server_port)
        connection.request(method, path, body, headers or {})
        response = connection.getresponse()
        result = response.status, response.read()
        connection.close()
        return result

    def test_cannot_serve_env_or_directory_listing(self):
        for path in ("/.env", "/../.env", "/dev_server.py", "/scripts/"):
            self.assertEqual(self.request("GET", path)[0], 404)

    def test_rejects_foreign_origin_and_rebinding_host(self):
        self.assertEqual(self.request("POST", "/demo/payment", {"Origin": "https://foreign.example"}, "{}")[0], 403)
        self.assertEqual(self.request("GET", "/", {"Host": "foreign.example"})[0], 403)

    def test_browser_simulator_requires_origin_and_custom_header(self):
        self.assertEqual(self.request("POST", "/demo/payment", body="{}")[0], 403)

    def test_serves_angular_deep_links_and_compiled_assets_only(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "index.html").write_text('<app-root></app-root>')
            (root / "main-ABC.js").write_text('/* built Angular */')
            (root / "source.ts").write_text('private source')
            with patch("dev_server.WEB_ROOT", root):
                for path in ("/", "/events", "/events/abc-123", "/orders", "/tickets", "/login", "/register", "/eventos/abc-123"):
                    status, body = self.request("GET", path)
                    self.assertEqual(status, 200)
                    self.assertIn(b'<app-root>', body)
                self.assertEqual(self.request("GET", "/main-ABC.js")[0], 200)
                for path in ("/source.ts", "/missing.js", "/%2e%2e/dev_server.py", "/unknown"):
                    self.assertEqual(self.request("GET", path)[0], 404)

    def test_deep_link_csp_allows_same_origin_base(self):
        connection = HTTPConnection("127.0.0.1", self.server.server_port)
        try:
            connection.request("GET", "/eventos/abc-123")
            response = connection.getresponse()
            policy = response.getheader("Content-Security-Policy")
            self.assertIn("base-uri 'self'", policy)
            self.assertIn("default-src 'self'", policy)
            self.assertIn("frame-ancestors 'none'", policy)
            response.read()
        finally:
            connection.close()

    def test_missing_build_explains_how_to_start(self):
        with tempfile.TemporaryDirectory() as directory, patch("dev_server.WEB_ROOT", Path(directory)):
            self.assertEqual(self.request("GET", "/events")[0], 503)


if __name__ == "__main__":
    unittest.main()
