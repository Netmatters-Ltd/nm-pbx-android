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

import android.util.Xml
import java.io.StringReader
import org.xmlpull.v1.XmlPullParser

/**
 * A status chosen by a user, with the time (Unix seconds) it was chosen. `chosen` 0 means "no
 * choice yet" and never wins on the server.
 */
data class StatusChoice(val status: UserPresence, val note: String, val chosen: Long)

/** One `<tuple>` of a PIDF document: its id, basic status and notes. */
data class PresenceTuple(val id: String, val open: Boolean, val note: String)

/**
 * Wire format of the phone server's shared extension status (NMPBX Portal change
 * `shared-extension-status`). The data travels in the PIDF tuple id because liblinphone neither
 * writes nor keeps custom XML elements:
 *
 * - each app publishes its user's choice as `nmpbx-c.<status>.<chosen>.<device>`
 * - the server publishes the shared status as `nmpbx-s.<status>.<chosen>.<source>`
 *
 * When a customer isn't switched on there is no `nmpbx-s.` tuple and the app behaves as before.
 */
object SharedStatus {
    const val CHOICE_PREFIX = "nmpbx-c."
    const val SHARED_PREFIX = "nmpbx-s."
    const val CALL_STATE_PREFIX = "nmpbx-callstate"

    const val MAX_NOTE_LENGTH = 80

    private val SHARED_REGEX =
        Regex("^nmpbx-s\\.(available|away|busy|dnd)\\.(\\d{1,12})\\.([A-Za-z0-9-]{1,64})$")

    private val DEVICE_INVALID_CHARS = Regex("[^A-Za-z0-9-]")

    private const val PIDF_NS = "urn:ietf:params:xml:ns:pidf"

    fun token(status: UserPresence): String = when (status) {
        UserPresence.Away -> "away"
        UserPresence.Busy -> "busy"
        UserPresence.DoNotDisturb -> "dnd"
        else -> "available"
    }

    private fun fromToken(token: String): UserPresence = when (token) {
        "away" -> UserPresence.Away
        "busy" -> UserPresence.Busy
        "dnd" -> UserPresence.DoNotDisturb
        else -> UserPresence.Online
    }

    /** Keeps only letters, digits and hyphens, at most 64 of them. Null if nothing is left. */
    fun sanitiseDevice(raw: String?): String? {
        val cleaned = raw.orEmpty()
            .removePrefix("<").removeSuffix(">")
            .removePrefix("urn:uuid:")
            .replace(DEVICE_INVALID_CHARS, "")
            .take(64)
        return cleaned.ifEmpty { null }
    }

    /** One printable line of at most 80 characters, as the server stores it. */
    fun normaliseNote(note: String): String {
        val oneLine = buildString {
            for (c in note) append(if (Character.isISOControl(c)) ' ' else c)
        }
        return oneLine.trim().take(MAX_NOTE_LENGTH).trim()
    }

    fun choiceTupleId(choice: StatusChoice, device: String): String =
        "$CHOICE_PREFIX${token(choice.status)}.${choice.chosen}.$device"

    /** Parses a shared status tuple, or null when the id isn't one. */
    fun parseShared(tupleId: String?, note: String): StatusChoice? {
        val match = SHARED_REGEX.matchEntire(tupleId ?: return null) ?: return null
        val chosen = match.groupValues[2].toLongOrNull() ?: return null
        return StatusChoice(fromToken(match.groupValues[1]), normaliseNote(note), chosen)
    }

    /** The newest shared status among the tuples, if any. */
    fun findShared(tuples: List<PresenceTuple>): StatusChoice? =
        tuples.mapNotNull { parseShared(it.id, it.note) }.maxByOrNull { it.chosen }

    /** Tuples of a presence model received through the SDK (a colleague's, for example). */
    fun tuples(model: PresenceModel?): List<PresenceTuple> {
        model ?: return emptyList()
        return (0 until model.nbServices).mapNotNull { i ->
            val service = model.getNthService(i) ?: return@mapNotNull null
            val note = (0 until service.nbNotes).firstNotNullOfOrNull { j ->
                service.getNthNote(j)?.content?.takeIf { it.isNotEmpty() }
            }.orEmpty()
            PresenceTuple(
                service.id.orEmpty(),
                service.basicStatus == PresenceBasicStatus.Open,
                note
            )
        }
    }

    /**
     * Tuples of a raw PIDF document (the body of a NOTIFY on the app's own-extension subscription).
     * Only the tuple id, `<basic>` and the first non-empty `<note>` of each tuple are read.
     */
    fun tuples(pidf: String): List<PresenceTuple> {
        val result = arrayListOf<PresenceTuple>()
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(StringReader(pidf))

        var id: String? = null
        var open = false
        var note = ""
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.namespace == PIDF_NS) {
                when (parser.name) {
                    "tuple" -> {
                        id = parser.getAttributeValue(null, "id").orEmpty()
                        open = false
                        note = ""
                    }
                    "basic" -> if (id != null) open = parser.nextText().trim() == "open"
                    "note" -> if (id != null && note.isEmpty()) note = parser.nextText()
                }
            } else if (event == XmlPullParser.END_TAG && parser.namespace == PIDF_NS &&
                parser.name == "tuple"
            ) {
                id?.let { result.add(PresenceTuple(it, open, note)) }
                id = null
            }
            event = parser.next()
        }
        return result
    }
}
