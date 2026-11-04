package fr.arthurbrugiere.forgeline.configchecks

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ShrinkerRulesTest {
    @Test
    fun r8_keeps_the_native_regexes_code_blocks_are_highlighted_with() {
        // R8 dropped the list tm4e's native searcher keeps its regexes alive with (never read, batch search being always
        // on): a GC then freed them mid-search and any README with code blocks and images crashed the release app in
        // onig_search. Only a minified build shows it, so the rule is checked here.
        assertThat(Repo.text("core/markdown/consumer-rules.pro"))
            .contains("-keep class org.eclipse.tm4e.core.internal.oniguruma.impl.onig.** { *; }")
    }
}
