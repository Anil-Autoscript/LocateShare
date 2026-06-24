# 📍 Location Share

A minimal Android app + GitHub-Actions "backend" + Telegram Bot that lets
someone request your **current** location on demand — no continuous
tracking, no history, no database, no servers to maintain.

## How it works (high level)

```
User B (Telegram)  --"Get Location"-->  Telegram Bot
                                              │
                              GitHub Action (cron, every ~3 min)
                              polls Telegram getUpdates
                                              │
                              writes backend/request.json
                              { "request": true, "requested_by": <chat_id> }
                                              │
        Android app (WorkManager, polls every ~5 min)
        sees request:true  ─────────────────►│
            │
            ├─ Fused Location Provider → GPS fix
            ├─ Nominatim reverse-geocode → address
            ├─ Sends formatted message DIRECTLY to Telegram (Bot API)
            └─ Commits backend/request.json back to {"request": false}
                 and backend/response.json with the latest fix
```

There is **no server**. The GitHub repo's `backend/*.json` files act as the
only shared state ("mailbox"). The phone talks to Telegram directly (it has
internet anyway), and to GitHub only to read/clear the pending request flag
and to leave a copy of the last known reply (used by the "Last Shared
Location" field if you ever reinstall, and for the optional Workflow 2
below).

This satisfies the brief's two workflows:
* **Workflow 1** – `poll-telegram.yml`: polls Telegram, turns `/get`-type
  commands into a pending request in `backend/request.json`, and answers
  `Status` / `Help` directly (those don't need the phone at all).
* **Workflow 2** – `store-response.yml`: triggered via `repository_dispatch`
  from the phone after it answers a request; simply commits
  `backend/response.json` so the repo always has a copy of the latest known
  location (handy for debugging / the dashboard-free "Last Shared Location"
  field). The phone *also* messages Telegram directly so the reply is instant
  — this workflow is just bookkeeping, not on the critical path.

Nothing here uses Firebase, a database, or a hosted server. Everything lives
in this one GitHub repo.

---

## 1. Repository layout

```
LocationShare/
├── app/                          # Android app (Kotlin + Compose)
├── backend/
│   ├── request.json              # { "request": false, "requested_by": "" }
│   └── response.json             # latest known location (informational)
├── scripts/
│   └── poll_telegram.py          # used by Workflow 1
└── .github/workflows/
    ├── poll-telegram.yml         # Workflow 1
    └── store-response.yml        # Workflow 2
```

---

## 2. Create the Telegram Bot

1. Open Telegram, message **@BotFather**.
2. `/newbot` → give it a name and a username (must end in `bot`).
3. BotFather gives you a **token** like `123456:ABC-DEF...`. Save it.
4. Send your new bot any message once (e.g. `/start`) so it knows about
   your chat — needed before it can message you.
5. (Optional, recommended) Disable group privacy mode isn't needed since
   this bot is used 1:1.

You do **not** need to set a webhook — the GitHub Action *polls* Telegram
with `getUpdates`, which is simpler to host than a webhook server.

---

## 3. Create a GitHub Personal Access Token (PAT)

The phone app needs permission to edit `backend/request.json` /
`response.json` in your repo.

1. GitHub → Settings → Developer settings → **Personal access tokens** →
   Fine-grained tokens.
2. Scope it to **this one repository only**.
3. Permissions → **Contents: Read and write**. Nothing else.
4. Generate, copy the token (`github_pat_...`). You'll paste this once into
   the Android app on first launch (stored locally on-device only, never
   uploaded anywhere else).

---

## 4. Push this repo to GitHub

```bash
cd LocationShare
git init
git add .
git commit -m "Initial commit: Location Share"
git branch -M main
git remote add origin https://github.com/<your-username>/LocationShare.git
git push -u origin main
```

---

## 5. Configure GitHub Actions secrets

Repo → Settings → Secrets and variables → **Actions** → New repository
secret:

| Secret name | Value |
|---|---|
| `TELEGRAM_BOT_TOKEN` | token from BotFather |

That's the only secret the workflows need (they read/write files in the
same repo using the built-in `GITHUB_TOKEN`, which Actions provides
automatically — no extra setup).

The workflows are scheduled (`cron`) so they start running automatically
once pushed — nothing else to deploy.

---

## 6. Build & install the Android app

1. Open `app/` (or the whole repo root) in **Android Studio** (Hedgehog or
   newer recommended).
2. Let Gradle sync.
3. Run on a device/emulator with Google Play services (needed for the
   Fused Location Provider).
4. **First launch** asks for:
   * Your Name
   * Mobile Number
   * GitHub repo (`owner/repo`, pre-fill with yours)
   * GitHub PAT (from step 3)
   * Telegram Bot Token (from step 2)
   These are stored **locally only** via Jetpack DataStore — never sent
   anywhere except the two intended endpoints (GitHub Contents API for the
   request flag, Telegram Bot API to send the reply).
5. Grant location permission when prompted (foreground location is enough
   — there is no background tracking).
6. Toggle **Share My Location** to ON.

---

## 7. Try it end to end

1. From any phone, message your bot: **Get Location**.
2. Within ~3 minutes (Workflow 1's schedule), `backend/request.json` flips
   to `request: true`.
3. Within ~5 minutes (the app's background check interval), the app wakes,
   confirms sharing is ON, grabs one GPS fix, reverse-geocodes it via
   Nominatim, and replies on Telegram:

```
📍 Current Location
Name: Anil Patil
Address: Andheri East, Mumbai, Maharashtra
Latitude: 19.0760
Longitude: 72.8777
Google Maps: https://maps.google.com/?q=19.0760,72.8777
Time: 24-Jun-2026 03:45 PM
```

4. The request flag is cleared so the same request isn't answered twice.

Other commands:
* **Status** → bot replies immediately (no phone wake needed) with whether
  sharing is currently ON/OFF and when it was last seen, read straight from
  `backend/response.json`.
* **Help** → lists the 3 commands.

---

## 8. Notes on the "every few minutes" checks

* Workflow 1 cron: every 3 minutes (`*/3 * * * *`) — GitHub's actual
  minimum granularity in practice is closer to ~5 min depending on queue
  load; this is fine since the brief explicitly says "no continuous
  tracking."
* Android `WorkManager` periodic worker: every 15 minutes is the **OS
  minimum** for `PeriodicWorkRequest` on stock Android. The sample sets 15
  minutes; if you need it faster for testing, trigger `OneTimeWorkRequest`
  manually with the "TEST LOCATION" button, which bypasses the schedule and
  fetches+sends immediately.
* The phone **never** tracks location on a timer — it only ever asks for a
  location fix at the moment it sees `request: true`. The periodic worker
  is just checking a flag, not the GPS.

---

## 9. What's intentionally NOT here

* No database, no Firebase, no hosted server.
* No location history — only the single latest fix is ever stored
  (overwritten each time), and only as a convenience JSON file.
* No multi-screen UI — one screen, one toggle, two buttons.
* No background location tracking — `ACCESS_FINE_LOCATION` (foreground) is
  enough; a single fix is taken only at request time.

---

## 10. File map

| File | Purpose |
|---|---|
| `app/src/main/java/.../MainActivity.kt` | Single Compose screen |
| `app/src/main/java/.../data/PreferencesManager.kt` | DataStore: name, mobile, tokens, toggle |
| `app/src/main/java/.../network/GithubApi.kt` | Read/write `backend/*.json` via GitHub Contents API |
| `app/src/main/java/.../network/TelegramApi.kt` | Send Telegram message |
| `app/src/main/java/.../network/NominatimApi.kt` | Reverse geocode |
| `app/src/main/java/.../worker/LocationCheckWorker.kt` | Periodic check + on-demand fetch/send |
| `app/src/main/java/.../LocationShareApp.kt` | Application class, WorkManager scheduling |
| `.github/workflows/poll-telegram.yml` | Workflow 1 |
| `.github/workflows/store-response.yml` | Workflow 2 |
| `scripts/poll_telegram.py` | Logic for Workflow 1 |
| `backend/request.json`, `backend/response.json` | Shared state files |
