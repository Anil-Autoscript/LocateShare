#!/usr/bin/env python3
"""
Workflow 1 logic.

Polls Telegram's getUpdates for new messages since the last seen update_id,
and:
  - "Get Location" / "/getlocation"  -> writes backend/request.json
        { "request": true, "requested_by": "<chat_id>" }
    The phone app picks this up, fetches GPS, and replies to the user
    directly via the Telegram Bot API.
  - "Status" / "/status"  -> replies immediately using backend/response.json
    (no need to wake the phone for a simple status check).
  - "Help" / "/help"      -> replies immediately with command list.

State kept between runs (committed back to the repo):
  - backend/last_update_id.txt  -> last processed Telegram update_id
  - backend/request.json        -> pending request flag
"""
import json
import os
import sys
import urllib.request
import urllib.parse

BOT_TOKEN = os.environ["TELEGRAM_BOT_TOKEN"].strip()

if not BOT_TOKEN:
    print("ERROR: TELEGRAM_BOT_TOKEN is empty. Check the secret is set in "
          "Settings > Secrets and variables > Actions (exact name: TELEGRAM_BOT_TOKEN).",
          file=sys.stderr)
    sys.exit(1)

if BOT_TOKEN.lower().startswith("bot"):
    print("ERROR: TELEGRAM_BOT_TOKEN should NOT include the word 'bot' at the "
          "start (the API path already adds it). Remove the 'bot' prefix from "
          "the secret value.", file=sys.stderr)
    sys.exit(1)

if ":" not in BOT_TOKEN or not BOT_TOKEN.split(":")[0].isdigit():
    print(f"WARNING: TELEGRAM_BOT_TOKEN doesn't look like a valid token "
          f"(length={len(BOT_TOKEN)}, contains_colon={':' in BOT_TOKEN}). "
          f"A real token looks like 123456789:AAExxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx. "
          f"Re-copy it from @BotFather.", file=sys.stderr)

print(f"Using token with length={len(BOT_TOKEN)}, format looks like "
      f"{'valid' if ':' in BOT_TOKEN else 'INVALID'}", file=sys.stderr)

API = f"https://api.telegram.org/bot{BOT_TOKEN}"

REQUEST_FILE = "backend/request.json"
RESPONSE_FILE = "backend/response.json"
LAST_UPDATE_FILE = "backend/last_update_id.txt"


def api_get(method, params=None):
    url = f"{API}/{method}"
    if params:
        url += "?" + urllib.parse.urlencode(params)
    try:
        with urllib.request.urlopen(url, timeout=20) as resp:
            return json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        body = e.read().decode(errors="replace")
        print(f"Telegram API error calling {method}: HTTP {e.code} - {body}", file=sys.stderr)
        print(
            "This usually means TELEGRAM_BOT_TOKEN is missing/incorrect. "
            "Check Settings > Secrets and variables > Actions, and make sure "
            "the value has no quotes or extra whitespace.",
            file=sys.stderr,
        )
        sys.exit(1)


def api_post(method, payload):
    url = f"{API}/{method}"
    data = json.dumps(payload).encode()
    req = urllib.request.Request(
        url, data=data, headers={"Content-Type": "application/json"}
    )
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            return json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        body = e.read().decode(errors="replace")
        print(f"Telegram API error calling {method}: HTTP {e.code} - {body}", file=sys.stderr)
        raise


def send_message(chat_id, text):
    try:
        api_post("sendMessage", {"chat_id": chat_id, "text": text})
    except Exception as e:
        print(f"Failed to send message to {chat_id}: {e}", file=sys.stderr)


def load_json(path, default):
    if not os.path.exists(path):
        return default
    with open(path) as f:
        try:
            return json.load(f)
        except json.JSONDecodeError:
            return default


def save_json(path, data):
    with open(path, "w") as f:
        json.dump(data, f, indent=2)
        f.write("\n")


def main():
    last_update_id = 0
    if os.path.exists(LAST_UPDATE_FILE):
        with open(LAST_UPDATE_FILE) as f:
            content = f.read().strip()
            last_update_id = int(content) if content else 0

    updates = api_get("getUpdates", {"offset": last_update_id + 1, "timeout": 0})
    if not updates.get("ok"):
        print("getUpdates failed:", updates, file=sys.stderr)
        return

    request_state = load_json(REQUEST_FILE, {"request": False, "requested_by": ""})
    response_state = load_json(
        RESPONSE_FILE,
        {"latitude": "", "longitude": "", "address": "", "timestamp": "",
         "sharing_enabled": False},
    )

    max_update_id = last_update_id
    changed_request = False

    for update in updates["result"]:
        max_update_id = max(max_update_id, update["update_id"])
        message = update.get("message")
        if not message or "text" not in message:
            continue

        chat_id = message["chat"]["id"]
        text = message["text"].strip().lower()

        if text in ("get location", "/getlocation", "/get_location", "get_location"):
            request_state = {"request": True, "requested_by": str(chat_id)}
            changed_request = True
            send_message(
                chat_id,
                "📡 Requesting current location from the device... "
                "this can take a few minutes. You'll get a reply automatically.",
            )

        elif text in ("status", "/status"):
            if response_state.get("sharing_enabled"):
                status_line = "🟢 Sharing is ON"
            else:
                status_line = "⚪ Sharing is OFF"
            last_seen = response_state.get("timestamp") or "Never"
            send_message(
                chat_id,
                f"{status_line}\nLast known location: "
                f"{response_state.get('address') or 'N/A'}\n"
                f"Last update: {last_seen}",
            )

        elif text in ("help", "/help", "/start"):
            send_message(
                chat_id,
                "📍 Location Share Bot\n\n"
                "Commands:\n"
                "• Get Location – request the current location\n"
                "• Status – check if sharing is ON and the last known fix\n"
                "• Help – show this message",
            )

    with open(LAST_UPDATE_FILE, "w") as f:
        f.write(str(max_update_id))

    if changed_request:
        save_json(REQUEST_FILE, request_state)

    print("Processed updates up to", max_update_id)


if __name__ == "__main__":
    main()
