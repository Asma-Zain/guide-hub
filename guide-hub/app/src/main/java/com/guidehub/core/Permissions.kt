package com.guidehub.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object Permissions {
    fun blePermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun required(): Array<String> {
        val list = mutableListOf(Manifest.permission.RECORD_AUDIO)
        list += blePermissions()
        if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.POST_NOTIFICATIONS
        return list.toTypedArray()
    }

    fun has(context: Context, p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    fun hasBle(context: Context) = blePermissions().all { has(context, it) }

    fun hasMic(context: Context) = has(context, Manifest.permission.RECORD_AUDIO)

    fun missing(context: Context) = required().filterNot { has(context, it) }
}
