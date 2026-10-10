# Suprah Driver Tracker (Android): behaviour specification

What the app must do. Written from the working prototype (`suprah-driver-tracker-android-prototype`, tested on a Xiaomi Redmi Note 12 5G, October 2026) so the rebuilt app keeps every behaviour. Items marked **NEW** close gaps found while testing the prototype.

Each item has an ID. Tests and pull requests should reference the IDs they cover.

**Wording rule (2026-10-07).** Every message a driver sees says, in plain English, what is happening, whether they have to wait (and roughly how long), and whether they need to do anything ("You don't need to do anything" when nothing is needed). No internal terms such as batches, requests or servers. Exact texts live in `app/src/main/res/values/strings.xml`; the wording below is what they mean.

**Delivery order.** Step 1 rebuilds the prototype's behaviour with a production structure (everything here except items marked **NEW**, plus the dev/prod builds in B-2). Step 2 adds the **NEW** items once step 1 passes section 8 on a real phone.

---

## 1. Scope

- **S-1** The app only shares the driver's location. Everything else (loads, chat, documents) stays in the Driver Portal.
- **S-2** The server side is unchanged: same endpoints, pairing code, device key and data format (section 3). No backend or website change is needed for this app.
- **S-3** Location is only collected while the driver is on duty **and** approved **and** has an accepted load (Accepted, Picked Up or In-Transit). That's a business rule, not a technical one.

## 2. Identity and builds

- **B-1** Application ID `com.suprah.drivertracker`; debug builds add `.debug`. minSdk 26, target/compile SDK 35.
- **B-2** Two flavors: **dev** and **prod**.
  - prod: server fixed to `https://api.suprah-app.com`, https only, no server address field.
  - dev: server address editable on the pairing screen, http allowed (for a backend on the developer's PC, e.g. `http://localhost:5000` with `adb reverse`).
- **B-3** Driver Portal link: `https://suprah-app.com/driver`.
- **B-4** Notification channels keep their IDs: `tracking` (low importance, "Shows while you're on duty.") and `alerts` (high importance, "When tracking needs your attention, for example a missing permission.").
- **B-5** Signing keys and their passwords never go into git.

## 3. Server contract (unchanged)

All calls go to `<server>/api/tracking-app/...`. After pairing, every call carries `Authorization: Device <deviceId>:<deviceKey>`.

| Call | Request | Success |
|---|---|---|
| `POST /pair` | `{ code, deviceName, platform: "android", appVersion }` (no sign-in) | 201, `data: { deviceId, deviceKey, ...status }` |
| `GET /status` | none | 200, `data: status` |
| `POST /positions` | `{ positions: [ { lat, lng, measuredAt (ISO 8601), accuracy?, speed? (m/s), heading? (degrees) } ] }`, at most 100 | 200, `data: { received, accepted, refused: { reason: count }, ...status }` |
| `DELETE /device` | none | 200 (unlinks this phone) |

**Status fields:** `status` (`approved` / `waiting_for_approval`), `driverName`, `shouldTrack`, `activeLoadCount`, `uploadIntervalSeconds` (10–300, default 30), `locationIntervalSeconds` (5–120, default 15), `lastPositionAt`.

**Errors:** body `{ success: false, code, reason, message }`. The app acts on `reason` and shows `message` to the driver.

| reason | HTTP | Meaning |
|---|---|---|
| `tracking_app_off` | 503 | The company hasn't switched phone tracking on |
| `device_unlinked` | 401 | This phone was unlinked (by the driver, staff or a newer pairing) |
| `device_unknown` | 401 | Unknown device |
| `driver_inactive` | 403 | The driver account isn't active |
| `waiting_for_approval` | 403 | Positions refused until a dispatcher/admin approves the phone |

- **C-1** Pairing is rate limited to 10 attempts per 15 minutes per network. A code with the wrong format gets 400; a well-formed but unknown or expired code gets 404. Codes last 15 minutes.
- **C-2** The server accepts readings up to 24 hours old, stores the same moment only once, and lets the newest measurement win for the live position. Sending a reading twice is harmless.
- **C-3** **NEW, additive:** each position may carry `mock: true|false`. The current server ignores unknown fields, so this is safe to send before the backend uses it.

## 4. Pairing screen

- **P-1** Shown whenever the phone isn't paired.
- **P-2** Intro: the app shares the driver's location with Dispatch while they have an accepted load, even with the screen locked; keep using the Driver Portal for everything else. Instructions: Driver Portal → Settings → Phone tracking → Set up phone tracking, then enter the 8-character code.
- **P-3** Code field: letters and digits only, upper-cased, at most 8 characters; anything else is ignored. **Pair** is enabled only with exactly 8 characters; the keyboard's Done key also pairs.
- **P-4** dev only: the server address can be changed. It must start with `https://` (or `http://` in dev); show the full address including the scheme. **NEW:** the prototype hid the scheme, which led to `https://localhost` by mistake.
- **P-5** Sends device name (`<manufacturer> <model>`, at most 80 characters), platform `android` and the app version.
- **P-6** On success: save the device ID; save the device key **encrypted with a key held in the Android Keystore** (AES-GCM; never stored in plain text); save the driver name and approval; open the home screen.
- **P-7** On refusal: show the server's message. With no connection: "Couldn't reach Suprah. Check your internet connection and try again."

## 5. Home screen

- **H-1** Header "SUPRAH AI · DRIVER TRACKER" and the driver's name ("Driver" if unknown).
- **H-2** Status card, first match wins:
  - not approved → "Waiting for approval": a dispatcher or admin needs to approve this phone before your location is used;
  - off duty → "Off duty": tap Start on duty at the start of your day; location is only shared while you have an accepted load;
  - GPS on → "Sharing your location": Dispatch can follow your trip; you can lock your screen or use other apps;
  - otherwise → "On duty · no active load": location is paused and starts by itself when you accept a load.
  - When approved, also "No accepted load right now." or "N accepted load(s).". A current problem shows underneath in red.
- **H-3** Duty button: "Start on duty" / "Go off duty". Start is enabled only when approved, precise location is allowed and location services are on; otherwise a hint explains what's missing. Going off duty is always possible.
- **H-4** Info rows: Last location sent (time), Last GPS reading (time and ±accuracy in metres), Saved, waiting to send (count). Under the count, a plain note when it helps: on duty with 20 or more saved and no other message showing, "Sending N saved locations from earlier. Your current location is shared first, so Dispatch still sees where you are. This can take a few minutes; you don't need to do anything."; off duty with saved readings, "N saved locations will be sent the next time you go on duty."
- **H-5** While the screen is open it checks status: every 5 s while waiting for approval (changed from the prototype's 15 s, 2026-10-06), otherwise every 60 s. `device_unlinked` / `device_unknown` → forget the pairing and return to the pairing screen. No connection → "No internet connection right now. Your locations are still being saved on this phone and will be sent when you're back online."
- **H-6** **Resume on open:** if the driver is on duty and paired with location allowed but tracking isn't running (app updated, or the phone closed it), opening the app restarts tracking. The button must never show a stale state.
- **H-7** Phone settings checklist (green dot = done, red = fix, grey = unknown):

  | Row | Fix action |
  |---|---|
  | Location services on | Open location settings |
  | Precise location allowed | Request fine + coarse location |
  | Location allowed all the time (enabled after precise) | Request background location; if refused, open the app's settings |
  | Notifications allowed (Android 13+) | Request notification permission |
  | No battery restrictions | Ask to ignore battery optimisation; fall back to the battery optimisation list |
  | Autostart on (Xiaomi, Redmi, POCO only) | Open Xiaomi's Autostart screen; fall back to the app's settings |

  Xiaomi Autostart is read from app-op 10008: allowed → Done, ignored or errored → Fix, unreadable → grey "Check". When not all done: "Each one helps Android keep tracking running while your screen is locked." On Xiaomi with Autostart not confirmed: "Xiaomi phones need Autostart on so tracking comes back by itself after a restart or an app update."
- **H-8** "Open the Driver Portal" opens the portal link in the browser.
- **H-9** "Unlink this phone" asks first: "Dispatch stops receiving locations from this phone right away. You can pair it again later with a new code from the Driver Portal." On confirm: go off duty, tell the server (best effort), delete saved locations, forget the pairing, return to the pairing screen.
  - **NEW:** if there's an accepted load, the dialog warns that Dispatch will stop seeing the trip.
  - **NEW:** saved locations are sent before unlinking when there's a connection.
  - **NEW:** with no connection, the unlink is remembered and sent later, so the server doesn't keep showing the phone as linked.

## 6. Tracking (on duty)

**Starting and staying alive**
- **T-1** Start on duty saves "on duty" (survives restarts) and starts a foreground service of type `location` with an ongoing notification. Tapping the notification opens the app; its **Go off duty** action goes off duty.
- **T-2** When starting: not on duty or not paired → stop. Precise location not allowed → alert "Location permission needed: Open Suprah Tracker and allow precise location so Dispatch can follow your trip." and switch off duty. Android refuses the foreground service → alert "Tap to resume tracking: Open Suprah Tracker to continue sharing your location with Dispatch."
- **T-3** Restarts by itself after a phone restart and after an app update when on duty and paired. Android may restart the service after killing it.
- **T-4** **NEW:** after a restart with no signal, GPS starts from the last known status (approved with an accepted load) instead of waiting until the server answers.

**GPS**
- **T-5** Checks status every 60 s. GPS is on only when on duty **and** approved **and** `shouldTrack`; otherwise off.
- **T-6** GPS request: high accuracy, interval = `locationIntervalSeconds`, fastest = half of that, no waiting for an accurate fix, no delayed batching.
- **T-7** Each reading keeps latitude, longitude, the fix time, and accuracy, speed and heading when the phone provides them. **NEW:** also whether it's a mock (fake-GPS) location (C-3).

**Saving and sending**
- **T-8** Every reading is saved on the phone first. Saved readings survive no signal, the app being closed and a phone restart. At most about 5,000 are kept (about 20 hours at one every 15 s); beyond that the oldest go first.
- **T-9** **Instant:** each new reading is sent as soon as it's measured, not on a timer. The only exception is right after the server didn't answer: then new readings wait for the regular retry (every `uploadIntervalSeconds`).
- **T-10** **Oldest first, newest ahead:** saved readings go oldest first in batches of up to 100. While there's a backlog, the newest reading is sent on its own first, so Dispatch sees where the driver is now; older readings then fill in the trip history.
- **T-11** **Slow server:** an upload waits up to 60 s for an answer (other calls: 15 s to connect, 20 s for an answer). After a timeout the batch size halves (minimum 10); after each success it grows by 10 (maximum 100). The same batch is never retried forever.
- **T-12** Readings older than 23 hours are dropped before sending (the server refuses them after 24).
- **T-13** Server answers while sending:
  - `waiting_for_approval` → delete saved readings (the server won't use them);
  - HTTP 400 → drop that batch, so a malformed batch can't block the queue;
  - `device_unlinked` / `device_unknown` → GPS off, delete saved readings, forget the pairing, alert "Phone unlinked: This phone is no longer linked to your Suprah account. Open the app to pair it again.", stop;
  - `tracking_app_off` → GPS off, "Phone tracking is switched off by your company right now.";
  - anything else → show the server's message.
- **T-14** Messages: no connection, "No internet connection. N locations are saved on this phone and will be sent automatically when you're back online. You don't need to do anything."; slow connection, "The connection is slow, so N saved locations are taking longer to send. Your current location is still shared first. This can take a few minutes; you don't need to do anything."
- **T-15** **NEW:** saved readings left over after going off duty or after the app is closed are sent in the background once there's a connection (WorkManager with a network constraint and backoff). Live sending stays in the service, because background jobs can be delayed by minutes.

**Notification text**
- **T-16** First match wins: a current problem ("On duty" + problem); waiting for approval ("On duty · waiting for approval" / "Dispatch needs to approve this phone. Tracking starts as soon as they do."); GPS on ("Sharing your location" / "Last sent HH:MM", or "Starting…"); otherwise ("On duty · no active load" / "Location is paused until you have an accepted load.").

**Going off duty**
- **T-17** Go off duty (button or notification): saves "off duty", turns GPS off **immediately**, sends what's saved, then stops. Server answers during that final send never turn GPS back on and don't update the notification.

## 7. Diagnostics (NEW)

- **D-1** A diagnostics screen shows: app version and build, server address (dev), paired yes/no, approval, on duty, service running, GPS on, last reading, last send, saved count, the last error, and each phone-settings row.
- **D-2** "Copy report" puts that text on the clipboard so the driver can paste it to Dispatch. It never includes the device key.

## 8. Acceptance checks on a real phone

Run on a real Android phone (Xiaomi included) before a release. Evidence: the backend log (`/api/tracking-app/*` requests) and Driver Tracker.

1. Pair with a code; waiting for approval; Start on duty unlocks by itself after approval (H-5).
2. On duty with an accepted load: a location reaches the server for every reading **with the screen locked** and with another app open (T-9).
3. Go off duty: GPS stops right away and the service ends (T-17).
4. No signal for 10+ minutes, then back: the newest location arrives first, then the backlog; nothing is lost (T-8, T-10).
5. Slow server: uploads still get through in smaller batches (T-11).
6. Update the app while on duty: tracking restarts by itself (T-3, with Xiaomi Autostart on).
7. Restart the phone while on duty: tracking resumes; with no signal, GPS starts anyway (T-3, T-4).
8. Open the app after the phone closed it: tracking resumes (H-6).
9. Unlink from the app, and from the Driver Portal: tracking stops and the app returns to pairing (H-9, T-13).
10. Waiting for approval: no GPS, nothing sent (S-3, T-13).
11. Fake-GPS app: locations are flagged as mock (T-7).
12. Phone settings rows match reality, including Xiaomi Autostart (H-7).
