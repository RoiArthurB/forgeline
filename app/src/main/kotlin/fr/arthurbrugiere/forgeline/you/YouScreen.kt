package fr.arthurbrugiere.forgeline.you

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.model.Account
import fr.arthurbrugiere.forgeline.core.ui.soft.Soft
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftHeader
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftNotice
import fr.arthurbrugiere.forgeline.core.ui.soft.SoftTokens
import fr.arthurbrugiere.forgeline.core.ui.soft.softPressable
import fr.arthurbrugiere.forgeline.session.SessionState
import fr.arthurbrugiere.forgeline.ui.Avatar
import fr.arthurbrugiere.forgeline.ui.LocalOpenSearch
import fr.arthurbrugiere.forgeline.ui.listBottomPadding

@Composable
fun YouScreen(
    session: SessionState,
    onSignIn: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenProfile: (String) -> Unit = {},
) {
    val colors = Soft.colors
    Column(
        modifier
            .fillMaxSize()
            .background(colors.ground)
            .verticalScroll(rememberScrollState())
            .padding(bottom = listBottomPadding()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val openSearch = LocalOpenSearch.current
        SoftHeader(
            tint = colors.fields[2],
            title = stringResource(R.string.tab_you),
            actions = {
                if (openSearch != null) {
                    IconButton(onClick = openSearch) {
                        Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.search), tint = colors.ink)
                    }
                }
            },
        ) {
            if (session is SessionState.SignedIn) {
                AccountHeader(session.account) { onOpenProfile(session.account.user.login) }
            }
        }
        if (session == SessionState.SignedOut) {
            SoftNotice(
                stringResource(R.string.you_signed_out_title),
                stringResource(R.string.you_signed_out_body),
                action = stringResource(R.string.sign_in),
                onAction = onSignIn,
            )
        }
        Spacer(Modifier.padding(top = 8.dp))
        NavigationRow(Icons.Outlined.Settings, stringResource(R.string.settings_title), onOpenSettings)
    }
}

/** Who is signed in, big and friendly on the field; tapping it opens their profile. */
@Composable
private fun AccountHeader(account: Account, onClick: () -> Unit) {
    val colors = Soft.colors
    Row(
        Modifier
            .fillMaxWidth()
            .softPressable(role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(
            account.user.avatarUrl,
            account.user.login,
            size = 64.dp,
            placeholderColor = colors.ground,
            placeholderContentColor = colors.inkMuted,
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                account.user.name ?: account.user.login,
                style = Soft.type.name,
                color = colors.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text("@${account.user.login} · ${account.forge.host}", style = Soft.type.secondary, color = colors.inkMuted)
        }
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = colors.inkMuted)
    }
}

/** A row that leads somewhere: a soft round icon, its name, and a chevron. */
@Composable
internal fun NavigationRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    val colors = Soft.colors
    Row(
        Modifier
            .widthIn(max = SoftTokens.MaxReadingWidth)
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .softPressable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).background(colors.surface, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = colors.ink, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(14.dp))
        Text(label, style = Soft.type.body, color = colors.ink, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = colors.inkMuted)
    }
}
