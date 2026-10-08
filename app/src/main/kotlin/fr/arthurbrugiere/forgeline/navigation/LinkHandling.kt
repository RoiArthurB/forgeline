package fr.arthurbrugiere.forgeline.navigation

import fr.arthurbrugiere.forgeline.core.model.ForgeInstance
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.RepoId

/**
 * Opens a link tapped in forge content (a README, a comment, a file) in the app when it points at a repository, a
 * conversation, a release, a run or a person on a forge Forgeline knows, and in the browser otherwise. [here] is the forge the
 * content is on: a person on another forge opens in the browser, since [onOpenUser] only knows logins here.
 */
fun openForgeLink(
    url: String,
    here: ForgeInstance,
    onOpenRepo: (RepoId) -> Unit,
    onOpenIssue: (IssueRef) -> Unit,
    onOpenUser: (String) -> Unit,
    openUrl: (String) -> Unit,
    onOpenRun: ((RepoId, Long) -> Unit)? = null,
    onOpenRelease: ((RepoId, String) -> Unit)? = null,
    /** Null outside the app shell: a discussion then opens on its forge. */
    onOpenDiscussion: ((RepoId, Int) -> Unit)? = null,
) {
    when (val target = ForgeLinks.routeFor(url)) {
        is RepoRoute -> onOpenRepo(target.repo)
        is IssueRoute -> onOpenIssue(target.issue)
        is ReleaseRoute -> if (onOpenRelease != null) onOpenRelease(target.repo, target.tag) else onOpenRepo(target.repo)
        is DiscussionRoute -> if (onOpenDiscussion != null) onOpenDiscussion(target.repo, target.number) else openUrl(url)
        is RunRoute -> if (onOpenRun != null) onOpenRun(target.repo, target.runId) else onOpenRepo(target.repo)
        is UserRoute -> if (target.forge == here) onOpenUser(target.login) else openUrl(url)
        // In-page anchors stay put.
        else -> if (!url.startsWith("#")) openUrl(url)
    }
}
