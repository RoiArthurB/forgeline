package fr.arthurbrugiere.forgeline.configchecks

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TestConventionsTest {
    @Test
    fun view_models_are_never_built_in_test_property_initializers() {
        // Property initializers run before MainDispatcherRule installs the test Main dispatcher,
        // which made SettingsViewModelTest flaky. Build them inside the test or `by lazy`.
        // Class-level properties sit at exactly one indent level; locals inside tests are fine.
        val eagerViewModel = Regex("""^ {4}(private\s+)?val\s+\w+\s*(:\s*\w+)?\s*=\s*\w+ViewModel\(""", RegexOption.MULTILINE)
        val offenders = Repo.sources(Regex("""/src/test/.*\.kt$"""))
            .filter { eagerViewModel.containsMatchIn(it.readText()) }
            .map { it.relativeTo(Repo.root).path }
            .toList()

        assertThat(offenders).isEmpty()
    }
}
