package com.meo.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meo.diagnostics.CrashLog
import com.meo.ui.theme.Catpuccin

/**
 * Shown once, after Meo has died unexpectedly.
 *
 * Plan §6.5 forbids uploading anything, ever, so the only way a crash on a
 * phone the maintainer does not own can ever be fixed is if the user can see it
 * and chooses to pass it on. Hence: visible, readable, and shared only by an
 * explicit tap.
 */
@Composable
fun CrashReportBanner() {
    val context = LocalContext.current
    var report by remember { mutableStateOf(CrashLog.read(context)) }
    var expanded by remember { mutableStateOf(false) }

    val current = report ?: return

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Catpuccin.Surface0)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Meo closed unexpectedly",
                color = Catpuccin.Red,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "A report was saved on this phone. Nothing has been sent " +
                    "anywhere. Sharing it is what makes the crash fixable.",
                color = Catpuccin.Subtext0,
                fontSize = 13.sp
            )

            if (expanded) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = current,
                    color = Catpuccin.Subtext1,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "Meo crash report")
                            putExtra(Intent.EXTRA_TEXT, current)
                        }
                        context.startActivity(Intent.createChooser(share, "Send crash report"))
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Catpuccin.Mauve,
                        contentColor = Catpuccin.Crust
                    )
                ) {
                    Text("Share report", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                TextButton(onClick = { expanded = !expanded }) {
                    Text(
                        text = if (expanded) "Hide" else "Show",
                        color = Catpuccin.Subtext1,
                        fontSize = 13.sp
                    )
                }
                TextButton(
                    onClick = {
                        CrashLog.clear(context)
                        report = null
                    }
                ) {
                    Text("Dismiss", color = Catpuccin.Subtext0, fontSize = 13.sp)
                }
            }
        }
    }
}
