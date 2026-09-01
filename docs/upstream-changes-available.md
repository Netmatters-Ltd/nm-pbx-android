# Upstream Changes Available But Not Taken

Assessed against upstream `linphone-android` as of 20 August 2026. This is the
companion to [upstream-changes-pulled-in.md](upstream-changes-pulled-in.md),
which records what we did take.

## Upstream's branch layout

| Branch | Latest | Last activity | Notes |
|---|---|---|---|
| `release/6.0` | 6.0.23 | 2 February 2026 | Our base. No longer maintained. |
| `release/6.2` | 6.2.6 | 20 August 2026 | Current maintained release |
| `master` | 6.3.0-alpha | 18 August 2026 | Pre-release |

We forked from `release/6.0` at 6.0.21. We are 614 commits behind
`release/6.2`.

## What is left on the 6.0 line

Nothing of substance. We have taken the only functional change we were missing,
the telecom manager availability fix. The remaining four commits between our
base and the 6.0.23 tag are version-code bumps, changelog entries, and Weblate
translation updates for German, Dutch and Polish.

Those translation updates are worth ignoring rather than deferring. We have
reworked a good deal of the string resources during rebranding, so importing
upstream translations wholesale would reintroduce Linphone naming.

## Why we are not rebasing onto 6.2

This is a deliberate decision, not a backlog item.

Beyond the 614-commit gap, the 6.1 and 6.2 work includes several sweeping UI
reworks that would collide directly with the changes we have made. Among them:

- "Reworked layout to improve coherence & display on large screen in landscape"
- "Improved & uniformized UI"
- "Reworked UI for dialogs buttons"
- "Using a style for top bar icons to prevent duplicating code"
- "Added pressed/hover effect to icons that can be clicked"

Our own work touches most of the same surface: rebranding, the theme update
(4999073), the settings restructure and permissions rework (5000572), removal
of the side menu, hiding inactive features, and the contacts split into
extensions and external (5071291). Merging the two would mean re-deciding
almost every one of those choices.

Moving to 6.2 is a project to be scoped and estimated on its own, not something
to fold into a maintenance branch. All time noted below is estimated, on a time
and materials basis.

## SDK versions beyond 5.5.16

We are now pinned to `5.5.16`. Later stable releases on the 5.5 line can be
taken as they appear by editing the single version in
`gradle/libs.versions.toml`, and are low risk.

SDK 5.6 exists only as alpha builds (`5.6.0-alpha.63` at the time of writing)
and should be left alone until it reaches a stable release.

Note for whoever picks this up next: keep the version pinned exactly. Do not go
back to a `5.5.+` style range. That is what caused us to silently resolve to a
pre-release build on the 5.4 line.

## Candidate changes on the 6.2 line

These are call-quality and call-handling fixes that our 6.0 base will never
receive, because upstream stopped maintaining 6.0. Several overlap with work we
already have in flight.

Important caveat that applies to all of them: **none of these cherry-pick
cleanly.** We dry-ran every one against our tree and all of them conflict, which
is expected after eighteen months of divergence. The conflicts are generally
context drift rather than genuine disagreement, so the smaller ones are
straightforward to port by hand. The larger ones are not worth attempting
piecemeal.

### Worth porting by hand

**No audio when the device is close to the ear on Samsung S23 family**
Upstream `a773eace6`, released in 6.2.4. Seven insertions in
`TelecomManager.kt`. Adds a device allow-list (`dm3q`, `dm1q`, `dm2q`, `r11s`,
`r11q` and several Japanese carrier models) and forces
`CALL_TYPE_AUDIO_CALL` for them even on video calls. Our `TelecomManager.kt`
has the same call-type block, just with the if and else inverted, so this is a
small and safe port. Highest value-to-effort ratio of anything on this list,
given how common those handsets are.

**Answered early-media call using speaker instead of earpiece**
Upstream `37dae2bc6`, released in 6.2.4. Fourteen insertions in
`CoreContext.kt`. When a call reaches Connected having previously been in
`IncomingEarlyMedia` with ring-during-early-media enabled, it explicitly routes
audio back to the earpiece. Worth checking whether we actually enable ring
during early media before spending time on it, since the fix only fires in that
configuration.

**Automatically use headphones or headset when available**
Upstream `b65f3aa0e`, released in 6.2.1. Two files. Extends the existing
"route audio to bluetooth when possible" behaviour to wired devices. Our tree
already has the bluetooth routing call site this builds on.

### Larger, assess separately

**CallActivity not visible when answering from the notification**
Upstream `6e12cdbea`, released in 6.2.6, plus related work in `42fbbc51f`.
Six files, four of which conflict. This is an Android notification trampoline
restriction, and the fix reworks `NotificationsManager` to use a `PendingIntent`
with an `ActivityOptions` bundle. Directly relevant to our push notification
work (5000568), so worth reading before we do more there, but it is entangled
with a broader notifications refactor rather than being a standalone fix.

**Local network access permission on Android 17**
Upstream `6115c5591`, released in 6.2.4. Eleven files, eight of which conflict.
Requests the new Android 17 local network permission in the assistant and on
account registration failure. Relevant to us because a PBX on the local network
is a real deployment case, and because we have just reworked the permissions
screens in 5000572. The conflicts are mostly in the assistant and settings
flows we have already changed, so this needs porting deliberately rather than
cherry-picking.

**Proximity sensor setting**
Upstream `8a7fbd926` and related commits, released in 6.2.3. Six files, five of
which conflict. Adds a setting to disable the proximity sensor turning the
screen off during audio calls, plus fixes for the sensor not re-enabling after
the app is resumed from a call notification. Only worth doing if users report
the behaviour as a problem.

### Not applicable to us

**DTMF tone playing indefinitely**
Upstream `53e59d8f5`, released in 6.2.1. A one-line change adding
`MotionEvent.ACTION_CANCEL` alongside `ACTION_UP` in a `setTouchListener`
binding adapter. That adapter does not exist in our tree, having been added
upstream after our fork point. Our numpad calls `coreContext.playDtmf` as a
one-shot on click rather than holding a tone open between press and release, so
the underlying bug does not appear to be reachable in our build. Worth
re-checking only if we change the numpad to a press-and-hold model.

**Transfer layout constraint and default account list refresh**
Upstream `973398307`, released in 6.2. Five files, two of which conflict. Moves
the search progress spinner inside the results container in
`call_transfer_fragment.xml`, and refreshes the contacts, history and meetings
lists when the default account changes. The layout half conflicts with our
version of that file and the list-refresh half touches screens we have
reworked. Low value for the effort. We reviewed this alongside the transfer
fixes we did take and decided against it.

## Transfer work: current status

For the avoidance of doubt, the transfer differences against iOS and desktop
that prompted this review are now addressed. We took all three genuinely
missing transfer fixes, including the transfer icon in the ongoing calls list.

Everything else transfer-related from upstream's 2025 work was already in our
base, having been backported into 6.0.x under different commit hashes. We
verified the content is present for the dialpad floating action button, the
numpad dial button fix, blind and attended transfer logging, the
`automaticallyShowDialpad` behaviour on the transfer screen, and the guard
against transferring a call in the Ended, Error or Released state.

`973398307` above is the only remaining transfer-adjacent commit, and we are
recommending against it.
