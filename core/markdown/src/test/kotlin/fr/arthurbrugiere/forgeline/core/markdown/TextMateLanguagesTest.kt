package fr.arthurbrugiere.forgeline.core.markdown

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class TextMateLanguagesTest {
    private val languages = TextMateLanguages.bundled

    @Test
    fun every_language_and_grammar_in_the_index_is_shipped() {
        assertThat(languages.languages.size).isAtLeast(70)
        languages.languages.forEach { language ->
            assertWithMessage(language.id).that(languages.grammars).containsKey(language.scopeName)
        }
        languages.grammars.values.forEach { grammar ->
            assertWithMessage(grammar.scopeName).that(javaClass.getResource("/textmate/${grammar.file}")).isNotNull()
        }
    }

    // A grammar tm4e can't read would leave its files plain without a word: each one must load and run over code.
    @Test
    fun every_grammar_loads_and_runs() {
        languages.grammars.keys.forEach { scope ->
            assertWithMessage(scope).that(CodeHighlighter.grammarWorks(scope, "x = \"a\" // 1 # 2\n<a b=\"c\">{ d: [1, 2] }\n")).isTrue()
        }
    }

    @Test
    fun a_name_pattern_matches_folders_and_names_as_vs_code_does() {
        val workflow = TextMateLanguages.globToRegex("**/.github/workflows/*.{yml,yaml}")

        assertThat(workflow.matches(".github/workflows/ci.yml")).isTrue()
        assertThat(workflow.matches("app/.github/workflows/release.YAML")).isTrue()
        assertThat(workflow.matches(".github/workflows/nested/ci.yml")).isFalse()
        assertThat(TextMateLanguages.globToRegex("*.a,b").matches("x.a,b")).isTrue()
    }

    @Test
    fun the_longest_extension_wins() {
        // .d.ts is TypeScript's like .ts; a longer, more specific extension is never shadowed by a shorter one.
        assertThat(languages.forFile("lib.d.ts")?.id).isEqualTo("typescript")
        assertThat(languages.forFile("x.agent.md")?.id).isNotEqualTo("markdown")
    }
}
