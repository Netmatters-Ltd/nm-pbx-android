/*
 * Copyright (c) 2010-2023 Belledonne Communications SARL.
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
import androidx.lifecycle.MutableLiveData
import java.util.Locale
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.LinphoneApplication.Companion.corePreferences
import org.linphone.core.tools.Log

/**
 * Owns the user's own presence status and custom note, publishes it to the server as PIDF, and
 * handles multi-device sync on registration. It is a process-singleton owned by [CoreContext]
 * (mirrors contactsManager / notificationsManager), NOT an AndroidX ViewModel, because it must
 * outlive fragments and be reachable from the registration listener.
 */
class PresenceManager {
    companion object {
        private const val TAG = "[Presence Manager]"

        private const val CONFIG_SECTION = "app"
        private const val CONFIG_KEY_STATUS = "presence_status"
        private const val CONFIG_KEY_NOTE = "presence_note"

        private const val SERVER_SYNC_FALLBACK_DELAY = 2000L // 2 seconds
    }

    val currentPresence = MutableLiveData<UserPresence>()

    val customStatusNote = MutableLiveData<String>()

    private var selfFriend: Friend? = null
    private var selfFriendListener: FriendListener? = null

    @Volatile
    private var didSyncFromServer = false

    /**
     * Stage 1 of startup, called from [CoreContext.onCoreStarted] (before registration completes)
     * so the SDK's automatic PUBLISH on register already carries the saved status.
     */
    @WorkerThread
    fun loadSavedPresence() {
        val core = coreContext.core
        val presence = UserPresence.fromRaw(
            core.config.getString(CONFIG_SECTION, CONFIG_KEY_STATUS, UserPresence.Online.raw)
        )
        val note = core.config.getString(CONFIG_SECTION, CONFIG_KEY_NOTE, "").orEmpty()
        Log.i("$TAG Loading saved presence [${presence.raw}] with note [$note]")

        currentPresence.postValue(presence)
        customStatusNote.postValue(note)
        publish(presence, note, core)
    }

    /**
     * Called from the picker (UI thread) when the user changes status or note. Updates the
     * in-memory state immediately (so the header refreshes without waiting for the network), then
     * persists and publishes on the core thread.
     */
    fun setPresence(presence: UserPresence, note: String) {
        Log.i("$TAG Setting presence to [${presence.raw}] with note [$note]")
        currentPresence.postValue(presence)
        customStatusNote.postValue(note)
        coreContext.postOnCoreThread { core ->
            persist(presence, note)
            publish(presence, note, core)
        }
    }

    /**
     * Stage 2 of startup, called from [org.linphone.ui.main.viewmodel.MainViewModel] when the
     * default account reaches [RegistrationState.Ok]. Looks up the account's own friend entry to
     * pick up a status set on another device, falling back to the locally saved values.
     */
    @WorkerThread
    fun restoreOrGoOnline() {
        val core = coreContext.core
        didSyncFromServer = false

        // Remove any leftover listener from a previous registration cycle
        val previousListener = selfFriendListener
        if (previousListener != null) {
            selfFriend?.removeListener(previousListener)
            selfFriendListener = null
            selfFriend = null
        }

        val ownAddress = core.defaultAccount?.params?.identityAddress
        if (ownAddress == null) {
            Log.w("$TAG No default account identity address, publishing local config")
            publishLocalConfig(core)
            return
        }

        val friend = core.findFriend(ownAddress)
        if (friend != null) {
            Log.i(
                "$TAG Found own friend entry [${ownAddress.asStringUriOnly()}], waiting for presence NOTIFY"
            )
            val listener = object : FriendListenerStub() {
                @WorkerThread
                override fun onPresenceReceived(fr: Friend) {
                    if (didSyncFromServer) return
                    didSyncFromServer = true
                    fr.removeListener(this)
                    selfFriendListener = null
                    selfFriend = null
                    applyServerPresence(fr, core)
                }
            }
            selfFriend = friend
            selfFriendListener = listener
            friend.addListener(listener)
        } else {
            Log.i("$TAG Own address not found in friend list, publishing local config immediately")
            publishLocalConfig(core)
            return
        }

        // Fallback: if no NOTIFY arrives within the timeout, re-publish the locally saved values
        coreContext.postOnCoreThreadDelayed({
            if (!didSyncFromServer) {
                Log.i("$TAG No presence NOTIFY received within timeout, publishing local config")
                didSyncFromServer = true
                val listener = selfFriendListener
                if (listener != null) {
                    selfFriend?.removeListener(listener)
                    selfFriendListener = null
                    selfFriend = null
                }
                publishLocalConfig(it)
            }
        }, SERVER_SYNC_FALLBACK_DELAY)
    }

    @WorkerThread
    private fun applyServerPresence(friend: Friend, core: Core) {
        val consolidated = friend.consolidatedPresence
        Log.i("$TAG Applying server presence, consolidated status is [$consolidated]")

        if (consolidated == ConsolidatedPresence.Offline) {
            Log.i("$TAG Server state is Offline, restoring from local config instead")
            publishLocalConfig(core)
            return
        }

        val activityType = friend.presenceModel?.activity?.type
        val description = friend.presenceModel?.activity?.description
        var resolved = UserPresence.from(activityType, description)

        // Busy fallback: server says Busy but no parseable activity -> keep Busy, not Available
        if (resolved == UserPresence.Online && consolidated == ConsolidatedPresence.Busy) {
            resolved = UserPresence.Busy
        }

        val savedPresence = UserPresence.fromRaw(
            core.config.getString(CONFIG_SECTION, CONFIG_KEY_STATUS, UserPresence.Online.raw)
        )
        val savedNote = core.config.getString(CONFIG_SECTION, CONFIG_KEY_NOTE, "").orEmpty()

        // Available race guard: server returns plain Available but locally we have a more specific
        // status -> the server still has the SDK's automatic online tuple from before our PUBLISH
        // landed, so prefer the local config.
        if (resolved == UserPresence.Online &&
            (savedPresence == UserPresence.Away || savedPresence == UserPresence.Busy || savedPresence == UserPresence.DoNotDisturb)
        ) {
            Log.i("$TAG Server returned Available but local status is [${savedPresence.raw}], using local config")
            publishLocalConfig(core)
            return
        }

        val serverNote = friend.presenceModel?.getNote(null)?.content.orEmpty()
        val note = serverNote.ifEmpty { savedNote }

        Log.i("$TAG Resolved server presence to [${resolved.raw}] with note [$note]")
        persist(resolved, note)
        currentPresence.postValue(resolved)
        customStatusNote.postValue(note)
        publish(resolved, note, core)
    }

    @WorkerThread
    private fun publishLocalConfig(core: Core) {
        val presence = UserPresence.fromRaw(
            core.config.getString(CONFIG_SECTION, CONFIG_KEY_STATUS, UserPresence.Online.raw)
        )
        val note = core.config.getString(CONFIG_SECTION, CONFIG_KEY_NOTE, "").orEmpty()
        currentPresence.postValue(presence)
        customStatusNote.postValue(note)
        publish(presence, note, core)
    }

    @WorkerThread
    private fun persist(presence: UserPresence, note: String) {
        val core = coreContext.core
        core.config.setString(CONFIG_SECTION, CONFIG_KEY_STATUS, presence.raw)
        core.config.setString(CONFIG_SECTION, CONFIG_KEY_NOTE, note)
    }

    @WorkerThread
    private fun publish(presence: UserPresence, note: String, core: Core) {
        if (!corePreferences.publishPresence) {
            Log.i("$TAG Presence publishing is disabled, only updating local state")
            return
        }

        try {
            val lang = Locale.getDefault().language
            val model: PresenceModel = when {
                presence == UserPresence.Offline -> {
                    core.createPresenceModel().apply { basicStatus = PresenceBasicStatus.Closed }
                }
                presence.activityType != null -> { // Away / Busy / DnD
                    val desc = if (presence == UserPresence.DoNotDisturb) UserPresence.DND_DESCRIPTION else null
                    if (note.isNotEmpty()) {
                        core.createPresenceModelWithActivityAndNote(presence.activityType, desc, note, lang)
                    } else {
                        core.createPresenceModelWithActivity(presence.activityType, desc)
                    }
                }
                else -> { // Online / "Available"
                    if (note.isNotEmpty()) {
                        // Note in <tuple>, no <rpid:unknown/> -> consolidatedPresence resolves to Online
                        core.createPresenceModelWithActivityAndNote(
                            PresenceActivity.Type.Unknown,
                            null,
                            note,
                            lang
                        ).apply { clearActivities() }
                    } else {
                        core.createPresenceModel().apply { basicStatus = PresenceBasicStatus.Open }
                    }
                }
            }
            core.presenceModel = model
            core.isFriendListSubscriptionEnabled = true
        } catch (e: Exception) {
            Log.e("$TAG Publish failed: $e")
        }
    }
}
