package fr.arthurbrugiere.forgeline.forge.bitbucket

import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.forge.bitbucket.api.Account
import java.util.concurrent.ConcurrentHashMap

/**
 * Who people are on Bitbucket. Since 2019 (Atlassian's privacy changes) an account has no username anyone can address
 * it by: the API names it by a UUID (`{933f1401-…}`) and an Atlassian account id, and shows its nickname. Forgeline
 * names a person by that nickname, as Bitbucket's own pages do, and remembers the UUID behind each nickname it was told,
 * so that a profile opened, or a reviewer asked, by name reaches the right account. A workspace (where repositories
 * live, a person's own or a team's) is named by its slug, which is addressable.
 */
class BitbucketPeople {
    private val uuids = ConcurrentHashMap<String, String>()

    /** The person [account] is, remembered so that their nickname can be asked for later. */
    fun user(account: Account?): ForgeUser? {
        account ?: return null
        val login = loginOf(account) ?: return null
        account.uuid?.let { uuids[login.lowercase()] = it }
        return ForgeUser(login, account.displayName?.takeIf { it != login }, account.links?.avatar?.href)
    }

    /** The UUID of the person named [login], where it was seen; a UUID or an account id is taken as it is. */
    fun uuidOf(login: String): String? = when {
        login.startsWith("{") && login.endsWith("}") -> login
        ACCOUNT_ID.matches(login) -> login
        else -> uuids[login.lowercase()]
    }

    companion object {
        /** An Atlassian account id: `557057:845513f1-…`, or 24 hex digits for the older ones. */
        private val ACCOUNT_ID = Regex("""\d+:[0-9a-f-]{36}|[0-9a-f]{24}""")

        /** How an account is named: its nickname, else what else it has (a team's username, its display name, its UUID). */
        fun loginOf(account: Account): String? =
            account.nickname?.takeIf { it.isNotBlank() }
                ?: account.username?.takeIf { it.isNotBlank() }
                ?: account.displayName?.takeIf { it.isNotBlank() }
                ?: account.uuid
    }
}
