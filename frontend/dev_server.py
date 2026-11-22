"""Local frontend and simulated gateway. Standard library only; never deploy this server."""
import hashlib
import hmac
from http.client import HTTPConnection
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
from urllib.parse import unquote, urlsplit
from uuid import UUID, NAMESPACE_URL, uuid5

WEB_ROOT = Path(__file__).resolve().parent / "dist" / "ticketing-ui" / "browser"
CLIENT_ROUTE = re.compile(r"/(?:|index\.html|events(?:/[a-zA-Z0-9-]+)?|login|register|orders|tickets|eventos(?:/[a-zA-Z0-9-]+)?|entrar|registro|compras|entradas)/?$")
ASSET_TYPES = {".js": "text/javascript", ".css": "text/css", ".ico": "image/x-icon",
               ".svg": "image/svg+xml", ".png": "image/png", ".jpg": "image/jpeg",
               ".webp": "image/webp", ".woff2": "font/woff2"}


def static_asset(path):
    """Serve only built assets, with explicit SPA routes and no source/directory access."""
    root = WEB_ROOT.resolve()
    if CLIENT_ROUTE.fullmatch(path):
        return root / "index.html", "text/html"
    target = (root / unquote(path).lstrip("/")).resolve()
    if target.is_relative_to(root) and target.suffix in ASSET_TYPES and target.is_file():
        return target, ASSET_TYPES[target.suffix]
    return None


def upstream(method, path, body=None, authorization=None, extra_headers=None):
    connection = HTTPConnection("127.0.0.1", int(os.environ.get("TICKETING_API_PORT", "8080")), timeout=20)
    headers = {"Content-Type": "application/json", **(extra_headers or {})}
    if authorization:
        headers["Authorization"] = authorization
    try:
        connection.request(method, path, body, headers)
        response = connection.getresponse()
        return response.status, response.read(), response.getheader("Content-Type", "application/json")
    finally:
        connection.close()


def simulate_payment(order_id, action, authorization):
    """Read ownership and amount from the API; the browser never chooses financial evidence."""
    if not authorization or not authorization.startswith("Bearer "):
        return 401, {"detail": "Sign in to continue."}
    try:
        order_id = str(UUID(order_id))
    except (ValueError, TypeError, AttributeError):
        return 400, {"detail": "Invalid order identifier."}
    operations = {"capture": ("SUCCEEDED", "PAYMENT_CAPTURE"),
                  "failure": ("FAILED", "PAYMENT_FAILURE"), "refund": ("REFUNDED", "PAYMENT_REFUND")}
    if action not in operations:
        return 400, {"detail": "Invalid operation."}
    secret = os.environ.get("PAYMENT_WEBHOOK_SECRET")
    if not secret:
        return 503, {"detail": "Configure the local simulator secret in your .env file."}
    status, raw_order, _ = upstream("GET", f"/api/v1/orders/{order_id}", authorization=authorization)
    if status != 200:
        return status, {"detail": "This order is not available to the current session."}
    order = json.loads(raw_order)
    if action in ("capture", "failure") and order["status"] not in ("PENDING_PAYMENT", "PAID"):
        return 409, {"detail": "This order no longer accepts payments."}
    if action == "refund" and order["refundStatus"] not in (
            "REQUESTED", "REQUIRED", "PENDING_RECONCILIATION", "FAILED", "CONFIRMED"):
        return 409, {"detail": "Request an order refund before confirming it."}
    payment_status, kind = operations[action]
    identity = f"local-demo:{order_id}:{action}"
    payload = {"orderId": order_id, "status": payment_status,
               "providerReference": f"demo-{action}-{order_id}",
               "providerEventId": str(uuid5(NAMESPACE_URL, identity)),
               "providerOperationId": f"demo-{action}-{order_id}", "operationKind": kind,
               "amount": order["totalAmount"], "currency": order["currency"]}
    raw = json.dumps(payload, separators=(",", ":")).encode()
    signature = hmac.new(secret.encode(), raw, hashlib.sha256).hexdigest()
    status, result, _ = upstream("POST", "/api/v1/payments/webhook", raw,
                                 extra_headers={"X-Signature": signature})
    if status != 204:
        return status, json.loads(result) if result else {"detail": "The simulator did not confirm the payment."}
    return 200, {"simulated": True, "orderId": order_id}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass  # Do not log credentials, tokens or webhook bodies.

    def send_body(self, status, body, content_type="application/json"):
        if not isinstance(body, bytes):
            body = json.dumps(body, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", content_type + "; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'")
        self.end_headers()
        self.wfile.write(body)

    def handle_request(self):
        port = self.server.server_port
        authorities = {f"localhost:{port}", f"127.0.0.1:{port}"}
        if self.headers.get("Host") not in authorities:
            self.send_body(403, {"detail": "The server is available only on localhost."})
            return
        origin = self.headers.get("Origin")
        if origin and origin not in {"http://" + host for host in authorities}:
            self.send_body(403, {"detail": "Origin not allowed."})
            return
        path = urlsplit(self.path).path
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if not 0 <= length <= 65536:
                self.send_body(413, {"detail": "Request body too large."})
                return
            body = self.rfile.read(length) if length else None
            authorization = self.headers.get("Authorization")
            asset = static_asset(path) if self.command == "GET" else None
            if asset:
                file, content_type = asset
                if not file.is_file():
                    self.send_body(503, {"detail": "Build Angular first: cd frontend && npm ci && npm run build"})
                    return
                self.send_body(200, file.read_bytes(), content_type)
            elif path.startswith("/api/v1/"):
                extra = {"Idempotency-Key": self.headers["Idempotency-Key"]} if "Idempotency-Key" in self.headers else {}
                status, data, content_type = upstream(self.command, self.path, body, authorization, extra)
                self.send_body(status, data, content_type.split(";")[0])
            elif path == "/demo/payment" and self.command == "POST":
                if not origin or self.headers.get("X-Demo-Action") != "simulate":
                    self.send_body(403, {"detail": "Use the simulator from the local interface."})
                    return
                data = json.loads(body or b"{}")
                if not isinstance(data, dict):
                    raise ValueError("Invalid request")
                status, result = simulate_payment(data.get("orderId"), data.get("action"), authorization)
                self.send_body(status, result)
            else:
                self.send_body(404, {"detail": "Route not found."})
        except (ValueError, TypeError):
            self.send_body(400, {"detail": "Invalid request."})
        except (OSError, TimeoutError):
            self.send_body(502, {"detail": "Could not connect to the API. Check that it is running."})

    do_GET = handle_request
    do_POST = handle_request
    do_DELETE = handle_request


if __name__ == "__main__":
    port = int(os.environ.get("FRONTEND_PORT", "5173"))
    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    print(f"Ticketing: http://localhost:{port} — local demo with simulated payments", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
