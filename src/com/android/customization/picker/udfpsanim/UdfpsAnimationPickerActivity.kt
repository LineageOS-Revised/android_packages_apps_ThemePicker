/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.customization.picker.udfpsanim

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import android.widget.Toolbar
import android.widget.VideoView
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.android.themepicker.R
import com.android.wallpaper.R as WallpaperR
import com.android.wallpaper.picker.customization.ui.view.ApplyButton
import java.io.File

/** Previews and picks the UDFPS unlock animation. */
class UdfpsAnimationPickerActivity : Activity() {

    private lateinit var videoView: VideoView
    private lateinit var previewIconView: ImageView
    private lateinit var selectedNameView: TextView
    private lateinit var statusView: TextView
    private lateinit var applyButton: ApplyButton
    private lateinit var adapter: AnimationAdapter
    private var options: List<UdfpsAnimationOption> = emptyList()
    private var selectedPackage: String = UdfpsAnimationHelper.DEFAULT_PACKAGE
    private var appliedPackage: String = UdfpsAnimationHelper.DEFAULT_PACKAGE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_udfps_animation_picker)

        findViewById<View>(R.id.udfps_animation_root).setOnApplyWindowInsetsListener { view, insets ->
            val bars =
                insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsets.CONSUMED
        }

        findViewById<View>(R.id.udfps_animation_back).setOnClickListener { finish() }
        applyButton = findViewById<ApplyButton>(R.id.udfps_animation_apply).apply {
            setApplyButtonBackgroundColor(getColor(WallpaperR.color.system_primary))
            setIndicatorColor(getColor(WallpaperR.color.system_on_primary))
            setOnClickListener { applySelection() }
        }
        findViewById<Toolbar>(R.id.udfps_animation_toolbar).title =
            getString(R.string.udfps_animation_title)

        videoView = findViewById(R.id.udfps_animation_preview)
        videoView.setOnErrorListener { _, _, _ ->
            videoView.visibility = View.GONE
            true
        }
        previewIconView = findViewById(R.id.udfps_animation_preview_icon)
        selectedNameView = findViewById(R.id.udfps_animation_selected_name)
        statusView = findViewById(R.id.udfps_animation_status)

        options = UdfpsAnimationHelper.load(this)
        appliedPackage =
            UdfpsAnimationHelper.getSelectedPackage(this) ?: UdfpsAnimationHelper.DEFAULT_PACKAGE
        selectedPackage = appliedPackage

        adapter = AnimationAdapter()
        findViewById<RecyclerView>(R.id.udfps_animation_list).apply {
            layoutManager =
                LinearLayoutManager(this@UdfpsAnimationPickerActivity, LinearLayoutManager.HORIZONTAL, false)
            adapter = this@UdfpsAnimationPickerActivity.adapter
            val selectedIndex = options.indexOfFirst { it.packageName == selectedPackage }
            if (selectedIndex > 0) scrollToPosition(selectedIndex)
        }
    }

    override fun onStart() {
        super.onStart()
        showPreview(selectedPackage)
    }

    override fun onStop() {
        super.onStop()
        videoView.stopPlayback()
    }

    private fun onOptionSelected(option: UdfpsAnimationOption) {
        selectedPackage = option.packageName
        adapter.notifyDataSetChanged()
        showPreview(option.packageName)
    }

    private fun applySelection() {
        val saved = UdfpsAnimationHelper.setSelectedPackage(this, selectedPackage)
        Log.d(
            TAG,
            "Apply '$selectedPackage' saved=$saved now=" +
                UdfpsAnimationHelper.getSelectedPackage(this),
        )
        if (!saved) {
            Toast.makeText(this, R.string.udfps_animation_apply_failed, Toast.LENGTH_SHORT).show()
            return
        }
        appliedPackage = selectedPackage
        restartSystemUi()
        finish()
    }

    /** SystemUI is persistent, so force-stop refuses it; only a root kill can restart it. */
    private fun restartSystemUi() {
        try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "killall $SYSTEMUI_PACKAGE"))
        } catch (t: Throwable) {
            Log.d(TAG, "SystemUI restart unavailable: ${t.message}")
        }
    }

    private fun updateSelectionUi() {
        val isActive = selectedPackage == appliedPackage
        statusView.text = if (isActive) getString(R.string.udfps_animation_active) else ""
        applyButton.setApplyButtonState(
            if (isActive) {
                ApplyButton.ApplyButtonState.APPLY_BUTTON_DISABLED
            } else {
                ApplyButton.ApplyButtonState.APPLY_BUTTON_ENABLED
            }
        )
        applyButton.setApplyButtonTextColor(
            if (isActive) {
                ColorUtils.setAlphaComponent(getColor(WallpaperR.color.system_on_surface), 97)
            } else {
                getColor(WallpaperR.color.system_on_primary)
            }
        )
    }

    private fun showPreview(packageName: String) {
        videoView.stopPlayback()
        videoView.visibility = View.GONE
        previewIconView.visibility = View.GONE

        val option =
            options.firstOrNull { it.packageName == packageName } ?: options.firstOrNull() ?: return
        previewIconView.setImageDrawable(option.previewIcon)
        selectedNameView.text = option.name
        updateSelectionUi()

        if (option.previewVideo == 0) {
            previewIconView.visibility = View.VISIBLE
            return
        }
        if (playVideo(option)) {
            videoView.visibility = View.VISIBLE
        } else {
            previewIconView.visibility = View.VISIBLE
        }
    }

    private fun playVideo(option: UdfpsAnimationOption): Boolean {
        val file = File(cacheDir, "udfps_preview_${option.packageName.ifEmpty { "default" }}.mp4")
        try {
            val resources =
                if (option.packageName.isEmpty()) {
                    resources
                } else {
                    packageManager.getResourcesForApplication(option.packageName)
                }
            resources.openRawResource(option.previewVideo)
                .use { input -> file.outputStream().use { input.copyTo(it) } }
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to extract preview for ${option.packageName}", t)
            return false
        }
        if (file.length() == 0L) return false

        videoView.setVideoPath(file.absolutePath)
        videoView.setOnPreparedListener { player ->
            player.isLooping = true
            player.setVolume(0f, 0f)
            videoView.start()
        }
        return true
    }

    private inner class AnimationAdapter : RecyclerView.Adapter<AnimationAdapter.Holder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(layoutInflater.inflate(R.layout.item_udfps_animation, parent, false))

        override fun getItemCount(): Int = options.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val option = options[position]
            val isSelected = option.packageName == selectedPackage
            holder.icon.setImageDrawable(option.previewIcon)
            holder.check.visibility = if (isSelected) View.VISIBLE else View.GONE
            holder.itemView.isSelected = isSelected
            holder.itemView.contentDescription = option.name
            holder.itemView.setOnClickListener { onOptionSelected(option) }
        }

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.udfps_animation_item_icon)
            val check: ImageView = view.findViewById(R.id.udfps_animation_item_check)
        }
    }

    companion object {
        private const val TAG = "UdfpsAnimationPicker"
        private const val SYSTEMUI_PACKAGE = "com.android.systemui"
    }
}
