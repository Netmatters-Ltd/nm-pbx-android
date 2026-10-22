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
package org.linphone.ui.main.contacts.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.StringRes
import androidx.annotation.UiThread
import androidx.recyclerview.widget.RecyclerView
import org.linphone.R

/**
 * Single-item adapter shown after the last contact (through a ConcatAdapter) to tell the user
 * that the list was cut short. It scrolls with the list instead of taking space on the page.
 */
@UiThread
class ContactsListFooterAdapter : RecyclerView.Adapter<ContactsListFooterAdapter.ViewHolder>() {
    @StringRes
    private var messageRes = 0

    fun setMessage(@StringRes message: Int) {
        if (message == messageRes) {
            return
        }

        val hadMessage = messageRes != 0
        messageRes = message

        if (!hadMessage && message != 0) {
            notifyItemInserted(0)
        } else if (hadMessage && message == 0) {
            notifyItemRemoved(0)
        } else {
            notifyItemChanged(0)
        }
    }

    override fun getItemCount(): Int {
        return if (messageRes != 0) 1 else 0
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(
            R.layout.contacts_list_footer_cell,
            parent,
            false
        )
        return ViewHolder(view as TextView)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.label.setText(messageRes)
    }

    class ViewHolder(val label: TextView) : RecyclerView.ViewHolder(label)
}
