# Contacts and Extensions Performance Investigation

Investigation date: 23 October 2026. App version 6.3.009 (release build in the morning, then a debuggable build installed at 09:49).

We were looking into slow loading of the Extensions and Contacts lists when there are a large number of contacts, plus a full crash seen once on a low-spec tablet during first-time use. This document records what we measured and what we think should change. No code changes had been made at the time of writing.

## Summary

| # | Finding | Impact | Status |
|---|---|---|---|
| 1 | `GenericActivity.getTheme()` re-applies the theme on every call | Main cause of slow list layout, and it gets worse the longer the app runs | Fixed |
| 2 | Extension avatar models leak on every list rebuild, each holding its base64 photo | Memory grows about 1.5MB per Extensions visit on the test server; most likely cause of the crash | Not fixed |
| 3 | First CardDAV sync of about 5,000 vCards takes 2.5 minutes | Lists are empty until it completes | Mostly inside the SDK |
| 4 | Presence is subscribed for about 3,500 external phone numbers | Privacy concern and extra presence load | Needs a decision |
| 5 | Rows rebuilt twice per load; repeated native calls in sorting | Smaller CPU costs on main and core threads | Not fixed |

The crash itself was not reproduced. The test tablet had no record of an app crash, ANR or native crash.

## Test setup

- Device: Oukitel RT1 tablet, Android 11, 1200x1920, about 4GB RAM, app heap limit 256MB.
- Account: extension 5 on `nm-test.nmpbx.uk`.
- CardDAV address book: 5,028 vCards, made up of about 5,000 external contacts and 23 extensions. The extensions have inline base64 JPEG photos (`PHOTO;VALUE=URI:data:image/jpeg;base64,...`).
- READ_CONTACTS denied, so the phone's own address book is not imported. Everything comes from CardDAV.

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

**Suggested fix:** destroy the previous list's models when a new list is built and in `onCleared()`, or cache and reuse models per friend so listeners are only registered once. Separately, consider not keeping the full base64 photo string in each model.

### 3. First CardDAV sync takes 2.5 minutes

Timeline from `files/linphone1.log` and `files/linphone2.log` after first-time provisioning:

| Phase | Time | Duration |
|---|---|---|
| Provisioning applied, sync started | 09:50:43 | |
| List the address book | 09:50:43 to 09:50:48 | 5s |
| Download 5,028 vCards | 09:50:48 to 09:51:28 | 40s |
| Parse them | 09:51:28 to 09:51:29 | 1s |
| Create each friend and insert it into the database | 09:51:30 to 09:53:12 | 102s, about 20ms per contact |
| Sync reported successful | 09:53:12 | |

The core thread used 88 seconds of CPU in the first four minutes. The slow phase is inside the linphone SDK (`liblinphone` 5.5.16), which creates and stores friends one at a time. The lists stay empty until the sync completes.

The log files reached 20MB in the first 8 minutes, as the SDK logs at MESSAGE level including full SIP bodies. We have not measured how much of the core thread's time that costs.

**Options:**
- Check whether a newer SDK version batches these inserts.
- Show a "syncing contacts" state so the lists don't look empty or broken during first sync.
- Consider a lower SDK log level in release builds, after measuring the effect.

### 4. Presence subscriptions include external phone numbers

`android-presence-feature-spec.md` assumes the CardDAV address book is the PBX directory of internal extensions, so subscriptions are enabled for that whole friend list (`ContactsManager.applySubscriptionPolicy`). On the test server the same address book also holds the 5,000 external contacts.

The aggregated RLS SUBSCRIBE therefore lists 3,512 unique resources. Only 14 of them look like extension numbers. The rest are external numbers such as `sip:01514960757@nm-test.nmpbx.uk;user=phone`. This is the privacy problem the spec says we should avoid, and it makes every subscription and full-state NOTIFY much larger than it needs to be.

After each successful sync, `ContactsManager` also toggles subscriptions off and on to force a fresh SUBSCRIBE, which repeats this every time.

**Needs a decision:** either the server keeps extensions in a separate address book, or the app turns off presence for friends that are not extensions (for example `friend.isSubscribesEnabled = false` for external entries).

### 5. Smaller costs

- `ContactDiffCallback.areContentsTheSame` (`ContactsListAdapter.kt`) always returns `false`. Combined with the default item animator, every visible row is recreated when the full list replaces the first 20-item slice, so each load has two expensive layout passes.
- `processMagicSearchResults` sorts with a comparator that calls `getResultName` (two native calls) on each comparison, and `isInternalResult` reads `friend.addresses` for every result. During a tab switch this was about 40% of the core thread's time. Computing the name and the extension check once per result would remove most of it.
- Every tab switch builds a new fragment, view model, Magic search and full set of row views. Keeping the list state between tabs would avoid repeating that work.
- `ContactAvatarModel.getAvatarUri` returns `null` for CardDAV friends without a photo, and `picturePath` then holds the string `"null"`. The image loader tries and fails to load it before falling back to initials.

### Already ruled out on this device

The phone address book import (`ContactLoader.parseFriends`) checks every contact's photo with `openAssetFileDescriptor` and compares vCard dumps for each contact. That could be slow with a large phone address book, but it did not run in these tests because READ_CONTACTS was denied.

## Recommended order of work

1. Fix the theme re-application (finding 1). Small change, biggest effect on list speed.
2. Fix the avatar model leak (finding 2). Contained change, addresses the memory growth and the likely crash.
3. Fix the double row rebuild and the per-result native calls (finding 5).
4. Decide how to keep external numbers out of presence subscriptions (finding 4).
5. Look at first-sync options (finding 3).

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

App logs, including the SDK's, are written to the app's files directory and can be read on a debuggable build:

```sh
adb exec-out run-as com.netmatters.nmpbx cat files/linphone1.log > linphone1.log
```
