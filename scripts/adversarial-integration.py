#!/usr/bin/env python3
"""Run fault injection against disposable Testcontainers in a temporary source copy."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
CASES = {
    "order/OrderPaymentFlowIT.java": (
        "cancelled-reservation.java.txt",
        "should_expireUnpaidOrderAfterGraceWhenItsReservationWasCancelled",
    ),
    "operation/service/OperationWorkerIT.java": (
        "recovery-projection.java.txt",
        "should_notReevaluateEarlyRefundWhenRecoveringProjectionAfterCapture",
    ),
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    if (output / "integration.json").exists():
        parser.error("La evidencia ya existe; utiliza otra carpeta.")
    if os.environ.get("DOCKER_HOST", "unix:///var/run/docker.sock").startswith(("tcp:", "ssh:")):
        parser.error("Esta prueba requiere un Docker local, no un host remoto.")
    work = Path(tempfile.mkdtemp(prefix="ticketing-adversarial-"))
    try:
        for name in ("pom.xml", "mvnw", ".mvn", "common-events", "ticketing-api"):
            source, target = ROOT / name, work / name
            if source.is_dir():
                shutil.copytree(source, target, ignore=shutil.ignore_patterns("target", ".env", "*.log"))
            else:
                shutil.copy2(source, target)
        # The reactor also declares notification-service, so retain its source configuration.
        shutil.copytree(ROOT / "notification-service", work / "notification-service",
                        ignore=shutil.ignore_patterns("target", ".env", "*.log"))
        tests = work / "ticketing-api/src/test/java/com/portfolio/ticketing"
        for relative, (fixture, _) in CASES.items():
            path = tests / relative
            text = path.read_text()
            insertion = (ROOT / "scripts/adversarial-fixtures" / fixture).read_text()
            index = text.rfind("}")
            path.write_text(text[:index] + "\n" + insertion + text[index:])
        base = tests / "support/AbstractIntegrationTest.java"
        base.write_text(base.read_text().replace(".withReuse(true)", ".withReuse(false)"))
        selected = [Path(path).stem + "#" + method for path, (_, method) in CASES.items()]
        selected += ["RedisOutageIT", "ReservationLifecycleConcurrencyIT"]
        command = ["./mvnw", "-B", "-ntp", "-pl", "ticketing-api", "-am",
                   "-Dtest=NoAdversarialUnitTests", "-Dsurefire.failIfNoSpecifiedTests=false",
                   "-Dit.test=" + ",".join(selected), "-Dfailsafe.failIfNoSpecifiedTests=false",
                   "test-compile", "failsafe:integration-test", "failsafe:verify"]
        # Never inherit the running demo's database/Redis/JWT configuration into these tests.
        prefixes = ("SPRING_", "POSTGRES_", "REDIS_", "TICKETING_", "PAYMENT_", "JWT_", "MONGO", "NOTIFICATION_")
        environment = {key: value for key, value in os.environ.items() if not key.startswith(prefixes)}
        environment["TESTCONTAINERS_REUSE_ENABLE"] = "false"
        print("Integración aislada: PostgreSQL/Redis temporales; log en " + str(output / "integration.log"), flush=True)
        with (output / "integration.log").open("w") as log:
            try:
                completed = subprocess.run(command, cwd=work, env=environment, stdout=log,
                                           stderr=subprocess.STDOUT, timeout=600, check=False)
                exit_code = completed.returncode
            except subprocess.TimeoutExpired:
                exit_code = 124
                log.write("\nAdversarial runner: exceeded the 600-second limit.\n")
        results = []
        for file in sorted(work.glob("*/target/failsafe-reports/TEST-*.xml")):
            suite = ET.parse(file).getroot()
            for case in suite.findall("testcase"):
                failure, error = case.find("failure"), case.find("error")
                status = "FAIL" if failure is not None else "INCONCLUSIVE" if error is not None or case.find("skipped") is not None else "PASS"
                detail = (failure if failure is not None else error)
                results.append({"class": case.get("classname"), "test": case.get("name"),
                                "status": status, "seconds": case.get("time"),
                                "detail": detail.get("message", "") if detail is not None else ""})
            shutil.copy2(file, output / file.name)
        report = {"command": command, "exitCode": exit_code, "results": results,
                  "isolation": "Temporary source copy; disposable PostgreSQL and Redis; no dev DB writes"}
        if not results:
            report["error"] = "No test results; inspect integration.log. Do not interpret this as a pass."
        (output / "integration.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
        lines = ["# Integración adversaria aislada", "", f"Salida de Maven: `{exit_code}`.", "",
                 "| Prueba | Resultado |", "| --- | --- |"]
        lines += [f"| `{item['test']}` | {item['status']} |" for item in results]
        for item in results:
            if item["detail"]:
                lines += ["", "## " + item["test"], "", "```text", item["detail"], "```"]
        (output / "integration.md").write_text("\n".join(lines) + "\n")
        print(json.dumps({"exitCode": exit_code, "counts": {
            status: sum(item["status"] == status for item in results)
            for status in ("PASS", "FAIL", "INCONCLUSIVE")}}, indent=2), flush=True)
        if not results or any(item["status"] == "INCONCLUSIVE" for item in results) or exit_code not in (0, 1):
            return 2
        return 1 if any(item["status"] == "FAIL" for item in results) else 2 if exit_code else 0
    finally:
        shutil.rmtree(work)


if __name__ == "__main__":
    raise SystemExit(main())
