#!/usr/bin/env python3
"""DEVELOPMENT / TEST DATA ONLY – never run against a production instance.

Creates a demo family on a FRESH FamilyCircle instance through the public API:
  anna@example.test (instance admin), bob@example.test, clara@example.test
  password for all: "demo password 1", family "Demo", everyone shares with everyone,
  Bob has a live position, Clara a 3-hour-old (stale) one. Anna has none (use the app).

Usage: python scripts/seed-dev-data.py --url http://localhost:9092 --setup-code DEV-SETUP
       [--domain smoke.test] [--password "..."]
Only the Python standard library is used.
"""
import argparse
import datetime as dt
import http.cookiejar
import json
import sys
import urllib.request
import uuid


class Client:
    def __init__(self, base, bearer=None):
        self.base = base.rstrip("/") + "/api/v1"
        self.bearer = bearer
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

    def call(self, method, path, body=None, expect=(200, 201, 204)):
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(self.base + path, data=data, method=method)
        req.add_header("X-FC-CSRF", "1")
        if data is not None:
            req.add_header("Content-Type", "application/json")
        if self.bearer:
            req.add_header("Authorization", "Bearer " + self.bearer)
        try:
            with self.opener.open(req) as res:
                raw = res.read()
                status = res.status
        except urllib.error.HTTPError as e:
            raw, status = e.read(), e.code
        if status not in expect:
            sys.exit(f"{method} {path} -> {status}: {raw[:300]!r}")
        return json.loads(raw) if raw else None


def device(base, email, password, name):
    t = Client(base).call("POST", "/auth/token", {"grant_type": "password", "email": email, "password": password, "device_name": name})
    return Client(base, t["access_token"]), t["user"]["id"]


def point(lat, lon, minutes_ago, acc=8.0):
    at = dt.datetime.now(dt.timezone.utc) - dt.timedelta(minutes=minutes_ago)
    return {"id": str(uuid.uuid4()), "recorded_at": at.isoformat(), "lat": lat, "lon": lon, "accuracy_m": acc, "battery_pct": 70}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--url", default="http://localhost:9092")
    ap.add_argument("--setup-code", required=True)
    ap.add_argument("--domain", default="example.test")
    ap.add_argument("--password", default="demo password 1")
    a = ap.parse_args()
    pw, d = a.password, a.domain

    anna = Client(a.url)
    anna.call("POST", "/setup", {"setup_token": a.setup_code, "email": f"anna@{d}", "password": pw,
                                 "display_name": "Anna", "family_name": "Demo"}, expect=(201,))
    fam = anna.call("GET", "/families")["families"][0]["id"]
    ids = {"anna": anna.call("GET", "/me")["id"]}
    for name in ("Bob", "Clara"):
        code = anna.call("POST", f"/families/{fam}/invites", {})["code"]
        Client(a.url).call("POST", "/auth/register", {"invite_code": code, "email": f"{name.lower()}@{d}",
                                                      "password": pw, "display_name": name}, expect=(201,))
    bob, ids["bob"] = device(a.url, f"bob@{d}", pw, "Bob (seed)")
    clara, ids["clara"] = device(a.url, f"clara@{d}", pw, "Clara (seed)")
    bob.call("POST", "/locations", {"points": [point(47.3769, 8.5417, 0.2)]})
    clara.call("POST", "/locations", {"points": [point(47.3605, 8.5552, 180, 35)]})
    for who, cli in (("bob", bob), ("clara", clara)):
        cli.call("PATCH", "/me/settings", {"share_battery": True})
        for other in ids:
            if other != who:
                cli.call("PUT", f"/shares/{ids[other]}")
    for other in ("bob", "clara"):
        anna.call("PUT", f"/shares/{ids[other]}")
    print(f"Seeded DEV data on {a.url}: anna/bob/clara@{d}, password {pw!r}, family 'Demo' ({fam})")


if __name__ == "__main__":
    main()
