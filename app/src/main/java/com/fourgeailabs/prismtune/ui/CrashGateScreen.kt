package com.fourgeailabs.prismtune.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 1.4.1 CRASH GATE screen — deliberately minimal.
 *
 * Shown ONLY when the previous launch died before drawing
 * (launch marker still present). It uses NO ViewModel, NO
 * player, NO library load, NO media service, NO Coil — just
 * plain files and prefs — so it can draw even when the full
 * app cannot launch. From here the user can copy the crash that
 * killed the last launch, clear the saved queue or the
 * metadata cache without signing out, and then try a normal
 * launch. If the normal launch dies before drawing again,
 * this screen comes back on the next attempt, with the new
 * crash appended — no uninstall needed to reach it.
 */
@androidx.compose.runtime.Composable
fun CrashGateScreen(
    onTryNormalLaunch: () -> Unit,
    onClearSavedQueue: () -> Unit,
    onClearMetadataCache: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var crashHistory by remember {
        mutableStateOf(com.fourgeailabs.prismtune.data.CrashLog.readHistory(context))
    }
    var clearedMessage by remember { mutableStateOf<String?>(null) }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
    ) {
        Text("PrismTune did not open last time", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface)
        Text(
            "The last launch closed before the app could draw, so this safe screen opened instead of the full app. Nothing here plays music or loads your library — it just lets you see what crashed and clear it, without uninstalling or signing out.",
            fontSize = 16.sp, color = PrismSecondary, modifier = Modifier.padding(vertical = 8.dp),
        )
        if (clearedMessage != null) {
            Text(clearedMessage!!, fontSize = 16.sp, color = PrismOnSurface, modifier = Modifier.padding(vertical = 8.dp))
        }
        OutlinedButton(
            onClick = onTryNormalLaunch,
            modifier = Modifier.fillMaxWidth().heightIn(min = 76.dp).padding(vertical = 4.dp),
        ) { Text("Try normal launch", fontSize = 18.sp) }
        OutlinedButton(
            onClick = { onClearSavedQueue(); clearedMessage = "Saved queue cleared. Sign-in kept. Now tap Try normal launch." },
            modifier = Modifier.fillMaxWidth().heightIn(min = 76.dp).padding(vertical = 4.dp),
        ) { Text("Clear saved queue (keep sign-in)", fontSize = 16.sp) }
        OutlinedButton(
            onClick = { onClearMetadataCache(); clearedMessage = "Metadata cache cleared. Sign-in kept. Now tap Try normal launch." },
            modifier = Modifier.fillMaxWidth().heightIn(min = 76.dp).padding(vertical = 4.dp),
        ) { Text("Clear metadata cache (keep sign-in)", fontSize = 16.sp) }
        Text("Last crash", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = PrismOnSurface, modifier = Modifier.padding(top = 16.dp))
        if (crashHistory.isEmpty()) {
            Text("No crash was recorded for the last launch. That itself is useful: it means the app was killed by the system, not by its own exception, or it closed before the crash handler could write.", fontSize = 16.sp, color = PrismSecondary, modifier = Modifier.padding(vertical = 8.dp))
        } else {
            crashHistory.forEach { entry ->
                Text(entry.title, fontSize = 15.sp, color = PrismOnSurface, modifier = Modifier.padding(vertical = 4.dp))
                Text(entry.body.take(1200), fontSize = 14.sp, color = PrismSecondary, modifier = Modifier.padding(vertical = 4.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { clipboard.setText(AnnotatedString(entry.fullText)) },
                        modifier = Modifier.weight(1f).heightIn(min = 64.dp).padding(2.dp),
                    ) { Text("Copy this crash", fontSize = 16.sp) }
                    OutlinedButton(
                        onClick = {
                            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(android.content.Intent.EXTRA_TEXT, entry.fullText)
                                putExtra(android.content.Intent.EXTRA_SUBJECT, "PrismTune crash log")
                            }
                            context.startActivity(android.content.Intent.createChooser(intent, "Share crash log"))
                        },
                        modifier = Modifier.weight(1f).heightIn(min = 64.dp).padding(2.dp),
                    ) { Text("Share this crash", fontSize = 16.sp) }
                }
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = {
                        com.fourgeailabs.prismtune.data.CrashLog.clear(context)
                        crashHistory = emptyList()
                    },
                    modifier = Modifier.weight(1f).heightIn(min = 64.dp).padding(2.dp),
                ) { Text("Clear crash history", fontSize = 16.sp) }
            }
        }
    }
}
