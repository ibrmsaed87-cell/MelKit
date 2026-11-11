#!/usr/bin/env python3
"""Observe the authorized GitLab build. Never builds Android on GitHub."""
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

PROJECT = "spinel-group/melkit"
API = "https://gitlab.com/api/v4/projects/" + urllib.parse.quote(PROJECT, safe="")
WEB = "https://gitlab.com/" + PROJECT
TOKEN = os.environ["GITLAB_TOKEN"]
SHA = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()


class SafeRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        target = urllib.parse.urlsplit(newurl)
        if target.scheme != "https":
            raise urllib.error.URLError("Non-HTTPS redirect rejected")
        redirected = super().redirect_request(req, fp, code, msg, headers, newurl)
        if target.hostname != "gitlab.com":
            redirected.remove_header("Private-token")
        return redirected


OPENER = urllib.request.build_opener(SafeRedirect())


def request(path, raw=False):
    req = urllib.request.Request(API + path, headers={"PRIVATE-TOKEN": TOKEN})
    with OPENER.open(req, timeout=30) as response:
        data = response.read(8 * 1024 * 1024)
    return data.decode("utf-8", "replace") if raw else json.loads(data)


def emit(message):
    # Never emit credentials, even if an unexpected response echoes this token.
    print(str(message).replace(TOKEN, "[REDACTED]"), flush=True)


def main():
    deadline = time.monotonic() + 30 * 60
    pipeline = None
    last = None
    transient = 0
    while time.monotonic() < deadline:
        try:
            if pipeline is None:
                rows = request("/pipelines?sha=" + SHA + "&ref=main&per_page=10")
                pipeline = next((item for item in rows if item.get("sha") == SHA and item.get("status") != "skipped"), None)
                if pipeline is None:
                    if last is None:
                        emit("Waiting for the exact main commit to start on GitLab: " + SHA)
                        last = "waiting"
                    time.sleep(10)
                    continue
            pipeline = request("/pipelines/" + str(pipeline["id"]))
            assert pipeline["sha"] == SHA
            status = pipeline["status"]
            if status != last:
                emit("GitLab pipeline: " + pipeline["web_url"] + " | " + status)
                last = status
            transient = 0
            if status in ("success", "failed", "canceled", "skipped", "manual"):
                jobs = request("/pipelines/" + str(pipeline["id"]) + "/jobs?per_page=100")
                for job in jobs:
                    emit("Job: " + job["name"] + " | " + job["status"] + " | " + job["web_url"])
                    if job["status"] == "failed":
                        trace = request("/jobs/" + str(job["id"]) + "/trace", raw=True)
                        # Error summaries only; no complete build/environment/credential logs.
                        lines = [re.sub(r"\x1b\[[0-9;]*m", "", line) for line in trace.splitlines()]
                        selected = [line for line in lines if re.search(
                            r"(^e: |^error: |^> Task .*FAILED|^Execution failed|^Could not |^A failure occurred|^FAILURE:|^\* What went wrong|^> .*failed|^> .*error)", line)]
                        for line in selected[-65:]:
                            emit(line[:1200])
                    if job["name"] == "build_signed_apk" and job["status"] == "success" and job.get("artifacts_file"):
                        emit("ARTIFACTS_URL=" + job["web_url"] + "/artifacts/download")
                        report = request("/jobs/" + str(job["id"]) + "/artifacts/build/reports/melkit-build.txt", raw=True)
                        for line in report.splitlines():
                            if line.startswith(("Package:", "Version code:", "Signing certificate SHA-256:", "Live AdMob units:")):
                                emit(line)
                            elif re.fullmatch(r"[a-f0-9]{64}\s+app/build/outputs/apk/release/[A-Za-z0-9_.-]+\.apk", line):
                                digest, path = line.split()
                                emit("APK_SHA256=" + digest)
                                emit("APK_URL=" + job["web_url"] + "/artifacts/raw/" + path)
                return 0 if status == "success" and any(job["name"] == "build_signed_apk" and job["status"] == "success" for job in jobs) else 1
        except urllib.error.HTTPError as error:
            if error.code in (401, 403, 404):
                emit("GitLab source was pushed, but build observation is unavailable with this token. HTTP " + str(error.code))
                emit("PIPELINES_URL=" + WEB + "/-/pipelines")
                return 1
            transient += 1
            if transient >= 5:
                raise
        except (urllib.error.URLError, TimeoutError):
            transient += 1
            if transient >= 5:
                raise
        time.sleep(15)
    emit("Build observation reached its time limit. The pipeline may still be running.")
    return 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as error:
        emit("Build observation failed: " + type(error).__name__)
        sys.exit(1)
