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
package org.linphone.ui.main.presence

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import androidx.annotation.UiThread
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.linphone.LinphoneApplication.Companion.coreContext
import org.linphone.R
import org.linphone.core.UserPresence

@UiThread
class PresencePickerDialogFragment : BottomSheetDialogFragment() {
    companion object {
        private const val MAX_NOTE_LENGTH = 80
    }

    private lateinit var noteEditText: AppCompatEditText

    private var currentStatus: UserPresence = UserPresence.Online

    // Offline is intentionally excluded from the picker
    private val rowViews = mutableMapOf<UserPresence, View>()

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = inflater.inflate(R.layout.presence_picker, container, false)

        noteEditText = root.findViewById(R.id.note)
        val charCount = root.findViewById<AppCompatTextView>(R.id.char_count)
        val clear = root.findViewById<ImageView>(R.id.clear)

        setupRow(root.findViewById(R.id.row_available), UserPresence.Online)
        setupRow(root.findViewById(R.id.row_away), UserPresence.Away)
        setupRow(root.findViewById(R.id.row_busy), UserPresence.Busy)
        setupRow(root.findViewById(R.id.row_dnd), UserPresence.DoNotDisturb)

        currentStatus = coreContext.presenceManager.currentPresence.value ?: UserPresence.Online
        updateChecks()

        val savedNote = coreContext.presenceManager.customStatusNote.value.orEmpty()
        noteEditText.setText(savedNote)
        noteEditText.setSelection(savedNote.length)
        charCount.text = "${savedNote.length}/$MAX_NOTE_LENGTH"
        clear.visibility = if (savedNote.isEmpty()) View.GONE else View.VISIBLE

        noteEditText.addTextChangedListener { editable ->
            val length = editable?.length ?: 0
            charCount.text = "$length/$MAX_NOTE_LENGTH"
            clear.visibility = if (length == 0) View.GONE else View.VISIBLE
        }

        clear.setOnClickListener {
            noteEditText.setText("")
            coreContext.presenceManager.setPresence(currentStatus, "")
        }

        noteEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                coreContext.presenceManager.setPresence(currentStatus, noteEditText.text.toString())
                dismiss()
                true
            } else {
                false
            }
        }

        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Keep the active-row checkmark in sync if the status changes while open
        coreContext.presenceManager.currentPresence.observe(viewLifecycleOwner) { presence ->
            currentStatus = presence ?: UserPresence.Online
            updateChecks()
        }
    }

    override fun onDismiss(dialog: DialogInterface) {
        // Flush any pending note even if the user didn't press Done (mirrors iOS .onDisappear).
        // The extra publish is idempotent.
        if (::noteEditText.isInitialized) {
            coreContext.presenceManager.setPresence(currentStatus, noteEditText.text.toString())
        }
        super.onDismiss(dialog)
    }

    private fun setupRow(row: View, status: UserPresence) {
        rowViews[status] = row

        val dot = row.findViewById<ImageView>(R.id.dot)
        dot.setColorFilter(ContextCompat.getColor(requireContext(), status.badgeColorRes()))

        val label = row.findViewById<AppCompatTextView>(R.id.label)
        label.setText(status.labelRes())

        row.setOnClickListener {
            // Update currentStatus synchronously: currentPresence.observe only refreshes it
            // asynchronously, and onDismiss() (fired by dismiss() below) flushes the note via
            // setPresence(currentStatus, ...). Without this the flush would re-publish the
            // previously-selected status and clobber the one just picked.
            currentStatus = status
            coreContext.presenceManager.setPresence(status, noteEditText.text.toString())
            dismiss()
        }
    }

    private fun updateChecks() {
        for ((status, row) in rowViews) {
            row.findViewById<ImageView>(R.id.check).visibility =
                if (status == currentStatus) View.VISIBLE else View.GONE
        }
    }
}
