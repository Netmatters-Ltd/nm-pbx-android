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

import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import org.linphone.R

/**
 * Display-level presence status, mapping to/from the SDK's [PresenceActivity] values.
 *
 * The [raw] values must stay exactly "online"/"away"/"busy"/"doNotDisturb"/"offline" so the
 * persisted config remains compatible with the iOS app on shared accounts.
 */
enum class UserPresence(val raw: String) {
    Online("online"),
    Away("away"),
    Busy("busy"),
    DoNotDisturb("doNotDisturb"),
    Offline("offline");

    // PIDF activity to publish for this status (null = no rpid:activity, plain "open" tuple)
    val activityType: PresenceActivity.Type?
        get() = when (this) {
            Online -> null
            Away -> PresenceActivity.Type.Away
            Busy -> PresenceActivity.Type.Busy
            DoNotDisturb -> PresenceActivity.Type.Other
            Offline -> PresenceActivity.Type.PermanentAbsence
        }

    @ColorRes
    fun badgeColorRes(): Int = when (this) {
        Online -> R.color.green_success_500
        Away -> R.color.orange_away
        Busy -> R.color.blue_info_500
        DoNotDisturb -> R.color.red_danger_500
        Offline -> R.color.gray_main2_400
    }

    @StringRes
    fun labelRes(): Int = when (this) {
        Online -> R.string.presence_status_available
        Away -> R.string.presence_status_away
        Busy -> R.string.presence_status_busy
        DoNotDisturb -> R.string.presence_status_do_not_disturb
        Offline -> R.string.presence_status_available
    }

    companion object {
        const val DND_DESCRIPTION = "dnd"

        fun fromRaw(raw: String?): UserPresence =
            entries.firstOrNull { it.raw == raw } ?: Online

        // Maps the SDK activity dimension to a display status (spec §5).
        // The consolidatedPresence dimension is combined on top of this in
        // ContactAvatarModel.computePresence().
        fun from(activityType: PresenceActivity.Type?, description: String?): UserPresence =
            when (activityType) {
                PresenceActivity.Type.Away -> Away
                PresenceActivity.Type.Busy -> Busy
                // "other" maps to Do Not Disturb (the "dnd" description is our own signal,
                // but any "other" activity is treated as DnD)
                PresenceActivity.Type.Other -> DoNotDisturb
                PresenceActivity.Type.PermanentAbsence -> Offline
                else -> Online
            }
    }
}
