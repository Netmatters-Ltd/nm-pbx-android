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
package org.linphone.ui.main.viewmodel

import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import androidx.lifecycle.MutableLiveData
import java.text.Collator
import java.util.Locale
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.LinphoneApplication.Companion.corePreferences
import org.linphone.R
import org.linphone.contacts.ContactsManager
import org.linphone.core.Address
import org.linphone.core.ChatRoom
import org.linphone.core.Friend
import org.linphone.core.MagicSearch
import org.linphone.core.MagicSearchListenerStub
import org.linphone.core.SearchResult
import org.linphone.mediastream.Log
import org.linphone.ui.main.contacts.model.ContactAvatarModel
import org.linphone.ui.main.model.ConversationContactOrSuggestionModel
import org.linphone.ui.main.model.SelectedAddressModel
import org.linphone.utils.AppUtils
import org.linphone.utils.LinphoneUtils

abstract class AddressSelectionViewModel
    @UiThread
    constructor() : DefaultAccountChangedViewModel() {
    companion object {
        private const val TAG = "[Address Selection ViewModel]"
    }

    val multipleSelectionMode = MutableLiveData<Boolean>()

    val selection = MutableLiveData<ArrayList<SelectedAddressModel>>()

    val selectionCount = MutableLiveData<String>()

    val searchFilter = MutableLiveData<String>()

    val searchInProgress = MutableLiveData<Boolean>()

    val modelsList = MutableLiveData<ArrayList<ConversationContactOrSuggestionModel>>()

    val isEmpty = MutableLiveData<Boolean>()

    protected var magicSearchSourceFlags = MagicSearch.Source.All.toInt()

    protected var skipConversation: Boolean = true

    private var currentFilter = ""
    private var previousFilter = "NotSet"

    private lateinit var magicSearch: MagicSearch

    // Avatar models currently displayed in the list. They are built fresh for every search and
    // those with an address register a listener on their Friend, which the SDK holds onto, so the
    // previous list's models must be destroyed once replaced or they are never released.
    // Only accessed from the Core thread.
    private val avatarModels = arrayListOf<ContactAvatarModel>()

    private val magicSearchListener = object : MagicSearchListenerStub() {
        @WorkerThread
        override fun onSearchResultsReceived(magicSearch: MagicSearch) {
            Log.i("$TAG Magic search contacts available")
            processMagicSearchResults(magicSearch.lastSearch)
        }
    }

    private val contactsListener = object : ContactsManager.ContactsListener {
        @WorkerThread
        override fun onContactsLoaded() {
            Log.i("$TAG Contacts have been (re)loaded, updating list")
            magicSearch.resetSearchCache()

            applyFilter(
                currentFilter,
                magicSearchSourceFlags
            )
        }

        @WorkerThread
        override fun onContactFoundInRemoteDirectory(friend: Friend) { }
    }

    init {
        multipleSelectionMode.value = false
        isEmpty.value = true

        coreContext.postOnCoreThread { core ->
            coreContext.contactsManager.addListener(contactsListener)
            magicSearch = core.createMagicSearch()
            // No Magic search limit: it applies before extensions are split from contacts, so it
            // silently dropped everything after the first results. Contacts are capped ourselves.
            magicSearch.limitedSearch = false
            magicSearch.addListener(magicSearchListener)
        }

        applyFilter(currentFilter)
    }

    @UiThread
    override fun onCleared() {
        coreContext.postOnCoreThread {
            magicSearch.removeListener(magicSearchListener)
            coreContext.contactsManager.removeListener(contactsListener)

            avatarModels.forEach(ContactAvatarModel::destroy)
            avatarModels.clear()
        }
        super.onCleared()
    }

    @UiThread
    fun clearFilter() {
        if (searchFilter.value.orEmpty().isNotEmpty()) {
            searchFilter.value = ""
        }
    }

    @UiThread
    fun switchToMultipleSelectionMode() {
        Log.i("$TAG Multiple selection mode ON")
        multipleSelectionMode.value = true

        selectionCount.postValue(
            AppUtils.getStringWithPlural(
                R.plurals.selection_count_label,
                0,
                "0"
            )
        )
    }

    @WorkerThread
    fun updateSelectedParticipants(selectedAddresses: List<Address>) {
        for (address in selectedAddresses) {
            val found = modelsList.value.orEmpty().find {
                it.address.weakEqual(address)
            }
            found?.selected?.postValue(true)
        }
    }

    @WorkerThread
    fun addAddressModelToSelection(model: SelectedAddressModel) {
        val actual = selection.value.orEmpty()
        if (actual.find {
            it.address.weakEqual(model.address)
        } == null
        ) {
            Log.i("$TAG Adding [${model.address.asStringUriOnly()}] address to selection")

            val list = arrayListOf<SelectedAddressModel>()
            list.add(model)
            list.addAll(actual)

            val found = modelsList.value.orEmpty().find {
                it.address.weakEqual(model.address) || it.friend == model.avatarModel?.friend
            }
            found?.selected?.postValue(true)

            selectionCount.postValue(
                AppUtils.getStringWithPlural(
                    R.plurals.selection_count_label,
                    list.size,
                    list.size.toString()
                )
            )
            selection.postValue(list)
        } else {
            Log.w("$TAG Address is already in selection, doing nothing")
        }
    }

    @WorkerThread
    fun removeAddressModelFromSelection(model: SelectedAddressModel) {
        val actual = selection.value.orEmpty()
        if (actual.find {
            it.address.weakEqual(model.address)
        } != null
        ) {
            Log.i("$TAG Removing [${model.address.asStringUriOnly()}] address from selection")

            val list = arrayListOf<SelectedAddressModel>()
            list.addAll(actual)
            model.avatarModel?.destroy()
            list.remove(model)

            val found = modelsList.value.orEmpty().find {
                it.address.weakEqual(model.address) || it.friend == model.avatarModel?.friend
            }
            found?.selected?.postValue(false)

            selectionCount.postValue(
                AppUtils.getStringWithPlural(
                    R.plurals.selection_count_label,
                    list.size,
                    list.size.toString()
                )
            )
            selection.postValue(list)
        } else {
            Log.w("$TAG Address isn't in selection, doing nothing")
        }
    }

    @UiThread
    fun applyFilter(filter: String) {
        coreContext.postOnCoreThread {
            applyFilter(
                filter,
                magicSearchSourceFlags
            )
        }
    }

    @WorkerThread
    private fun applyFilter(
        filter: String,
        sources: Int
    ) {
        if (previousFilter.isNotEmpty() && (
            previousFilter.length > filter.length ||
                (previousFilter.length == filter.length && previousFilter != filter)
            )
        ) {
            magicSearch.resetSearchCache()
        }
        currentFilter = filter
        previousFilter = filter

        // Always search every friend: a non-empty domain ("*" = SIP-only, or a specific domain)
        // would drop phone-number-only external contacts (eg. CardDAV) before we ever see them.
        // Extensions and contacts are split ourselves in processMagicSearchResults().
        val domain = ""
        Log.i(
            "$TAG Asking Magic search for contacts matching filter [$filter], domain [$domain] and in sources [$sources]"
        )
        searchInProgress.postValue(filter.isNotEmpty())
        magicSearch.getContactsListAsync(
            filter,
            domain,
            sources,
            MagicSearch.Aggregation.Friend
        )
    }

    @WorkerThread
    private fun processMagicSearchResults(results: Array<SearchResult>) {
        Log.i("$TAG Processing [${results.size}] results")

        val newAvatarModels = arrayListOf<ContactAvatarModel>()
        val conversationsList = if (!skipConversation) {
            getConversationsList(currentFilter, newAvatarModels)
        } else {
            arrayListOf()
        }

        val defaultAccountDomain = LinphoneUtils.getDefaultAccount()?.params?.domain

        // Split friends into favourites, extensions (internal) and contacts (external) before
        // building any model (the costly part), so contacts past the cap are never built.
        // Favourites are picked out of these results rather than through Magic search's
        // FavoriteFriends source: CardDAV contacts are favourited by the app, the SDK doesn't know.
        val favouriteResults = arrayListOf<SearchResult>()
        val extensionResults = arrayListOf<SearchResult>()
        val contactResults = arrayListOf<SearchResult>()
        val seenFriends = hashSetOf<Friend>()
        val suggestionsList = arrayListOf<ConversationContactOrSuggestionModel>()

        for (result in results) {
            val address = result.address
            val friend = result.friend
            if (friend != null) {
                if (!seenFriends.add(friend)) continue

                if (coreContext.contactsManager.isFavourite(friend)) {
                    favouriteResults.add(result)
                } else if (isInternalFriend(friend, defaultAccountDomain.orEmpty())) {
                    extensionResults.add(result)
                } else {
                    contactResults.add(result)
                }
            } else if (address != null) {
                if (result.sourceFlags == MagicSearch.Source.Request.toInt()) {
                    val model = ConversationContactOrSuggestionModel(address) {
                        coreContext.startAudioCall(address)
                    }
                    val avatarModel = getContactAvatarModelForAddress(address)
                    newAvatarModels.add(avatarModel)
                    model.avatarModel.postValue(avatarModel)
                    suggestionsList.add(model)
                    continue
                }

                val defaultAccountAddress = coreContext.core.defaultAccount?.params?.identityAddress
                if (defaultAccountAddress != null && address.weakEqual(defaultAccountAddress)) {
                    Log.i("$TAG Removing from suggestions current default account address")
                    continue
                }

                val model = ConversationContactOrSuggestionModel(address, defaultAccountDomain = defaultAccountDomain) {
                    coreContext.startAudioCall(address)
                }
                val avatarModel = getContactAvatarModelForAddress(address)
                newAvatarModels.add(avatarModel)
                model.avatarModel.postValue(avatarModel)
                suggestionsList.add(model)
            }
        }

        val collator = Collator.getInstance(Locale.getDefault())
        val byName = Comparator<SearchResult> { result1, result2 ->
            collator.compare(getResultName(result1), getResultName(result2))
        }
        favouriteResults.sortWith(byName)
        extensionResults.sortWith(byName)
        contactResults.sortWith(byName)

        // Only contacts are capped, including search results: typing more of the name narrows
        // them down so any contact can be reached. Extensions are always all listed.
        val maxContacts = corePreferences.maxContactsDisplayed
        val capped = maxContacts > 0 && contactResults.size > maxContacts
        val contactsToDisplay = if (capped) {
            contactResults.take(maxContacts)
        } else {
            contactResults
        }

        // Favourites are never capped
        val favoritesList = buildFriendModels(favouriteResults, newAvatarModels, isFavourite = true)
        val extensionsList = buildFriendModels(extensionResults, newAvatarModels, isExtension = true)
        val contactsList = buildFriendModels(contactsToDisplay, newAvatarModels)
        if (capped) {
            contactsList.lastOrNull()?.truncatedMessage = AppUtils.getString(
                if (currentFilter.isEmpty()) {
                    R.string.contacts_list_truncated_message
                } else {
                    R.string.contacts_list_search_truncated_message
                }
            )
        }

        suggestionsList.sortWith { model1, model2 ->
            collator.compare(model1.name, model2.name)
        }

        val list = arrayListOf<ConversationContactOrSuggestionModel>()
        list.addAll(conversationsList)
        list.addAll(favoritesList)
        list.addAll(extensionsList)
        list.addAll(contactsList)
        list.addAll(suggestionsList)

        avatarModels.forEach(ContactAvatarModel::destroy)
        avatarModels.clear()
        avatarModels.addAll(newAvatarModels)

        searchInProgress.postValue(false)
        modelsList.postValue(list)
        isEmpty.postValue(list.isEmpty())
        Log.i(
            "$TAG Processed [${results.size}] results: [${conversationsList.size}] conversations, [${favoritesList.size}] favorites, [${extensionsList.size}] extensions, [${contactsList.size}] contacts (from [${contactResults.size}]) and [${suggestionsList.size}] suggestions"
        )
    }

    @WorkerThread
    private fun buildFriendModels(
        results: List<SearchResult>,
        newAvatarModels: ArrayList<ContactAvatarModel>,
        isFavourite: Boolean = false,
        isExtension: Boolean = false
    ): ArrayList<ConversationContactOrSuggestionModel> {
        val list = arrayListOf<ConversationContactOrSuggestionModel>()
        for (result in results) {
            val friend = result.friend ?: continue
            val mainAddress = result.address ?: LinphoneUtils.getFirstAvailableAddressForFriend(friend)
            if (mainAddress != null) {
                val model = ConversationContactOrSuggestionModel(
                    mainAddress,
                    friend = friend,
                    isFavourite = isFavourite,
                    isExtension = isExtension
                )
                val avatarModel = coreContext.contactsManager.getContactAvatarModelForFriend(
                    friend
                )
                newAvatarModels.add(avatarModel)
                model.avatarModel.postValue(avatarModel)
                list.add(model)
            } else {
                Log.w("$TAG Found friend [${friend.name}] in search results but no Address could be found, skipping it")
            }
        }
        return list
    }

    // Same rule as ContactAvatarModel.isInternal, without having to build a model first
    @WorkerThread
    private fun isInternalFriend(friend: Friend, defaultDomain: String): Boolean {
        return defaultDomain.isNotEmpty() && friend.addresses.any { it.domain == defaultDomain }
    }

    @WorkerThread
    private fun getResultName(result: SearchResult): String {
        return result.friend?.name ?: result.address?.displayName ?: result.address?.username.orEmpty()
    }

    @WorkerThread
    private fun getConversationsList(
        filter: String,
        newAvatarModels: ArrayList<ContactAvatarModel>
    ): ArrayList<ConversationContactOrSuggestionModel> {
        val conversationsList = arrayListOf<ConversationContactOrSuggestionModel>()
        for (chatRoom in LinphoneUtils.getDefaultAccount()?.chatRooms.orEmpty()) {
            // Do not list conversations in which we can't send a message
            val isBasic = chatRoom.hasCapability(ChatRoom.Capabilities.Basic.toInt())
            if (chatRoom.isReadOnly || (!isBasic && chatRoom.participants.isEmpty())) continue

            val isOneToOne = chatRoom.hasCapability(ChatRoom.Capabilities.OneToOne.toInt())
            val conversationId = LinphoneUtils.getConversationId(chatRoom)
            val remoteAddress = chatRoom.peerAddress
            val matchesFilter: Any? = if (filter.isEmpty()) {
                null
            } else {
                if (isBasic) {
                    // Search in address but also in contact name if exists
                    val model =
                        coreContext.contactsManager.getContactAvatarModelForAddress(remoteAddress)
                    if (model.contactName?.contains(filter, ignoreCase = true) == true ||
                        remoteAddress.asStringUriOnly().contains(
                                filter,
                                ignoreCase = true
                            )
                    ) {
                        model
                    } else {
                        null
                    }
                } else {
                    if (chatRoom.subject.orEmpty().contains(filter, ignoreCase = true)) {
                        chatRoom
                    } else {
                        chatRoom.participants.find {
                            // Search in address but also in contact name if exists
                            val model =
                                coreContext.contactsManager.getContactAvatarModelForAddress(
                                    it.address
                                )
                            model.contactName?.contains(
                                filter,
                                ignoreCase = true
                            ) == true || it.address.asStringUriOnly().contains(
                                filter,
                                ignoreCase = true
                            )
                        }
                    }
                }
            }
            if (filter.isEmpty() || matchesFilter != null) {
                val friend = if (isBasic) {
                    coreContext.contactsManager.findContactByAddress(remoteAddress)
                } else {
                    val participantAddress = chatRoom.participants.firstOrNull()?.address
                    if (participantAddress != null) {
                        val friendFound = coreContext.contactsManager.findContactByAddress(
                            participantAddress
                        )
                        if (friendFound == null) {
                            val fakeFriend = coreContext.core.createFriend()
                            fakeFriend.name = LinphoneUtils.getDisplayName(participantAddress)
                            fakeFriend
                        } else {
                            friendFound
                        }
                    } else {
                        null
                    }
                }
                val subject = if (isOneToOne) {
                    friend?.name
                } else {
                    chatRoom.subject
                }
                val model = ConversationContactOrSuggestionModel(
                    remoteAddress,
                    conversationId,
                    subject,
                    friend
                )

                val avatarModel = if (!isOneToOne) {
                    val fakeFriend = coreContext.core.createFriend()
                    fakeFriend.name = chatRoom.subject
                    val avatarModel = ContactAvatarModel(fakeFriend)
                    avatarModel.defaultToConversationIcon.postValue(true)
                    avatarModel
                } else {
                    coreContext.contactsManager.getContactAvatarModelForFriend(friend)
                }
                newAvatarModels.add(avatarModel)
                model.avatarModel.postValue(avatarModel)
                conversationsList.add(model)
            }
        }
        return conversationsList
    }

    @WorkerThread
    private fun getContactAvatarModelForAddress(address: Address): ContactAvatarModel {
        val fakeFriend = coreContext.core.createFriend()
        fakeFriend.name = LinphoneUtils.getDisplayName(address)
        fakeFriend.address = address
        return ContactAvatarModel(fakeFriend)
    }
}
