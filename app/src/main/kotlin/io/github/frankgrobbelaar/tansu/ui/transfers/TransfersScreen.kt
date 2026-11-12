package io.github.frankgrobbelaar.tansu.ui.transfers

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.frankgrobbelaar.tansu.R
import io.github.frankgrobbelaar.tansu.ui.kit.EmptyState
import io.github.frankgrobbelaar.tansu.ui.kit.TansuIcons
import io.github.frankgrobbelaar.tansu.ui.kit.TansuTopBar

/** Every copy, move and delete, while it runs and for a while after. */
@Composable
fun TransfersScreen(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize()) {
        TansuTopBar(title = stringResource(R.string.nav_transfers))
        EmptyState(
            icon = TansuIcons.Transfers,
            title = stringResource(R.string.transfers_empty_title),
            body = stringResource(R.string.transfers_empty_body),
        )
    }
}
