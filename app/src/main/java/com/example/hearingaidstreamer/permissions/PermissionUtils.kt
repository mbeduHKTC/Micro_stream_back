package com.example.hearingaidstreamer.permissions

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

fun missingPermissions(context: Context, permissions: Collection<String>): List<String> {
    return permissions.filter { permission ->
        ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
    }
}
