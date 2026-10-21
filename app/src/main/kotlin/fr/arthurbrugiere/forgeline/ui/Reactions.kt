package fr.arthurbrugiere.forgeline.ui

import fr.arthurbrugiere.forgeline.R
import fr.arthurbrugiere.forgeline.core.model.Reaction

/** What a reaction is called, for whoever can't see its emoji. */
internal val Reaction.label: Int
    get() = when (this) {
        Reaction.THUMBS_UP -> R.string.reaction_thumbs_up
        Reaction.THUMBS_DOWN -> R.string.reaction_thumbs_down
        Reaction.LAUGH -> R.string.reaction_laugh
        Reaction.HOORAY -> R.string.reaction_hooray
        Reaction.CONFUSED -> R.string.reaction_confused
        Reaction.HEART -> R.string.reaction_heart
        Reaction.ROCKET -> R.string.reaction_rocket
        Reaction.EYES -> R.string.reaction_eyes
    }

val Reaction.emoji: String
    get() = when (this) {
        Reaction.THUMBS_UP -> "👍"
        Reaction.THUMBS_DOWN -> "👎"
        Reaction.LAUGH -> "😄"
        Reaction.HOORAY -> "🎉"
        Reaction.CONFUSED -> "😕"
        Reaction.HEART -> "❤️"
        Reaction.ROCKET -> "🚀"
        Reaction.EYES -> "👀"
    }
