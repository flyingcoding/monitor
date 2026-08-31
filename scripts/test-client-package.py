#!/usr/bin/env python3
"""Smoke-test the shaded client against a loopback-only protocol fixture."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def main():
    """Verify packaged logging, original routes and bounded graceful shutdown."""
    root = Path(__file__).resolve().parents[1]
    jar = root / "monitor-client/target/monitor-client.jar"
    java_home = os.environ.get("JAVA_HOME")
    java = str(Path(java_home) / "bin/java") if java_home else "java"
    seen = set()
    errors = []

    class Handler(BaseHTTPRequestHandler):
        """Accept only the original agent protocol without forwarding any metrics."""

        def do_GET(self):
            """Accept heartbeat/offline; registration should use the persisted fixture."""
            self.respond()

        def do_POST(self):
            """Consume bounded metric payloads without printing host data."""
            size = int(self.headers.get("Content-Length", "0"))
            if size > 262144:
                errors.append("oversized request")
            else:
                json.loads(self.rfile.read(size))
            self.respond()

        def respond(self):
            """Reply using RestBean code=200 and validate the raw agent credential."""
            if not self.path.startswith("/monitor/"):
                errors.append("wrong protocol prefix")
            if self.headers.get("Authorization") != "package-fixture-token":
                errors.append("wrong credential")
            seen.add(self.path)
            body = b'{"code":200,"message":"ok"}'
            self.send_response(200)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *_args):
            """Keep fixture traffic and sampled hardware details out of CI logs."""

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    # Keep artifacts for inspection; cleanup is explicit through the user's trash tool.
    directory = Path(tempfile.mkdtemp(prefix="monitor-package-smoke-"))
    address = "http://127.0.0.1:" + str(server.server_port)
    (directory / "config").mkdir()
    (directory / "config/server.json").write_text(json.dumps({"address": address, "token": "package-fixture-token"}))
    environment = dict(os.environ, MONITOR_SERVER=address, MONITOR_TOKEN="package-fixture-token", MONITOR_LOG_DIR=str(directory / "logs"))
    process = None
    try:
        with (directory / "stderr.log").open("w") as stderr:
            process = subprocess.Popen([java, "-Xms32m", "-Xmx64m", "-Dmonitor.report.interval-seconds=1",
                                        "-jar", str(jar)], cwd=directory, env=environment,
                                       stdout=subprocess.DEVNULL, stderr=stderr)
            deadline = time.monotonic() + 20
            while time.monotonic() < deadline and process.poll() is None:
                if {"/monitor/detail", "/monitor/runtime/batch"}.issubset(seen):
                    break
                time.sleep(0.1)
            assert {"/monitor/detail", "/monitor/runtime/batch"}.issubset(seen), "packaged agent did not report"
            assert "/monitor/register" not in seen, "restart consumed registration token"
            assert not errors, errors
            process.terminate()
            process.wait(timeout=10)
        logs = (directory / "logs/monitor-client.log").read_text()
        assert "Starting monitoring" in logs, "packaged Logback provider/configuration inactive"
        assert "package-fixture-token" not in logs, "credential leaked to log"
        assert (directory / "stderr.log").stat().st_size < 32768, "unexpected stderr growth"
        print("PASS packaged client: original protocol, reporting, logging and shutdown")
        print("Fixture artifacts: " + str(directory))
    finally:
        if process is not None and process.poll() is None:
            process.kill()
            process.wait(timeout=5)
        server.shutdown()
        server.server_close()


if __name__ == "__main__":
    main()
