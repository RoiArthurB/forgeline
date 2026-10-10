package fr.arthurbrugiere.forgeline.core.model

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** How often the Inbox is checked in the background for new notifications. */
enum class InboxCheckInterval(val minutes: Long?) { OFF(null), MIN_15(15), MIN_30(30), HOUR_1(60), HOUR_3(180), HOUR_6(360) }

/** The tab the app opens on. [AUTOMATIC] is the Inbox for someone signed in, and Trending for someone signed in nowhere. */
enum class StartTab { AUTOMATIC, INBOX, FEED, TRENDING, YOU }

/** What swiping a thread of the Inbox to one side does. */
enum class SwipeAction { MARK_READ, DONE, NONE }

/** How long an Inbox action can be taken back before it reaches the forge; [OFF] sends it at once. */
enum class UndoDelay(val millis: Long) { OFF(0), SEC_3(3_000), SEC_5(5_000), SEC_10(10_000) }

/** What a tap on a share button does; a long press does the other. */
enum class ShareTap { SHARE, COPY }

/**
 * Every default is how Forgeline behaved before the choice was offered: someone who never opens Settings sees no change.
 */
data class UserSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val amoledBlack: Boolean = false,
    val inboxCheckInterval: InboxCheckInterval = InboxCheckInterval.HOUR_1,
    val feedKinds: Set<FeedKind> = FeedKind.defaults,
    /** One Inbox tab per signed-in account instead of one list; only offered with several accounts. */
    val separateInboxPerForge: Boolean = false,
    /**
     * Forges (by host) whose Trending the phone measures itself, once a day: self-hosted servers nobody publishes a
     * list for, or Codeberg instead of its shared list.
     */
    val measuredTrending: Set<String> = emptySet(),
    val startTab: StartTab = StartTab.AUTOMATIC,
    /** The period Trending opens on. */
    val trendingPeriod: TrendingPeriod = TrendingPeriod.DAILY,
    /** Toward the end of the line (right, in left-to-right), and toward its start. */
    val inboxSwipeRight: SwipeAction = SwipeAction.MARK_READ,
    val inboxSwipeLeft: SwipeAction = SwipeAction.DONE,
    val undoDelay: UndoDelay = UndoDelay.SEC_5,
    /** Whether each Inbox check also loads the conversations waiting on you, so they open at once. */
    val loadConversationsAhead: Boolean = true,
    /** A double tap on a comment gives it a thumbs up. */
    val doubleTapReaction: Boolean = true,
    /** Pulling a comment aside starts a reply quoting it. */
    val swipeToReply: Boolean = true,
    val shareTap: ShareTap = ShareTap.SHARE,
    /** An unread conversation opens at what is new instead of at its title. */
    val openAtUnread: Boolean = true,
    /** Trending and the Feed mark where the last visit's reading stopped. */
    val readingMarks: Boolean = true,
)
