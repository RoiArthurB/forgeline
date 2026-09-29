package fr.arthurbrugiere.forgeline.core.ui.format

import androidx.compose.ui.graphics.Color

/**
 * The dot colours GitHub shows for common languages (from github-linguist). The repository API names a language but
 * doesn't give its colour; unknown languages get no dot.
 */
fun languageColor(language: String?): Color? = language?.let { LANGUAGE_COLORS[it.lowercase()] }?.let { Color(0xFF000000 or it) }

private val LANGUAGE_COLORS = mapOf(
    "kotlin" to 0xA97BFFL, "java" to 0xB07219L, "python" to 0x3572A5L, "javascript" to 0xF1E05AL,
    "typescript" to 0x3178C6L, "rust" to 0xDEA584L, "go" to 0x00ADD8L, "c" to 0x555555L, "c++" to 0xF34B7DL,
    "c#" to 0x178600L, "swift" to 0xF05138L, "ruby" to 0x701516L, "php" to 0x4F5D95L, "shell" to 0x89E051L,
    "dart" to 0x00B4ABL, "html" to 0xE34C26L, "css" to 0x663399L, "scss" to 0xC6538CL, "vue" to 0x41B883L,
    "svelte" to 0xFF3E00L, "zig" to 0xEC915CL, "lua" to 0x000080L, "haskell" to 0x5E5086L, "elixir" to 0x6E4A7EL,
    "scala" to 0xC22D40L, "nix" to 0x7E7EFFL, "ocaml" to 0xEF7A08L, "clojure" to 0xDB5855L, "r" to 0x198CE7L,
    "jupyter notebook" to 0xDA5B0BL, "objective-c" to 0x438EFFL, "perl" to 0x0298C3L, "vim script" to 0x199F4BL,
    "gdscript" to 0x355570L, "elm" to 0x60B5CCL, "erlang" to 0xB83998L, "julia" to 0xA270BAL, "nim" to 0xFFC200L,
    "powershell" to 0x012456L, "dockerfile" to 0x384D54L, "makefile" to 0x427819L, "tex" to 0x3D6117L,
)
