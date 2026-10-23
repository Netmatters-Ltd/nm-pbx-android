# Contacts and Extensions Performance Investigation

Investigation date: 23 October 2026. App version 6.3.009 (release build in the morning, then a debuggable build installed at 09:49).

We were looking into slow loading of the Extensions and Contacts lists when there are a large number of contacts, plus a full crash seen once on a low-spec tablet during first-time use. This document records what we measured and what we think should change. Fixes made since are recorded under each finding.

## Summary

| # | Finding | Impact | Status |
|---|---|---|---|
| 1 | `GenericActivity.getTheme()` re-applies the theme on every call | Main cause of slow list layout, and it gets worse the longer the app runs | Fixed |
| 2 | Extension avatar models leak on every list rebuild, each holding its base64 photo | Memory grows about 1.5MB per Extensions visit on the test server; most likely cause of the crash | Fixed |
| 3 | First CardDAV sync of about 5,000 vCards takes 2.5 minutes | Lists looked empty or broken until it completed; the core thread is blocked for about 100s | "Fetching" state added; the rest is in the SDK and the CardDAV server |
| 4 | Presence is subscribed for about 3,500 external phone numbers | About 0.5s of background CPU and 48KB of data per re-subscription, a few times a day | SDK patch deferred; redundant re-subscription after each sync removed |
| 5 | Rows rebuilt twice per load; per-result native calls when processing search results | Smaller CPU costs on main and core threads | Row rebuild fixed; result processing investigated, no simple fix |
| 6 | Every cold start spends 25s loading the stored contacts | Core thread blocked for 25s on each cold start: no account, lists or calls until it finishes | Inside the SDK; needs a decision |

The crash itself was not reproduced. The test tablet had no record of an app crash, ANR or native crash.

## Test setup

- Device: Oukitel RT1 tablet, Android 11, 1200x1920, about 4GB RAM, app heap limit 256MB.
- Account: extension 5 on `nm-test.nmpbx.uk`.
- CardDAV address book: 5,028 vCards, made up of about 5,000 external contacts and 23 extensions. The extensions have inline base64 JPEG photos (`PHOTO;VALUE=URI:data:image/jpeg;base64,...`).
- READ_CONTACTS denied, so the phone's own address book is not imported. Everything comes from CardDAV. (The first-sync runs in finding 3 granted it, but the tablet has no contacts of its own.)

## Findings

### 1. The theme is re-applied on every `getTheme()` call

`GenericActivity.getTheme()` (`app/src/main/java/org/linphone/ui/GenericActivity.kt:58`) calls `theme.applyStyle(...)` each time it is called. `CallActivity` (`app/src/main/java/org/linphone/ui/call/CallActivity.kt:112`) does the same. This came from upstream Linphone.

Android calls `getTheme()` constantly: for every view inflated, every styled attribute lookup and every themed colour or drawable. Each `applyStyle` call appends another entry to the theme's internal key, and changes the key's identity. As a result:

- After about 10 minutes, 40 theme keys held 10.7MB of int arrays, roughly 67,000 entries each.
- Android's themed colour and drawable caches are keyed on the theme, so they never get a hit. Every lookup reloads from XML and stores another copy of the growing key (8,825 cached keys in the heap dump).

Effect on the lists, from a CPU profile (`simpleperf`) of switching between Extensions and Contacts:

- 54% of samples were on the main thread.
- `ContactsListAdapter.onCreateViewHolder` (row inflation) accounted for 44% of main-thread time.
- Within that, style resolution (`obtainStyledAttributes`) was 24%, colour state list loading 9% and drawable loading 5%.
- Binding data into rows was only about 5%, and image loading about 3%.

Layout times for the contacts RecyclerView, which shows around 13 rows:

| Session | Layout pass times |
|---|---|
| Release build, shortly after start-up | 500-850ms |
| Debug build, about 10 minutes in | 1.4-2.3s |

Some of the difference is the debug build running interpreted code, but the growth over time is consistent with this finding. Frame stats from the release session showed 54% of all frames as janky.

**Fix:** the colour style is now applied in `onApplyThemeResource()`, which the framework calls when it (re)applies the activity's base theme, instead of in `getTheme()`. `CallActivity` supplies its in-call styles through `getMainColorStyle()`; each in-call style inherits from the matching colour style, so applying it alone gives the same result as before. Because the colour is now only read when the theme is created, changing it in Settings saves the preference first and then recreates the activity through `recreateActivityEvent`, rather than recreating straight away.

**Result**, same 10-round tab-switch test on the debug build:

| | Before | After |
|---|---|---|
| Layout passes per switch | 0.8s and 1.3s at the start, rising to 1.05s and 1.7s by round 10 | 0.4-0.6s and 0.5-0.9s, flat across all 10 rounds |
| `Resources$ThemeKey` objects in the heap | 8,825 | 13 |
| Int arrays held by theme keys | 10.7MB | Negligible |

Some layout passes now fall under the device's 400ms warning threshold and are no longer logged at all. Memory still grows during the test because of finding 2.

### 2. Extension avatar models leak, including their photos

`ContactsManager.getContactAvatarModelForFriend` (`app/src/main/java/org/linphone/contacts/ContactsManager.kt:567`) creates a new `ContactAvatarModel` on every call. It does not cache them. When the friend has SIP addresses, the model registers a `FriendListener` in its `init` block (`ContactAvatarModel.kt`). `ContactsListViewModel.processMagicSearchResults` never calls `destroy()` on the previous list's models, despite the comment at the top of that function saying they are cached.

The SDK holds each registered listener as a JNI global reference, so the listener, its model and everything the model holds can never be garbage collected.

What the heap dump showed after 12 Extensions visits:

- 376 `ContactAvatarModel` instances alive, while the screen needed 100.
- 276 of their friend listeners held as JNI global references. That is exactly 12 visits x 23 extensions.
- External contacts do not leak. They only have phone numbers, so no listener is registered.
- 18.4MB of strings held by `MutableLiveData` fields inside these models, about 67KB per leaked model. This is mainly `picturePath`, which holds the full base64 `data:` URI of the vCard photo.

Live heap after garbage collection, during 10 round trips between Extensions and Contacts:

| Point | Live heap |
|---|---|
| Start of test | 17MB |
| End of test | 52MB |
| After a forced GC for the heap dump | 47MB |

The view count stayed flat (318), so views are not leaking.

Leaked models also still receive presence updates. Every incoming NOTIFY recalculates presence on every leaked model, each posting around 10 LiveData updates to the main thread.

The cost scales with the number of extensions that have photos and with how often the list is rebuilt. Rebuilds happen on every tab switch (each switch creates a new fragment and view model), after CardDAV syncs and when new SIP addresses are discovered. On a PBX with a few hundred extensions this would use memory far faster, which is the most likely explanation for the crash on a lower-spec device.

**Fix:** `ContactsListViewModel` now keeps track of the friend models it creates for each list (contacts and favourites). When a list is rebuilt, the previous list's models are destroyed, which removes their listeners, and both lists are destroyed in `onCleared()`. Models for results without a friend come from the `ContactsManager` cache and are shared, so those are left alone. This covers any friend with SIP addresses, so external contacts with SIP addresses and photos are handled the same way as extensions.

**Result**, same 10-round tab-switch test on the debug build:

| | Before | After |
|---|---|---|
| Live heap after GC over the test | 17MB rising to 52MB | 13-17MB throughout |
| `ContactAvatarModel` instances after the test | 376 (276 held by the SDK) | 24 (the 23 on screen plus one) |
| Java and native heap over 5 further rounds | Growing | Flat (about 27MB and 96MB) |

**Contact picker:** `AddressSelectionViewModel` (the picker used for new calls, new conversations, forwarding messages and adding participants) had the same problem on every search. It now tracks every avatar model it creates for the list, including its own address-based and conversation models, destroys the previous list's models when a search completes and destroys the rest in `onCleared()`. The selected-participant chips use models from the `ContactsManager` cache, so they are unaffected.

Result, typing and deleting a letter 20 times in the New call picker (40 searches), with the picker still open:

| | Before | After |
|---|---|---|
| `ContactAvatarModel` instances | 721, all held by the SDK | 31, of which 25 live (the rows on screen plus one) |
| Heap dump size | 98MB | 43MB |

Separately, consider not keeping the full base64 photo string in each model.

### 3. First CardDAV sync takes 2.5 minutes

Measured with scripted first-time provisioning on the test tablet (app data cleared, provisioning URL entered, timed from the SDK's sync start to sync success). Each run downloads and stores all 5,028 vCards.

| Run | Sync start to first contact created | Creating and storing all contacts | Total |
|---|---|---|---|
| Baseline (debug build, MESSAGE logging to file and logcat) | 41s | 103s | 145s |
| Baseline, repeated | 39s | 101s | 139s |
| SDK log level set to WARNING | 37s | 97s | 134s |
| Database writes failing (see below) | 40s | 60s | 100s |

**After the portal update (23 October, afternoon).** The portal was changed to check credentials before parsing the request, and to cache results. Three full first-time syncs on the tablet:

| Run | Sync start to first contact created | Creating and storing all contacts | Total |
|---|---|---|---|
| 1 | 23.2s | 106.0s | 129.2s |
| 2 | 22.1s | 104.0s | 126.1s |
| 3 | 25.3s | 107.2s | 132.5s |

- The unauthenticated multiget now gets its `401` in about 0.4s (previously 13-18s). Timed from a desktop, it's 0.27s for all 5,028 hrefs, against 17s before.
- The authenticated multiget still takes 15-17s before the response starts on the tablet, the same as before. Timed from a desktop with credentials, three requests in a row took 30s, 18s and 16s, for a 5.5MB response. So caching doesn't yet make repeat requests noticeably faster, and this is now the largest part of the network phase.
- 5,000 of the 5,028 vCards in the response still share the eTag `cb0cb5a3...`.
- Processing on the device is unchanged, as expected.

Overall, a first sync now takes about 2 minutes 10 seconds, against 2 minutes 20-25 seconds before.

**Where the time goes.** The two phases have different causes. The figures below are from before the portal update.

*Before the first contact (about 40s), mostly the CardDAV server:*

- Listing the address book takes about 4s, including a 1MB response listing 5,028 eTags.
- The SDK then sends one `addressbook-multiget` REPORT naming all 5,028 vCards (about 460KB). Like every request the SDK makes, it is first sent without credentials. The portal took 18s to answer with `401`, and then another 18s to answer the authenticated retry.
- Timing unauthenticated requests from a desktop shows the `401` delay grows with the request body: 0.2s for a small PROPFIND, 1.5s for 500 hrefs, 6.7s for 2,500 and 17s for all 5,028. So the portal parses the whole request before checking credentials, and that parsing gets slower per item as the list grows. The authenticated response probably pays the same parsing cost.

*Creating and storing contacts (about 100s), inside the SDK on the core thread:*

| Part | Approximate cost | Evidence |
|---|---|---|
| Matching each new contact against the existing list | About 37s | For every downloaded vCard, `CardDAVContext::vcardsPulled` scans every contact already in the list, comparing vCard UIDs (fetched and copied as strings on each comparison). The gap between the SDK's "Downloaded vCard" and "Contact created" log lines grows steadily from 1.5ms to 13ms per contact across the sync. Upstream master has the same code. |
| Database inserts | About 40s | Each contact is stored in its own transaction (`MainDb::insertFriend`), with SQLite's default rollback journal, so every contact costs several flash writes and syncs (about 11ms each on this tablet). A run where the database could not be written finished this phase in 60s against 101s. |
| Logging | About 5s | The SDK writes 6 lines per contact. Setting the log level to WARNING saved 4-6s (about 5%). |
| vCard parsing, friend creation and the rest | About 20s | Includes about 4.8s parsing the 16 vCards with inline photos (2.7MB between them, the largest 325KB). |

The core thread is fully occupied for the whole of this phase. Nothing else that needs it runs, including SIP traffic and anything the UI asks of the SDK. That is why the lists can't even start loading until the sync ends.

**What we tried that did not work.** Switching `linphone.db` to WAL journal mode before the SDK opens it (WAL mode is stored in the file, so it can be set from the app). The SDK's SQLite could not open the database afterwards ("unable to open database file"), so nothing was stored at all. The change was reverted. It did give the measurement of the insert cost above.

**Done in the app:** the Extensions and Contacts lists now show a spinner with "Fetching extensions…" or "Fetching contacts…" while a CardDAV sync is running and the list has nothing to show. `ContactsManager` exposes `cardDavSyncInProgress` from the SDK's sync status, and `ContactsListViewModel.fetchingFromServer` combines it with the list being empty and not filtered. The "No contact for the moment…" state is now only shown once a load has finished, so it no longer flashes up between the sync ending and the list appearing. `ContactsManager` also logs how long each sync took.

**Options that remain:**

- **CardDAV server (quickest win):** check credentials before parsing a REPORT body, which would save about 18s, and look at why the multiget takes 18s to process. Also check the eTags: 5,000 of the 5,028 vCards have the same eTag (`cb0cb5a3...`), so later syncs probably won't notice when those contacts change.
- **SDK patch:** replace the UID scan with a lookup table (about 37s, and more with larger address books), and store the downloaded contacts in a single transaction (most of about 40s). This fits with the patched SDK build discussed in finding 4.
- **Fewer or smaller vCards:** everything above scales with the number of contacts synced, and the inline photos add parse time and memory. See finding 6.
- **Log level:** lowering it in release builds would save about 5% here. It would also make support logs much less useful, so we don't think it's worth it for this alone.

### 4. Presence subscriptions include external phone numbers

`android-presence-feature-spec.md` assumes the CardDAV address book is the PBX directory of internal extensions, so subscriptions are enabled for that whole friend list (`ContactsManager.applySubscriptionPolicy`). On the test server the same address book also holds the 5,000 external contacts.

The aggregated RLS SUBSCRIBE therefore lists 3,512 unique resources. Only 14 of them look like extension numbers. The rest are external numbers such as `sip:01514960757@nm-test.nmpbx.uk;user=phone`. This is the privacy problem the spec says we should avoid, and it makes every subscription and full-state NOTIFY much larger than it needs to be.

After each successful sync, `ContactsManager` also toggles subscriptions off and on to force a fresh SUBSCRIBE, which repeats this every time.

Changing how the address books are served is ruled out, as customers already use them.

**What we found when looking for an app-side fix:**

- The SDK builds the resource list in `FriendList::createResourceListXml` from `mFriendsMapByUri`. `Friend::addAddressesAndNumbersIntoMaps` adds every SIP address and every phone number (as `sip:<number>@<domain>;user=phone`) of every friend in the list.
- The per-friend `isSubscribesEnabled` flag only affects individual subscriptions, not the resource list, and there is no configuration option that leaves phone numbers out. Upstream master behaves the same way.
- The desktop app solved this with an SDK source patch, `liblinphone-rls-exclude-phone-numbers.patch` in `linphone-desktop/nm-pbx-docs/sdk-patches/`, which skips `;user=phone` entries (`[sip] rls_include_phone_numbers=1` restores upstream behaviour). The desktop build compiles the SDK from source, so the patch is applied to the source tree and built with the app.
- `createResourceListXml` is identical in 5.4.114 (desktop) and upstream master, and the desktop patch applies cleanly to master, so it should apply to 5.5.16 (Android) as well.

**Options for Android:**

1. **Build a patched Android SDK (recommended).** This project already supports a locally built SDK: set `LinphoneSdkBuildDir` in `gradle.properties` and the build uses `<dir>/maven_repository` in place of Belledonne's prebuilt SDK. Upstream supports Android builds on Linux or macOS, or in their Docker image (`bc-dev-android-r27`); this machine has WSL (Ubuntu 22.04) and Docker. Costs: a first-time build setup, somewhere to keep the built SDK so release builds use it, and re-applying the patch on SDK upgrades. Note that the SDK source and its 48 submodules, and the Docker image, are all hosted on `gitlab.linphone.org`, which was unreachable from the office network on 23 October 2026.
2. **App-only workaround.** Turn off subscriptions on the CardDAV list and subscribe through a separate hidden list holding copies of the extensions' SIP addresses only. Presence would then arrive on the copies, so every place that reads presence (contact rows, our own status in `PresenceManager`) would need redirecting, and the copies would need filtering out of contact searches and caller lookups. This avoids an SDK build but touches a lot of the app.

**Measured cost**, from the SDK logs captured on 23 October 2026 (3,512 entries, of which about 23 are needed). The SDK is a release-built native library even in our debug builds, so these timings are representative.

| Per full re-subscription | Now | Extensions only |
|---|---|---|
| Data | About 24KB up and 24KB down (deflate-compressed from 473KB of XML out and 496KB back) | Under 1KB each way |
| Core thread CPU | About 130-280ms to build and compress the list, plus about 210ms to decompress and handle the full-state NOTIFY | A few milliseconds |
| Log volume | Each list is logged line by line: 46,585 lines (3MB), 23% of all log lines in the 35 minutes captured | Negligible |
| Lasting memory | No difference. The SDK keeps phone numbers in its lookup map either way; the patch only filters when building the list | |

Routine presence updates are small partial NOTIFYs (about 0.5KB) and are unaffected. Full re-subscriptions happened:

- at every app start, usually twice: once on start, then again when `ContactsManager` toggles subscriptions after the CardDAV sync succeeds. The reply to the first one arrived after it had been replaced, so it was rejected and wasted.
- on most returns to the foreground: 4 of the 6 foreground events captured.
- probably on each hourly renewal (`Expires: 3600`), although the logs were too short to confirm this.

For a typical user that is roughly 10-30 exchanges a day: about 0.5-1.5MB of data and 5-15 seconds of background CPU. This is negligible for battery and data. The only user-visible effect is the core thread being busy for about half a second as the app opens, which can slightly delay the lists on a low-spec device. The cost scales with the number of contact phone numbers; the desktop test address book (8,352 numbers) would be about 1 second per exchange on the test tablet.

The numbers only go to our own presence server, and they come from our own CardDAV server in the first place, so the privacy concern is about tidiness rather than exposure.

**Decision:** deferred. The benefit is small compared with findings 1 and 2, and the fix needs SDK build infrastructure, ongoing maintenance at each SDK upgrade, and access to `gitlab.linphone.org`. Revisit if a customer's address book is much larger than the test one, or if we end up building the Android SDK for another reason, when adding this patch would cost little extra.

**Cheaper app-only improvements** that would recover part of the waste without an SDK build:

- Only toggle subscriptions after a CardDAV sync when it is needed (done, see below).
- Reduce SDK log verbosity in release builds (see also finding 3). Not yet measured.

**Done in the app:** `ContactsManager` now records which CardDAV lists had a contact created, updated or deleted during a sync, and only toggles subscriptions after a successful sync of one of those. This covers the first sync after provisioning, as every contact is created then. Checked on the test tablet:

| Cold start | Sync | Result |
|---|---|---|
| Address book unchanged | 0.6s | No refresh. One RLS SUBSCRIBE for the whole start, answered by a full-state NOTIFY, then routine partial NOTIFYs |
| One contact's stored eTag and the list's ctag altered in `linphone.db`, so the sync downloads it again | 6.2s | Refresh sent, as before |
| Next start after that | 0.6s | No refresh |

After a force-stop, the server still sends one NOTIFY for the previous process's subscription, which the app rejects with `481`. That's expected and unrelated to this change.

### 5. Smaller costs

- **Rows rebuilt on every update (fixed).** `ContactDiffCallback.areContentsTheSame` (`ContactsListAdapter.kt`) always returns `false`, which is needed because every update hands the rows new model objects. Combined with the default item animator's change animation, every visible row got a newly inflated view rather than a rebind, so each load had two expensive layout passes. Change animations are now disabled on the contacts and favourites lists (`ContactsListFragment`), so rows are rebound in place.
- **`"null"` picture path (fixed).** `ContactAvatarModel.getAvatarUri` returns `null` for CardDAV friends without a photo, and `picturePath` then held the string `"null"`, so the image loader tried and failed to load it before falling back to initials. It now holds an empty string, which goes straight to initials.
- **Processing search results (investigated, not changed).** We first thought the sort comparator was costly because it makes native calls on each comparison. Reading each result's friend and name once and sorting on collation keys made no difference (Extensions) or was slightly slower (Contacts), so it was reverted. A CPU profile showed why: processing is about 72% of the core thread during a switch, and it is dominated by one `SearchResult.getFriend` call (27%) and one `Friend.getName` call (23%) for each of the 5,028 results, which both versions make. The results already arrive close to sorted, so the sort itself makes few comparisons. The real fix is to process fewer results per tab, for example by keeping extensions in their own address book or friend list (see finding 4) so each tab only searches its own entries. Note these timings are from a debug build, where the Java side of each native call is not compiled, so the release cost will be lower.
- **New fragment on every tab switch (not changed).** Every tab switch builds a new fragment, view model, Magic search and full set of row views. Keeping the list state between tabs would avoid repeating that work, but it's a larger structural change.

**Result** of the two fixes, same 10-round tab-switch test on the debug build, following fixes 1 and 2:

| | Before | After |
|---|---|---|
| Layout passes over the 400ms warning threshold | 18, at 440-840ms | 4, at 430-610ms |
| Skipped-frame events | 16 | 3 |
| Processing 5,028 results (Extensions / Contacts) | About 430ms / 810ms | Unchanged |

### 6. Every cold start spends 25 seconds loading stored contacts

When the app process starts, the SDK loads every stored friend from `linphone.db` before the Core finishes starting. On the test tablet the SDK logs `Duration of [Get friend lists.]: 25482ms` (25.0s and 24.9s on repeat runs). Until then the core thread does nothing else: the top bar has no account name, the lists show a spinner, and the SDK can't register or handle calls. We haven't tested it, but an incoming call that wakes the app from a push notification would probably wait for this as well.

A CPU profile of a cold start shows most of the core thread's time in the SDK's grammar-based vCard parser (`belr` and `belcard`). `MainDb::getFriends` parses every stored vCard again on each start. That's about 4ms per contact, even though the median vCard is 196 bytes.

To measure the photos' share, we stripped the 16 `PHOTO` properties from a copy of the database on the device:

| Database | Get friend lists |
|---|---|
| As synced (3.7MB of vCards, 2.7MB of it inline photos) | 25.0s, 24.9s |
| Photos removed | 20.0s, 19.9s |

So inline photos cost about 5s per cold start, and the remaining 20s is roughly proportional to the number of contacts.

**Options:**

- **Fewer contacts in the synced address book.** This is the biggest lever. The 5,000 external contacts cost about 20s per cold start and most of the first sync. The SDK can also search a CardDAV server on demand (Magic search's remote CardDAV source) instead of holding a full copy. That would need either a separate address book for external contacts, or a server-side search endpoint. We previously ruled out changing how address books are served, but these figures may be worth weighing against that.
- **Serve photos by URL rather than inline.** This saves about 5s per cold start, about 5s on first sync, and the 67KB string each avatar model holds (finding 2). The app would need to load the photo URL, which may need authentication.
- **SDK changes** to parse vCards lazily or cache what the parsed vCard provides. This is a much bigger patch than the ones in findings 3 and 4.

### Already ruled out on this device

The phone address book import (`ContactLoader.parseFriends`) checks every contact's photo with `openAssetFileDescriptor` and compares vCard dumps for each contact. That could be slow with a large phone address book, but it did not run in these tests because READ_CONTACTS was denied.

## Recommended order of work

1. Fix the theme re-application (finding 1). Small change, biggest effect on list speed.
2. Fix the avatar model leak (finding 2). Contained change, addresses the memory growth and the likely crash.
3. Fix the double row rebuild and the per-result native calls (finding 5).
4. External numbers in presence subscriptions (finding 4): SDK patch deferred after measuring the cost. The redundant re-subscription after each sync has been removed.
5. First sync (finding 3): "Fetching" state done. The server-side fixes (authentication before parsing, multiget speed, eTags) are the cheapest next step.
6. Cold start (finding 6): needs a decision on address book size and photos. Both findings 3 and 6 strengthen the case for a patched SDK build.

After each change, re-run the tab-switch test below and compare against the figures in this document.

## How to reproduce the measurements

All commands assume `adb` from the Android SDK platform tools and a debuggable build. In Git Bash, set `MSYS_NO_PATHCONV=1` so device paths are not rewritten.

Raise the logcat buffer, as the default 256KB wraps within seconds:

```sh
adb logcat -G 16M
```

Tab-switch stress test (tap positions are for a 1200x1920 portrait screen):

```sh
adb shell dumpsys meminfo com.netmatters.nmpbx > mem_before.txt
for i in 1 2 3 4 5 6 7 8 9 10; do
  adb shell input tap 200 1760   # Extensions
  sleep 4
  adb shell input tap 600 1760   # Contacts
  sleep 5
done
adb shell dumpsys meminfo com.netmatters.nmpbx > mem_after.txt
```

Live heap after each GC can be read from the `concurrent copying GC` lines in logcat. Layout times appear as `[ANR Warning]onLayout time too long ... contactsList` on some devices.

CPU profile of a tab switch (run the recording and the taps in one device shell, or the output file comes back empty):

```sh
adb shell "simpleperf record --app com.netmatters.nmpbx -g -f 1000 --duration 16 -o /data/local/tmp/perf.data & sleep 2; input tap 200 1760; sleep 7; input tap 600 1760; wait"
adb shell "simpleperf report -i /data/local/tmp/perf.data --tids <main thread id> --children --sort symbol"
```

Heap dump:

```sh
adb shell am dumpheap -g com.netmatters.nmpbx /data/local/tmp/nmpbx.hprof
adb pull /data/local/tmp/nmpbx.hprof
hprof-conv -z nmpbx.hprof nmpbx-conv.hprof
```

The converted file opens in Android Studio's profiler or Eclipse MAT. Look at instance counts for `ContactAvatarModel` and `ContactAvatarModel$friendListener$1`, and the GC roots holding the listeners.

First-sync timing: clear the app's data, grant permissions, enter the provisioning URL on the Login screen, and read the sync duration from logcat:

```sh
adb shell pm clear com.netmatters.nmpbx
for p in CAMERA RECORD_AUDIO READ_CONTACTS; do adb shell pm grant com.netmatters.nmpbx android.permission.$p; done
# launch, tap OK on the permissions screen, enter the provisioning URL and tap Apply
adb logcat | grep "synced in"
```

Cold-start friend loading:

```sh
adb shell am force-stop com.netmatters.nmpbx
adb shell monkey -p com.netmatters.nmpbx -c android.intent.category.LAUNCHER 1
adb logcat | grep "Get friend lists"
```

App logs, including the SDK's, are written to the app's files directory and can be read on a debuggable build:

```sh
adb exec-out run-as com.netmatters.nmpbx cat files/linphone1.log > linphone1.log
```
