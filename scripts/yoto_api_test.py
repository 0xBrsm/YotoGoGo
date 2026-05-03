#!/usr/bin/env python3
"""
Yoto API test script — run from Termux to iterate on API calls without rebuilding the app.

One-time setup: add http://localhost:8765/callback to Allowed Callback URLs
in your Yoto developer portal app settings.

Usage:
  python yoto_api_test.py                        # auth + interactive prompt
  python yoto_api_test.py --token TOKEN SLUG      # skip auth, test card directly
  python yoto_api_test.py --token TOKEN SLUG QUERY # include NFC query string
"""

import argparse
import base64
import hashlib
import http.server
import json
import secrets
import threading
import urllib.parse
import webbrowser
import requests

CLIENT_ID    = "Ui8g0T3UR0CIsZJMhHpzouU8dfAm4ZEK"
REDIRECT_URI = "http://localhost:8765/callback"
AUTHORIZE    = "https://login.yotoplay.com/authorize"
TOKEN_URL    = "https://login.yotoplay.com/oauth/token"
API_BASE     = "https://api.yotoplay.com"
SCOPES       = "family:library:view user:content:view offline_access"

# ── PKCE helpers ────────────────────────────────────────────────────────────

def pkce_pair():
    verifier  = base64.urlsafe_b64encode(secrets.token_bytes(64)).rstrip(b"=").decode()
    challenge = base64.urlsafe_b64encode(
        hashlib.sha256(verifier.encode()).digest()
    ).rstrip(b"=").decode()
    return verifier, challenge

# ── Auth flow ────────────────────────────────────────────────────────────────

def authenticate():
    verifier, challenge = pkce_pair()
    auth_url = AUTHORIZE + "?" + urllib.parse.urlencode({
        "response_type":         "code",
        "client_id":             CLIENT_ID,
        "redirect_uri":          REDIRECT_URI,
        "code_challenge":        challenge,
        "code_challenge_method": "S256",
        "scope":                 SCOPES,
    })

    code_holder = {}

    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            parsed = urllib.parse.urlparse(self.path)
            params = urllib.parse.parse_qs(parsed.query)
            if "code" in params:
                code_holder["code"] = params["code"][0]
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b"<h2>Authorized! You can close this tab.</h2>")

        def log_message(self, *args):
            pass  # suppress server noise

    server = http.server.HTTPServer(("localhost", 8765), Handler)
    thread = threading.Thread(target=server.handle_request)
    thread.start()

    print(f"\nOpening browser for Yoto login…\n{auth_url}\n")
    webbrowser.open(auth_url)
    thread.join(timeout=120)

    code = code_holder.get("code")
    if not code:
        raise RuntimeError("No code received — did the browser redirect back?")

    resp = requests.post(TOKEN_URL, data={
        "grant_type":    "authorization_code",
        "client_id":     CLIENT_ID,
        "code":          code,
        "redirect_uri":  REDIRECT_URI,
        "code_verifier": verifier,
    })
    resp.raise_for_status()
    token = resp.json().get("access_token")
    if not token:
        raise RuntimeError(f"No access_token in response: {resp.text}")
    print(f"Token: {token[:20]}…\n")
    return token

# ── API helpers ──────────────────────────────────────────────────────────────

HEADERS = {
    "User-Agent": "Yoto/2.73 (com.yotoplay.Yoto; build:10405; iOS 17.4.0) Alamofire/5.6.4",
}

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
    try:
        data = r.json()
        cards = data.get("cards", [])
        print(f"{len(cards)} card(s) in library:")
        for c in cards[:10]:
            card = c.get("card", {})
            print(f"  {card.get('cardId')} — {card.get('title')}")
        if len(cards) > 10:
            print(f"  … and {len(cards) - 10} more")
    except Exception:
        print(r.text[:1000])
    return r

# ── Main ─────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--token", help="Skip auth and use this token")
    parser.add_argument("slug", nargs="?", help="Card slug to fetch")
    parser.add_argument("query", nargs="?", help="NFC query string (e.g. key=abc123)")
    args = parser.parse_args()

    token = args.token or authenticate()

    if args.slug:
        get_card(token, args.slug, args.query)
    else:
        print("\n── Library ──────────────────────────────")
        list_library(token)

        print("\n── Interactive ──────────────────────────")
        print("Commands: card <slug> [query]  |  lib  |  quit")
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
            elif parts[0] == "card" and len(parts) >= 2:
                get_card(token, parts[1], parts[2] if len(parts) > 2 else None)
            else:
                print("card <slug> [query]  |  lib  |  quit")

if __name__ == "__main__":
    main()
