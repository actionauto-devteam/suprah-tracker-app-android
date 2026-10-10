# Development plan and status

Last updated: 11 October 2026. This is where the Suprah Driver Tracker work stands, what comes next, and how to pick it up again on a new PC.

## The plan right now

1. **Batch 9 is live (11 October 2026).** The Driver Portal and Driver Tracker onboarding fixes were merged into `main` in both projects and deployed. Still to do: one full driver test on the live site with test accounts (sign-up → documents → approval → accept → pickup → deliver → chat → expired-document warning), then delete the test accounts.
2. **Follow-up fixes are live (11 October 2026, from `fix/driver-document-renewal`):**
   - **Renewed documents:** approving a renewed CDL, medical card or insurance updates the date Dispatch sees and clears the "Expired" warning. The hourly job repairs older records. The driver's Documents page has a visible **Replace** button (plus "Add another file" for a CDL back or a trailer registration).
   - **Live refresh:** the payout review page and the Transportation load page update by themselves when the driver uploads a pickup or delivery photo, a status changes, the load is edited, or delivery is confirmed.
   - **Confirm Delivery** turns grey and reads "Delivery Confirmed" once confirmed.
   - **Dispatch Chat** no longer goes blank after being open for a while (it hid the messages when the sign-in renewed).
3. **Check on the live site:** a payout page confirming a delivery, Dispatch Chat open for about 20 minutes, and a renewed test driver's CDL showing its new date.
4. **iPhone redesign preview (`feature/mobile-redesign-preview`, website only).** The iPhone deck's phone design, built into the website and switched off by default:
   - Transportation, Driver Tracker, and the Driver Portal's Dashboard, My Loads, Available Loads and load pages.
   - It keeps Suprah's branded headers, green highlights, clickable number cards and maps.
   - It stays off `main` until it's approved. The plan and progress are in the workspace folder `suprah-ios-plan/`.
5. **The tracking app comes later.** Update `feature/driver-tracking-app` (server and website) from `main`, re-test the app with batch 9 included, then do the next fix round below.
6. **iPhone app is a separate track.** The team plans a Capacitor iPhone app. My part is Transportation and Driver Tracker (plus the small Driver Portal changes tracking needs). It needs a Mac, an iPhone and an Apple Developer account before real device work can start. The options without a Mac are rented Macs or a cloud build service, with a used Mac mini recommended for the tracking work. Traccar Client is a possible stopgap for iPhone drivers, but only if the senior agrees.

## Where the code is

| What | Where | State |
|---|---|---|
| Android tracking app | this repository, branch `feature/driver-tracker-app` | Works on a real phone; next fix round pending |
| Server API for the app (`/api/tracking-app/*`), arrival times (ETA), route line | `suprah-backend` and `suprah-frontend`, branch `feature/driver-tracking-app` | Not in production; everything is off by default |
| Batch 9 onboarding fixes | `main` in both projects | Live since 11 October 2026 |
| Renewed documents, live refresh, Confirm Delivery lock, Dispatch Chat fix | `main` in both projects (from `fix/driver-document-renewal`) | Live since 11 October 2026 |
| iPhone redesign preview | `suprah-frontend`, branch `feature/mobile-redesign-preview` (includes batch 9) | Off by default; visual check pending; not for `main` yet |

The app's server routes only work when the backend runs with `TRACKING_APP_ENABLED=true`. Arrival times also need the AWS / Amazon Location setup, which the lead owns.

## Real-phone checks (behaviour spec, section 8)

- **Passed:** 1 (pairing and approval), 2 (screen locked), 3 (going off duty, button and notification), 6 (app update), 7 (phone restart, main case), 8 (reopening after Android closes the app), 9 (unlink from the app and from the Driver Portal), 10 (waiting for approval), 12 (phone settings rows).
- **Still to run:** 4 (no signal for 10+ minutes, then back), 5 (slow server), 11 (fake GPS flagged; needs fix 4 below).
- **Re-run after the fix round:** 7 part A (restart with no signal) and 9 part 2 (leftover notification).

## Next fix round (do these together, then rebuild and re-test once)

1. Restart with no signal: GPS should start anyway. Fix the messages that say locations are being saved, or that there's no load, when the app just can't reach Suprah.
2. Send leftover saved locations in the background after going off duty (WorkManager).
3. Warn before unlinking, and send saved locations first.
4. Mark fake-GPS readings so the server knows.
5. Diagnostics screen with a "Copy report" button.
6. Remove the leftover "On duty" notification after the phone is unlinked from the Driver Portal.
7. While offline, ask Suprah again about every 15 seconds instead of once a minute.

Ideas not decided yet: a "weak GPS signal" note on the home screen; stop rough readings (worse than about 100 m) from moving the driver on the map (needs the senior's go-ahead).

## Decisions waiting on the senior or lead

- Whether customer accounts may apply to become drivers.
- Suspended drivers are sent to the sign-in page with no explanation (sign-in doesn't check suspension).
- Self-service account deletion for drivers (no button exists today).
- AWS / Amazon Location setup for arrival times and route lines.
- Automatic renewal of the API server's SSL certificate (it expired on 8 October; the new one expires 6 January 2027).
- iPhone: Apple Developer account, a test iPhone, who owns the shared app shell, or Traccar Client as a stopgap.

## Setting up again on a new PC

1. **Install the tools:** Git, Node.js (the version the projects use), 7-Zip, Android Studio, and a JDK 21 (Gradle 8.11 doesn't run on newer Java).
2. **Get the projects back.** Pick one:
   - **From the backup zip (recommended):** unzip `suprah-code-backup-<date>.zip` into `C:\Users\Admin\Jov\actionauto-devteam`. This also brings back the stashes and the branches that were never pushed, plus the workspace folders `suprah-ios-plan/` and `batch9-pull-requests/`.
   - **From GitHub:** clone `suprah-backend`, `suprah-frontend` and this repository into one folder. Only pushed branches come back this way.
3. **Restore the private files:**
   - The settings files from the password-protected 7-Zip archive: `suprah-backend/.env`, `suprah-frontend/.env.local`, and `suprah-backend.env.local.bak` in the workspace folder (the development-database settings, moved out while testing against production). They are never in git.
   - The `memory` folder (Claude's project notes) goes to `C:\Users\Admin\.claude\projects\c--Users-Admin-Jov-actionauto-devteam\memory`.
   - `debug.keystore` goes to `C:\Users\Admin\.android\`. With it, the test phone's app can be updated without reinstalling.
4. **Install the libraries.** In `suprah-backend`, run `npm ci`. It installs exactly what the lock file lists; the team adds libraries often, and a missing one makes the type check and tests fail. In `suprah-frontend`, run `npm install`.
5. **Switch to your branches:**
   - website: `feature/mobile-redesign-preview` for the iPhone redesign preview
   - server: `main` (make a new branch from it for the next task)
   - this repository: `feature/driver-tracker-app`
6. **Android:** open this project in Android Studio once; it creates `local.properties`. For the command line, set `JAVA_HOME` to the JDK 21 folder. If you didn't restore `debug.keystore`, uninstall "Suprah Tracker (dev)" from the test phone before installing a new debug build, then pair again.

### Releasing (the team's process, no pull requests)

1. Commit and push to your feature branch.
2. Switch to `main` and pull: `git switch main`, then `git pull`.
3. Merge: `git merge --no-edit <your branch>`.
4. **Check before pushing:**
   - server: `npx tsc --noEmit` and `npm run test:unit`
   - website: `npx tsc --noEmit` and lint
5. `git push origin main`. Pushing the server's `main` deploys it automatically; watch GitHub → Actions until the run is green.
6. Release the server first when the website uses something new from it.

Never put `NEXT_PUBLIC_MOBILE_PREVIEW` in the live settings.

### Website builds and the redesign preview

- **Production builds** (`npm run build`) refuse a `localhost` server address, on purpose. To build locally, use a new Command Prompt window:
  1. `set NEXT_PUBLIC_API_URL=https://api.suprah-app.com`
  2. `npm run build`
  3. `git restore public/sw.js`
  4. Close that window.
- **To see the redesign preview:** in the website's window, run `set NEXT_PUBLIC_MOBILE_PREVIEW=true` and then `npm run dev`. Open Chrome DevTools (F12, then Ctrl+Shift+M) at an iPhone size, and use the "New design / Current design" button at the bottom left.

### Running the backend against the production database (test accounts only)

The settings file `suprah-backend/.env.local` must be moved out of the folder for this, and moved back afterwards. `<database host>` is the host part of the production database address in your backend settings (it ends in `.mongodb.net`). Never write it into a settings file as `ALLOW_PRODUCTION_DB`; the backend refuses that on purpose.

Command Prompt:

```
cd suprah-backend
set ALLOW_PRODUCTION_DB=<database host>
set DISABLE_SCHEDULERS=true
set TRACKING_APP_ENABLED=true
npm run dev
```

PowerShell: use `$env:ALLOW_PRODUCTION_DB="<database host>"` and so on instead of `set`. Wait for the line saying schedulers are NOT running before using the app. If the database refuses the connection, your internet address may need adding in MongoDB Atlas → Network Access (ask the senior).

### Phone

USB debugging on, then `adb reverse tcp:5000 tcp:5000` (run it again after every reconnect or phone restart). Xiaomi phones also need "Install via USB" and Autostart on.
