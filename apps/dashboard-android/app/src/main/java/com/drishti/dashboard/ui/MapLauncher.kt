package com.drishti.dashboard.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
fun rememberMapLauncher(): (Double, Double, String) -> Unit {
    val context = LocalContext.current
    return remember(context) {
        { latitude, longitude, label ->
            val query = Uri.encode("$latitude,$longitude ($label)")
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$latitude,$longitude?q=$query"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        }
    }
}
