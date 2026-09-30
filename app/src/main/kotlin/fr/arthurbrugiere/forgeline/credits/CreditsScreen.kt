package fr.arthurbrugiere.forgeline.credits

import fr.arthurbrugiere.forgeline.ui.sideSafeArea
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer
import com.mikepenz.aboutlibraries.util.withContext
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.settings.SOURCE_CODE_URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.sp
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftHeader
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTonalButton
import fr.arthurbrugiere.forgeline.ui.listBottomPadding
import androidx.compose.runtime.getValue

@Composable
fun CreditsRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val libraries by produceState<Libs?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { Libs.Builder().withContext(context).build() }
    }
    CreditsScreen(
        libraries = libraries,
        onOpenLicense = { uriHandler.openUri("$SOURCE_CODE_URL/blob/main/LICENSE") },
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreditsScreen(
    libraries: Libs?,
    onOpenLicense: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Soft.colors
    Box(modifier.fillMaxSize().background(colors.ground).sideSafeArea()) {
        LibrariesContainer(
            libraries = libraries,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = listBottomPadding()),
            header = {
                item(key = "header") {
                    SoftHeader(
                        tint = colors.fields[2],
                        title = stringResource(R.string.credits_title),
                        onBack = onBack,
                        backDescription = stringResource(R.string.navigate_up),
                    )
                }
                item(key = "app-license") { AppLicenseHeader(onOpenLicense) }
            },
        )
    }
}

@Composable
private fun AppLicenseHeader(onOpenLicense: () -> Unit) {
    val colors = Soft.colors
    Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.app_name), style = Soft.type.name, color = colors.ink)
        Text(stringResource(R.string.credits_app_license), style = Soft.type.body, color = colors.inkMuted)
        SoftTonalButton(stringResource(R.string.credits_read_license), onOpenLicense, Modifier.padding(top = 4.dp))
        // The fonts ship in the app under the SIL Open Font License; the library list only covers Gradle dependencies.
        Text(stringResource(R.string.credits_fonts), style = Soft.type.secondary, color = colors.inkMuted, modifier = Modifier.padding(top = 8.dp))
        Text(stringResource(R.string.credits_icons), style = Soft.type.secondary, color = colors.inkMuted, modifier = Modifier.padding(top = 8.dp))
        Text(
            stringResource(R.string.credits_libraries_intro),
            style = Soft.type.section,
            color = colors.ink,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

