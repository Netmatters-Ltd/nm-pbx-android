/*
 * Copyright (c) 2010-2023 Belledonne Communications SARL.
 * Copyright (c) 2024-2026 Netmatters Ltd.
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
package org.linphone.ui.main.settings.fragment

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.UiThread
import androidx.core.net.toUri
import org.linphone.R
import org.linphone.core.tools.Log
import org.linphone.databinding.AboutFragmentBinding
import org.linphone.ui.main.fragment.GenericMainFragment

@UiThread
class AboutFragment : GenericMainFragment() {
    companion object {
        private const val TAG = "[About Fragment]"
    }

    private lateinit var binding: AboutFragmentBinding

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = AboutFragmentBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        postponeEnterTransition()
        super.onViewCreated(view, savedInstanceState)

        binding.lifecycleOwner = viewLifecycleOwner
        binding.licenseContent = readLicenseText()

        binding.setBackClickListener {
            goBack()
        }

        binding.setSourceCodeClickListener {
            openUrlInBrowser(getString(R.string.settings_about_source_url))
        }

        startPostponedEnterTransition()
    }

    private fun readLicenseText(): String {
        return try {
            resources.openRawResource(R.raw.license).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.e("$TAG Failed to read bundled licence: $e")
            getString(R.string.about_license_unavailable)
        }
    }

    private fun openUrlInBrowser(url: String) {
        try {
            val browserIntent = Intent(Intent.ACTION_VIEW, url.toUri())
            startActivity(browserIntent)
        } catch (ise: IllegalStateException) {
            Log.e("$TAG Can't start ACTION_VIEW intent for URL [$url], IllegalStateException: $ise")
        } catch (anfe: ActivityNotFoundException) {
            Log.e("$TAG Can't start ACTION_VIEW intent for URL [$url], ActivityNotFoundException: $anfe")
        } catch (e: Exception) {
            Log.e("$TAG Can't start ACTION_VIEW intent for URL [$url]: $e")
        }
    }
}
