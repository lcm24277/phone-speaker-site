package com.mobilespeaker.app.util

import android.content.Context
import android.util.Log
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object AppLogger {
    private const val TAG = "MobileSpeakerAndroid"

    fun init(context: Context) = Unit

    fun log(line: String) {
        val ts = OffsetDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val formatted = "$ts | $line"
        Log.d(TAG, formatted)
    }
}
