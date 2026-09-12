package com.drishti.dashboard.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Open the dialler with a number filled in.
 *
 * ACTION_DIAL, never ACTION_CALL. The app has no CALL_PHONE permission and
 * should not have one: a monitoring tool must not be able to place a call on
 * its own, and the operator's final tap is what makes the call theirs. It also
 * means a mis-tap on a red card cannot ring anybody.
 */
@Composable
fun rememberDialer(): (String) -> Unit {
    val context = LocalContext.current
    return remember(context) { { number -> dial(context, number) } }
}

private fun dial(context: Context, number: String) {
    // Spaces and punctuation are fine in a tel: URI, but stripping them keeps
    // diallers that parse strictly from dropping the leading +.
    val cleaned = number.filter { it.isDigit() || it == '+' }
    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$cleaned"))
    try {
        context.startActivity(intent)
    } catch (absent: ActivityNotFoundException) {
        // A tablet with no dialler. Say so rather than doing nothing: the
        // operator needs to know to reach for another phone.
        Toast.makeText(context, "No dialler on this device — call $number", Toast.LENGTH_LONG).show()
    }
}
