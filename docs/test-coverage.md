# Test coverage of the behaviour spec

Which automated test covers each item of [behaviour-spec.md](behaviour-spec.md), and what can only be proven on a real phone. Tests live in `app/src/test` and run with `./gradlew testDevDebugUnitTest` (99 tests, all on the JVM; Robolectric for Android parts).

**Phone** means the item depends on real Android behaviour (foreground service, GPS hardware, phone restarts, Xiaomi settings) and is checked with section 8 of the spec.

| Spec | Covered by | Phone |
|---|---|---|
| S-2 server contract unchanged | RetrofitTrackerApiTest | |
| S-3 GPS only on duty + approved + accepted load | PoliciesTest, TrackingEngineTest | ✓ |
| B-2 dev/prod builds | DataStoreSettingsRepositoryTest, PairViewModelTest, PairScreenTest | |
| B-3 Driver Portal link | HomeScreenTest | ✓ opens the browser |
| C-1 pairing refusals | PairViewModelTest, PairScreenTest | |
| C-2 the newest reading may be sent twice | PositionUploaderTest | |
| P-1 pairing until paired | AppViewModelTest | |
| P-2 intro and instructions | PairScreenTest | |
| P-3 code: 8 letters/digits, upper-cased | PairViewModelTest, PairScreenTest | |
| P-4 server address (dev): http/https, full address shown | PairViewModelTest, PairScreenTest | |
| P-5 device details sent | RetrofitTrackerApiTest | |
| P-6 key encrypted, pairing saved | DataStoreSettingsRepositoryTest, PairViewModelTest | ✓ real Keystore |
| P-7 error wording | PairViewModelTest, PairScreenTest | |
| H-1 driver name | HomeScreenTest | |
| H-2 status card states, loads, problems | HomeScreenTest, HomeViewModelTest | |
| H-3 duty button and hints | HomeScreenTest, HomeViewModelTest | |
| H-4 info rows, saved count | HomeScreenTest, HomeViewModelTest | |
| H-5 status check while the screen is open | HomeViewModelTest (incl. 5 s / 60 s timing), HomeScreenTest | |
| H-6 resume on open | HomeViewModelTest | ✓ |
| H-7 phone-settings rows (incl. Android 9 and Xiaomi) | HomeScreenTest, AndroidPhoneSettingsCheckerTest | ✓ Xiaomi Autostart reading |
| H-8 open the Driver Portal | HomeScreenTest | ✓ |
| H-9 unlink | HomeViewModelTest, HomeScreenTest | ✓ |
| T-1 on-duty sessions | TrackingSessionsTest | ✓ foreground service, notification |
| T-2 start checks | TrackingEngineTest, TrackingSessionsTest | ✓ permission and "tap to resume" alerts |
| T-3 restart after reboot/update | (wiring reviewed) | ✓ |
| T-5 GPS on/off with status | TrackingEngineTest, PoliciesTest | ✓ |
| T-6 GPS interval from Suprah | TrackingEngineTest | ✓ |
| T-7 reading fields | RetrofitTrackerApiTest | ✓ |
| T-8 save first, 5,000 cap | RoomPositionStoreTest, TrackingEngineTest | ✓ survives restart |
| T-9 instant send, wait after no answer | PositionUploaderTest, TrackingEngineTest, PoliciesTest | ✓ screen locked |
| T-10 oldest first, newest ahead | PositionUploaderTest | ✓ after no signal |
| T-11 slow server: 60 s, halve/grow batch | PositionUploaderTest, RetrofitTrackerApiTest, PoliciesTest | |
| T-12 drop readings older than 23 h | PositionUploaderTest, RoomPositionStoreTest | |
| T-13 refusals | PositionUploaderTest, TrackingEngineTest, TrackingNotificationsTest | ✓ unlink from the portal |
| T-14 problem messages | PositionUploaderTest, TrackingNotificationsTest | |
| T-16 notification text | TrackingNotificationsTest | ✓ |
| T-17 going off duty (incl. straight back on) | TrackingEngineTest, TrackingSessionsTest | ✓ |

Items marked **NEW** in the spec (C-3, H-9's additions, T-4, T-15, the mock flag in T-7, and section 7) are step 2 and not covered here yet. P-4's full address display is already in step 1.
