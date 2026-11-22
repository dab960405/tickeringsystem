"""Real Chromium SPA bootstrap regression: direct deep links and reload, no live backend.

Run only after the production Angular build. Uses loopback HTTP and a disposable
browser profile. No user credentials, durable database or external network requests.
"""

from contextlib import contextmanager
from http.server import ThreadingHTTPServer
import json
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import threading
from urllib.parse import urlsplit

from dev_server import Handler, WEB_ROOT


class TracedHandler(Handler):
    def send_body(self, status, body, content_type="application/json"):
        self.server.responses.append((urlsplit(self.path).path, status, content_type))
        super().send_body(status, body, content_type)


@contextmanager
def local_gateway():
    server = ThreadingHTTPServer(("127.0.0.1", 0), TracedHandler)
    server.responses = []
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield server
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)


def chrome_binary():
    for binary in ("google-chrome", "google-chrome-stable", "chromium"):
        result = shutil.which(binary)
        if result:
            return result
    raise AssertionError("A real Chromium/Chrome executable is required for the browser gate")


def render(chrome, url):
    with tempfile.TemporaryDirectory(prefix="ticketing-browser-") as profile:
        result = subprocess.run(
            [
                chrome,
                "--headless=new",
                "--no-sandbox",
                "--disable-gpu",
                "--disable-dev-shm-usage",
                "--disable-background-networking",
                "--disable-extensions",
                "--no-proxy-server",
                "--no-first-run",
                "--virtual-time-budget=5000",
                "--user-data-dir=" + profile,
                "--dump-dom",
                url,
            ],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            timeout=40,
            check=False,
        )
    if result.returncode:
        raise AssertionError(
            "Chrome failed to render a local deep link (exit "
            + str(result.returncode)
            + "): "
            + result.stderr[-800:]
        )
    return result.stdout


def verify_rendered_page(html, path):
    if not re.search(r"<header(?:\s|>)", html):
        raise AssertionError(path + ": Angular did not bootstrap (header missing)")
    if not re.search(r"<main(?:\s|>)", html):
        raise AssertionError(path + ": Angular did not bootstrap (main missing)")
    if not re.search(r"<app-root[^>]*>\s*<", html):
        raise AssertionError(path + ": application root was not rendered")


def main():
    if not (Path(WEB_ROOT) / "index.html").is_file():
        raise AssertionError("Build Angular before running the real-browser smoke gate")
    chrome = chrome_binary()
    routes = [
        "/events/00000000-0000-0000-0000-000000000001",
        "/events/00000000-0000-0000-0000-000000000001",  # fresh reload
        "/eventos/00000000-0000-0000-0000-000000000001",  # legacy bookmark
    ]

    with local_gateway() as server:
        for path in routes:
            start = len(server.responses)
            html = render(chrome, f"http://127.0.0.1:{server.server_port}{path}")
            verify_rendered_page(html, path)

            requests = server.responses[start:]
            assets = [
                (requested, status)
                for requested, status, _ in requests
                if requested.endswith((".js", ".css"))
            ]
            if not assets or any(status != 200 for _, status in assets):
                raise AssertionError(path + ": JS/CSS assets missing or not HTTP 200: " + str(assets))
            if any(requested.startswith(("/events/", "/eventos/")) for requested, _ in assets):
                raise AssertionError(path + ": bundled assets resolved relative to the nested route")

    print(json.dumps({"result": "PASS", "browser": Path(chrome).name,
                      "direct_or_reload_routes": routes, "all_assets_http_200": True}))


if __name__ == "__main__":
    main()
