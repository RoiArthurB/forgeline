package fr.arthurbrugiere.forgeline.forge.gitlab

import fr.arthurbrugiere.forgeline.core.model.ClosingCommit
import fr.arthurbrugiere.forgeline.core.model.ConversationEvent
import fr.arthurbrugiere.forgeline.core.model.ForgeUser
import fr.arthurbrugiere.forgeline.core.model.IssueRef
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.ReviewState
import fr.arthurbrugiere.forgeline.core.model.TimelineItem
import java.time.Instant

/**
 * What a GitLab system note says happened. GitLab writes them as sentences, in English whatever the reader's language
 * (read on gitlab.com, 2026-11-05, from gitlab-org/gitlab, gitlab-org/gitlab-runner, inkscape/inkscape and
 * fdroid/fdroiddata): "assigned to @alice", "changed title from ... to ...", "mentioned in merge request !12",
 * "marked this issue as blocking #3", "set status to **In dev**"... One note may tell several things ("requested review
 * from @a and removed review request for @b"). A note it doesn't know, or that is noise (a checklist item ticked, a
 * Duo session), tells nothing.
 *
 * Labels, milestones, weights and iterations aren't notes: they are resource events, read apart.
 */
internal fun systemNoteItems(id: Long, body: String, actor: ForgeUser?, at: Instant, home: RepoId): List<TimelineItem> {
    val text = withoutHtml(body.lineSequence().first()).trim()

    fun event(event: ConversationEvent, subject: String? = null, target: IssueRef? = null) = listOf(TimelineItem.Event(event, actor, subject, at, target))

    /** An event naming a conversation by its reference ("#12", "!3", "group/project#7"), which opens it where it can be told. */
    fun linked(event: ConversationEvent, reference: String): List<TimelineItem> {
        val named = reference.trim().trimEnd('.')
        return event(event, named, gitLabReference(named, home))
    }
    return when {
        text.startsWith("assigned to ") || text.startsWith("unassigned ") -> assignments(text, actor, at)
        text.startsWith("requested review from ") || text.startsWith("removed review request for ") -> reviewRequests(text, actor, at)
        text.startsWith("unlocked ") -> event(ConversationEvent.UNLOCKED)
        text.startsWith("locked ") -> event(ConversationEvent.LOCKED)
        text.startsWith("changed due date to ") -> event(ConversationEvent.DEADLINE_SET, text.removePrefix("changed due date to "))
        text.startsWith("removed due date") -> event(ConversationEvent.DEADLINE_REMOVED)
        text.startsWith("added ") && " of time spent" in text -> event(ConversationEvent.TIME_ADDED, text.removePrefix("added ").substringBefore(" of time spent"))
        text.startsWith("subtracted ") && " of time spent" in text -> event(ConversationEvent.TIME_REMOVED)
        text.startsWith("deleted ") && " of spent time" in text -> event(ConversationEvent.TIME_REMOVED)
        text.startsWith("removed time spent") -> event(ConversationEvent.TIME_REMOVED)
        // "added time estimate of 1h", "changed time estimate to 2h from 1h", "removed time estimate of 1h".
        text.startsWith("added time estimate of ") -> event(ConversationEvent.ESTIMATE_SET, text.removePrefix("added time estimate of "))
        text.startsWith("changed time estimate to ") -> event(ConversationEvent.ESTIMATE_SET, text.removePrefix("changed time estimate to ").substringBefore(" from "))
        text.startsWith("removed time estimate") -> event(ConversationEvent.ESTIMATE_REMOVED)
        // Older GitLab wrote milestones as notes; newer keeps them as resource events.
        text.startsWith("changed milestone to ") -> event(ConversationEvent.MILESTONED, text.removePrefix("changed milestone to ").removePrefix("%"))
        text.startsWith("removed milestone") -> event(ConversationEvent.DEMILESTONED, text.removePrefix("removed milestone").trim().removePrefix("%").ifBlank { null })
        text.startsWith("changed title from ") -> renamed(body, actor, at)
        text == "changed the description" -> event(ConversationEvent.DESCRIPTION_CHANGED)
        text.startsWith("mentioned in commit ") -> mentionedInCommit(text.removePrefix("mentioned in commit "), actor, at, home)
        text.startsWith("mentioned in merge request ") -> mentionedIn(text.removePrefix("mentioned in merge request "), true, actor, at, home)
        // An issue, or a work item of another kind; an epic is a group's, which Forgeline doesn't open.
        MENTIONED_IN_ITEM.matches(text) -> mentionedIn(MENTIONED_IN_ITEM.find(text)!!.groupValues[1], false, actor, at, home)
        text.startsWith("created merge request ") && text.endsWith(" to address this issue") ->
            linked(ConversationEvent.LINKED, text.removePrefix("created merge request ").removeSuffix(" to address this issue"))
        DUPLICATE_OF.matches(text) -> linked(ConversationEvent.MARKED_DUPLICATE, DUPLICATE_OF.find(text)!!.groupValues[1])
        DUPLICATE_HERE.matches(text) -> linked(ConversationEvent.DUPLICATE_ADDED, DUPLICATE_HERE.find(text)!!.groupValues[1])
        RELATED.matches(text) -> linked(ConversationEvent.RELATED_ADDED, RELATED.find(text)!!.groupValues[1])
        text.startsWith("removed the relation with ") -> linked(ConversationEvent.RELATED_REMOVED, text.removePrefix("removed the relation with "))
        BLOCKING.matches(text) -> linked(ConversationEvent.BLOCKING_ADDED, BLOCKING.find(text)!!.groupValues[1])
        BLOCKED_BY.matches(text) -> linked(ConversationEvent.DEPENDENCY_ADDED, BLOCKED_BY.find(text)!!.groupValues[1])
        // "made the issue confidential", or the task, the epic...
        text.startsWith("made the ") && text.endsWith(" confidential") -> event(ConversationEvent.CONFIDENTIAL)
        text.startsWith("made the ") && text.endsWith(" visible to everyone") -> event(ConversationEvent.NOT_CONFIDENTIAL)
        text.startsWith("moved to ") -> linked(ConversationEvent.MOVED_AWAY, text.removePrefix("moved to "))
        text.startsWith("moved from ") -> linked(ConversationEvent.TRANSFERRED, text.removePrefix("moved from "))
        text.startsWith("cloned to ") -> linked(ConversationEvent.CLONED, text.removePrefix("cloned to "))
        text.startsWith("set status to ") -> event(ConversationEvent.STATUS_CHANGED, withoutMarks(text.removePrefix("set status to ")))
        PARENT_ADDED.matches(text) -> linked(ConversationEvent.PARENT_ADDED, PARENT_ADDED.find(text)!!.groupValues[1])
        PARENT_REMOVED.matches(text) -> linked(ConversationEvent.PARENT_REMOVED, PARENT_REMOVED.find(text)!!.groupValues[1])
        CHILD_ADDED.matches(text) -> linked(ConversationEvent.SUB_ISSUE_ADDED, CHILD_ADDED.find(text)!!.groupValues[1])
        CHILD_REMOVED.matches(text) -> linked(ConversationEvent.SUB_ISSUE_REMOVED, CHILD_REMOVED.find(text)!!.groupValues[1])
        // "changed type from issue to incident", or "changed type to Task".
        text.startsWith("changed type ") -> event(ConversationEvent.TYPE_SET, withoutMarks(text.substringAfterLast(" to ")).replaceFirstChar { it.uppercase() })
        text == "approved this merge request" -> listOf(TimelineItem.Review(id, actor, ReviewState.APPROVED, null, at))
        text == "requested changes" -> listOf(TimelineItem.Review(id, actor, ReviewState.CHANGES_REQUESTED, null, at))
        text == "left review comments" -> listOf(TimelineItem.Review(id, actor, ReviewState.COMMENTED, null, at))
        text == "unapproved this merge request" -> event(ConversationEvent.APPROVAL_REVOKED)
        text.startsWith("marked this merge request as **draft**") || text.startsWith("marked as a **Work In Progress**") -> event(ConversationEvent.CONVERTED_TO_DRAFT)
        text.startsWith("marked this merge request as **ready**") || text.startsWith("unmarked as a **Work In Progress**") -> event(ConversationEvent.READY_FOR_REVIEW)
        COMMITS_ADDED.matches(text) -> event(ConversationEvent.PUSHED, COMMITS_ADDED.find(text)!!.groupValues[1])
        text.startsWith("changed target branch from ") -> event(ConversationEvent.BASE_CHANGED, text.substringAfterLast(" to ").trim('`', ' '))
        // "deleted the `old` branch. This merge request now targets the `new` branch": its target went, it took another.
        text.startsWith("deleted the `") && "now targets the `" in text -> event(ConversationEvent.BASE_CHANGED, text.substringAfter("now targets the `").substringBefore('`'))
        text.startsWith("deleted source branch ") -> event(ConversationEvent.BRANCH_DELETED, text.removePrefix("deleted source branch ").trim('`', ' '))
        text.startsWith("restored source branch ") -> event(ConversationEvent.BRANCH_RESTORED, text.removePrefix("restored source branch ").trim('`', ' '))
        text.startsWith("enabled an automatic merge") || text.startsWith("enabled automatic add to merge train") -> event(ConversationEvent.AUTO_MERGE_ENABLED)
        text.startsWith("canceled the automatic merge") || text.startsWith("aborted the automatic merge") ||
            text.startsWith("aborted automatic add to merge train") || text.startsWith("canceled automatic add to merge train") -> event(ConversationEvent.AUTO_MERGE_DISABLED)
        text.startsWith("added this merge request to the merge train") || text.startsWith("started a merge train") -> event(ConversationEvent.ADDED_TO_MERGE_QUEUE)
        text.startsWith("removed this merge request from the merge train") -> event(ConversationEvent.REMOVED_FROM_MERGE_QUEUE)
        text == "resolved all threads" -> event(ConversationEvent.THREADS_RESOLVED)
        else -> emptyList()
    }
}

/**
 * Where a GitLab reference points: "#12" and "!3" in [home], "project#12" beside it in its namespace, "group/project#12"
 * by its full path. One that names a group's work item ("group#12", as a parent epic) points nowhere Forgeline opens.
 */
internal fun gitLabReference(reference: String, home: RepoId): IssueRef? {
    val match = REFERENCE.matchEntire(reference.trim()) ?: return null
    val (path, sign, number) = match.destructured
    val repo = when {
        path.isEmpty() -> home
        '/' in path -> RepoId(path.substringBeforeLast('/'), path.substringAfterLast('/'), home.forge)
        // A bare name is a project of the same namespace, unless it is that namespace's own group.
        path.equals(home.owner.substringBefore('/'), ignoreCase = true) -> return null
        else -> RepoId(home.owner, path, home.forge)
    }
    return IssueRef(repo, number.toIntOrNull() ?: return null, isPullRequest = sign == "!")
}

/** "assigned to @a and @b and unassigned @c": one event for each. */
private fun assignments(text: String, actor: ForgeUser?, at: Instant): List<TimelineItem> {
    val assigned = text.substringAfter("assigned to ", "").substringBefore("unassigned ").takeIf { text.startsWith("assigned to ") }.orEmpty()
    val unassigned = text.substringAfter("unassigned ", "")
    return logins(assigned).map { TimelineItem.Event(ConversationEvent.ASSIGNED, actor, it, at) } +
        logins(unassigned).map { TimelineItem.Event(ConversationEvent.UNASSIGNED, actor, it, at) }
}

/** "requested review from @a and @b and removed review request for @c" (and "... and removed approval"). */
private fun reviewRequests(text: String, actor: ForgeUser?, at: Instant): List<TimelineItem> {
    val asked = text.substringAfter("requested review from ", "").substringBefore("removed review request for ").takeIf { text.startsWith("requested review from ") }.orEmpty()
    val removed = text.substringAfter("removed review request for ", "")
    return logins(asked).map { TimelineItem.Event(ConversationEvent.REVIEW_REQUESTED, actor, it, at) } +
        logins(removed).map { TimelineItem.Event(ConversationEvent.REVIEW_REQUEST_REMOVED, actor, it, at) }
}

private fun logins(text: String): List<String> = LOGIN.findAll(text).map { it.groupValues[1] }.toList()

/**
 * "changed title from **{-Old-}** to **{+New+}**" in Markdown, or GitLab's HTML of it (`<code class="idiff">` around
 * each title, `<span>`s marking what changed): the whole titles, without the marks.
 */
private fun renamed(body: String, actor: ForgeUser?, at: Instant): List<TimelineItem> {
    val line = body.lineSequence().first()
    val (from, to) = if ("</code> to <code" in line) {
        line.substringAfter("changed title from ").split("</code> to <code", limit = 2).map { withoutHtml("<code$it").trim() }.let { it[0] to it[1] }
    } else {
        val words = withoutHtml(line).removePrefix("changed title from ")
        val split = words.split("** to **", limit = 2).takeIf { it.size == 2 } ?: return emptyList()
        withoutMarks(split[0]) to withoutMarks(split[1])
    }
    if (from.isEmpty() || to.isEmpty()) return emptyList()
    return listOf(TimelineItem.Renamed(from, to, actor, at))
}

/** "mentioned in commit 2e5d38f0", or "omnibus-gitlab@1bcfe113" when it is another project's. */
private fun mentionedInCommit(reference: String, actor: ForgeUser?, at: Instant, home: RepoId): List<TimelineItem> {
    val path = reference.substringBefore('@', "")
    val sha = reference.substringAfter('@').trim().takeIf { SHA.matches(it) } ?: return emptyList()
    val repo = when {
        path.isEmpty() -> home
        '/' in path -> RepoId(path.substringBeforeLast('/'), path.substringAfterLast('/'), home.forge)
        else -> RepoId(home.owner, path, home.forge)
    }
    return listOf(TimelineItem.CommitReferenced(ClosingCommit(sha, null, "${repo.webUrl}/-/commit/$sha"), actor, at))
}

/** "mentioned in issue #12" or "merge request group/project!3": GitLab doesn't say its title here. */
private fun mentionedIn(reference: String, mergeRequest: Boolean, actor: ForgeUser?, at: Instant, home: RepoId): List<TimelineItem> {
    val ref = gitLabReference(reference, home) ?: return emptyList()
    return listOf(TimelineItem.CrossReferenced(ref.copy(isPullRequest = mergeRequest), "", mergeRequest, actor, at))
}

/** GitLab's emphasis and inline-diff marks taken off a value: "**{-Old-}**" reads "Old". */
private fun withoutMarks(text: String) = text.replace("**", "").replace("{-", "").replace("-}", "").replace("{+", "").replace("+}", "").trim()

private fun withoutHtml(text: String) = TAG.replace(text, "")
    .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")

private val TAG = Regex("<[^>]+>")
private val LOGIN = Regex("""@([\w.\-]+)""")
private val SHA = Regex("[0-9a-f]{7,64}")
private const val REF = """([\w.\-/]*[#!]\d+)"""
private val REFERENCE = Regex("""([\w.\-/]*)([#!])(\d+)""")
private val DUPLICATE_OF = Regex("""marked this (?:issue|item|merge request) as a duplicate of $REF.*""")
private val DUPLICATE_HERE = Regex("""marked $REF as a duplicate of this (?:issue|item|merge request).*""")
private val RELATED = Regex("""marked (?:this (?:issue|item|merge request) )?as related to $REF.*""")
private val BLOCKING = Regex("""marked this (?:issue|item) as blocking $REF.*""")
private val BLOCKED_BY = Regex("""marked this (?:issue|item) as blocked by $REF.*""")
private val PARENT_ADDED = Regex("""added $REF as parent (?:item|issue|epic).*""")
private val PARENT_REMOVED = Regex("""removed parent (?:item|issue|epic) $REF.*""")
private val CHILD_ADDED = Regex("""added $REF as (?:child|sub) (?:item|issue|task|objective|key result).*""")
private val CHILD_REMOVED = Regex("""removed (?:child|sub) (?:item|issue|task|objective|key result) $REF.*""")
private val MENTIONED_IN_ITEM = Regex("""mentioned in (?:issue|task|incident|work item|objective|key result|test case|requirement|ticket) $REF.*""")
private val COMMITS_ADDED = Regex("""added (\d+) commits?\b.*""")
