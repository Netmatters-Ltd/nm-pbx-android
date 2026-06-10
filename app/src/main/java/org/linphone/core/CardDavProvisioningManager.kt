/*
 * Copyright (c) 2010-2025 Belledonne Communications SARL.
 *
 * This file is part of linphone-android
 * (see https://www.linphone.org).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.linphone.core

import androidx.annotation.WorkerThread
import java.net.URI
import org.linphone.core.tools.Log

/**
 * Reads the `[carddav_provision]` section from the remote provisioning config and turns it into a
 * CardDAV [FriendList] (the PBX directory of internal extensions), pre-registering the HTTP Basic
 * credentials the NMPBX portal expects.
 *
 * Android port of iOS `CardDavProvisioningManager.swift`. The Linphone SDK does not consume the
 * `carddav_provision` section by itself, so without this nothing creates the friend list, no
 * contacts are fetched and (because no `rlsUri` is set) no presence SUBSCRIBE is ever sent.
 */
object CardDavProvisioningManager {
    private const val TAG = "[CardDAV Provisioning]"

    // Realm advertised by the NMPBX portal's CardDAV endpoint. Used when the provisioning XML
    // doesn't specify one, matching what a user would enter in the manual Settings > Contacts >
    // CardDAV form for this server.
    private const val DEFAULT_REALM = "NMPBX CardDAV"

    @WorkerThread
    fun applyIfPresent(core: Core) {
        val config = core.config
        if (config == null) {
            Log.e("$TAG core.config is null, skipping")
            return
        }

        val serverUrl = config.getString("carddav_provision", "server_url", "").orEmpty()
        if (serverUrl.isEmpty()) {
            Log.i("$TAG No carddav_provision section (or empty server_url), skipping")
            return
        }

        val username = config.getString("carddav_provision", "username", "").orEmpty()
        val password = config.getString("carddav_provision", "password", "").orEmpty()
        val configuredName = config.getString("carddav_provision", "display_name", "").orEmpty()
        val configuredRealm = config.getString("carddav_provision", "realm", "").orEmpty()
        val rlsUri = config.getString("carddav_provision", "rls_uri", "").orEmpty()
        val resolvedRealm = configuredRealm.ifEmpty { DEFAULT_REALM }

        val normalisedUri = if (serverUrl.startsWith("http://") || serverUrl.startsWith("https://")) {
            serverUrl
        } else {
            "https://$serverUrl"
        }

        val serverHost = try {
            URI(normalisedUri).host.orEmpty()
        } catch (e: Exception) {
            Log.e("$TAG server_url [$serverUrl] is not a valid URL: $e")
            ""
        }
        if (serverHost.isEmpty()) {
            Log.e("$TAG server_url [$serverUrl] has no valid host, skipping")
            return
        }

        val resolvedName = configuredName.ifEmpty { serverHost }

        // CardDAV servers fronted by the NMPBX portal use HTTP Basic, which requires the plaintext
        // password on the client. The SDK default converts plaintext to HA1 on save, which would
        // break Basic auth. Disable that for this Core.
        config.setInt("sip", "store_ha1_passwd", 0)

        // Pre-register an AuthInfo with the realm the server is known to advertise (see
        // DEFAULT_REALM). The SDK's CardDAV HTTP auth does not invoke the app-level
        // onAuthenticationRequested callback for Basic challenges in all cases; it relies on
        // findAuthInfo returning a stored entry before the request goes out. Matching this mirrors
        // what a user would enter in the manual CardDAV form (CardDavViewModel.addAddressBook).
        if (username.isNotEmpty() && password.isNotEmpty()) {
            // domain = serverHost so the manual CardDAV editor can find this AuthInfo back from the
            // FriendList URI. Doesn't affect Basic auth matching (the realm is what the SDK keys on
            // for the HTTP 401 lookup).
            val existing = core.findAuthInfo(resolvedRealm, username, serverHost)
            if (existing != null) {
                Log.i("$TAG Replacing existing auth info for [$username] realm=[$resolvedRealm] domain=[$serverHost]")
                core.removeAuthInfo(existing)
            }

            val info = Factory.instance().createAuthInfo(
                username,
                null,
                password,
                null,
                resolvedRealm,
                serverHost
            )
            if (info != null) {
                core.addAuthInfo(info)
                Log.i("$TAG Added auth info for [$username] realm=[$resolvedRealm] domain=[$serverHost]")
            } else {
                Log.e("$TAG Failed to create auth info for [$username] realm=[$resolvedRealm]")
            }
        } else {
            Log.w("$TAG Missing username or password, creating CardDAV list without auth")
        }

        val existingList = core.friendsLists.firstOrNull {
            it.type == FriendList.Type.CardDAV && it.uri == normalisedUri
        }
        val friendList = if (existingList != null) {
            existingList.displayName = resolvedName
            Log.i("$TAG Updating existing CardDAV friend list at [$normalisedUri] (name=[$resolvedName])")
            existingList
        } else {
            val created = core.createFriendList()
            created.type = FriendList.Type.CardDAV
            created.uri = normalisedUri
            created.displayName = resolvedName
            created.isDatabaseStorageEnabled = true
            core.addFriendList(created)
            Log.i("$TAG Created CardDAV friend list at [$normalisedUri] (name=[$resolvedName])")
            created
        }

        // The Flexisip presence server uses a Resource List Server (RLS) to aggregate
        // subscriptions. Without an rlsUri on the friend list the SDK falls back to individual
        // per-friend SUBSCRIBE, which the RLS-only server silently ignores, meaning no NOTIFY ever
        // arrives. Set it from the provisioned value if present.
        if (rlsUri.isNotEmpty()) {
            friendList.rlsUri = rlsUri
            Log.i("$TAG Set rlsUri=[$rlsUri] on [${friendList.displayName ?: normalisedUri}]")
        } else {
            Log.w("$TAG No rls_uri in [carddav_provision] - presence subscriptions will fall back to individual SUBSCRIBE (add rls_uri to provisioning XML to fix)")
        }

        // Do NOT call synchronizeFriendsFromServer() here.
        //
        // applyIfPresent() is called twice per session: once at GlobalState.On (with the cached
        // config) and again at ConfiguringState.Successful (after the remote XML is downloaded).
        // Starting a sync from each call (plus the one ContactsManager.onCoreStarted does) produces
        // concurrent syncs on the same FriendList. The liblinphone CardDAV engine has a shared state
        // machine per FriendList, so concurrent syncs corrupt its internal URL tracking and the
        // contacts come back empty.
        //
        // The friend list created/updated here is synced once by ContactsManager.onCoreStarted (and
        // again after RegistrationState.Ok via notifyContactsListChanged path), which is enough.
    }

    /**
     * Called from [CoreContext]'s onAuthenticationRequested when the SDK asks the app to supply
     * credentials for an HTTP Basic challenge (which CardDAV servers use). Populates the supplied
     * [authInfo] from the provisioning config and registers it with the Core so the in-flight
     * request can retry successfully. Returns true if credentials were applied.
     */
    @WorkerThread
    fun fulfillHttpBasicChallenge(core: Core, authInfo: AuthInfo): Boolean {
        val config = core.config ?: return false

        val username = config.getString("carddav_provision", "username", "").orEmpty()
        val password = config.getString("carddav_provision", "password", "").orEmpty()
        if (username.isEmpty() || password.isEmpty()) {
            Log.w("$TAG Basic auth challenged but no carddav_provision credentials configured")
            return false
        }

        val challengeRealm = authInfo.realm
        val challengeDomain = authInfo.domain

        authInfo.username = username
        authInfo.password = password
        core.addAuthInfo(authInfo)

        Log.i("$TAG Fulfilled Basic auth challenge (realm=[$challengeRealm], domain=[$challengeDomain]) for [$username]")
        return true
    }
}
