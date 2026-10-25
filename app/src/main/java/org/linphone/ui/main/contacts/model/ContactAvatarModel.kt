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
package org.linphone.ui.main.contacts.model

import android.net.Uri
import androidx.annotation.WorkerThread
import androidx.lifecycle.MutableLiveData
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.contacts.AbstractAvatarModel
import org.linphone.contacts.getNativeContactPictureUri
import org.linphone.core.Address
import org.linphone.core.ChatRoom
import org.linphone.core.ConsolidatedPresence
import org.linphone.core.Friend
import org.linphone.core.FriendListenerStub
import org.linphone.core.SecurityLevel
import org.linphone.core.UserPresence
import org.linphone.core.tools.Log
import org.linphone.utils.AppUtils
import androidx.core.net.toUri

class ContactAvatarModel
    @WorkerThread
    constructor(val friend: Friend, val address: Address? = null) : AbstractAvatarModel() {
    companion object {
        private const val TAG = "[Contact Avatar Model]"
    }

    val id = friend.refKey ?: friend.name ?: ""

    val contactName = friend.name

    val isStored = friend.inList()

    // True when this friend has an address on the default account's domain (an internal PBX
    // extension), as opposed to an external contact. Mirrors iOS ContactAvatarModel.isInternal.
    // Recomputed in update() so it refreshes when the default account changes.
    var isInternal: Boolean = false

    val isFavourite = MutableLiveData<Boolean>()

    // Presence display (status label + custom note) for list rows and the details screens,
    // hidden when Offline
    val presenceStatusLabel = MutableLiveData<String>()

    val presenceVisible = MutableLiveData<Boolean>()

    val presenceNoteVisible = MutableLiveData<Boolean>()

    val name = MutableLiveData<String>()

    val firstLetter: String = AppUtils.getFirstLetter(friend.name.orEmpty())

    private val friendListener = object : FriendListenerStub() {
        @WorkerThread
        override fun onPresenceReceived(fr: Friend) {
            Log.d(
                "$TAG Presence received for friend [${fr.name}]: [${fr.consolidatedPresence}]"
            )
            computePresence()
        }
    }

    init {
        presenceStatus.postValue(ConsolidatedPresence.Offline)
        presenceUserStatus.postValue(UserPresence.Offline)
        presenceVisible.postValue(false)
        presenceNoteVisible.postValue(false)

        if (friend.addresses.isNotEmpty()) {
            friend.addListener(friendListener)
        }

        update(address)
    }

    @WorkerThread
    fun destroy() {
        if (friend.addresses.isNotEmpty()) {
            friend.removeListener(friendListener)
        }
    }

    @WorkerThread
    fun update(address: Address?) {
        updateSecurityLevel(address)

        isFavourite.postValue(coreContext.contactsManager.isFavourite(friend))
        initials.postValue(AppUtils.getInitials(friend.name.orEmpty()))
        showTrust.postValue(true)
        // Empty rather than "null" when there's no picture, so the avatar goes straight to initials
        picturePath.postValue(getAvatarUri(friend)?.toString().orEmpty())

        val domain = coreContext.core.defaultAccount?.params?.domain.orEmpty()
        isInternal = domain.isNotEmpty() && friend.addresses.any { it.domain == domain }

        name.postValue(friend.name)
        computePresence(address)
    }

    @WorkerThread
    fun updateSecurityLevelUsingConversation(chatRoom: ChatRoom) {
        /*
        // Don't do that, as chatRoom securityLevel is taking into account the security level
        // between the device and the other devices that shares the same SIP identity
        val securityLevel = when (chatRoom.securityLevel) {
            ChatRoom.SecurityLevel.Unsafe -> {
                SecurityLevel.Unsafe
            }
            ChatRoom.SecurityLevel.Encrypted -> {
                SecurityLevel.EndToEndEncrypted
            }
            ChatRoom.SecurityLevel.Safe -> {
                SecurityLevel.EndToEndEncryptedAndVerified
            }
            else -> SecurityLevel.None
        }*/

        val participants = chatRoom.participants
        var lowestSecurityLevel = if (participants.isEmpty()) {
            SecurityLevel.None
        } else {
            SecurityLevel.EndToEndEncryptedAndVerified
        }
        for (participant in participants) {
            val avatar = coreContext.contactsManager.getContactAvatarModelForAddress(
                participant.address
            )
            val level = avatar.trust.value ?: SecurityLevel.None
            if (level == SecurityLevel.None) {
                lowestSecurityLevel = SecurityLevel.None
            } else if (level == SecurityLevel.Unsafe) {
                lowestSecurityLevel = SecurityLevel.Unsafe
                break
            } else if (lowestSecurityLevel != SecurityLevel.None && level != SecurityLevel.EndToEndEncryptedAndVerified) {
                lowestSecurityLevel = SecurityLevel.EndToEndEncrypted
            }
        }
        trust.postValue(lowestSecurityLevel)
    }

    @WorkerThread
    fun updateSecurityLevel(address: Address?) {
        if (address == null) {
            trust.postValue(friend.securityLevel)
        } else {
            trust.postValue(friend.getSecurityLevelForAddress(address))
        }
    }

    @WorkerThread
    private fun getAvatarUri(friend: Friend): Uri? {
        val picturePath = friend.photo
        if (!picturePath.isNullOrEmpty()) {
            return picturePath.toUri()
        }

        val refKey = friend.refKey
        if (refKey != null) {
            try {
                return friend.getNativeContactPictureUri()
            } catch (numberFormatException: NumberFormatException) {
                // Expected for contacts created by Linphone
            }
        }

        return null
    }

    @WorkerThread
    private fun computePresence(address: Address? = null) {
        val presence = if (address == null) {
            friend.consolidatedPresence
        } else {
            friend.getPresenceModelForUriOrTel(address.asStringUriOnly())?.consolidatedPresence ?: friend.consolidatedPresence
        }
        Log.d("$TAG Friend [${friend.name}] presence status is [$presence]")
        presenceStatus.postValue(presence)

        // Compute the display-level status (spec §5): combine consolidatedPresence with the
        // person-level RPID activity so Away/Busy/DnD show even when consolidated == Online.
        val presenceModel = if (address == null) {
            friend.presenceModel
        } else {
            friend.getPresenceModelForUriOrTel(address.asStringUriOnly()) ?: friend.presenceModel
        }
        val activityType = presenceModel?.activity?.type
        val activityDescription = presenceModel?.activity?.description
        val mapped = UserPresence.from(activityType, activityDescription)
        val userStatus = when (presence) {
            ConsolidatedPresence.Online ->
                if (mapped == UserPresence.Offline) UserPresence.Online else mapped
            ConsolidatedPresence.Busy ->
                if (mapped == UserPresence.Online || mapped == UserPresence.Offline) UserPresence.Busy else mapped
            else -> UserPresence.Offline
        }
        presenceActivity.postValue(activityType)
        presenceUserStatus.postValue(userStatus)

        val note = presenceModel?.getNote(null)?.content.orEmpty()
        presenceNote.postValue(note)

        val visible = userStatus != UserPresence.Offline
        presenceVisible.postValue(visible)
        presenceNoteVisible.postValue(visible && note.isNotEmpty())
        presenceStatusLabel.postValue(
            if (visible) AppUtils.getString(userStatus.labelRes()) else ""
        )
    }
}
