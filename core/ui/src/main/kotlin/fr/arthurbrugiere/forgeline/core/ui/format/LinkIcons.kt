package fr.arthurbrugiere.forgeline.core.ui.format

import androidx.annotation.DrawableRes
import fr.arthurbrugiere.forgeline.core.model.LinkKind
import fr.arthurbrugiere.forgeline.core.ui.R

/** A profile link's logo (Simple Icons, CC0, in one colour); null for an email or a website, drawn with plain icons. */
@get:DrawableRes
val LinkKind.iconRes: Int?
    get() = when (this) {
        LinkKind.MASTODON -> R.drawable.ic_link_mastodon
        LinkKind.BLUESKY -> R.drawable.ic_link_bluesky
        LinkKind.X -> R.drawable.ic_link_x
        LinkKind.THREADS -> R.drawable.ic_link_threads
        LinkKind.LINKEDIN -> R.drawable.ic_link_linkedin
        LinkKind.YOUTUBE -> R.drawable.ic_link_youtube
        LinkKind.TWITCH -> R.drawable.ic_link_twitch
        LinkKind.INSTAGRAM -> R.drawable.ic_link_instagram
        LinkKind.FACEBOOK -> R.drawable.ic_link_facebook
        LinkKind.REDDIT -> R.drawable.ic_link_reddit
        LinkKind.MATRIX -> R.drawable.ic_link_matrix
        LinkKind.SIGNAL -> R.drawable.ic_link_signal
        LinkKind.XMPP -> R.drawable.ic_link_xmpp
        LinkKind.DISCORD -> R.drawable.ic_link_discord
        LinkKind.ORCID -> R.drawable.ic_link_orcid
        LinkKind.NPM -> R.drawable.ic_link_npm
        LinkKind.GITHUB -> R.drawable.ic_forge_github
        LinkKind.GITLAB -> R.drawable.ic_forge_gitlab
        LinkKind.CODEBERG -> R.drawable.ic_forge_codeberg
        LinkKind.EMAIL, LinkKind.WEB -> null
    }
