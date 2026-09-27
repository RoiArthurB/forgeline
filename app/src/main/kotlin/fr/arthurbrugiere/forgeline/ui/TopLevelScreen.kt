package fr.arthurbrugiere.forgeline.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import fr.arthurbrugiere.forgeline.R

/** Opens search; provided by the app shell so every top-level screen offers it without extra plumbing. */
val LocalOpenSearch = staticCompositionLocalOf<(() -> Unit)?> { null }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopLevelScreen(
    title: String,
    modifier: Modifier = Modifier,
    snackbarHost: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            val openSearch = LocalOpenSearch.current
            TopAppBar(
                title = { Text(title) },
                actions = {
                    if (openSearch != null) {
                        IconButton(onClick = openSearch) {
                            Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.search))
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = snackbarHost,
        content = content,
    )
}
