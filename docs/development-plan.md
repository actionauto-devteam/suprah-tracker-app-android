# Development plan and status

Last updated: 10 October 2026. This is where the Suprah Driver Tracker work stands, what comes next, and how to pick it up again on a new PC.

## The plan right now

1. **Production fixes first.** The Driver Portal and Driver Tracker fixes for onboarding ("batch 9") go to production before the tracking app. They live on the branch `fix/driver-tracker-batch-9` in `suprah-backend` and `suprah-frontend`. Production deploys only when that branch is merged into `main` after senior review.
2. **The tracking app comes after.** Once batch 9 is in `main`, update the app's server branch from `main`, re-test the app with batch 9 included, then do the next fix round below.
3. **iPhone is a separate track.** The team plans a Capacitor iPhone app. My part is Transportation and Driver Tracker (plus the small Driver Portal changes tracking needs). It needs a Mac, an iPhone and an Apple Developer account before real device work can start. Traccar Client is a possible stopgap for iPhone drivers, but only if the senior agrees.

## Where the code is

| What | Where | State |
|---|---|---|
| Android tracking app | this repository | Works on a real phone; next fix round pending |
| Server API for the app (`/api/tracking-app/*`), arrival times (ETA), route line | `suprah-backend` and `suprah-frontend`, branch `feature/driver-tracking-app` | Not in production; everything is off by default |
| Batch 9 production fixes | `suprah-backend` and `suprah-frontend`, branch `fix/driver-tracker-batch-9` | All automated checks and the website build pass; manual QA partly done; waiting for senior review |

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

- Merge batch 9 (review the pull requests).
- Whether customer accounts may apply to become drivers.
- Self-service account deletion for drivers (no button exists today).
- AWS / Amazon Location setup for arrival times and route lines.
- Automatic renewal of the API server's SSL certificate (it expired on 8 October; the new one expires 6 January 2027).
- iPhone: Apple Developer account, a test iPhone, who owns the shared app shell, or Traccar Client as a stopgap.

## Setting up again on a new PC

1. Install Git, Node.js (the version the projects use), Android Studio, and a JDK 21 (Gradle 8.11 doesn't run on newer Java).
2. Clone the projects into one folder: `suprah-backend`, `suprah-frontend`, and this repository.
3. Check out the branches you're working on, for example:
   `git switch fix/driver-tracker-batch-9` or `git switch feature/driver-tracking-app`, then `npm install` in each project.
4. Put the settings files back from your private backup: `suprah-backend/.env` and `suprah-frontend/.env.local` (and `suprah-backend/.env.local` if you use the development database). They are never in git.
5. Android: open this project in Android Studio once (it creates `local.properties`). For the command line, set `JAVA_HOME` to the JDK 21 folder.
6. A new PC has a new debug signing key. Uninstall the old "Suprah Tracker (dev)" app from the test phone before installing a new debug build, then pair again.

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
