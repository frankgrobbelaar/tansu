package io.github.frankgrobbelaar.tansu.ui.firstrun

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.frankgrobbelaar.tansu.R
import io.github.frankgrobbelaar.tansu.ui.kit.TansuIcons

/**
 * The two screens before Browse the first time: what tansu is, then the one permission it needs.
 * [onOpenSettings] opens Android's All files access screen; [onNotNow] goes on without it.
 */
@Composable
fun FirstRunScreen(
    onOpenSettings: () -> Unit,
    onNotNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var explaining by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = explaining) { explaining = false }
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .safeDrawingPadding()
    ) {
        if (explaining) {
            AccessStep(onBack = { explaining = false }, onOpenSettings, onNotNow)
        } else {
            WelcomeStep(onStart = { explaining = true })
        }
    }
}

@Composable
private fun WelcomeStep(onStart: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier =
            modifier.fillMaxSize().padding(start = 28.dp, end = 28.dp, top = 20.dp, bottom = 28.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Image(
                painterResource(R.drawable.mark),
                contentDescription = null,
                modifier = Modifier.size(32.dp),
            )
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.illustration_tansu), contentDescription = null)
        }
        Text(stringResource(R.string.welcome_title), style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.welcome_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Promise(stringResource(R.string.welcome_no_sign_in))
        Promise(stringResource(R.string.welcome_no_ads))
        Promise(stringResource(R.string.welcome_passwords))
        Spacer(Modifier.height(24.dp))
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(stringResource(R.string.welcome_start))
        }
    }
}

@Composable
private fun Promise(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier =
                Modifier.size(26.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                TansuIcons.Check,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun AccessStep(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onNotNow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().padding(bottom = 28.dp)) {
        IconButton(onClick = onBack, modifier = Modifier.padding(6.dp)) {
            Icon(TansuIcons.Back, contentDescription = stringResource(R.string.back))
        }
        AccessExplainer(modifier = Modifier.weight(1f).padding(horizontal = 28.dp))
        Column(
            modifier = Modifier.padding(horizontal = 28.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(stringResource(R.string.access_open_settings))
            }
            TextButton(onClick = onNotNow, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text(stringResource(R.string.not_now))
            }
        }
    }
}

/** Why tansu asks for All files access, before Android asks the same thing. */
@Composable
private fun AccessExplainer(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterVertically),
    ) {
        Box(
            modifier =
                Modifier.size(64.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                TansuIcons.Folder,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Text(stringResource(R.string.access_title), style = MaterialTheme.typography.headlineMedium)
        Text(
            text =
                buildAnnotatedString {
                    append(stringResource(R.string.access_body_before))
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(stringResource(R.string.access_body_name))
                    }
                    append(stringResource(R.string.access_body_after))
                },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier =
                Modifier.clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                TansuIcons.Shield,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.secondary,
            )
            Text(
                text = stringResource(R.string.access_promise),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
