#!/usr/bin/env python3
"""Exercise the dev API over HTTP using only Python's standard library."""

import hashlib
import hmac
import json
import os
import secrets
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import datetime, timedelta, timezone


class SmokeFailure(Exception):
    pass


class NoRedirect(urllib.request.HTTPRedirectHandler):
    """Prevent automatic credential forwarding to a redirected URL."""

    def redirect_request(self, *_args, **_kwargs):
        return None


class Client:
    def __init__(self, base_url):
        self.base_url = base_url.rstrip("/")
        self.count = 0
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())

    def request(self, method, path, expected=200, body=None, token=None, headers=None):
        headers = dict(headers or {})
        if token:
            headers["Authorization"] = "Bearer " + token
        if body is not None:
            headers["Content-Type"] = "application/json"
            body = body if isinstance(body, bytes) else json.dumps(body).encode()
        request = urllib.request.Request(
            self.base_url + path, data=body, headers=headers, method=method
        )
        try:
            response = self.opener.open(request, timeout=15)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            status, data = response.status, response.read()
        if status != expected:
            # Never print response bodies: authentication responses contain tokens.
            raise SmokeFailure(f"{method} {path}: HTTP {status}; esperado {expected}")
        self.count += 1
        return json.loads(data) if data else None

    def login(self, email, password):
        return self.request(
            "POST", "/api/v1/auth/login", body={"email": email, "password": password}
        )


def check(condition, message):
    if not condition:
        raise SmokeFailure(message)


def main():
    base_url = os.environ.get("TICKETING_BASE_URL", "http://localhost:8080")
    parsed = urllib.parse.urlparse(base_url)
    check(
        parsed.scheme == "http" and parsed.hostname in {"localhost", "127.0.0.1", "::1"},
        "La prueba usa datos demo y requiere una URL HTTP local.",
    )
    secret = os.environ.get("PAYMENT_WEBHOOK_SECRET")
    check(bool(secret), "Exporta PAYMENT_WEBHOOK_SECRET desde el .env de la API.")
    client = Client(base_url)
    check(client.request("GET", "/actuator/health")["status"] == "UP", "API no saludable")
    check("paths" in client.request("GET", "/v3/api-docs"), "OpenAPI no disponible")
    client.request("GET", "/api/v1/me/tickets", expected=401)

    run_id = uuid.uuid4().hex
    organizer = client.login(
        os.environ.get("SMOKE_ORGANIZER_EMAIL", "organizer@ticketing.dev"),
        os.environ.get("SMOKE_ORGANIZER_PASSWORD", "Password123"),
    )["accessToken"]
    now = datetime.now(timezone.utc)

    def at(delta):
        return (now + delta).isoformat()

    event_body = {
        "venueId": os.environ.get("SMOKE_VENUE_ID", "10000000-0000-0000-0000-000000000001"),
        "title": "Local smoke " + run_id,
        "description": "Datos de prueba local; conservar para inspeccionar resultados.",
        "category": "MUSIC",
        "startsAt": at(timedelta(days=2)),
        "endsAt": at(timedelta(days=2, hours=2)),
    }
    event = client.request("POST", "/api/v1/events", 201, event_body, organizer)
    event_path = "/api/v1/events/" + event["id"]
    ticket_type = client.request(
        "POST", event_path + "/ticket-types", 201,
        {"name": "General", "price": 40.00, "totalQuantity": 10,
         "saleStartsAt": at(timedelta(hours=-1)), "saleEndsAt": at(timedelta(days=1)),
         "maxPerOrder": 4}, organizer,
    )
    client.request("POST", event_path + "/publish", token=organizer)
    check(client.request("GET", event_path)["status"] == "PUBLISHED", "Evento no publicado")

    def new_buyer(label):
        email = f"smoke-{label}-{run_id}@example.com"
        password = "Smoke1!" + secrets.token_hex(16)
        client.request("POST", "/api/v1/auth/register", 201,
                       {"email": email, "password": password, "fullName": "Local Smoke"})
        return client.login(email, password)

    buyer = new_buyer("buyer")
    token = buyer["accessToken"]
    stranger = new_buyer("stranger")["accessToken"]
    client.request("POST", "/api/v1/events", 403, event_body, token)
    reservation = client.request(
        "POST", "/api/v1/reservations", 201,
        {"ticketTypeId": ticket_type["id"], "quantity": 1}, token,
    )

    def stock():
        types = client.request("GET", event_path)["ticketTypes"]
        return next(item["availableQuantity"] for item in types if item["id"] == ticket_type["id"])

    check(stock() == 9, "La reserva no descontó exactamente una entrada")
    headers = {"Idempotency-Key": "smoke-" + run_id}
    body = {"reservationId": reservation["id"]}
    order = client.request("POST", "/api/v1/orders", 201, body, token, headers)
    replay = client.request("POST", "/api/v1/orders", 200, body, token, headers)
    check(replay["id"] == order["id"], "La orden duplicada cambió de identidad")
    order_path = "/api/v1/orders/" + order["id"]
    client.request("GET", order_path, 404, token=stranger)

    def webhook(status, kind):
        payload = {
            "orderId": order["id"], "status": status,
            "providerReference": "smoke-" + uuid.uuid4().hex,
            "providerEventId": str(uuid.uuid4()),
            "providerOperationId": "smoke-" + uuid.uuid4().hex,
            "operationKind": kind, "amount": order["totalAmount"],
            "currency": order["currency"],
        }
        raw = json.dumps(payload, separators=(",", ":")).encode()
        signature = hmac.new(secret.encode(), raw, hashlib.sha256).hexdigest()
        return raw, {"X-Signature": signature}

    raw, signature = webhook("SUCCEEDED", "PAYMENT_CAPTURE")
    client.request("POST", "/api/v1/payments/webhook", 401, raw,
                   headers={"X-Signature": "0" * 64})
    check(client.request("GET", order_path, token=token)["status"] == "PENDING_PAYMENT",
          "El webhook inválido alteró la orden")
    for _ in range(2):
        client.request("POST", "/api/v1/payments/webhook", 204, raw, headers=signature)
    paid = client.request("GET", order_path, token=token)
    check(paid["status"] == "PAID" and paid["fulfillmentStatus"] == "FULFILLED",
          "El pago no completó la emisión")
    tickets = client.request("GET", "/api/v1/me/tickets", token=token)
    check(len(tickets) == 1 and tickets[0]["orderId"] == order["id"],
          "El pago duplicado debe emitir exactamente un ticket")
    check(stock() == 9, "El pago duplicado alteró el inventario")

    requested = client.request("POST", order_path + "/cancel", token=token)
    check(requested["refundStatus"] == "REQUESTED" and requested["status"] == "PAID",
          "Solicitar reembolso no debe confirmar un movimiento financiero")
    raw, signature = webhook("REFUNDED", "PAYMENT_REFUND")
    for _ in range(2):
        client.request("POST", "/api/v1/payments/webhook", 204, raw, headers=signature)
    refunded = client.request("GET", order_path, token=token)
    check(refunded["status"] == "REFUNDED" and refunded["refundStatus"] == "CONFIRMED",
          "Reembolso no confirmado")
    check(client.request("GET", "/api/v1/me/tickets", token=token) == [],
          "El ticket reembolsado sigue siendo válido")

    refreshed = client.request("POST", "/api/v1/auth/refresh",
                               body={"refreshToken": buyer["refreshToken"]})
    client.request("GET", order_path, token=refreshed["accessToken"])
    client.request("POST", "/api/v1/auth/logout", 204,
                   body={"refreshToken": refreshed["refreshToken"]})
    client.request("POST", "/api/v1/auth/refresh", 401,
                   body={"refreshToken": refreshed["refreshToken"]})
    print(json.dumps({"result": "PASS", "http_checks": client.count,
                      "runId": run_id, "eventId": event["id"], "orderId": order["id"]}, indent=2))


if __name__ == "__main__":
    try:
        main()
    except (SmokeFailure, urllib.error.URLError, TimeoutError, KeyError, ValueError) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        sys.exit(1)
