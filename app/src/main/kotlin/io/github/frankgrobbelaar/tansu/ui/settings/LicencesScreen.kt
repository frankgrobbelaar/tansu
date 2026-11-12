package io.github.frankgrobbelaar.tansu.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import io.github.frankgrobbelaar.tansu.R
import io.github.frankgrobbelaar.tansu.ui.kit.TansuIcons
import io.github.frankgrobbelaar.tansu.ui.kit.TansuTopBar

/** Something tansu is built with, under its licence, and where to read both. */
private class Work(val name: String, val licence: String, val url: String)

private const val APACHE = "Apache License 2.0"
private const val MIT = "MIT License"

/** What ships inside tansu, besides tansu itself. */
private val works =
    listOf(
        Work("Tansu", MIT, "https://github.com/frankgrobbelaar/tansu"),
        Work("AndroidX and Jetpack Compose", APACHE, "https://github.com/androidx/androidx"),
        Work("Media3", APACHE, "https://github.com/androidx/media"),
        Work("Kotlin and kotlinx", APACHE, "https://github.com/JetBrains/kotlin"),
        Work("Coil", APACHE, "https://github.com/coil-kt/coil"),
        Work("Okio", APACHE, "https://github.com/square/okio"),
        Work("smbj", APACHE, "https://github.com/hierynomus/smbj"),
        Work("sshj", APACHE, "https://github.com/hierynomus/sshj"),
        Work("ASN.One", APACHE, "https://github.com/hierynomus/asn-one"),
        Work("Bouncy Castle", "Bouncy Castle Licence", "https://www.bouncycastle.org/licence.html"),
        Work("MBassador", MIT, "https://github.com/bennidi/mbassador"),
        Work("SLF4J", MIT, "https://www.slf4j.org/license.html"),
        Work("Guava", APACHE, "https://github.com/google/guava"),
        Work("Accompanist", APACHE, "https://github.com/google/accompanist"),
        Work("JSpecify", APACHE, "https://github.com/jspecify/jspecify"),
        Work("Figtree", "SIL Open Font License 1.1", "https://github.com/erikdkennedy/figtree"),
    )

/** Tansu and the open-source work it's built with, each opening in the browser. */
@Composable
fun LicencesScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(modifier = modifier.fillMaxSize()) {
        TansuTopBar(
            title = stringResource(R.string.licences),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(TansuIcons.Back, contentDescription = stringResource(R.string.back))
                }
            },
        )
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Text(
                    text = stringResource(R.string.licences_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
            items(works, key = { it.name }) { work ->
                Column(
                    modifier =
                        Modifier.fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .clickable(role = Role.Button) { browse(context, work.url) }
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(text = work.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = work.licence,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Opens [url] in the browser: the browser goes there, not tansu. */
private fun browse(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    } catch (_: ActivityNotFoundException) {
        // No browser: the name and licence are on the screen all the same.
    }
}
