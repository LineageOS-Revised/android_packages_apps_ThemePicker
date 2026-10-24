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

import android.content.Context
import android.content.om.OverlayManager
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.SystemProperties
import android.os.UserHandle
import android.util.Log
import com.android.themepicker.R
import lineageos.providers.LineageSettings

/** Marker option used to surface the picker in the lock screen customization list. */
enum class UdfpsAnimationCustomizationOption :
    com.android.wallpaper.picker.customization.ui.util.CustomizationOptionUtil.CustomizationOption {
    FINGERPRINT_ANIMATION,
}

/** An installed OxygenOS UDFPS animation overlay and its preview resources. */
data class UdfpsAnimationOption(
    val packageName: String,
    val name: CharSequence,
    val order: Int,
    val previewIcon: Drawable?,
    val previewVideo: Int,
    val previewSound: Int,
)

/** Discovers the UDFPS animation overlays shipped by vendor/lineage/udfpsanimations. */
object UdfpsAnimationHelper {

    const val PROP_ANIMATIONS = "ro.udfps.animations"
    const val DEFAULT_PACKAGE = ""
    private const val TAG = "UdfpsAnimationHelper"
    private const val SYSTEMUI_PACKAGE = "com.android.systemui"
    private const val CATEGORY = "category.systemui.fingerprint.anim"
    private const val PACKAGE_PREFIX = "com.android.systemui.overlay.fingerprint.anim."

    val isSupported: Boolean
        get() = SystemProperties.getBoolean(PROP_ANIMATIONS, false)

    fun load(context: Context): List<UdfpsAnimationOption> {
        if (!isSupported) return emptyList()
        val pm = context.packageManager
        val packages = LinkedHashSet<String>()

        // Preferred source: overlays registered by OverlayManager.
        try {
            context.getSystemService(OverlayManager::class.java)
                ?.getOverlayInfosForTarget(SYSTEMUI_PACKAGE, UserHandle.of(context.userId))
                ?.forEach { overlay ->
                    if (overlay.category == CATEGORY) packages.add(overlay.packageName)
                }
        } catch (t: Throwable) {
            Log.w(TAG, "OverlayManager discovery failed", t)
        }

        // Fallback for preinstalled overlays that OverlayManager does not report.
        if (packages.isEmpty()) {
            try {
                pm.getInstalledApplications(PackageManager.MATCH_DISABLED_COMPONENTS)
                    .forEach { appInfo ->
                        if (appInfo.packageName.startsWith(PACKAGE_PREFIX)) {
                            packages.add(appInfo.packageName)
                        }
                    }
            } catch (t: Throwable) {
                Log.w(TAG, "PackageManager discovery failed", t)
            }
        }

        val result = ArrayList<UdfpsAnimationOption>(packages.size + 1)
        result.add(
            UdfpsAnimationOption(
                packageName = DEFAULT_PACKAGE,
                name = context.getString(R.string.udfps_animation_default),
                order = Int.MIN_VALUE,
                previewIcon = context.getDrawable(R.drawable.ic_udfps_animation),
                previewVideo = 0,
                previewSound = 0,
            )
        )
        packages.forEach { packageName ->
            val appInfo = applicationInfo(pm, packageName) ?: return@forEach
            try {
                result.add(buildOption(context, pm, appInfo, packageName))
            } catch (t: Throwable) {
                Log.w(TAG, "Unable to load $packageName", t)
            }
        }
        Log.d(TAG, "Discovered ${result.size - 1} UDFPS animations")
        return result.sortedBy { it.order }
    }

    fun getSelectedPackage(context: Context): String? =
        LineageSettings.Secure.getString(
            context.contentResolver,
            LineageSettings.Secure.UDFPS_ANIMATION_STYLE,
        )

    /** Display name of the currently applied animation, for the customization list summary. */
    fun getActiveName(context: Context): CharSequence {
        val packageName = getSelectedPackage(context)
        if (packageName.isNullOrEmpty()) {
            return context.getString(R.string.udfps_animation_default)
        }
        return try {
            context.packageManager
                .getApplicationInfo(packageName, 0)
                .loadLabel(context.packageManager)
        } catch (e: PackageManager.NameNotFoundException) {
            context.getString(R.string.udfps_animation_default)
        }
    }

    fun setSelectedPackage(context: Context, packageName: String?): Boolean =
        try {
            LineageSettings.Secure.putString(
                context.contentResolver,
                LineageSettings.Secure.UDFPS_ANIMATION_STYLE,
                packageName ?: DEFAULT_PACKAGE,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Unable to save UDFPS animation", t)
            false
        }

    private fun buildOption(
        context: Context,
        pm: PackageManager,
        appInfo: ApplicationInfo,
        packageName: String,
    ): UdfpsAnimationOption {
        val metadata = appInfo.metaData
        val resources = try {
            pm.getResourcesForApplication(packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
        val iconRes = metadata?.getInt(METADATA_ICON, 0) ?: 0
        val fallbackIconRes = metadata?.getInt(METADATA_PREVIEW_ICON, 0) ?: 0
        val previewIcon = when {
            resources == null -> appInfo.loadIcon(pm)
            iconRes != 0 -> resources.getDrawable(iconRes, null)
            fallbackIconRes != 0 -> resources.getDrawable(fallbackIconRes, null)
            else -> appInfo.loadIcon(pm)
        }
        return UdfpsAnimationOption(
            packageName = packageName,
            name = appInfo.loadLabel(pm),
            order = metadata?.getInt(METADATA_ORDER, 0) ?: 0,
            previewIcon = previewIcon,
            previewVideo = metadata?.getInt(METADATA_ANIM, 0) ?: 0,
            previewSound = metadata?.getInt(METADATA_SOUND, 0) ?: 0,
        )
    }

    private fun applicationInfo(pm: PackageManager, packageName: String): ApplicationInfo? =
        try {
            pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }

    private const val METADATA_ORDER = "order"
    private const val METADATA_PREVIEW_ICON = "entrance_preview_icon"
    private const val METADATA_ICON = "fingerprint_icon"
    private const val METADATA_ANIM = "fingerprint_anim"
    private const val METADATA_SOUND = "fingerprint_sound"
}
