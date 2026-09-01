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
package org.linphone.ui.assistant.fragment

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.UiThread
import androidx.core.net.toUri
import androidx.navigation.fragment.findNavController
import androidx.navigation.navGraphViewModels
import org.linphone.R
import org.linphone.core.tools.Log
import org.linphone.databinding.AssistantLandingFragmentBinding
import org.linphone.ui.GenericFragment
import org.linphone.ui.assistant.viewmodel.AccountLoginViewModel
import org.linphone.ui.assistant.viewmodel.QrCodeViewModel

@UiThread
class LandingFragment : GenericFragment() {
    companion object {
        private const val TAG = "[Landing Fragment]"
    }

    private lateinit var binding: AssistantLandingFragmentBinding

    private val viewModel: AccountLoginViewModel by navGraphViewModels(
        R.id.assistant_nav_graph
    )

    // Same nav-graph scope as QrCodeScannerFragment, so this is the same instance:
    // the manually-typed URL feeds into the exact path used by a scanned QR code.
    private val qrCodeViewModel: QrCodeViewModel by navGraphViewModels(
        R.id.assistant_nav_graph
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = AssistantLandingFragmentBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.lifecycleOwner = viewLifecycleOwner
        binding.viewModel = viewModel
        binding.qrCodeViewModel = qrCodeViewModel
        observeToastEvents(viewModel)
        observeToastEvents(qrCodeViewModel)

        binding.setBackClickListener {
            requireActivity().finish()
        }

        binding.setNmpbxWebsiteClickListener {
            val url = getString(R.string.website_nmpbx_url)
            try {
                startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
            } catch (ise: IllegalStateException) {
                Log.e(
                    "$TAG Can't start ACTION_VIEW intent for URL [$url], IllegalStateException: $ise"
                )
            } catch (anfe: ActivityNotFoundException) {
                Log.e(
                    "$TAG Can't start ACTION_VIEW intent for URL [$url], ActivityNotFoundException: $anfe"
                )
            } catch (e: Exception) {
                Log.e(
                    "$TAG Can't start ACTION_VIEW intent for URL [$url]: $e"
                )
            }
        }

        binding.setQrCodeClickListener {
            if (findNavController().currentDestination?.id == R.id.landingFragment) {
                val action =
                    LandingFragmentDirections.actionLandingFragmentToQrCodeScannerFragment()
                findNavController().navigate(action)
            }
        }

        qrCodeViewModel.remoteProvisioningSuccessfulEvent.observe(viewLifecycleOwner) {
            it.consume { success ->
                if (success) {
                    Log.i("$TAG Remote provisioning applied successfully, leaving assistant")
                    requireActivity().finish()
                } else {
                    Log.w("$TAG Remote provisioning applied but no account was configured, staying")
                }
            }
        }
    }

}
