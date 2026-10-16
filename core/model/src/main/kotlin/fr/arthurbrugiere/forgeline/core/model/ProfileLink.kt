package fr.arthurbrugiere.forgeline.core.model

import java.net.URI

/** Where a person or organisation can also be found, as their forge profile lists it. */
enum class LinkKind(val displayName: String) {
    EMAIL("Email"),
    MASTODON("Mastodon"),
    BLUESKY("Bluesky"),
    X("X"),
    THREADS("Threads"),
    LINKEDIN("LinkedIn"),
    YOUTUBE("YouTube"),
    TWITCH("Twitch"),
    INSTAGRAM("Instagram"),
    FACEBOOK("Facebook"),
    REDDIT("Reddit"),
    MATRIX("Matrix"),
    SIGNAL("Signal"),
    XMPP("XMPP"),
    DISCORD("Discord"),
    ORCID("ORCID"),
    NPM("npm"),
    GITHUB("GitHub"),
    GITLAB("GitLab"),
    CODEBERG("Codeberg"),
    WEB("Website"),
}

/** A link of a profile: what it is, where it goes, and how people write it ("@ana@mastodon.social"). */
data class ProfileLink(val kind: LinkKind, val url: String, val label: String) {
    companion object {
        /**
         * A link as a profile lists it, recognised from its address: GitHub files Matrix, Signal and XMPP links as
         * "generic", so the address says more than the forge does. [mastodon] when the forge says it's a Mastodon
         * account (GitHub does), whatever its server is called. Null for what isn't a link at all.
         */
        fun of(raw: String, mastodon: Boolean = false): ProfileLink? {
            val text = raw.trim().takeIf { it.isNotEmpty() } ?: return null
            if (text.startsWith("mailto:", ignoreCase = true)) return email(text.substringAfter(':'))
            if (text.startsWith("xmpp:", ignoreCase = true)) return ProfileLink(LinkKind.XMPP, text, text.substringAfter(':').substringBefore('?'))
            val url = if (text.contains("://")) text else "https://$text"
            val uri = runCatching { URI(url) }.getOrNull() ?: return null
            val host = uri.host?.lowercase()?.removePrefix("www.")?.removePrefix("m.") ?: return null
            val path = uri.path.orEmpty().trim('/')
            val first = path.substringBefore('/').removePrefix("@")
            fun handle(kind: LinkKind) = ProfileLink(kind, url, if (first.isEmpty()) kind.displayName else "@$first")
            return when {
                host == "matrix.to" -> ProfileLink(LinkKind.MATRIX, url, uri.fragment?.removePrefix("/")?.substringBefore('?')?.takeIf { it.isNotEmpty() } ?: "Matrix")
                host == "signal.me" || host == "signal.group" -> ProfileLink(LinkKind.SIGNAL, url, "Signal")
                host == "xmpp.link" -> ProfileLink(LinkKind.XMPP, url, uri.fragment?.substringBefore('?')?.takeIf { it.isNotEmpty() } ?: "XMPP")
                host == "bsky.app" -> ProfileLink(LinkKind.BLUESKY, url, path.removePrefix("profile/").substringBefore('/').takeIf { it.isNotEmpty() }?.let { "@$it" } ?: "Bluesky")
                host == "x.com" || host == "twitter.com" -> handle(LinkKind.X)
                host == "threads.net" || host == "threads.com" -> handle(LinkKind.THREADS)
                host == "instagram.com" -> handle(LinkKind.INSTAGRAM)
                host == "twitch.tv" -> handle(LinkKind.TWITCH)
                host == "facebook.com" -> ProfileLink(LinkKind.FACEBOOK, url, first.ifEmpty { "Facebook" })
                host == "linkedin.com" -> ProfileLink(LinkKind.LINKEDIN, url, path.substringAfter('/', "").substringBefore('/').ifEmpty { "LinkedIn" })
                host == "youtube.com" || host == "youtu.be" -> ProfileLink(LinkKind.YOUTUBE, url, if (path.startsWith("@")) "@$first" else "YouTube")
                host == "reddit.com" -> ProfileLink(LinkKind.REDDIT, url, path.substringAfter('/', "").substringBefore('/').takeIf { it.isNotEmpty() }?.let { "u/$it" } ?: "Reddit")
                host.endsWith("discord.gg") || host.endsWith("discord.com") -> ProfileLink(LinkKind.DISCORD, url, "Discord")
                host == "orcid.org" -> ProfileLink(LinkKind.ORCID, url, first.ifEmpty { "ORCID" })
                host == "npmjs.com" -> ProfileLink(LinkKind.NPM, url, path.substringAfterLast('/').removePrefix("~").ifEmpty { "npm" })
                host == "github.com" -> ProfileLink(LinkKind.GITHUB, url, first.ifEmpty { "GitHub" })
                host == "gitlab.com" -> ProfileLink(LinkKind.GITLAB, url, first.ifEmpty { "GitLab" })
                host == "codeberg.org" -> ProfileLink(LinkKind.CODEBERG, url, first.ifEmpty { "Codeberg" })
                // A fediverse account: "https://server/@name", on any server.
                mastodon || (path.startsWith("@") && !path.contains('/')) -> {
                    // "/@ana" on most servers, "/users/ana" on some: the name is the last part.
                    val name = path.substringAfterLast('/').removePrefix("@")
                    ProfileLink(LinkKind.MASTODON, url, if (name.isEmpty()) host else "@$name@$host")
                }
                else -> ProfileLink(LinkKind.WEB, url, (host + if (path.isEmpty()) "" else "/$path").removeSuffix("/"))
            }
        }

        /** A public address, not one of the forge's own that only hides the real one (`…@users.noreply.github.com`). */
        fun email(address: String?): ProfileLink? {
            val text = address?.trim()?.takeIf { '@' in it } ?: return null
            if (text.substringAfter('@').contains("noreply", ignoreCase = true)) return null
            return ProfileLink(LinkKind.EMAIL, "mailto:$text", text)
        }
    }
}

/** [links] without repeats: the same place said twice (an X handle and an X link) shows once, first kept. */
fun List<ProfileLink>.distinctPlaces(): List<ProfileLink> = distinctBy { it.kind to it.label.lowercase() }
