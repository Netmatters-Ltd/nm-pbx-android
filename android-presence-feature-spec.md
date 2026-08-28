# Android Feature Specification: Header, Presence & Status Display

This document describes five features implemented in the iOS app that need to be replicated in the Android app. Both apps use the Linphone SDK and have near-identical visual structure, so SDK calls and most logic map directly. Where iOS uses SwiftUI idioms, equivalent Android/Compose or View-based patterns are noted.

### Shared reference: fonts and colours

These apply throughout the features below, so they are listed once here.

**Font family**
All presence/header text uses the **Poppins** font family (the app's standard typeface):
- `Poppins-Regular` for body and status text.
- `Poppins-SemiBold` for the picker's "Status" section heading.

Match this on Android (bundle the Poppins font family and apply it to these views) rather than the system default.

**Colour palette** (exact hex values from the iOS app, `ColorExtension.swift`)

| Token | Hex | Used for |
|---|---|---|
| `nmpbxSecondary` | `#0E2826` | Header bar background (dark teal/navy) |
| `greenSuccess500` | `#4FAE80` | Available status |
| `orangeAway` | `#FFA645` | Away status |
| `blueInfo500` | `#4AA8FF` | Busy status |
| `redDanger500` | `#DD5F5F` | Do Not Disturb status |
| `grayMain2c400` | `#9AABB5` | Offline (badge not shown), placeholders, char count, muted note text |
| `grayMain2c500` | `#6C7A87` | "Custom status message" field label |
| `grayMain2c700` | `#364860` | Picker status-row label text |
| `grayMain2c800` | `#22334D` | Picker "Status" heading text |
| `grayMain2c100` | `#EEF6F8` | Picker note input background |
| `orangeMain500` | (theme primary orange) | Active-status checkmark in the picker |

---

## 1. Replace Hamburger Menu with Direct Settings Button

### What changed
The hamburger (three-line) menu icon in the top-left of the main screen header was replaced with a gear/cog icon. Tapping it navigates directly to the Settings screen rather than opening a side drawer or dropdown menu.

### iOS implementation
- Icon: `Image("gear")` — a gear/cog asset, rendered as a template (tintable), white, 25×25 pt.
- Tap action: sets a state flag `isShowSettingsFragment = true`, which triggers a slide-in settings fragment using `withAnimation`.
- The hamburger menu, its associated `isMenuOpen` state, and the drawer/overlay it controlled are entirely removed from this button's position. (Other menu-like elements elsewhere in the screen may remain; only the top-left header button changed.)

### Android guidance
- Replace the hamburger `ImageButton`/`MenuItem` in the top-left of the main `Toolbar` or `AppBar` with a gear icon (`ic_settings` or equivalent from Material Icons: `Settings`).
- The tap handler should call `findNavController().navigate(R.id.settingsFragment)` (or equivalent) — a direct, unconditional navigation to the Settings destination, with no drawer toggle or menu display.
- Remove any `DrawerLayout` toggle logic that was previously attached to this button. The navigation drawer (if used) can remain accessible via swipe if other UI requires it, but this button should no longer open it.
- Apply white tint to match the header style.

---

## 2. Header: Active Extension Name and Status Display

### What changed
The static screen title text ("Contacts", "Calls", etc.) was removed from the centre/left of the header. In its place, the existing avatar circle (already present for the logged-in account) is now accompanied by the extension's display name and current presence status, displayed as a two-line block to the right of the avatar.

### Visual layout (left to right in the header bar)
```
[Gear icon]  [Avatar circle + presence badge]  [Display name (line 1)]
                                                [Status label · Custom note (line 2)]
                                                                        [Search icon] [...]
```

### Avatar circle
- Size: 45×45 dp/pt, clipped to a circle.
- Shows the account's profile image if available; falls back to a generated initials image (same `textToImage` utility already used for contacts).
- A small presence status badge is overlaid at the **bottom-trailing** corner (see below).
- **Tapping the avatar opens the presence picker** (see Section 3).

### Presence badge on the avatar
- A filled circle, diameter = `avatarSize / 4` ≈ 11 dp/pt.
- White stroke/border of 1.5 dp/pt.
- Fill colour is the current presence status colour (see colour table in Section 5).
- Positioned bottom-trailing, offset 1 dp/pt from each edge of the avatar.
- **Only shown when the current status is not Offline.** When the user has no presence information yet or is offline, no badge is rendered.

### Text block (VStack / vertical LinearLayout to the right of the avatar)
**Line 1 — Display name**
- Source: `account.displayName` (the SIP display name for the default registered account, e.g. "Sam Driver").
- Font: Regular, 16 sp.
- Colour: **White.** (This is different from the contacts list, which uses the app's primary orange or dark colours. The header sits on a dark brand-colour background, so everything here is white.)
- Single line.

**Line 2 — Status and custom note**
- Only shown when the current presence status is **not** Offline.
- Two text spans in a horizontal row (HStack / horizontal LinearLayout), spacing 4 dp/pt:
  1. **Status label** — e.g. "Available", "Away", "Busy", "Do Not Disturb". Font: Regular, 11 sp. Colour: **White** (unlike the contacts list, where status text is tinted in the status colour — in the header everything is white to contrast with the dark background).
  2. **Custom note** (optional) — shown only if the custom note string is non-empty. Text: `"· <note text>"`. Font: Regular, 11 sp. Colour: **White**. Truncated to a single line.
- When no custom note is set, only the status label is shown.

### Header background
The header bar uses the app's `nmpbxSecondary` brand colour (a dark blue/navy). White text is used throughout the header for contrast.

### Android guidance
- Remove the `TextView` or `Toolbar.title` that previously showed "Contacts" / "Calls".
- In its place, add a horizontal layout containing:
  1. The existing circular account avatar `ImageView`, made tappable.
  2. A `FrameLayout` wrapping the avatar with a small `View` (circle shape drawable) badge overlaid at bottom-right, visibility governed by presence state.
  3. A vertical `LinearLayout` with two `TextView`s as described above.
- Observe the `PresenceViewModel` (or equivalent) for `currentPresence` and `customStatusNote` changes and update the badge colour, status label, and note text reactively.
- The display name comes from the SDK's `account.displayName()` method on the default account (`core.defaultAccount?.displayName()`), **not** from `identityAddress.displayName`. The iOS app caches this in its `AccountModel`; `account.displayName()` falls back through the account's configured display name automatically. Use the same SDK call on Android.
- Implementation detail from iOS: the display name text uses `Poppins-Regular` 16 sp white; the status label and note both use `Poppins-Regular` 11 sp white. The badge diameter is `avatarSize / 4` (≈ 11 dp for a 45 dp avatar) with a 1.5 dp white stroke, offset 1 dp from the bottom and trailing edges.

---

## 3. Presence Picker: Tap Avatar to Set Status

### What changed
Tapping the avatar in the header opens a floating picker panel. On iOS this is presented as a `.popover` (iOS 16+: with a `.medium` detent, displayed as a half-height sheet). On Android the closest equivalent is a `BottomSheetDialogFragment` or a `PopupWindow` anchored to the avatar.

### Picker contents
The picker has a "Status" section heading followed by two sections separated by a divider:

**Section 1 — Status list**
Four selectable status options rendered as a vertical list (Offline is intentionally excluded):

| Status | Badge colour |
|---|---|
| Available | Green (`greenSuccess500` = `#4FAE80`) |
| Away | Orange (`orangeAway` = `#FFA645`) |
| Busy | Blue (`blueInfo500` = `#4AA8FF`) |
| Do Not Disturb | Red (`redDanger500` = `#DD5F5F`) |

The "Status" section heading uses `Poppins-SemiBold` 16 sp in `grayMain2c800` (`#22334D`).

Each row contains:
- A small filled circle (14 dp diameter, white stroke 1.5 dp) in the status colour.
- The status label text (`Poppins-Regular`, 14 sp, `grayMain2c700` = `#364860`).
- A trailing checkmark icon in the app's primary orange (`orangeMain500`), visible only on the currently active status.

Tapping a row immediately calls `PresenceViewModel.setPresence(presence, note: currentNote)` and dismisses the picker. The note field is preserved when only the status changes.

**Section 2 — Custom status message field**
Below the divider, a labelled multi-line text input:
- Label: "Custom status message" (`Poppins-Regular` 12 sp, `grayMain2c500` = `#6C7A87`).
- The input itself is a multi-line field (iOS uses a `TextEditor`, min height ≈ 88 dp) with a `grayMain2c100` (`#EEF6F8`) background and 8 dp rounded corners.
- Input hint/placeholder: "What's your status?" (`Poppins-Regular` 14 sp, `grayMain2c400` = `#9AABB5`).
- Entered text: `Poppins-Regular` 14 sp, `grayMain2c700` (`#364860`).
- Maximum length: **80 characters** (enforce in the text watcher / input filter).
- A character count indicator is shown below the field (e.g. `"12/80"`), right-aligned, `Poppins-Regular` 11 sp, `grayMain2c400` (`#9AABB5`).
- A clear button (×) appears inside the field's top-right corner when text is present (iOS uses an `xmark.circle.fill` glyph tinted `grayMain2c400`); tapping it clears the note and immediately calls `setPresence` with an empty note.
- Pressing the keyboard Done/confirm action calls `setPresence` with the current status and note, then dismisses.
- On dismiss (picker closed by any means), `setPresence` is called with the current values to ensure any typed note is saved even if the user did not press Done.

### What `setPresence` does
1. Updates the in-memory `currentPresence` and `customStatusNote` on the main thread immediately (so the header updates without waiting for a network round-trip).
2. Persists both values to the app's config store:
   - Key `"presence_status"` → the string raw value of the status enum (e.g. `"online"`, `"away"`, `"busy"`, `"doNotDisturb"`).
   - Key `"presence_note"` → the note string.
3. Calls `publish(presence, note, core)` on the SDK queue (see Section 4 for the PIDF details).

Note: `publish()` checks a `"publish_presence"` boolean config key (section `"app"`, default `true`). If this is set to `false` by an administrator, presence is never published to the server. The UI state still updates locally.

### Android guidance
- Implement as a `BottomSheetDialogFragment` or a `PopupWindow` anchored to the avatar view.
- Inflate a layout with a `RecyclerView` (or static `LinearLayout`) for the four status rows, a `View` divider, and a multi-line `EditText` with 80-char `InputFilter` and a live character count `TextView`.
- Each status row click calls the equivalent of `setPresence` and dismisses.
- Observe dismiss to flush any pending note.

---

## 4. App Startup: Pre-Populating Presence Status

Presence is restored in two stages so the app publishes the correct status as soon as possible and also picks up any changes made on other devices.

### Stage 1 — Before registration (core init)

Called immediately after `core.start()` in the core initialisation sequence.

```
PresenceViewModel.loadSavedPresence()
```

This function:
1. Reads `"presence_status"` and `"presence_note"` from the app config (defaulting to `"online"` / `""` if absent).
2. Updates the UI state (`currentPresence`, `customStatusNote`) on the main thread.
3. Calls `publish(presence, note, core)` to set `core.presenceModel` **before** registration completes, so when the Linphone SDK fires its automatic `PUBLISH` on successful registration, it sends the saved status rather than the SDK default.

This means the very first `PUBLISH` the server receives after a restart already carries the correct status.

### Stage 2 — After successful registration

Called from the `onAccountRegistrationStateChanged` callback when state reaches `OK`:

```
PresenceViewModel.restoreOrGoOnline()
```

**Before** calling `restoreOrGoOnline()`, the registration callback does two things on the `.Ok` state:
1. Calls `ContactsManager.fetchContacts()` to refresh the contact/friend list.
2. Re-enables publishing if it was switched off: when the app was last backgrounded it set `account.params.publishEnabled = false` (see lifecycle below). On the next successful registration it clones the params, sets `publishEnabled = true`, and re-assigns them, so the account can publish again. Replicate this re-enable step on Android, otherwise presence will silently stop publishing after the first background/foreground cycle.

This function handles multi-device sync. The logic:

1. Resets a `didSyncFromServer` guard flag to `false`.
2. Removes any leftover `FriendDelegate` from a previous registration cycle (prevents duplicate callbacks if the account re-registers).
3. Looks up the account's own SIP address in the contact/friend list (`core.findFriend(address: ownAddress)`). If the own extension is registered as a contact (which it normally is on a PBX), the SDK will have a presence subscription for it and will receive a `NOTIFY` carrying whatever presence the server last stored for that identity.
4. Registers a **one-shot** `FriendDelegate` on the self-friend entry listening for `onPresenceReceived`. When it fires:
   - The guard flag is set to prevent re-entry.
   - The delegate removes itself immediately.
   - `applyServerPresence(from: friend, core: core)` is called.
5. A **2-second fallback timer** is started. If no `NOTIFY` arrives within 2 seconds (server has no stored state, or own extension is not in the contact list), the timer fires, checks the guard flag, and calls `publishLocalConfig(core: core)` to re-publish the locally saved values.
6. If the own address is not found in the contact list at all, `publishLocalConfig` is called immediately (no waiting).

#### `applyServerPresence` logic

1. Checks `friend.consolidatedPresence`. If it is **Offline** (i.e. the server's stored state is `basic=closed` or a `permanent-absence` activity), falls back to `publishLocalConfig` — an offline server state means all previous sessions ended; restore from local config.
2. Otherwise reads `friend.presenceModel?.activity?.type` and maps it via `UserPresence.from(activityKind:description:)` (see mapping table in Section 5) to determine the server's current status.
3. **Available race condition guard**: If the server returns plain Available (`consolidatedPresence == .Online` with no RPID activity), but the locally saved status is a more specific status (Away, Busy, or Do Not Disturb), the local config is used instead. This prevents a race condition where the server still has the SDK's automatic "online" registration tuple from before our own `PUBLISH` (with the user's actual status) has been processed.
4. **Busy fallback**: If `consolidatedPresence == .Busy` but no parseable RPID activity was found, the status remains `.busy` rather than incorrectly defaulting to Available.
5. Reads the custom note via `friend.presenceModel?.getNote(lang: nil)?.content`. If empty, falls back to the locally saved note (the note may not survive the server round-trip for plain Available presence; local fallback prevents it being silently erased).
6. Persists the resolved status and note back to config (so they survive the next restart).
7. Updates the UI state on the main thread.
8. Calls `publish(presence, note, core)` to confirm/refresh the presence publication.

#### Background/foreground lifecycle

- **Entering background** (app moves to background, no active call): The app sets `account.params.publishEnabled = false` and calls `core.stop()`. Setting `publishEnabled = false` causes the Linphone SDK to send a `PUBLISH` with `Expires: 0`, which tells the presence server to immediately remove the stored publication. This prevents stale tuples accumulating on the server across app restarts.
- **Entering foreground**: `core.start()` is called, which triggers re-registration, which calls `restoreOrGoOnline()` as in Stage 2 above.

---

## 5. Contact Presence Display

### Where it appears
Presence information is shown on contact rows in the contacts list, the conversation participant list, and call history contact fragments — but **only for contacts that the app actually subscribes to** (see the next subsection).

### Which contacts get a presence subscription (important)

The app does **not** subscribe to presence for every contact. Only contacts provisioned from the **CardDAV** address book (the PBX directory of internal extensions) have SIP `SUBSCRIBE` enabled. Both of the following lists have their subscriptions explicitly disabled (`friendList.subscriptionsEnabled = false`):
- The **local device/phone** contacts list (iOS native address book). These contacts are not registered on the presence server, and subscribing would leak their phone numbers off-device.
- The **manually-added NMPBX** contacts list (the app's own non-CardDAV friend list).

So in practice, presence badges and status lines appear only on internal PBX extensions (CardDAV contacts), never on imported phone contacts or manually-added ones. Those rows render with no badge and no status line, exactly as if they were Offline.

**Android guidance:** when building the friend lists, call `friendList.subscriptionsEnabled = false` on the device-contacts list and on any manually-added list, and leave it enabled only on the CardDAV-provisioned list. This both matches the iOS visual behaviour and preserves the privacy guarantee (no `SUBSCRIBE` is sent for off-server contacts). It is set per friend-list, not per friend.

### Data source
Each contact is backed by a `ContactAvatarModel` (observable/reactive object) which holds:
- `presenceStatus: ConsolidatedPresence` — the SDK-level status (Online, Busy, Offline).
- `presenceActivity: PresenceActivity.Kind?` — the specific RPID activity kind (Away, Busy, Other, etc.), or `nil` for Available/Offline.
- `presenceNote: String` — the custom status message text (may be empty).
- `lastPresenceInfo: String` — a human-readable "last seen" string. The exact iOS formats (en locale; `fr_FR` uses 24-hour and `dd/MM`):
  - Currently online → `"Online"`
  - Earlier today → `"Online today at h:mm a"` (e.g. `"Online today at 2:30 PM"`)
  - Yesterday → `"Online yesterday at h:mm a"`
  - Earlier this year → `"Online on MM/dd | h:mm a"`
  - Older → `"Online on MM/dd/yy | h:mm a"`
  - Offline → empty string.

These are populated on initial load from `friend.consolidatedPresence`, `friend.presenceModel?.activity?.type`, and `friend.presenceModel?.getNote(lang: nil)?.content`, then kept live by a `FriendDelegate` (`onPresenceReceived` callback) which updates all four properties whenever a `NOTIFY` arrives for that contact.

### `presenceUserStatus` mapping

The raw SDK values are mapped to a single display-level `UserPresence` enum. Note that a person-level RPID activity is honoured even when `consolidatedPresence` is `.Online`:

| `consolidatedPresence` | `presenceActivity` | Display status |
|---|---|---|
| `.Online` | `.Away` | **Away** |
| `.Online` | `.Busy` | **Busy** |
| `.Online` | `.Other` | **Do Not Disturb** |
| `.Online` | anything else / nil | **Available** |
| `.Busy` | `.Away` | **Away** |
| `.Busy` | `.Busy` | **Busy** |
| `.Busy` | `.Other` | **Do Not Disturb** |
| `.Busy` | anything else / nil | **Busy** (fallback) |
| `.Offline` (or any other) | (any) | **Offline** |

### Colour and label table

| Status | Label | Badge/text colour |
|---|---|---|
| Available | "Available" | Green (`greenSuccess500`) |
| Away | "Away" | Orange (`orangeAway`) |
| Busy | "Busy" | Blue (`blueInfo500`) |
| Do Not Disturb | "Do Not Disturb" | Red (`redDanger500`) |
| Offline | — | Grey (badge not shown) |

### Row layout (contacts list)

```
[Avatar 50dp circle]   [Contact name                    ]
  + [badge 12dp]       [Status label  ·  Custom note    ]
```

**Avatar badge**
- Small filled circle, diameter = `avatarSize / 4` ≈ 12 dp, with a 1.5 dp white stroke.
- Overlaid at the **bottom-trailing** corner of the avatar circle.
- Fill = the status colour from the table above.
- **Not rendered at all when status is Offline** (the `presenceUserStatus != .offline` guard controls visibility).

**Text below the contact name**
- Rendered only when status is **not** Offline.
- Horizontal row with 4 dp spacing:
  1. **Status label** — Regular, 11 sp, coloured in the status colour (e.g. green for Available, orange for Away).
  2. **Custom note** (optional) — shown only if non-empty. Text: `"· <note>"`. Regular, 11 sp, muted grey (`grayMain2c500`). Single line, truncated with ellipsis.
- When the status is Offline, this row is hidden entirely (no "Offline" text is shown; the contact simply has no badge and no status line).

### PIDF publishing details (for receiving compatibility)

When our app publishes presence, the resulting PIDF structure the receiving side should expect:

**Available, no note**
```xml
<tuple id="...">
  <status><basic>open</basic></status>
  <contact>sip:...</contact>
  <timestamp>...</timestamp>
</tuple>
<!-- No dm:person element -->
```
`consolidatedPresence` on receiver → `Online`.

**Available with note**
```xml
<tuple id="...">
  <status><basic>open</basic></status>
  <contact>sip:...</contact>
  <note xml:lang="en">Custom note here</note>
  <timestamp>...</timestamp>
</tuple>
<!-- No dm:person / no rpid:activities -->
```
The note is inside `<tuple>`, not inside `<dm:person>`. It is accessible via the SDK's `presenceModel.getNote(lang: null)`. `consolidatedPresence` → `Online`.

Implementation note: the SDK's `createPresenceModelWithActivityAndNote(.Unknown)` is used to get the note placed at the correct level, then `model.clearActivities()` is called immediately to remove the `.Unknown` activity — ensuring no `<rpid:unknown/>` element is published and `consolidatedPresence` resolves to `Online` (not `Busy`) on the subscriber.

**Away / Busy with note** (same structure, different activity)
```xml
<tuple id="...">
  <status><basic>open</basic></status>
  <contact>sip:...</contact>
  <note xml:lang="en">Custom note here</note>
  <timestamp>...</timestamp>
</tuple>
<dm:person id="...">
  <rpid:activities>
    <rpid:away/>         <!-- or <rpid:busy/> -->
  </rpid:activities>
  <dm:timestamp>...</dm:timestamp>
</dm:person>
```
`consolidatedPresence` → `Busy`. `activity.type` → `Away` or `Busy`.

**Do Not Disturb with note**
```xml
<dm:person id="...">
  <rpid:activities>
    <rpid:other description="dnd"/>
  </rpid:activities>
</dm:person>
```
`activity.type` → `Other`. The description string `"dnd"` is the signal that this is Do Not Disturb rather than a generic "other" activity.

**Offline** (not user-selectable in the picker; only received from contacts)
```xml
<tuple id="...">
  <status><basic>closed</basic></status>
  <contact>sip:...</contact>
  <timestamp>...</timestamp>
</tuple>
```
`consolidatedPresence` → `Offline`. No activity, no note.

### Incoming `NOTIFY` parsing

When a `NOTIFY` arrives for a contact's presence subscription, extract:

```kotlin
val consolidatedPresence = friend.consolidatedPresence   // Online / Busy / Offline
val activityKind = friend.presenceModel?.activity?.type  // Away, Busy, Other, PermanentAbsence, Unknown, null
val activityDescription = friend.presenceModel?.activity?.description  // "dnd" for DnD, else null
val note = friend.presenceModel?.getNote(null)?.content ?: ""
val latestActivityTimestamp = friend.presenceModel?.latestActivityTimestamp ?: -1L
```

Map to display status using the `presenceUserStatus` logic in the table above. Update the contact row reactively (LiveData / StateFlow / equivalent).

Update `lastPresenceInfo` based on the timestamp:
- `consolidatedPresence == Online` → `"Online"`
- `consolidatedPresence == Busy` and `latestActivityTimestamp != -1` → `"Online today at h:mm a"` / `"Online yesterday at h:mm a"` / `"Online on MM/dd | h:mm a"` / `"Online on MM/dd/yy | h:mm a"` (formatted per locale; see the format list above)
- `consolidatedPresence == Busy` and `latestActivityTimestamp == -1` → `"Away"`
- `consolidatedPresence == Offline` → `""` (empty)

The `FriendListener` (`onPresenceReceived`) should be registered when a contact's row becomes active and deregistered when it is destroyed to avoid memory leaks. On Android this is typically done in the `ViewModel`'s `onCleared()` or a lifecycle-aware component.
