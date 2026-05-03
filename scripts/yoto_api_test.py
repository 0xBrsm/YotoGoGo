#!/usr/bin/env python3
"""
Yoto API test script — run from Termux to iterate on API calls without rebuilding the app.

Token is read automatically from the app's SharedPreferences (requires the debug APK
to be installed). Just log in via the app once, then run this script.

Usage:
  python yoto_api_test.py                        # auto-load token, interactive prompt
  python yoto_api_test.py --token TOKEN SLUG      # use explicit token, test card directly
"""

import argparse
import json
import os
import subprocess
import requests

API_BASE = "https://api.yotoplay.com"
APP_ID   = "com.yotogogo"

HEADERS = {
    "User-Agent": "Yoto/2.73 (com.yotoplay.Yoto; build:10405; iOS 17.4.0) Alamofire/5.6.4",
}

# ── Token ────────────────────────────────────────────────────────────────────

def load_token_from_app():
    """Read auth_token from the app's SharedPreferences via run-as (debug APK only)."""
    prefs_path = f"/data/data/{APP_ID}/shared_prefs/yoto.xml"
    try:
        result = subprocess.run(
            ["/system/bin/run-as", APP_ID, "cat", prefs_path],
            capture_output=True, text=True, check=True
        )
        xml = result.stdout
        # <string name="auth_token">TOKEN</string>
        import re
        match = re.search(r'<string name="auth_token">([^<]+)</string>', xml)
        if match:
            return match.group(1)
        print("auth_token not found in SharedPreferences — log in via the app first.")
    except subprocess.CalledProcessError as e:
        print(f"run-as failed: {e.stderr.strip()}")
        print("Make sure the debug APK is installed and you've logged in via the app.")
    return None

# ── API helpers ──────────────────────────────────────────────────────────────

def get_card(token, slug, query=None):
    url = f"{API_BASE}/card/{slug}"
    if query:
        url += f"?{query}"
    print(f"GET {url}")
    r = requests.get(url, headers={**HEADERS, "Authorization": f"Bearer {token}"})
    print(f"Status: {r.status_code}")
    try:
        print(json.dumps(r.json(), indent=2)[:3000])
    except Exception:
        print(r.text[:3000])
    return r

def list_library(token):
    url = f"{API_BASE}/card/family/library"
    print(f"GET {url}")
    r = requests.get(url, headers={**HEADERS, "Authorization": f"Bearer {token}"})
    print(f"Status: {r.status_code}")
    if not r.ok:
        print(r.text[:500])
        return r
    try:
        data = r.json()
        print(json.dumps(data, indent=2)[:6000])
    except Exception:
        print(r.text[:1000])
    return r

def probe_card(token, slug, query=None):
    qs = f"?{query}" if query else ""
    candidates = [
        f"{API_BASE}/card/{slug}{qs}",
        f"{API_BASE}/card/{slug}",
        f"{API_BASE}/card/family/library/{slug}{qs}",
        f"{API_BASE}/card/family/library/{slug}",
    ]
    print(f"\n── Probing {slug} ──────────────────────────────")
    for url in candidates:
        r = requests.get(url, headers={**HEADERS, "Authorization": f"Bearer {token}"})
        body = r.text[:200].replace('\n', ' ')
        print(f"  {r.status_code}  {url}")
        if r.status_code == 200:
            try:
                data = r.json()
                card = data.get("card", data)
                has_tracks = bool(
                    card.get("content", {}).get("chapters") if isinstance(card, dict) else False
                )
                print(f"       ✓ card={card.get('title') if isinstance(card,dict) else '?'}  tracks={has_tracks}")
                print(json.dumps(data, indent=2)[:2000])
            except Exception:
                print(f"       body: {body}")
        else:
            print(f"       {body}")

# ── Main ─────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--token", help="Use this token instead of reading from app")
    parser.add_argument("slug", nargs="?", help="Card slug to fetch")
    parser.add_argument("query", nargs="?", help="NFC query string (e.g. key=abc123)")
    args = parser.parse_args()

    token = args.token or load_token_from_app()
    if not token:
        return

    if args.slug:
        probe_card(token, args.slug, args.query)
    else:
        print("\n── Library ──────────────────────────────")
        list_library(token)

        print("\n── Interactive ──────────────────────────")
        print("Commands: probe <slug> [query]  |  card <slug> [query]  |  lib  |  quit")
        while True:
            try:
                line = input("\n> ").strip()
            except (EOFError, KeyboardInterrupt):
                break
            if not line or line == "quit":
                break
            parts = line.split()
            if parts[0] == "lib":
                list_library(token)
            elif parts[0] == "probe" and len(parts) >= 2:
                probe_card(token, parts[1], parts[2] if len(parts) > 2 else None)
            elif parts[0] == "card" and len(parts) >= 2:
                get_card(token, parts[1], parts[2] if len(parts) > 2 else None)
            else:
                print("probe <slug> [query]  |  card <slug> [query]  |  lib  |  quit")

if __name__ == "__main__":
    main()
