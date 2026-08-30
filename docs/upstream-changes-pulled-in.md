# Upstream Changes Pulled In

A running record of changes we have taken from the Linphone upstream repository
(`https://gitlab.linphone.org/BC/public/linphone-android.git`) since we forked.

Companion document: [upstream-changes-available.md](upstream-changes-available.md)
lists what we have looked at but not taken.

## Our position relative to upstream

We forked from upstream `release/6.0` at commit `5d584abe3` (9 January 2026),
which sits at version 6.0.21.

Upstream has since stopped work on the 6.0 line. Its final commit was
2 February 2026 (6.0.23). Active development is on `release/6.2` and `master`.
We are deliberately staying on our 6.0 base rather than rebasing onto 6.2. The
reasoning is recorded in the companion document.

Because upstream backported most 2025 fixes into 6.0.x, the majority of the
fixes we might want are already in our base. Each entry below was checked
against our tree before being taken, so nothing here is a duplicate of content
we already had.

## Changes taken

### Linphone SDK pinned to 5.5.16

| | |
|---|---|
| Our commit | `9f5042fb7` |
| Type | Dependency change, not an upstream cherry-pick |
| File | `gradle/libs.versions.toml` |

We were pinned to `5.4.+`. That dynamic version was resolving to
`5.4.125-pre.2+8266825343`, a pre-release build, because Gradle's `+` matching
has no concept of semver pre-releases and simply took the highest number it
found. Two problems followed from that. Builds were not reproducible, since the
SDK could change between builds with no commit in our repository, and we were
shipping an unreleased SDK build.

We now pin an exact version, `5.5.16`, which was the latest stable release on
the 5.5 line as of 20 August 2026. This keeps us on SDK major version 5, so it
is not a major-version jump.

Worth knowing: upstream moved from `5.4.+` to `5.5.+` on 11 March 2025, the same
day 6.0.0 was released and `release/6.0` was branched. SDK 5.5 was therefore
developed alongside app 6.1 and 6.2, never against 6.0. We confirmed it is
source-compatible with our tree, but see the testing note at the end.

### Telecom manager feature availability check

| | |
|---|---|
| Our commit | `724c810e2` |
| Upstream commit | `3fc19e10f` (2 February 2026, released in 6.0.23) |
| Files | `Api28Compatibility.kt`, `Api33Compatibility.kt`, `Compatibility.kt`, `TelecomManager.kt` |

We were testing for the raw feature string `android.software.telecom`, which
does not exist below Android 13. On those devices the check silently returned
false and we never used the Telecom Manager APIs at all.

The fix splits the check by API level: `FEATURE_CONNECTION_SERVICE` on Android
9 to 12, and `FEATURE_TELECOM` on Android 13 and above. Both paths now log which
feature was queried and whether it was found.

This is relevant to us because our minimum supported version is API 28
(Android 9), so a meaningful share of devices were affected. This was the only
functional change left on the upstream 6.0 line that we did not already have.

### Transfer: show a transfer icon in the calls list

| | |
|---|---|
| Our commit | `28038550c` |
| Upstream commit | `4ba4254e4` (3 April 2026, 6.2 line only) |
| Files | `CallsListAdapter.kt`, `TransferCallFragment.kt`, `call_list_cell.xml` |

This is the difference against the iOS and desktop apps that prompted this
piece of work. When you tap Transfer during a call and see the list of ongoing
calls to complete an accompanied transfer, each row showed the call state
(active or paused) rather than indicating that tapping it performs a transfer.

`CallsListAdapter` now takes a `showTransferIconInsteadOfCallState` flag.
`TransferCallFragment` passes it as true, which swaps the row icon for
`@drawable/phone_transfer` and hides the call state text. Everywhere else the
adapter is used is unaffected, as the flag defaults to false.

The `phone_transfer` drawable already existed in our tree, so nothing new was
needed for it.

### Transfer: clear the search filter when leaving the screen

| | |
|---|---|
| Our commit | `34ca996e5` |
| Upstream commit | `c1bb0ebad` (3 April 2026, 6.2 line only) |
| Files | `NewCallFragment.kt`, `TransferCallFragment.kt` |

The search filter was left populated when you navigated away from the transfer
or new call screens, so the previous search was still sitting there the next
time you opened them. Both fragments now reset `viewModel.searchFilter` in
`onPause`.

### Transfer: SIP address picker for contacts with several addresses

| | |
|---|---|
| Our commit | `1ca6d51b7` |
| Upstream commit | `e4ba2bb82` (24 April 2026, 6.2 line only) |
| Files | `TransferCallFragment.kt`, `NewCallFragment.kt`, `ContactNumberOrAddressModel.kt` |

The most significant of the three transfer fixes. When transferring to a
contact that has more than one SIP address or phone number, we went straight
to a blind transfer against the first address on the contact. A contact with
both a desk extension and a mobile would silently get whichever came first,
with no way to choose.

`TransferCallFragment` now mirrors what `NewCallFragment` already did for
placing calls. It gains a `startCallTransfer` method that checks whether the
contact resolves to a single address. If it does, the transfer proceeds
directly. If it does not, the number or address picker dialog is shown first
and the transfer runs against whichever entry you select.

Supporting changes: `ContactNumberOrAddressModel` exposes a `name` property so
the confirmation dialog can show who the transfer is going to, and
`NewCallFragment` renames its private `action` method to `startCall` for
clarity. All the machinery this relies on
(`LinphoneUtils.getSingleAvailableAddressForFriend`,
`NumberOrAddressPickerDialogModel`, `DialogUtils.getNumberOrAddressPickerDialog`)
was already present in our tree.

### Android 16 background activity launch and notification trampoline fixes

| | |
|---|---|
| Upstream commits | `42fbbc51f` (18 June 2025), `df94d6c2b` (18 August 2026), part of `892f51869` (18 August 2026) |
| Files | `Api36Compatibility.kt` (new), `Api33Compatibility.kt`, `Api34Compatibility.kt`, `Compatibility.kt`, `CoreContext.kt`, `NotificationsManager.kt`, `NotificationBroadcastReceiver.kt`, `CallActivity.kt`, `CurrentCallViewModel.kt`, `gradle/libs.versions.toml` |

Taken because we now target API 36. Android 16 changes what a `PendingIntent`
is allowed to do in the background. `MODE_BACKGROUND_ACTIVITY_START_ALLOWED` no
longer carries the creator's launch privileges, so `ALLOW_ALWAYS` is needed,
and the notification trampoline restriction blocks a notification action that
fires a broadcast receiver which then starts an activity.

We were using exactly that blocked pattern. The Answer button on the incoming
call notification was a broadcast into `NotificationBroadcastReceiver`, which
answered the call and left `CoreContext.showCallActivity()` to bring up the UI
with a bare `startActivity`. On API 36 that risks answering the call with audio
running but no call screen on top.

These fixes live on upstream's `master` and `release/6.2` lines only. They were
never backported to `release/6.0`, so we have brought them across by hand. The
answer action is now an activity `PendingIntent` built with `TaskStackBuilder`
that starts `CallActivity` directly and asks it to answer, and both it and
`showCallActivity()` carry an `ActivityOptions` bundle from the new
`Compatibility.getPendingIntentActivityOptions()`.

We also took upstream's non-functional drift in these files, so they sit closer
to upstream for the next pull: the named request code constants
(`INTENT_ANSWER_CALL_NOTIF_CODE`, `INTENT_HANGUP_CALL_NOTIF_CODE`), the rename
of `INTENT_REMOTE_ADDRESS` to `INTENT_REMOTE_SIP_URI`, and the intent builders
written as `.apply { }` blocks.

AGP moved from 8.9.0 to 8.9.1 in the same change. 8.9.1 is the first release
that properly supports `compileSdk = 36`, and it is what upstream moved to
alongside their own bump. Gradle 8.11.1 in the wrapper is unchanged.

Three deliberate deviations from upstream, all reviewed before implementation:

1. **The answer intent's caller is honoured.** Upstream puts the caller's SIP
   URI in the intent, then calls `answer()` and ignores it, so it answers
   whichever call it finds in an incoming state. That is still the case at
   upstream's tip and looks like an oversight. We call `answerCallFrom(caller)`
   instead, which matters on a PBX where a second call can arrive while the
   first is still ringing.
2. **The answer extra is handled in `onNewIntent` as well as `onCreate`.**
   `CallActivity` is `launchMode="singleTask"`. Upstream only checks the extra
   in `onCreate`, so answering from the shade while the call screen is already
   alive would do nothing. Our `onNewIntent` already handled the `ActiveCall`
   and `IncomingCall` extras, so this fits the existing pattern.
3. **The extra is removed once consumed.** `CallActivity` does not declare
   `configChanges`, so a rotation recreates it with the same intent. Without
   this, `onCreate` would try to answer again, find no incoming call and fire
   `finishActivityEvent`, closing the call screen mid-call.

## Verification so far

Done:

- Full `app:assembleDebug` succeeds with all of the above applied together.
- `app:ktlintMainSourceSetCheck` passes.
- `dependencyInsight` confirms the SDK resolves to exactly `5.5.16`, with no
  dynamic or pre-release version selected.

Still needed. A clean compile is not the same as correct runtime behaviour,
particularly for the SDK bump, since most of the SDK is native code that the
compiler never sees. The following should be exercised on real devices before
this is considered done:

- Account registration and re-registration
- Inbound and outbound calls
- Blind transfer and accompanied transfer, including the new icon in the
  ongoing calls list
- Transferring to a contact with more than one SIP address or number, from both
  the extensions list and the external contacts list. This path is worth extra
  attention because we reworked contacts handling in 5071291 and upstream did
  not have that split.
- Push notification wake-up
- Telecom Manager behaviour on an Android 9 to 12 device, which is the case the
  telecom fix actually changes
