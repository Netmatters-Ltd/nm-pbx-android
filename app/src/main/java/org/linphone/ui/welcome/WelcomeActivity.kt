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
package org.linphone.ui.welcome

import android.content.Intent
import android.os.Bundle
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.databinding.DataBindingUtil
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import org.linphone.R
import org.linphone.core.tools.Log
import org.linphone.databinding.WelcomeActivityBinding
import org.linphone.ui.GenericActivity
import org.linphone.ui.assistant.AssistantActivity
import org.linphone.ui.welcome.fragment.WelcomePage1Fragment
import org.linphone.utils.AppUtils

class WelcomeActivity : GenericActivity() {
    companion object {
        private const val TAG = "[Welcome Activity]"
        private const val PAGES = 1
    }

    private lateinit var binding: WelcomeActivityBinding

    private lateinit var viewPager: ViewPager2

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Disable back gesture / button
        onBackPressedDispatcher.addCallback { }

        binding = DataBindingUtil.setContentView(this, R.layout.welcome_activity)
        binding.lifecycleOwner = this

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(insets.left, insets.top, insets.right, insets.bottom)
            WindowInsetsCompat.CONSUMED
        }

        viewPager = binding.pager
        val pagerAdapter = ScreenSlidePagerAdapter(this)
        viewPager.adapter = pagerAdapter

        binding.next.text = AppUtils.getString(R.string.start)

        binding.setNextClickListener {
            Log.i(
                "$TAG User clicked on 'start' button, leaving activity and going into Assistant"
            )
            goToAssistant()
        }
    }

    private fun goToAssistant() {
        finish()
        val intent = Intent(this, AssistantActivity::class.java)
        intent.putExtra(AssistantActivity.SKIP_LANDING_EXTRA, true)
        startActivity(intent)
    }

    private inner class ScreenSlidePagerAdapter(fa: FragmentActivity) : FragmentStateAdapter(fa) {
        override fun getItemCount(): Int = PAGES

        override fun createFragment(position: Int): Fragment {
            return WelcomePage1Fragment()
        }
    }

}
