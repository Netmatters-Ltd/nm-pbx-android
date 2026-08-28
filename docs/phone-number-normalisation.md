# Phone Number Normalisation

## Problem

Device contacts often store phone numbers in E.164 international format (e.g. `+447771514661`). When this number is passed directly to the Linphone SDK's `core.interpretUrl()`, the resulting SIP address preserves the international format verbatim — e.g. `sip:+447771514661@pbx.example.com`.

The PBX expects numbers in local UK format with the national trunk prefix (`0`), e.g. `07771514661`. Numbers arriving in international format are not matched to any user, and the call fails with a "User has not been found" (SIP 404) error.

Numbers already stored in local format (e.g. `01953438555`) work correctly without any normalisation.

## Expected Behaviour

Before any phone number is passed to `core.interpretUrl()`, it must be normalised from international format to local format using the following logic.

### Normalisation rule

If the number begins with `+{prefix}` or `00{prefix}`, strip that prefix and prepend `0`.

**Examples (UK, prefix `44`):**

| Stored value      | Normalised value |
|-------------------|-----------------|
| `+447771514661`   | `07771514661`   |
| `00447771514661`  | `07771514661`   |
| `07771514661`     | `07771514661` *(unchanged)* |
| `01953438555`     | `01953438555` *(unchanged)* |

If the number does not begin with the resolved prefix in either form, it is passed through unchanged.

### Determining the prefix

The country calling code (e.g. `44` for the UK, without the leading `+`) is resolved in priority order:

1. **Account setting** — the international prefix configured on the user's Linphone account (`account.params.internationalPrefix`). This is set when the user configures a prefix in account settings, or when it is provisioned automatically.
2. **Device SIM / network country** — if no account prefix is set, read the device's network country ISO code (e.g. `"gb"`) from the telephony system and look up the corresponding calling code using Linphone's `Factory.dialPlans`.
3. **UK default** (`"gb"` / `44`) — if neither source yields a country (e.g. a Wi-Fi-only device with no SIM), assume the UK.

If a prefix is found but the number does not start with `+{prefix}` or `00{prefix}`, the number is returned unchanged — the rule is not applied forcibly.

### Where to apply

Normalisation must be applied at every point where a phone number string is converted to a SIP address via `core.interpretUrl()`. At minimum this covers:

- **Contact phone numbers** — when building the list of callable addresses from a contact's phone number entries.
- **Dial pad input** — when the user dials or blind-transfers a number typed or pasted into the dial pad.

The original stored phone number string should be preserved for display purposes. Only the value passed to `interpretUrl()` is normalised.

## Notes

- The `0` national trunk prefix is standard across the UK and most of Europe. It is not universal — NANP countries (US, Canada, etc.) do not use a trunk prefix — but since this product targets UK customers, `0` is the correct constant to prepend.
- The Linphone SDK does have a built-in `useInternationalPrefixForCallsAndChats` flag on account params, but it does not reliably perform the E.164-to-local conversion described here unless the account's `internationalPrefix` is already set. App-level normalisation before calling `interpretUrl()` is therefore more dependable.
- The `00{prefix}` form is included to handle users who manually type international numbers with a double-zero prefix rather than `+`.
