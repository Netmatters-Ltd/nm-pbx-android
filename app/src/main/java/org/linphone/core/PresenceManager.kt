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

import android.os.SystemClock
import androidx.annotation.AnyThread
import androidx.annotation.WorkerThread
import androidx.lifecycle.MutableLiveData
import java.util.Locale
import java.util.UUID
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.LinphoneApplication.Companion.corePreferences
import org.linphone.core.tools.Log

/**
 * Owns the user's own presence status and custom note, publishes it to the server as PIDF, and
 * keeps it in step with the user's other devices. It is a process-singleton owned by
 * [CoreContext] (mirrors contactsManager / notificationsManager), NOT an AndroidX ViewModel,
 * because it must outlive fragments and be reachable from the registration listener.
 *
 * Shared status: where the phone server keeps one shared status per extension, it publishes it as
 * an `nmpbx-s.` tuple (see [SharedStatus]). Every publication from this app states the user's
 * choice and when it was made in an `nmpbx-c.` tuple id, and the app follows the server's shared
 * status for its own extension through a permanent subscription. Where there is no `nmpbx-s.` tuple
 * (the customer isn't switched on) the app behaves as it did before: a one-shot sync from the own
 * extension's merged presence after registration.
 */
class PresenceManager {
    companion object {
        private const val TAG = "[Presence Manager]"

        private const val CONFIG_SECTION = "app"
        private const val CONFIG_KEY_STATUS = "presence_status"
        private const val CONFIG_KEY_NOTE = "presence_note"
        private const val CONFIG_KEY_CHOSEN = "presence_chosen"
        private const val CONFIG_KEY_DEVICE = "presence_device_id"

        private const val SERVER_SYNC_FALLBACK_DELAY = 2000L // 2 seconds

        // How long a choice made on this device is kept on screen while the server's shared
        // status doesn't show it yet
        private const val PENDING_CHOICE_WINDOW = 30_000L // 30 seconds

        private const val OWN_SUBSCRIPTION_EXPIRES = 600 // seconds, refreshed by the SDK
        private const val OWN_SUBSCRIPTION_RETRY_DELAY = 15_000L // 15 seconds
    }

    val currentPresence = MutableLiveData<UserPresence>()

    val customStatusNote = MutableLiveData<String>()

    // Legacy (switched off) one-shot sync from the own extension's friend entry
    private var selfFriend: Friend? = null
    private var selfFriendListener: FriendListener? = null

    @Volatile
    private var didSyncFromServer = false

    // Permanent subscription to the app's own extension, whether or not it is in the contacts
    private var ownSubscription: Event? = null
    private var ownSubscriptionUri: String? = null
    private var ownSubscriptionRetryScheduled = false

    // True while the own extension's presence carries an nmpbx-s tuple (customer switched on)
    @Volatile
    private var sharedStatusActive = false

    private var lastShared: StatusChoice? = null

    // A choice made on this device that the server's shared status may not show yet
    private var pendingChoice: StatusChoice? = null
    private var pendingChoiceAt = 0L

    // Tuple id and note of the model last handed to the SDK, to avoid needless PUBLISHes
    private var lastPublishedKey: String? = null

    private val ownSubscriptionListener = object : EventListenerStub() {
        @WorkerThread
        override fun onNotifyReceived(event: Event, content: Content?) {
            if (event != ownSubscription || content == null) return
            onOwnPresenceReceived(content)
        }

        @WorkerThread
        override fun onSubscribeStateChanged(event: Event, state: SubscriptionState?) {
            Log.i("$TAG Own extension subscription state is now [$state]")
            if (event != ownSubscription) return
            if (state == SubscriptionState.Error || state == SubscriptionState.Terminated) {
                event.removeListener(this)
                ownSubscription = null
                ownSubscriptionUri = null
                scheduleOwnSubscriptionRetry()
            }
        }
    }

    /**
     * Stage 1 of startup, called from [CoreContext.onCoreStarted] (before registration completes)
     * so the SDK's automatic PUBLISH on register already carries the saved choice, with the time it
     * was originally made.
     */
    @WorkerThread
    fun loadSavedPresence() {
        val saved = savedChoice(coreContext.core)
        Log.i(
            "$TAG Loading saved presence [${saved.status.raw}] with note [${saved.note}] chosen at [${saved.chosen}]"
        )
        show(saved)
        publish(saved)
    }

    /**
     * Called from the picker (UI thread) when the user changes status or note. Updates the
     * in-memory state immediately (so the header refreshes without waiting for the network), then
     * persists and publishes it on the core thread as a new choice. Setting the same status and
     * note again is not a new choice and does nothing.
     */
    @AnyThread
    fun setPresence(presence: UserPresence, note: String) {
        if (!presence.isUserSelectable || presence == UserPresence.Offline) {
            Log.w("$TAG Unable to set presence to [${presence.raw}], it isn't a status the user can choose")
            return
        }
        val cleanNote = SharedStatus.normaliseNote(note)
        currentPresence.postValue(presence)
        customStatusNote.postValue(cleanNote)
        coreContext.postOnCoreThread { core ->
            val saved = savedChoice(core)
            if (saved.status == presence && saved.note == cleanNote) {
                Log.i("$TAG Presence [${presence.raw}] with note [$cleanNote] is unchanged, not a new choice")
                return@postOnCoreThread
            }

            // Strictly after the previous choice, so two quick choices in the same second still
            // reach the server in the right order
            val now = System.currentTimeMillis() / 1000
            val choice = StatusChoice(presence, cleanNote, maxOf(now, saved.chosen + 1))
            Log.i(
                "$TAG User chose presence [${presence.raw}] with note [$cleanNote] at [${choice.chosen}]"
            )
            pendingChoice = choice
            pendingChoiceAt = SystemClock.elapsedRealtime()
            persist(choice)
            show(choice)
            publish(choice)
        }
    }

    /**
     * Called when the default account reaches [RegistrationState.Ok] and when the app returns to
     * the foreground. Makes sure the own extension is watched (and asks the server for its current
     * presence), and runs the legacy one-shot sync for customers who aren't switched on.
     */
    @WorkerThread
    fun syncWithServer() {
        val core = coreContext.core
        ensureOwnSubscription(core)
        legacySyncFromOwnFriend(core)
    }

    @WorkerThread
    fun onCoreStopped() {
        stopOwnSubscription()
        val listener = selfFriendListener
        if (listener != null) {
            selfFriend?.removeListener(listener)
        }
        selfFriendListener = null
        selfFriend = null
        lastPublishedKey = null
    }

    // region Own extension subscription (shared status)

    @WorkerThread
    private fun ensureOwnSubscription(core: Core) {
        val identity = core.defaultAccount?.params?.identityAddress
        if (identity == null) {
            Log.w("$TAG No default account identity address, can't watch own extension")
            stopOwnSubscription()
            return
        }
        val uri = identity.asStringUriOnly()

        val existing = ownSubscription
        if (existing != null && ownSubscriptionUri == uri) {
            val state = existing.subscriptionState
            if (state == SubscriptionState.Active || state == SubscriptionState.Pending ||
                state == SubscriptionState.Expiring
            ) {
                // A re-SUBSCRIBE makes the server send the full current presence straight away
                Log.i("$TAG Refreshing own extension subscription [$uri]")
                existing.refreshSubscribe()
                return
            }
            if (state == SubscriptionState.OutgoingProgress) {
                Log.i("$TAG Own extension subscription [$uri] is in progress")
                return
            }
        }

        stopOwnSubscription()

        val resource = Factory.instance().createAddress(uri)
        if (resource == null) {
            Log.e("$TAG Failed to parse own address [$uri]")
            return
        }
        val event = core.createSubscribe(resource, "presence", OWN_SUBSCRIPTION_EXPIRES)
        if (event == null) {
            Log.e("$TAG Failed to create own extension subscription [$uri]")
            scheduleOwnSubscriptionRetry()
            return
        }
        Log.i("$TAG Subscribing to own extension [$uri]")
        event.addCustomHeader("Accept", "application/pidf+xml")
        event.addListener(ownSubscriptionListener)
        ownSubscription = event
        ownSubscriptionUri = uri
        event.sendSubscribe(null)
    }

    @WorkerThread
    private fun stopOwnSubscription() {
        val event = ownSubscription ?: return
        Log.i("$TAG Ending own extension subscription [$ownSubscriptionUri]")
        ownSubscription = null
        ownSubscriptionUri = null
        event.removeListener(ownSubscriptionListener)
        event.terminate()
    }

    @WorkerThread
    private fun scheduleOwnSubscriptionRetry() {
        if (ownSubscriptionRetryScheduled) return
        ownSubscriptionRetryScheduled = true
        coreContext.postOnCoreThreadDelayed({ core ->
            ownSubscriptionRetryScheduled = false
            if (ownSubscription == null && core.defaultAccount?.state == RegistrationState.Ok) {
                Log.i("$TAG Retrying own extension subscription")
                ensureOwnSubscription(core)
            }
        }, OWN_SUBSCRIPTION_RETRY_DELAY)
    }

    @WorkerThread
    private fun onOwnPresenceReceived(content: Content) {
        val pidf = pidfBody(content)
        if (pidf.isNullOrEmpty()) {
            Log.d("$TAG Own extension NOTIFY without a PIDF body")
            return
        }

        val tuples = try {
            SharedStatus.tuples(pidf)
        } catch (e: Exception) {
            Log.e("$TAG Failed to parse own extension presence: $e")
            return
        }
        Log.i("$TAG Own extension presence received, tuples ${tuples.map { it.id }}")

        val shared = SharedStatus.findShared(tuples)
        if (shared == null) {
            if (sharedStatusActive) {
                Log.w("$TAG Shared status is no longer published for the own extension")
            }
            // Switched off: the legacy sync stays in charge
            sharedStatusActive = false
            lastShared = null
            return
        }

        sharedStatusActive = true
        lastShared = shared
        followShared()
    }

    @WorkerThread
    private fun pidfBody(content: Content): String? {
        if (content.isMultipart) {
            for (part in content.parts) {
                if (part.subtype.equals("pidf+xml", ignoreCase = true)) {
                    return part.utf8Text
                }
            }
            return null
        }
        return content.utf8Text
    }

    /**
     * Shows and publishes the server's shared status as this app's own, with the time it was
     * originally chosen. The server ignores that as not newer, so following never makes a new
     * choice. The only exception is a choice made on this device in the last 30 seconds that the
     * server doesn't show yet: that stays on screen until the server takes it up or 30 seconds pass.
     */
    @WorkerThread
    private fun followShared() {
        val shared = lastShared ?: return

        val pending = pendingChoice
        if (pending != null) {
            val age = SystemClock.elapsedRealtime() - pendingChoiceAt
            when {
                pending == shared -> {
                    Log.i("$TAG Server has taken up the choice made on this device")
                    pendingChoice = null
                }
                age < PENDING_CHOICE_WINDOW -> {
                    Log.i(
                        "$TAG Shared status [${shared.status.raw}] chosen at [${shared.chosen}] doesn't show this device's choice yet, keeping it for now"
                    )
                    coreContext.postOnCoreThreadDelayed(
                        { followShared() },
                        PENDING_CHOICE_WINDOW - age + 100
                    )
                    return
                }
                else -> {
                    Log.w("$TAG Server didn't take up this device's choice within 30 seconds, following it")
                    pendingChoice = null
                }
            }
        }

        val saved = savedChoice(coreContext.core)
        if (saved != shared) {
            Log.i(
                "$TAG Following shared status [${shared.status.raw}] with note [${shared.note}] chosen at [${shared.chosen}]"
            )
            persist(shared)
        }
        show(shared)
        publish(shared)
    }

    // endregion

    // region Legacy sync (customer not switched on)

    /**
     * The behaviour from before shared status: looks up the account's own friend entry to pick up
     * a status set on another device, falling back to the locally saved values. It steps aside as
     * soon as the own extension's presence carries a shared status.
     */
    @WorkerThread
    private fun legacySyncFromOwnFriend(core: Core) {
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
        if (sharedStatusActive ||
            SharedStatus.findShared(SharedStatus.tuples(friend.presenceModel)) != null
        ) {
            Log.i("$TAG Own presence carries a shared status, following it instead of the merged presence")
            return
        }

        val consolidated = friend.consolidatedPresence
        Log.i("$TAG Applying server presence, consolidated status is [$consolidated]")

        if (consolidated == ConsolidatedPresence.Offline) {
            Log.i("$TAG Server state is Offline, restoring from local config instead")
            publishLocalConfig(core)
            return
        }

        // Skip the call-state bridge's on-the-phone, which isn't a status the user chose. If it came
        // first, reading only the first activity would resolve to Available and overwrite their status.
        val userActivity = UserPresence.userActivity(friend.presenceModel)
        val activityType = userActivity?.type
        val description = userActivity?.description
        var resolved = UserPresence.from(activityType, description)
        if (resolved == UserPresence.Offline) resolved = UserPresence.Online

        // Busy fallback: server says Busy but no parseable activity -> keep Busy, not Available
        if (resolved == UserPresence.Online && consolidated == ConsolidatedPresence.Busy) {
            resolved = UserPresence.Busy
        }

        val saved = savedChoice(core)

        // Available race guard: server returns plain Available but locally we have a more specific
        // status -> the server still has the SDK's automatic online tuple from before our PUBLISH
        // landed, so prefer the local config.
        if (resolved == UserPresence.Online &&
            (saved.status == UserPresence.Away || saved.status == UserPresence.Busy || saved.status == UserPresence.DoNotDisturb)
        ) {
            Log.i("$TAG Server returned Available but local status is [${saved.status.raw}], using local config")
            publishLocalConfig(core)
            return
        }

        val serverNote = friend.presenceModel?.getNote(null)?.content.orEmpty()
        val note = SharedStatus.normaliseNote(serverNote.ifEmpty { saved.note })

        // Not a choice made on this device: keep the saved time only if nothing changed, otherwise
        // state "no choice yet" so it can never win once the customer is switched on
        val chosen = if (saved.status == resolved && saved.note == note) saved.chosen else 0L
        val choice = StatusChoice(resolved, note, chosen)
        Log.i("$TAG Resolved server presence to [${resolved.raw}] with note [$note]")
        persist(choice)
        show(choice)
        publish(choice)
    }

    @WorkerThread
    private fun publishLocalConfig(core: Core) {
        if (sharedStatusActive) {
            // The server's shared status has already arrived and is on screen
            return
        }
        val saved = savedChoice(core)
        show(saved)
        publish(saved)
    }

    // endregion

    // region Storage and publishing

    @WorkerThread
    private fun savedChoice(core: Core): StatusChoice {
        val config = core.config
        var status = UserPresence.fromRaw(
            config.getString(CONFIG_SECTION, CONFIG_KEY_STATUS, UserPresence.Online.raw)
        )
        if (status == UserPresence.Offline) status = UserPresence.Online
        val note = SharedStatus.normaliseNote(
            config.getString(CONFIG_SECTION, CONFIG_KEY_NOTE, "").orEmpty()
        )
        // Stored as a string: the SDK's Java wrapper for 64-bit config values only takes an int
        val chosen = config.getString(CONFIG_SECTION, CONFIG_KEY_CHOSEN, "0")
            ?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        return StatusChoice(status, note, chosen)
    }

    @WorkerThread
    private fun persist(choice: StatusChoice) {
        val config = coreContext.core.config
        config.setString(CONFIG_SECTION, CONFIG_KEY_STATUS, choice.status.raw)
        config.setString(CONFIG_SECTION, CONFIG_KEY_NOTE, choice.note)
        config.setString(CONFIG_SECTION, CONFIG_KEY_CHOSEN, choice.chosen.toString())
    }

    @WorkerThread
    private fun show(choice: StatusChoice) {
        currentPresence.postValue(choice.status)
        customStatusNote.postValue(choice.note)
    }

    /**
     * Stable per-installation token for the choice tuple id: the SIP instance UUID the SDK puts in
     * the Contact header (`+sip.instance`), or a random UUID if there isn't one. Saved on first use.
     */
    @WorkerThread
    private fun deviceToken(core: Core): String {
        val config = core.config
        SharedStatus.sanitiseDevice(config.getString(CONFIG_SECTION, CONFIG_KEY_DEVICE, null))?.let {
            return it
        }
        val token = SharedStatus.sanitiseDevice(config.getString("misc", "uuid", null))
            ?: UUID.randomUUID().toString()
        Log.i("$TAG Using device token [$token] for presence publications")
        config.setString(CONFIG_SECTION, CONFIG_KEY_DEVICE, token)
        return token
    }

    @WorkerThread
    private fun publish(choice: StatusChoice) {
        if (!corePreferences.publishPresence) {
            Log.i("$TAG Presence publishing is disabled, only updating local state")
            return
        }
        val presence = choice.status
        if (!presence.isUserSelectable) {
            // On a call comes from the server's call-state bridge and must never be published
            Log.w("$TAG Not publishing presence [${presence.raw}], it is reported by the server only")
            return
        }

        val core = coreContext.core
        try {
            val tupleId = SharedStatus.choiceTupleId(choice, deviceToken(core))
            val key = "$tupleId|${choice.note}"
            if (key == lastPublishedKey) {
                Log.d("$TAG Presence [$tupleId] is already published")
                return
            }

            // The SDK writes whatever id the model's service has (it only makes a random one up
            // when there is no service), so the tuple always carries the choice.
            val model = core.createPresenceModel()
            model.clearServices()
            val contact = core.defaultAccount?.params?.identityAddress?.asStringUriOnly().orEmpty()
            val service = core.createPresenceService(tupleId, PresenceBasicStatus.Open, contact)
            if (choice.note.isNotEmpty()) {
                service.addNote(core.createPresenceNote(choice.note, Locale.getDefault().language))
            }
            model.addService(service)

            // The RPID activity is published exactly as before: older readers, the legacy DND
            // rules and customers who aren't switched on rely on it. Available has none.
            val activityType = presence.activityType
            if (activityType != null) {
                val description =
                    if (presence == UserPresence.DoNotDisturb) UserPresence.DND_DESCRIPTION else null
                model.addActivity(core.createPresenceActivity(activityType, description))
            }

            Log.i("$TAG Publishing presence tuple [$tupleId] with note [${choice.note}]")
            core.presenceModel = model
            core.isFriendListSubscriptionEnabled = true
            lastPublishedKey = key
        } catch (e: Exception) {
            Log.e("$TAG Publish failed: $e")
        }
    }

    // endregion
}
