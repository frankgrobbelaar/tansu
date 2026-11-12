package io.github.frankgrobbelaar.tansu.ui.browse

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.frankgrobbelaar.tansu.R
import io.github.frankgrobbelaar.tansu.ui.kit.TansuTopBar

/** The drawers and what's in them. */
@Composable
fun BrowseScreen(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize()) {
        TansuTopBar(title = stringResource(R.string.nav_browse))
    }
}
