#!/usr/bin/env python3
"""Bounded adversarial probes for the local Ticketing demo; Python standard library only."""
import argparse
import base64
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
import hashlib
import hmac
from http.client import HTTPConnection, RemoteDisconnected
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
XSS_PROBE = '<img src=x onerror="document.title=\'ADVERSARIAL_XSS_EXECUTED\'">'


class Finding(Exception):
    """An observed result violates the explicitly stated invariant."""


class Precondition(Exception):
    """A fixture or transport failed; this is not proof of a vulnerability."""


def expect(condition, message):
    if not condition:
        raise Finding(message)


def local_url(value):
    parsed = urllib.parse.urlsplit(value)
    if (parsed.scheme != "http" or parsed.hostname not in {"localhost", "127.0.0.1", "::1"}
            or parsed.username or parsed.password or parsed.path not in ("", "/")
            or parsed.query or parsed.fragment):
        raise ValueError("Solo se admiten URLs HTTP locales, sin credenciales, rutas ni parámetros.")
    if parsed.port is not None and not 1 <= parsed.port <= 65535:
        raise ValueError("Puerto inválido")
    return value.rstrip("/")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *_args, **_kwargs):
        return None  # Never forward generated credentials or signed evidence elsewhere.


@dataclass
class Response:
    status: int
    data: object


class Client:
    def __init__(self, url, trace, lock):
        self.url = local_url(url)
        self.trace, self.lock = trace, lock

    def request(self, method, path, body=None, token=None, headers=None):
        if not path.startswith("/") or path.startswith("//"):
            raise ValueError("Se requiere una ruta relativa local.")
        headers = dict(headers or {})
        if token:
            headers["Authorization"] = "Bearer " + token
        raw = body if isinstance(body, bytes) else json.dumps(body).encode() if body is not None else None
        if raw is not None:
            headers["Content-Type"] = "application/json"
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
        request = urllib.request.Request(self.url + path, data=raw, headers=headers, method=method)
        started = time.monotonic()
        try:
            try:
                response = opener.open(request, timeout=20)
            except urllib.error.HTTPError as error:
                response = error
            with response:
                status, content = response.status, response.read(2_000_000)
            try:
                data = json.loads(content) if content else None
            except (json.JSONDecodeError, UnicodeDecodeError):
                data = None
            return Response(status, data)
        finally:
            # Only method/path/status/timing: never headers, bodies, credentials or signatures.
            with self.lock:
                self.trace.append({"target": self.url, "method": method, "path": path,
                                   "status": locals().get("status", "TRANSPORT_ERROR"),
                                   "ms": round((time.monotonic() - started) * 1000)})

    def require(self, method, path, expected=200, **kwargs):
        response = self.request(method, path, **kwargs)
        if response.status != expected:
            raise Precondition(f"{method} {path}: HTTP {response.status}; preparación esperaba {expected}")
        return response.data


class Audit:
    def __init__(self, api_url, frontend_url, output):
        self.run_id = uuid.uuid4().hex[:12]
        self.output = output
        self.results, self.trace = [], []
        self.lock = threading.Lock()
        self.api = Client(api_url, self.trace, self.lock)
        self.frontend = Client(frontend_url, self.trace, self.lock)
        self.secret = os.environ.get("PAYMENT_WEBHOOK_SECRET")
        self.types = {}
        self.event = None
        self.organizer = None
        self.observed = {}

    def setup(self):
        if not self.secret:
            raise Precondition("Exporta PAYMENT_WEBHOOK_SECRET del .env local.")
        if self.api.require("GET", "/actuator/health").get("status") != "UP":
            raise Precondition("La API no está saludable")
        self.organizer = self.api.require("POST", "/api/v1/auth/login", body={
            "email": os.environ.get("SMOKE_ORGANIZER_EMAIL", "organizer@ticketing.dev"),
            "password": os.environ.get("SMOKE_ORGANIZER_PASSWORD", "Password123"),
        })["accessToken"]
        now = datetime.now(timezone.utc)
        self.event_body = {
            "venueId": os.environ.get("SMOKE_VENUE_ID", "10000000-0000-0000-0000-000000000001"),
            "title": "Adversarial " + self.run_id, "description": XSS_PROBE,
            "category": "MUSIC", "startsAt": (now + timedelta(days=7)).isoformat(),
            "endsAt": (now + timedelta(days=7, hours=2)).isoformat(),
        }
        self.event = self.api.require("POST", "/api/v1/events", 201, body=self.event_body, token=self.organizer)
        for name in ("access", "quantity", "price", "signature", "evidence", "duplicate", "semantic",
                     "oversell", "race", "dropped", "reorder", "simulator", "reservation-cancel"):
            self.types[name] = self.api.require("POST", self.event_path + "/ticket-types", 201,
                token=self.organizer, body={"name": name, "price": 45, "totalQuantity": 2 if name == "oversell" else 20,
                "maxPerOrder": 4, "saleStartsAt": (now - timedelta(hours=1)).isoformat(),
                "saleEndsAt": (now + timedelta(days=6)).isoformat()})
        self.api.require("POST", self.event_path + "/publish", token=self.organizer)

    @property
    def event_path(self):
        return "/api/v1/events/" + self.event["id"]

    def buyer(self, label, extra=None):
        email = f"adversarial-{label}-{self.run_id}-{uuid.uuid4().hex[:6]}@example.com"
        password = "Audit1!" + secrets.token_hex(16)
        registered = self.api.require("POST", "/api/v1/auth/register", 201, body={
            "email": email, "password": password, "fullName": "Comprador adversarial ficticio", **(extra or {}),
        })
        tokens = self.api.require("POST", "/api/v1/auth/login", body={"email": email, "password": password})
        return {**tokens, "id": registered["id"], "role": registered["role"], "email": email}

    def reserve(self, name, buyer, quantity=1):
        return self.api.require("POST", "/api/v1/reservations", 201, token=buyer["accessToken"],
                                body={"ticketTypeId": self.types[name]["id"], "quantity": quantity})

    def order(self, name, buyer, quantity=1):
        reservation = self.reserve(name, buyer, quantity)
        return self.api.require("POST", "/api/v1/orders", 201, token=buyer["accessToken"],
            headers={"Idempotency-Key": str(uuid.uuid4())}, body={"reservationId": reservation["id"]})

    def snapshot(self, order, buyer):
        value = self.api.require("GET", "/api/v1/orders/" + order["id"], token=buyer["accessToken"])
        tickets = self.api.require("GET", "/api/v1/me/tickets", token=buyer["accessToken"])
        result = {key: value[key] for key in ("id", "status", "fulfillmentStatus", "refundStatus", "totalAmount", "currency")}
        result["validTickets"] = sum(ticket["orderId"] == order["id"] and ticket["status"] == "VALID" for ticket in tickets)
        return result

    def stock(self, name):
        event = self.api.require("GET", self.event_path)
        return next(t["availableQuantity"] for t in event["ticketTypes"] if t["id"] == self.types[name]["id"])

    def evidence(self, order, status="SUCCEEDED"):
        return {"orderId": order["id"], "status": status, "providerReference": "audit-" + uuid.uuid4().hex,
                "providerEventId": str(uuid.uuid4()), "providerOperationId": "audit-" + uuid.uuid4().hex,
                "operationKind": {"SUCCEEDED": "PAYMENT_CAPTURE", "FAILED": "PAYMENT_FAILURE", "REFUNDED": "PAYMENT_REFUND"}[status],
                "amount": order["totalAmount"], "currency": order["currency"]}

    def signed(self, payload):
        raw = json.dumps(payload, separators=(",", ":")).encode()
        return raw, {"X-Signature": hmac.new(self.secret.encode(), raw, hashlib.sha256).hexdigest()}

    def webhook(self, payload):
        raw, headers = self.signed(payload)
        return self.api.request("POST", "/api/v1/payments/webhook", body=raw, headers=headers)

    def case(self, code, title, expected, operation):
        start = len(self.trace)
        self.observed = {}
        result = {"id": code, "title": title, "expected": expected, "status": "PASS"}
        try:
            operation()
        except Finding as error:
            result.update(status="FAIL", finding=str(error))
        except Precondition as error:
            result.update(status="INCONCLUSIVE", reason=str(error))
        except (OSError, ValueError, KeyError, TypeError) as error:
            result.update(status="INCONCLUSIVE", reason="Error de ejecución: " + type(error).__name__)
        result.update(observed=self.observed, requests=list(self.trace[start:]))
        self.results.append(result)
        self.save()
        print(f"{code}: {result['status']} — {title}", flush=True)

    def save(self):
        report = {"runId": self.run_id, "api": self.api.url, "frontend": self.frontend.url,
                  "eventId": self.event["id"] if self.event else None, "eventTitle": self.event_body["title"] if self.event else None,
                  "executedAt": datetime.now(timezone.utc).isoformat(), "results": self.results,
                  "httpRequests": len(self.trace), "fixturePolicy": "Only fresh generated accounts/event; retained for inspection"}
        self.output.mkdir(parents=True, exist_ok=True)
        (self.output / "http.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
        lines = ["# Pruebas adversarias HTTP", "", f"Run: `{self.run_id}`. API: `{self.api.url}`.", "",
                 "PASS = control/invariante satisfecho; FAIL = incumplimiento observado; INCONCLUSIVE = no comprobado.", "",
                 "No se guardan tokens, contraseñas, cuerpos HTTP ni firmas. Los datos ficticios se conservan.", "",
                 "| Caso | Resultado | Prueba |", "| --- | --- | --- |"]
        lines += [f"| {case['id']} | {case['status']} | {case['title']} |" for case in self.results]
        for case in self.results:
            lines += ["", "## " + case["id"] + " — " + case["title"], "", "Esperado: " + case["expected"],
                      "", case.get("finding", case.get("reason", "Invariantes comprobados.")), "", "```json",
                      json.dumps(case["observed"], ensure_ascii=False, indent=2), "```"]
        lines += ["", "Las trazas de método/ruta/estado HTTP están en `http.json`. No es una auditoría exhaustiva."]
        (self.output / "http.md").write_text("\n".join(lines) + "\n")

    def access(self):
        owner, other = self.buyer("owner"), self.buyer("other")
        order = self.order("access", owner)
        attempts = [
            self.api.request("GET", "/api/v1/orders/" + order["id"], token=other["accessToken"]),
            self.api.request("POST", "/api/v1/orders/" + order["id"] + "/cancel", token=other["accessToken"]),
            self.api.request("DELETE", "/api/v1/reservations/" + order["reservationId"], token=other["accessToken"]),
            self.api.request("POST", "/api/v1/orders", token=other["accessToken"],
                             headers={"Idempotency-Key": str(uuid.uuid4())}, body={"reservationId": order["reservationId"]}),
            self.frontend.request("POST", "/demo/payment", token=other["accessToken"],
                headers={"Origin": self.frontend.url, "X-Demo-Action": "simulate"},
                body={"orderId": order["id"], "action": "capture"}),
        ]
        self.observed = {"statuses": [r.status for r in attempts], "order": self.snapshot(order, owner)}
        expect(all(r.status in (403, 404) for r in attempts), "Alguna operación ajena no fue rechazada con 403/404")
        expect(self.observed["order"]["status"] == "PENDING_PAYMENT" and self.observed["order"]["validTickets"] == 0,
               "Cambió la orden de otro comprador")

    def auth(self):
        buyer = self.buyer("role", {"role": "ADMIN", "enabled": True})
        parts = buyer["accessToken"].split(".")
        payload = json.loads(base64.urlsafe_b64decode(parts[1] + "=" * (-len(parts[1]) % 4)))
        payload["role"] = "ADMIN"
        parts[1] = base64.urlsafe_b64encode(json.dumps(payload).encode()).decode().rstrip("=")
        attempts = [self.api.request("GET", "/api/v1/me/tickets"),
                    self.api.request("GET", "/api/v1/me/tickets", token=".".join(parts)),
                    self.api.request("GET", "/api/v1/me/tickets", token=buyer["refreshToken"]),
                    self.api.request("POST", "/api/v1/events", token=buyer["accessToken"], body=self.event_body)]
        self.observed = {"registeredRole": buyer["role"], "statuses": [r.status for r in attempts]}
        expect(buyer["role"] == "USER" and self.observed["statuses"] == [401, 401, 401, 403],
               "Falló la separación de roles o la validación de tokens")

    def quantity(self):
        buyer = self.buyer("quantity")
        attempts = []
        for quantity in (0, -1, None, 1.5, 2147483648, True, 5):
            response = self.api.request("POST", "/api/v1/reservations", token=buyer["accessToken"],
                body={"ticketTypeId": self.types["quantity"]["id"], "quantity": quantity})
            record = {"input": quantity, "httpStatus": response.status}
            if response.status == 201:
                record["acceptedQuantity"] = response.data["quantity"]
                self.api.require("DELETE", "/api/v1/reservations/" + response.data["id"], 204, token=buyer["accessToken"])
            attempts.append(record)
        self.observed = {"attempts": attempts, "stockAfterCleanup": self.stock("quantity")}
        expect(all(r["httpStatus"] in (400, 409, 422) for r in attempts), "La API aceptó una cantidad inválida; revisar coerción numérica")

    def price(self):
        buyer = self.buyer("price")
        reservation = self.reserve("price", buyer)
        response = self.api.request("POST", "/api/v1/orders", token=buyer["accessToken"],
            headers={"Idempotency-Key": str(uuid.uuid4())},
            body={"reservationId": reservation["id"], "totalAmount": 0, "currency": "EUR", "price": 0,
                  "userId": str(uuid.uuid4())})
        self.observed = {"httpStatus": response.status}
        if response.status == 400:
            return
        expect(response.status == 201, "Respuesta inesperada ante campos adicionales")
        self.observed["order"] = self.snapshot(response.data, buyer)
        expect(response.data["totalAmount"] == 45 and response.data["currency"] == "USD", "El cliente alteró el precio o moneda")

    def signature(self):
        buyer = self.buyer("signature")
        order = self.order("signature", buyer)
        payload = self.evidence(order)
        raw, headers = self.signed(payload)
        attempts = [self.api.request("POST", "/api/v1/payments/webhook", body=raw),
                    self.api.request("POST", "/api/v1/payments/webhook", body=raw, headers={"X-Signature": "0" * 64}),
                    self.api.request("POST", "/api/v1/payments/webhook", body=raw + b" ", headers=headers)]
        state = self.snapshot(order, buyer)
        self.observed = {"statuses": [r.status for r in attempts], "afterInvalidSignatures": state}
        expect(attempts[0].status in (400, 401) and all(r.status == 401 for r in attempts[1:])
               and state["status"] == "PENDING_PAYMENT" and state["validTickets"] == 0,
               "Una firma inválida no fue bloqueada sin efectos")
        expect(self.webhook(payload).status == 204, "La firma de control válida no fue aceptada")
        self.observed["afterValidSignature"] = self.snapshot(order, buyer)
        expect(self.observed["afterValidSignature"]["validTickets"] == 1, "El control positivo no emitió una entrada")

    def financial_evidence(self):
        buyer = self.buyer("evidence")
        order = self.order("evidence", buyer)
        statuses = []
        for change in ({"amount": 0}, {"currency": "EUR"}):
            statuses.append(self.webhook({**self.evidence(order), **change}).status)
        self.observed = {"trustedProviderSimulation": True, "statuses": statuses, "order": self.snapshot(order, buyer)}
        expect(all(s in (400, 409, 422) for s in statuses) and self.observed["order"]["validTickets"] == 0
               and self.observed["order"]["status"] == "PENDING_PAYMENT", "Se aceptó evidencia financiera contradictoria")

    def duplicate_orders(self):
        buyer = self.buyer("duplicate")
        reservation = self.reserve("duplicate", buyer)
        key = str(uuid.uuid4())
        def create(_):
            return self.api.request("POST", "/api/v1/orders", token=buyer["accessToken"],
                headers={"Idempotency-Key": key}, body={"reservationId": reservation["id"]})
        with ThreadPoolExecutor(max_workers=6) as pool:
            responses = list(pool.map(create, range(6)))
        ids = sorted({r.data["id"] for r in responses if r.status in (200, 201)})
        other = self.reserve("duplicate", buyer)
        conflict = self.api.request("POST", "/api/v1/orders", token=buyer["accessToken"],
            headers={"Idempotency-Key": key}, body={"reservationId": other["id"]})
        self.api.require("DELETE", "/api/v1/reservations/" + other["id"], 204, token=buyer["accessToken"])
        listing = self.api.require("GET", "/api/v1/orders", token=buyer["accessToken"])
        self.observed = {"statuses": [r.status for r in responses], "uniqueOrderIds": ids,
                         "conflictingPayloadStatus": conflict.status, "totalOrders": listing["totalElements"]}
        expect(all(r.status in (200, 201) for r in responses) and len(ids) == 1 and listing["totalElements"] == 1
               and conflict.status == 409, "El reintento concurrente o la clave con otro payload rompió la idempotencia")

    def duplicate_payments(self, semantic=False):
        buyer = self.buyer("semantic" if semantic else "replay")
        order = self.order("semantic" if semantic else "duplicate", buyer)
        evidence = self.evidence(order)
        payloads = [{**evidence, **({"providerEventId": str(uuid.uuid4())} if semantic else {})} for _ in range(8)]
        with ThreadPoolExecutor(max_workers=8) as pool:
            statuses = [r.status for r in pool.map(self.webhook, payloads)]
        state = self.snapshot(order, buyer)
        self.observed = {"captureStatuses": statuses, "afterCapture": state}
        expect(all(s == 204 for s in statuses) and state["status"] == "PAID" and state["validTickets"] == 1,
               "Las capturas duplicadas no convergieron a una sola entrada")
        if semantic:
            conflict = self.webhook({**evidence, "providerEventId": str(uuid.uuid4()), "amount": 1})
            self.observed["contradictoryOperationStatus"] = conflict.status
            expect(conflict.status == 409, "La misma operación aceptó datos contradictorios")
            return
        self.api.require("POST", "/api/v1/orders/" + order["id"] + "/cancel", token=buyer["accessToken"])
        refund = self.evidence(order, "REFUNDED")
        with ThreadPoolExecutor(max_workers=6) as pool:
            statuses = [r.status for r in pool.map(self.webhook, [refund] * 6)]
        state = self.snapshot(order, buyer)
        self.observed.update(refundStatuses=statuses, afterRefund=state)
        expect(all(s == 204 for s in statuses) and state["refundStatus"] == "CONFIRMED" and state["validTickets"] == 0,
               "Los reembolsos duplicados no convergieron a entradas invalidadas")

    def oversell(self):
        buyer = self.buyer("oversell")
        barrier = threading.Barrier(20)
        def reserve(_):
            barrier.wait(timeout=10)
            return self.api.request("POST", "/api/v1/reservations", token=buyer["accessToken"],
                body={"ticketTypeId": self.types["oversell"]["id"], "quantity": 1})
        with ThreadPoolExecutor(max_workers=20) as pool:
            responses = list(pool.map(reserve, range(20)))
        accepted = [r.data for r in responses if r.status == 201]
        before = self.stock("oversell")
        for reservation in accepted:
            self.api.require("DELETE", "/api/v1/reservations/" + reservation["id"], 204, token=buyer["accessToken"])
        self.observed = {"statuses": [r.status for r in responses], "accepted": len(accepted),
                         "stockWhileReserved": before, "stockAfterRelease": self.stock("oversell")}
        expect(len(accepted) == 2 and all(r.status in (201, 409) for r in responses) and before == 0
               and self.observed["stockAfterRelease"] == 2, "No se respetó el inventario de dos entradas")

    def race(self):
        outcomes = []
        self.observed = {"iterations": outcomes}
        for iteration in range(3):
            buyer = self.buyer("race" + str(iteration))
            stock = self.stock("race")
            order = self.order("race", buyer)
            barrier = threading.Barrier(2)
            def capture():
                barrier.wait(timeout=10)
                return self.webhook(self.evidence(order))
            def cancel():
                barrier.wait(timeout=10)
                return self.api.request("POST", "/api/v1/orders/" + order["id"] + "/cancel", token=buyer["accessToken"])
            with ThreadPoolExecutor(max_workers=2) as pool:
                captured, cancelled = pool.submit(capture), pool.submit(cancel)
                statuses = [captured.result().status, cancelled.result().status]
            state = self.snapshot(order, buyer)
            after = self.stock("race")
            outcomes.append({"statuses": statuses, "order": state, "stockBefore": stock, "stockAfter": after})
            consistent = (state["status"] == "PAID" and state["validTickets"] == 1 and after == stock - 1
                          or state["status"] == "CANCELLED" and state["validTickets"] == 0 and after == stock
                          and state["refundStatus"] == "REQUIRED")
            expect(consistent and statuses[0] == 204 and statuses[1] in (200, 409, 422), "Captura/cancelación dejó un estado inconsistente")

    def dropped_response(self):
        buyer = self.buyer("dropped")
        reservation = self.reserve("dropped", buyer)
        key, api, first = str(uuid.uuid4()), self.api, []
        class DropAfterCommit(BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass
            def do_POST(self):
                self.rfile.read(int(self.headers.get("Content-Length", "0")))
                first.append(api.request("POST", "/api/v1/orders", token=buyer["accessToken"],
                    headers={"Idempotency-Key": key}, body={"reservationId": reservation["id"]}))
                self.close_connection = True  # Commit upstream, then discard the entire HTTP response.
        server = ThreadingHTTPServer(("127.0.0.1", 0), DropAfterCommit)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        connection, dropped = HTTPConnection("127.0.0.1", server.server_port, timeout=25), False
        try:
            connection.request("POST", "/orders", b"{}")
            try:
                connection.getresponse()
            except RemoteDisconnected:
                dropped = True
        finally:
            connection.close()
            server.shutdown()
            server.server_close()
            thread.join(timeout=5)
        if not first or first[0].status != 201:
            raise Precondition("No se confirmó el commit antes de perder la respuesta")
        replay = api.request("POST", "/api/v1/orders", token=buyer["accessToken"],
            headers={"Idempotency-Key": key}, body={"reservationId": reservation["id"]})
        listing = api.require("GET", "/api/v1/orders", token=buyer["accessToken"])
        self.observed = {"responseDropped": dropped, "committedStatus": first[0].status, "retryStatus": replay.status,
                         "sameOrder": replay.data.get("id") == first[0].data["id"], "totalOrders": listing["totalElements"]}
        expect(dropped and replay.status == 200 and self.observed["sameOrder"] and listing["totalElements"] == 1,
               "El reintento después del commit no recuperó la misma orden")

    def reordered(self):
        buyer = self.buyer("reorder")
        order = self.order("reorder", buyer)
        early = self.evidence(order, "REFUNDED")
        statuses = [self.webhook(early).status]
        pending = self.snapshot(order, buyer)
        statuses.append(self.webhook(self.evidence(order)).status)
        statuses.append(self.webhook(early).status)
        statuses.append(self.webhook(self.evidence(order, "FAILED")).status)
        final = self.snapshot(order, buyer)
        self.observed = {"statuses": statuses, "afterEarlyRefund": pending, "afterCaptureAndReplays": final}
        expect(all(s == 204 for s in statuses) and pending["status"] == "PENDING_PAYMENT" and final["status"] == "PAID"
               and final["validTickets"] == 1, "La repetición o fallo tardío hizo retroceder el pago")

    def simulator(self):
        buyer = self.buyer("simulator")
        order = self.order("simulator", buyer)
        body = {"orderId": order["id"], "action": "capture", "amount": 0, "currency": "EUR"}
        headers = {"Origin": self.frontend.url, "X-Demo-Action": "simulate"}
        missing = self.frontend.request("POST", "/demo/payment", body=body, token=buyer["accessToken"])
        foreign = self.frontend.request("POST", "/demo/payment", body=body, token=buyer["accessToken"],
                                        headers={**headers, "Origin": "https://untrusted.example"})
        anonymous = self.frontend.request("POST", "/demo/payment", body=body, headers=headers)
        self.observed = {"blockedStatuses": [missing.status, foreign.status, anonymous.status]}
        expect(self.observed["blockedStatuses"] == [403, 403, 401], "El simulador no bloqueó solicitudes sin origen o autenticación")
        control = self.frontend.request("POST", "/demo/payment", body=body, token=buyer["accessToken"], headers=headers)
        state = self.snapshot(order, buyer)
        self.observed.update(validSimulationStatus=control.status, order=state)
        expect(control.status == 200 and state["totalAmount"] == 45 and state["currency"] == "USD"
               and state["validTickets"] == 1, "El simulador aceptó importe del navegador o no pudo completar el control")

    def static_files(self):
        paths = ("/.env", "/dev_server.py", "/../.env", "/%2e%2e/.env", "/src/main.ts", "/package.json")
        statuses = [self.frontend.request("GET", path).status for path in paths]
        host = self.frontend.request("GET", "/", headers={"Host": "untrusted.example"})
        self.observed = {"paths": list(paths), "statuses": statuses, "foreignHostStatus": host.status}
        expect(all(s == 404 for s in statuses) and host.status == 403, "El servidor local expuso un archivo privado o aceptó otro Host")

    def search_input(self):
        control = self.api.require("GET", "/api/v1/events?q=" + self.run_id)
        attempts = []
        for query in ("' OR '1'='1", "' OR 1=1 --", "' UNION SELECT NULL --"):
            response = self.api.request("GET", "/api/v1/events?q=" + urllib.parse.quote(query, safe=""))
            count = response.data.get("totalElements") if isinstance(response.data, dict) else None
            attempts.append({"query": query, "httpStatus": response.status, "results": count})
        self.observed = {"controlResults": control["totalElements"], "attempts": attempts}
        expect(control["totalElements"] == 1 and all(r["httpStatus"] == 400 or r["httpStatus"] == 200 and r["results"] == 0
               for r in attempts), "La búsqueda no trató las cadenas SQL como texto literal")

    def run(self):
        cases = [
            ("HTTP-01", "Acceso a compras ajenas", "403/404 y ninguna modificación ajena", self.access),
            ("HTTP-02", "JWT alterado y escalada de rol", "401/403 y registro como USER", self.auth),
            ("HTTP-03", "Cantidades inválidas", "Rechazar negativos, fracciones, booleanos, nulos, overflow y exceso", self.quantity),
            ("HTTP-04", "Precio manipulado", "Importe y moneda calculados por el servidor", self.price),
            ("HTTP-05", "Firma ausente, falsa o cuerpo modificado", "Cabecera ausente 400/401; firma inválida 401; sin entradas; control válido sí funciona", self.signature),
            ("HTTP-06", "Importe o moneda contradictorios", "Rechazar incluso evidencia firmada inválida del proveedor simulado", self.financial_evidence),
            ("HTTP-07", "Seis órdenes simultáneas con la misma clave", "Una orden; conflicto al cambiar payload", self.duplicate_orders),
            ("HTTP-08", "Capturas y reembolsos duplicados", "Una entrada y una invalidación efectiva", self.duplicate_payments),
            ("HTTP-09", "Eventos distintos para una operación", "Aplicar una vez y rechazar contradicciones", lambda: self.duplicate_payments(True)),
            ("HTTP-10", "Veinte reservas para dos entradas", "Exactamente dos reservas y stock nunca negativo", self.oversell),
            ("HTTP-11", "Pagar y cancelar a la vez", "Tres carreras conservan inventario y estado financiero coherentes", self.race),
            ("HTTP-12", "Respuesta perdida después del commit", "Reintento recupera la misma orden", self.dropped_response),
            ("HTTP-13", "Eventos de pago fuera de orden", "Repetir el mismo evento o fallo tardío no revierte la captura", self.reordered),
            ("HTTP-14", "Límites del simulador local", "Origen y sesión obligatorios; importe desde la API", self.simulator),
            ("HTTP-15", "Archivos privados y Host ajeno", "404 para archivos y 403 para Host", self.static_files),
            ("HTTP-16", "Cadenas SQL en búsqueda", "Búsqueda literal, sin errores ni acceso ampliado", self.search_input),
        ]
        for args in cases:
            self.case(*args)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--api", type=local_url, default="http://127.0.0.1:8080")
    parser.add_argument("--frontend", type=local_url, default="http://127.0.0.1:5173")
    parser.add_argument("--output", type=Path, default=ROOT / "reports/adversarial" / datetime.now().strftime("run-%Y%m%d-%H%M%S"))
    parser.add_argument("--with-integration", action="store_true", help="También usa Docker/Testcontainers aislados; máximo 10 minutos")
    args = parser.parse_args()
    if (args.output / "http.json").exists() or args.with_integration and (args.output / "integration.json").exists():
        parser.error("La evidencia ya existe; utiliza otra carpeta.")
    audit = Audit(args.api, args.frontend, args.output.resolve())
    try:
        audit.setup()
        audit.run()
    except (Precondition, OSError, ValueError, KeyError) as error:
        print("Preparación incompleta: " + (str(error) if isinstance(error, Precondition) else type(error).__name__), file=sys.stderr)
        audit.results.append({"id": "SETUP", "title": "Preparación", "expected": "Servicios y fixtures disponibles",
                              "status": "INCONCLUSIVE", "reason": type(error).__name__, "observed": {}})
        audit.save()
    integration_code = 0
    if args.with_integration:
        integration_code = subprocess.run([sys.executable, str(ROOT / "scripts/adversarial-integration.py"),
                                           "--output", str(args.output.resolve())], check=False).returncode
    counts = {status: sum(r["status"] == status for r in audit.results) for status in ("PASS", "FAIL", "INCONCLUSIVE")}
    print(json.dumps({"counts": counts, "report": str(args.output.resolve() / "http.md")}, indent=2))
    return 2 if counts["INCONCLUSIVE"] or integration_code == 2 else 1 if counts["FAIL"] or integration_code else 0


if __name__ == "__main__":
    raise SystemExit(main())
