#!/usr/bin/env python3
"""Verify the downloadable workbook against a local HTTP fixture and SQLite."""
import argparse
from contextlib import closing
import json
import os
from pathlib import Path
import sqlite3
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.error import HTTPError
from urllib.request import Request, urlopen
import uuid


class Fixture(BaseHTTPRequestHandler):
    calls = {"/echo": 0, "/upload": 0}

    def do_POST(self):
        if self.headers.get("Transfer-Encoding") == "chunked":
            chunks = []
            while True:
                length = int(self.rfile.readline().split(b";", 1)[0], 16)
                if length == 0:
                    while self.rfile.readline() != b"\r\n":
                        pass
                    break
                chunks.append(self.rfile.read(length))
                assert self.rfile.read(2) == b"\r\n"
            body = b"".join(chunks)
        else:
            body = self.rfile.read(int(self.headers["Content-Length"]))
        if self.path == "/echo":
            valid = body == b"hello"
            response, content_type = body, "text/plain; charset=UTF-8"
        elif self.path == "/upload":
            valid = (self.headers.get_content_type() == "multipart/form-data"
                     and b'filename="example.txt"' in body and b"hello" in body)
            response = json.dumps({"received": valid}).encode()
            content_type = "application/json; charset=UTF-8"
        else:
            self.send_error(404)
            return
        Fixture.calls[self.path] += 1
        self.send_response(200 if valid else 400)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(response)))
        self.end_headers()
        self.wfile.write(response)

    def log_message(self, *args):
        pass


class Platform:
    def __init__(self, url):
        self.url = url.rstrip("/")
        self.token = None

    def request(self, method, path, value=None, content_type=None, raw=False):
        headers = {}
        if self.token:
            headers["X-Platform-Token"] = self.token
        if content_type:
            body = value
            headers["Content-Type"] = content_type
        elif value is not None:
            body = json.dumps(value).encode()
            headers["Content-Type"] = "application/json"
        else:
            body = None
        try:
            with urlopen(Request(self.url + path, body, headers, method=method), timeout=30) as response:
                result = response.read()
        except HTTPError as error:
            raise RuntimeError("%s %s: HTTP %s: %s" % (
                method, path, error.code, error.read().decode("utf-8", errors="replace"))) from None
        if raw:
            return result
        result = json.loads(result)
        if result["success"] is not True:
            raise RuntimeError("%s %s: %s" % (method, path, result.get("message")))
        return result["data"]

    def import_workbook(self, workbook):
        boundary = "ProbeLoom" + uuid.uuid4().hex
        body = (("--%s\r\nContent-Disposition: form-data; name=\"file\"; "
                 "filename=\"smoke.xlsx\"\r\nContent-Type: application/vnd.openxmlformats-officedocument."
                 "spreadsheetml.sheet\r\n\r\n") % boundary).encode()
        body += workbook + ("\r\n--%s--\r\n" % boundary).encode()
        return self.request("POST", "/api/case-workbook/import", body,
                            "multipart/form-data; boundary=" + boundary)

    def execute(self, case_id, environment_id):
        execution_id = self.request("POST", "/api/cases/%s/run" % case_id,
                                    {"environmentId": environment_id})
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            result = self.request("GET", "/api/executions/%s" % execution_id)
            if result["status"] not in ("QUEUED", "RUNNING"):
                if result["status"] != "PASSED":
                    raise RuntimeError("Execution %s failed: %s" % (execution_id, json.dumps(result, ensure_ascii=False)))
                assert len(result["steps"]) == 8, "Expected eight executed template steps"
                assert all(step["status"] == "PASSED" for step in result["steps"]), "Step did not pass"
                return {"id": execution_id, "status": result["status"], "steps": len(result["steps"])}
            time.sleep(0.25)
        raise TimeoutError("Execution %s exceeded 90 seconds" % execution_id)


def workbook_contract(case):
    actions = case["actions"]
    assert [action["actionType"] for action in actions] == [
        "BACKUP", "INSERT", "UPDATE", "HTTP", "UPLOAD", "ASSERT", "CLEANUP", "RESTORE"]
    http = json.loads(actions[3]["requestBody"])
    assert http["connection"] == "demo" and http["path"] == "/echo"
    upload = actions[4]["upload"]
    assert upload["connection"] == "demo" and upload["path"] == "/upload"
    assert upload["contentBase64"] == "aGVsbG8="
    contract = json.loads(json.dumps(actions))
    for action in contract:
        del action["id"]
        if action.get("upload"):
            del action["upload"]["actionId"]
            del action["upload"]["assetId"]
    return contract


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default="http://127.0.0.1:18092")
    parser.add_argument("--username", default="admin")
    parser.add_argument("--password", default=os.environ.get("PROBELOOM_PASSWORD"))
    args = parser.parse_args()
    output = Path(__file__).resolve().parents[1] / ".build" / "smoke"
    run_dir = output / ("run-" + uuid.uuid4().hex)
    run_dir.mkdir(parents=True)
    database = run_dir / "business.db"
    with closing(sqlite3.connect(database)) as connection:
        connection.execute("CREATE TABLE demo_records (record_id VARCHAR(64), record_name VARCHAR(128), amount INTEGER)")
        connection.execute("INSERT INTO demo_records VALUES ('keep', 'untouched', 7)")
        connection.commit()
    fixture = ThreadingHTTPServer(("127.0.0.1", 0), Fixture)
    thread = threading.Thread(target=fixture.serve_forever, daemon=True)
    thread.start()
    report = {"url": args.url, "businessDatabase": str(database), "passed": False}
    try:
        api = Platform(args.url)
        status = api.request("GET", "/api/auth/status")
        if status["initializationRequired"]:
            login = api.request("POST", "/api/auth/initialize", {"password": args.password or uuid.uuid4().hex})
        else:
            if not args.password:
                raise ValueError("Provide --password or PROBELOOM_PASSWORD for the initialized account")
            login = api.request("POST", "/api/auth/login", {"username": args.username, "password": args.password})
        api.token = login["token"]
        environment = api.request("POST", "/api/environments", {
            "name": "Smoke local", "refapp": "default", "defaultEnvironment": True,
            "connections": [{"key": "demo", "protocol": "HTTP",
                             "baseUrl": "http://127.0.0.1:%s" % fixture.server_port, "headers": {}}]})
        datasource = api.request("POST", "/api/datasources", {
            "name": "Smoke SQLite", "resourceKey": "default", "databaseType": "SQLITE",
            "jdbcUrl": "jdbc:sqlite:" + database.as_posix(), "username": "", "password": "",
            "accessMode": "TEST_WRITE"})
        api.request("POST", "/api/datasources/%s/test" % datasource["id"])
        template = api.request("GET", "/api/case-workbook/template", raw=True)
        (run_dir / "template.xlsx").write_bytes(template)
        first = api.import_workbook(template)
        first_contract = workbook_contract(first)
        report["executions"] = [api.execute(first["id"], environment["id"])]
        exported = api.request("GET", "/api/cases/%s/workbook" % first["id"], raw=True)
        (run_dir / "exported.xlsx").write_bytes(exported)
        second = api.import_workbook(exported)
        assert workbook_contract(second) == first_contract, "Workbook roundtrip changed executable fields"
        report["executions"].append(api.execute(second["id"], environment["id"]))
        with closing(sqlite3.connect(database)) as connection:
            rows = connection.execute("SELECT record_id, record_name, amount FROM demo_records ORDER BY record_id").fetchall()
        assert rows == [("keep", "untouched", 7)], "Business database did not return to its initial state"
        assert Fixture.calls == {"/echo": 2, "/upload": 2}, "Expected two HTTP and upload calls"
        report.update(passed=True, caseId=second["id"], environmentId=environment["id"],
                      datasourceId=datasource["id"], fixtureCalls=Fixture.calls, restoredRows=rows)
    except Exception as error:
        report["error"] = str(error)
        raise
    finally:
        fixture.shutdown()
        fixture.server_close()
        thread.join(timeout=5)
        (output / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
