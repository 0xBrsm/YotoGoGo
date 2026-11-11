#!/usr/bin/env python3
"""
Turn a podcast RSS feed into a Yoto Make Your Own playlist of streaming tracks.

Yoto's built-in podcast feature only shows the 25 newest episodes. This script
reads the whole feed and writes every episode as a "stream" track pointing at
the podcast's own MP3 URL, so nothing is uploaded and there is no size cap
(Yoto allows up to 100 tracks per card). The player needs Wi-Fi to play it,
same as a Yoto podcast card.

Each episode's player icon is the first emoji of its title (Twemoji art,
CC-BY 4.0), uploaded once per emoji and cached in ~/.config/yoto-gogo/icons.json.

Re-run it with the same --card-id whenever new episodes come out; when the
card already matches the feed it changes nothing.

Usage:
  podcast_to_card.py login                      # one-time browser sign-in
  podcast_to_card.py list                       # show your MYO cards and IDs
  podcast_to_card.py sync FEED_URL --dry-run    # print the playlist JSON only
  podcast_to_card.py sync FEED_URL              # create a new playlist
  podcast_to_card.py sync FEED_URL --card-id ABCDE   # overwrite that card

Auth: uses a Yoto developer *public client* with
  http://127.0.0.1:8787/callback
registered as a redirect URL (yoto.dev dashboard). The client ID defaults to
the yoto-gogo app's; override with --client-id or YOTO_CLIENT_ID. The refresh
token is kept in ~/.config/yoto-gogo/token.json (mode 600).

Standard library only, so it runs in Termux or anywhere with Python 3.8+.
"""

import argparse
import base64
import email.utils
import hashlib
import http.server
import json
import os
import re
import secrets
import select
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

API_BASE = "https://api.yotoplay.com"
AUTHORIZE_URL = "https://login.yotoplay.com/authorize"
TOKEN_URL = "https://login.yotoplay.com/oauth/token"
DEFAULT_CLIENT_ID = "Ui8g0T3UR0CIsZJMhHpzouU8dfAm4ZEK"
REDIRECT_PORT = 8787
REDIRECT_URI = f"http://127.0.0.1:{REDIRECT_PORT}/callback"
# GET /content/{id} wants the view scopes explicitly; manage alone is refused.
SCOPES = "user:content:manage user:content:view family:library:view offline_access"
USER_AGENT = "yoto-gogo-podcast-sync/1.0"
MAX_TRACKS = 100

CONFIG_DIR = os.path.expanduser("~/.config/yoto-gogo")
TOKEN_FILE = os.path.join(CONFIG_DIR, "token.json")
BACKUP_DIR = os.path.join(CONFIG_DIR, "backups")

NS = {"itunes": "http://www.itunes.com/dtds/podcast-1.0.dtd"}


# ── HTTP ─────────────────────────────────────────────────────────────────────

class HttpError(Exception):
    def __init__(self, status, body):
        super().__init__(f"HTTP {status}: {body[:500]}")
        self.status = status
        self.body = body


def http(method, url, *, token=None, json_body=None, form=None, timeout=60):
    headers = {"User-Agent": USER_AGENT, "Accept": "application/json"}
    data = None
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if json_body is not None:
        data = json.dumps(json_body).encode()
        headers["Content-Type"] = "application/json"
    elif form is not None:
        data = urllib.parse.urlencode(form).encode()
        headers["Content-Type"] = "application/x-www-form-urlencoded"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.read()
    except urllib.error.HTTPError as e:
        raise HttpError(e.code, e.read().decode(errors="replace")) from None


def api(method, path, token, json_body=None):
    raw = http(method, API_BASE + path, token=token, json_body=json_body)
    return json.loads(raw) if raw else {}


# ── Auth ─────────────────────────────────────────────────────────────────────

def save_tokens(tok):
    os.makedirs(CONFIG_DIR, exist_ok=True)
    fd = os.open(TOKEN_FILE, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "w") as f:
        json.dump(tok, f)


def jwt_exp(token):
    try:
        payload = token.split(".")[1]
        payload += "=" * (-len(payload) % 4)
        return json.loads(base64.urlsafe_b64decode(payload)).get("exp", 0)
    except Exception:
        return 0


def wait_for_code(auth_url, state):
    """Catch the redirect on a loopback server, or accept a pasted URL.

    The loopback server works when the browser runs on this machine (desktop,
    or Termux with the phone's browser). On a headless box the browser will
    show a connection error after sign-in; copy the address bar and paste it.
    """
    result = {}
    done = threading.Event()

    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self):
            q = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            if "code" in q or "error" in q:
                result.update({k: v[0] for k, v in q.items()})
                done.set()
            self.send_response(200)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.end_headers()
            self.wfile.write(b"Signed in. You can close this tab.")

        def log_message(self, *a):
            pass

    server = None
    try:
        server = http.server.HTTPServer(("127.0.0.1", REDIRECT_PORT), Handler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
    except OSError as e:
        print(f"(Couldn't listen on port {REDIRECT_PORT}: {e}; paste mode only)")

    print("\nOpen this URL in a browser and sign in to Yoto:\n")
    print(auth_url)
    print("\nWaiting for the redirect. If the browser is on another machine, it")
    print("will show an error page after sign-in: paste that page's full URL here.\n> ",
          end="", flush=True)

    try:
        while not done.is_set():
            ready, _, _ = select.select([sys.stdin], [], [], 0.5)
            if ready:
                line = sys.stdin.readline()
                if not line:  # EOF
                    sys.exit("Sign-in aborted: no input.")
                line = line.strip()
                if not line:
                    continue
                q = urllib.parse.parse_qs(urllib.parse.urlparse(line).query)
                if "code" in q or "error" in q:
                    result.update({k: v[0] for k, v in q.items()})
                    break
                print("That URL has no ?code=… in it. Try again.\n> ", end="", flush=True)
    finally:
        if server:
            server.shutdown()

    if "error" in result:
        sys.exit(f"Sign-in failed: {result['error']}: {result.get('error_description', '')}")
    if result.get("state") != state:
        sys.exit("Sign-in failed: state mismatch (stale or foreign redirect URL).")
    return result["code"]


PENDING_FILE = os.path.join(CONFIG_DIR, "pending_login.json")


def start_login(client_id):
    """Make a PKCE sign-in URL. Returns (auth_url, verifier, state)."""
    verifier = secrets.token_urlsafe(64)
    challenge = base64.urlsafe_b64encode(
        hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    state = secrets.token_urlsafe(16)
    auth_url = AUTHORIZE_URL + "?" + urllib.parse.urlencode({
        "audience": API_BASE,
        "scope": SCOPES,
        "response_type": "code",
        "client_id": client_id,
        "code_challenge": challenge,
        "code_challenge_method": "S256",
        "redirect_uri": REDIRECT_URI,
        "state": state,
    })
    return auth_url, verifier, state


def code_from_url(url, state):
    q = {k: v[0] for k, v in urllib.parse.parse_qs(urllib.parse.urlparse(url).query).items()}
    if "error" in q:
        sys.exit(f"Sign-in failed: {q['error']}: {q.get('error_description', '')}")
    if "code" not in q:
        sys.exit("That URL has no ?code=... in it.")
    if q.get("state") != state:
        sys.exit("Sign-in failed: state mismatch (stale or foreign redirect URL).")
    return q["code"]


def finish_login(client_id, code, verifier):
    tok = json.loads(http("POST", TOKEN_URL, form={
        "grant_type": "authorization_code",
        "client_id": client_id,
        "code_verifier": verifier,
        "code": code,
        "redirect_uri": REDIRECT_URI,
    }))
    tok["client_id"] = client_id
    save_tokens(tok)
    print(f"Signed in. Tokens saved to {TOKEN_FILE}")
    return tok["access_token"]


def login(client_id):
    if not sys.stdin.isatty():
        sys.exit("Yoto sign-in needed, but there's no terminal to do it in. "
                 "Run `podcast_to_card.py login` interactively (or login --start/--finish).")
    auth_url, verifier, state = start_login(client_id)
    code = wait_for_code(auth_url, state)
    return finish_login(client_id, code, verifier)


def access_token(client_id):
    if not os.path.exists(TOKEN_FILE):
        return login(client_id)
    with open(TOKEN_FILE) as f:
        tok = json.load(f)
    if jwt_exp(tok.get("access_token", "")) > time.time() + 60:
        return tok["access_token"]
    if not tok.get("refresh_token"):
        return login(client_id)
    try:
        new = json.loads(http("POST", TOKEN_URL, form={
            "grant_type": "refresh_token",
            "client_id": tok.get("client_id", client_id),
            "refresh_token": tok["refresh_token"],
        }))
    except HttpError as e:
        print(f"Token refresh failed ({e.status}); signing in again.")
        return login(client_id)
    # Refresh tokens are single-use: always store the new one.
    tok.update(new)
    save_tokens(tok)
    return tok["access_token"]


# ── Feed ─────────────────────────────────────────────────────────────────────

# Episode titles in some feeds are wrapped in emoji ("🌊🐚 The Great Sea Race 🐚🌊").
EDGE_JUNK = re.compile(r"^[^\w\"'(]+|[^\w\"'!?.)]+$", re.UNICODE)


def first_emoji(title):
    """The first emoji of a title, with any variation selector, skin tone and
    ZWJ-joined parts (so 🕵️‍♂️ and 🏴‍☠️ stay whole). None if it doesn't start with one."""
    s = title.strip()
    if not s or ord(s[0]) < 0x2000:
        return None
    out, i = s[0], 1
    while i < len(s):
        c = s[i]
        if c == "\ufe0f" or 0x1F3FB <= ord(c) <= 0x1F3FF:
            out += c
            i += 1
        elif c == "\u200d" and i + 1 < len(s):
            out += c + s[i + 1]
            i += 2
        else:
            break
    return out


def clean_title(t):
    return EDGE_JUNK.sub("", t).strip() or t.strip()


def parse_duration(s):
    secs = 0
    for part in (s or "0").strip().split(":"):
        secs = secs * 60 + int(float(part or 0))
    return secs


def read_feed(url, include_types):
    root = ET.fromstring(http("GET", url, timeout=60))
    channel = root.find("channel")
    show = {
        "title": (channel.findtext("title") or "Podcast").strip(),
        "description": re.sub(r"<[^>]+>", "", channel.findtext("description") or "").strip(),
        "author": (channel.findtext("itunes:author", namespaces=NS) or "").strip(),
        "image": None,
    }
    img = channel.find("itunes:image", NS)
    if img is not None:
        show["image"] = img.get("href")
    elif channel.find("image/url") is not None:
        show["image"] = channel.find("image/url").text

    episodes = []
    for item in channel.findall("item"):
        enc = item.find("enclosure")
        if enc is None or not enc.get("url"):
            continue
        ep_type = (item.findtext("itunes:episodeType", namespaces=NS) or "full").strip().lower()
        if ep_type not in include_types:
            continue
        mime = (enc.get("type") or "").lower()
        fmt = "aac" if ("aac" in mime or "mp4" in mime or "m4a" in mime) else "mp3"
        pub = item.findtext("pubDate")
        num = item.findtext("itunes:episode", namespaces=NS)
        episodes.append({
            "title": clean_title(item.findtext("title") or "Episode"),
            "emoji": first_emoji(item.findtext("title") or ""),
            "url": enc.get("url"),
            "format": fmt,
            "size": int(enc.get("length") or 0),
            "duration": parse_duration(item.findtext("itunes:duration", namespaces=NS)),
            "episode": int(num) if num and num.strip().isdigit() else None,
            "published": email.utils.parsedate_to_datetime(pub).timestamp() if pub else 0,
        })
    # Oldest first, so episode 1 is track 1 and new episodes land at the end.
    episodes.sort(key=lambda e: (e["published"], e["episode"] or 0))
    return show, episodes


# ── Playlist ─────────────────────────────────────────────────────────────────

def build_content(show, episodes, *, title, existing=None, cover_url=None, icon=None):
    def display(ep):
        i = ep.get("icon") or icon
        return {"icon16x16": i} if i else {}

    chapters = []
    for i, ep in enumerate(episodes, 1):
        key = f"{i:02d}"
        label = str(i)
        chapters.append({
            "key": key,
            "title": ep["title"],
            "overlayLabel": label,
            "duration": ep["duration"],
            "fileSize": ep["size"],
            "display": display(ep),
            "tracks": [{
                "key": "01",
                "title": ep["title"],
                "overlayLabel": label,
                "type": "stream",
                "format": ep["format"],
                "trackUrl": ep["url"],
                "duration": ep["duration"],
                "fileSize": ep["size"],
                "display": display(ep),
            }],
        })

    metadata = dict((existing or {}).get("metadata") or {})
    metadata.pop("media", None)  # stale totals from the old content
    # Yoto's own RSS-podcast cards carry feedUrl + category "podcast" (and
    # content.editSettings, which we never copy). Yoto re-checks those cards
    # and rewrites them to the newest 25, so strip every podcast marker.
    metadata.pop("feedUrl", None)
    if metadata.get("category") == "podcast":
        metadata["category"] = "stories"
    metadata["description"] = metadata.get("description") or show["description"][:1000]
    if show["author"] and not metadata.get("author"):
        metadata["author"] = show["author"]
    if cover_url:
        metadata["cover"] = {"imageL": cover_url}
    metadata["media"] = {
        "duration": sum(e["duration"] for e in episodes),
        "fileSize": sum(e["size"] for e in episodes),
    }

    body = {"title": title, "content": {"chapters": chapters}, "metadata": metadata}
    old_config = ((existing or {}).get("content") or {}).get("config")
    if old_config:
        body["content"]["config"] = old_config
    if existing and existing.get("cardId"):
        body["cardId"] = existing["cardId"]
    return body


def first_icon(card):
    for ch in ((card or {}).get("content") or {}).get("chapters") or []:
        icon = (ch.get("display") or {}).get("icon16x16")
        if icon:
            return icon
    return None


def upload_cover(token, image_url):
    url = (f"{API_BASE}/media/coverImage/user/me/upload?autoconvert=true"
           f"&coverType=default&imageUrl={urllib.parse.quote(image_url, safe='')}")
    raw = http("POST", url, token=token)
    return json.loads(raw)["coverImage"]["mediaUrl"]


ICON_CACHE = os.path.join(CONFIG_DIR, "icons.json")
TWEMOJI_URL = "https://cdn.jsdelivr.net/gh/jdecked/twemoji@latest/assets/72x72/{}.png"


def twemoji_name(emoji):
    cps = [f"{ord(c):x}" for c in emoji]
    if "200d" not in cps:  # Twemoji drops FE0F except in ZWJ sequences
        cps = [c for c in cps if c != "fe0f"]
    return "-".join(cps)


def to_16x16(png):
    """Shrink locally if Pillow is around (sharper); else let Yoto autoconvert."""
    try:
        import io
        from PIL import Image
    except ImportError:
        return png, True
    im = Image.open(io.BytesIO(png)).convert("RGBA").resize((16, 16), Image.LANCZOS)
    buf = io.BytesIO()
    im.save(buf, "PNG")
    return buf.getvalue(), False


def emoji_icons(token, episodes):
    """Set ep["icon"] to a yoto:# icon made from each episode's first emoji.

    Each distinct emoji is uploaded once and remembered in icons.json.
    Twemoji graphics are CC-BY 4.0 (Twitter, Inc. and contributors).
    """
    cache = {}
    if os.path.exists(ICON_CACHE):
        with open(ICON_CACHE) as f:
            cache = json.load(f)
    for ep in episodes:
        if not ep.get("emoji"):
            continue
        name = twemoji_name(ep["emoji"])
        if name not in cache:
            try:
                png, auto = to_16x16(http("GET", TWEMOJI_URL.format(name)))
                url = (f"{API_BASE}/media/displayIcons/user/me/upload"
                       f"?autoConvert={'true' if auto else 'false'}&filename=emoji-{name}")
                req = urllib.request.Request(url, data=png, method="POST", headers={
                    "Authorization": f"Bearer {token}", "Content-Type": "image/png",
                    "User-Agent": USER_AGENT})
                with urllib.request.urlopen(req, timeout=60) as resp:
                    media_id = json.loads(resp.read())["displayIcon"]["mediaId"]
                cache[name] = f"yoto:#{media_id}"
                print(f"Uploaded icon {ep['emoji']} ({name})")
            except Exception as e:  # icons are nice-to-have
                print(f"No icon for {ep['emoji']} ({name}): {e}")
                continue
            os.makedirs(CONFIG_DIR, exist_ok=True)
            with open(ICON_CACHE, "w") as f:
                json.dump(cache, f, indent=1)
        ep["icon"] = cache[name]


def playlist_signature(card):
    """What a listener would notice: title plus each chapter's title, stream and icon."""
    sig = [card.get("title")]
    for ch in ((card or {}).get("content") or {}).get("chapters") or []:
        tr = (ch.get("tracks") or [{}])[0]
        sig.append((ch.get("title"), tr.get("trackUrl"), (ch.get("display") or {}).get("icon16x16")))
    return sig


# ── Commands ─────────────────────────────────────────────────────────────────

def cmd_login(args):
    if args.start:
        # Two-step sign-in for when this script can't be typed at directly.
        auth_url, verifier, state = start_login(args.client_id)
        os.makedirs(CONFIG_DIR, exist_ok=True)
        fd = os.open(PENDING_FILE, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "w") as f:
            json.dump({"client_id": args.client_id, "verifier": verifier, "state": state}, f)
        print(auth_url)
    elif args.finish:
        with open(PENDING_FILE) as f:
            pending = json.load(f)
        code = code_from_url(args.finish, pending["state"])
        finish_login(pending["client_id"], code, pending["verifier"])
        os.remove(PENDING_FILE)
    else:
        login(args.client_id)


def cmd_list(args):
    token = access_token(args.client_id)
    cards = api("GET", "/content/mine", token).get("cards", [])
    if not cards:
        print("No Make Your Own content found.")
    for c in cards:
        print(f"{c.get('cardId', '?'):8} {c.get('title', '')}")


def cmd_sync(args):
    include = {"full"}
    if args.include_bonus:
        include.add("bonus")
    if args.include_trailer:
        include.add("trailer")

    show, episodes = read_feed(args.feed_url, include)
    if not episodes:
        sys.exit("No playable episodes found in the feed.")
    if len(episodes) > MAX_TRACKS:
        print(f"Feed has {len(episodes)} episodes; a card holds {MAX_TRACKS}. "
              f"Keeping the newest {MAX_TRACKS}.")
        episodes = episodes[-MAX_TRACKS:]
    hours = sum(e["duration"] for e in episodes) / 3600
    print(f"{show['title']}: {len(episodes)} episodes, {hours:.1f} h")

    if args.dry_run:
        body = build_content(show, episodes, title=args.title or show["title"],
                             cover_url=show["image"])
        if args.card_id:
            body["cardId"] = args.card_id
        print(json.dumps(body, indent=2, ensure_ascii=False))
        return

    token = access_token(args.client_id)

    existing = None
    if args.card_id:
        existing = api("GET", f"/content/{args.card_id}", token).get("card")
        if not existing:
            sys.exit(f"Card {args.card_id} not found in your Make Your Own content.")

    cover = ((existing or {}).get("metadata") or {}).get("cover", {}).get("imageL")
    if not cover and show["image"] and not args.no_cover:
        try:
            cover = upload_cover(token, show["image"])
        except Exception as e:  # cover art is nice-to-have
            print(f"Cover upload failed, continuing without it: {e}")

    if not args.no_emoji_icons:
        emoji_icons(token, episodes)

    title = args.title or (existing or {}).get("title") or show["title"]
    body = build_content(show, episodes, title=title, existing=existing,
                         cover_url=cover, icon=args.icon or first_icon(existing))

    if existing:
        if playlist_signature(body) == playlist_signature(existing) and not args.force:
            print("Card is already up to date; nothing to do.")
            return
        os.makedirs(BACKUP_DIR, exist_ok=True)
        backup = os.path.join(BACKUP_DIR, f"{args.card_id}-{time.strftime('%Y%m%d-%H%M%S')}.json")
        with open(backup, "w") as f:
            json.dump(existing, f, indent=2, ensure_ascii=False)
        old_n = len((existing.get("content") or {}).get("chapters") or [])
        print(f"Overwriting \"{existing.get('title')}\" ({old_n} chapters). Backup: {backup}")

    result = api("POST", "/content", token, body)
    card = result.get("card", result)
    print(f"Saved \"{card.get('title', title)}\" as card {card.get('cardId', '?')} "
          f"with {len(episodes)} episodes.")
    if not args.card_id:
        print("Link it to a physical card in the Yoto app (Library > the playlist > Link card).")
        print(f"Next time, re-run with --card-id {card.get('cardId', '<id>')} to add new episodes.")


def main():
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    p.add_argument("--client-id", default=os.environ.get("YOTO_CLIENT_ID", DEFAULT_CLIENT_ID))
    sub = p.add_subparsers(dest="cmd", required=True)

    lg = sub.add_parser("login", help="sign in and store tokens")
    lg.add_argument("--start", action="store_true",
                    help="print a sign-in URL and exit (two-step sign-in)")
    lg.add_argument("--finish", metavar="REDIRECT_URL",
                    help="finish a --start sign-in with the URL the browser landed on")
    lg.set_defaults(fn=cmd_login)
    sub.add_parser("list", help="list your MYO cards").set_defaults(fn=cmd_list)

    s = sub.add_parser("sync", help="write a feed to a card")
    s.add_argument("feed_url")
    s.add_argument("--card-id", help="overwrite this existing MYO card (5 chars)")
    s.add_argument("--title", help="playlist title (default: existing card's, else the show's)")
    s.add_argument("--icon", help="16x16 icon for every track, e.g. yoto:#<sha256>")
    s.add_argument("--include-bonus", action="store_true", help="include bonus episodes")
    s.add_argument("--include-trailer", action="store_true", help="include trailers")
    s.add_argument("--no-cover", action="store_true", help="don't upload the show artwork")
    s.add_argument("--no-emoji-icons", action="store_true",
                   help="don't make track icons from each title's first emoji")
    s.add_argument("--force", action="store_true", help="write even if nothing changed")
    s.add_argument("--dry-run", action="store_true", help="print the JSON, don't sign in or save")
    s.set_defaults(fn=cmd_sync)

    args = p.parse_args()
    args.fn(args)


if __name__ == "__main__":
    main()
