package fr.arthurbrugiere.forgeline.core.data.di

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BackgroundScopeTest {
    @Test
    fun background_work_that_fails_is_logged_and_the_rest_goes_on() = runBlocking<Unit> {
        // Background work (states, conversations ahead, previews) is extra: its failure must never crash the app.
        val scope = DataModule.provideBackgroundScope()
        try {
            assertThat(scope.coroutineContext[CoroutineExceptionHandler]).isNotNull()

            scope.launch { error("a forge answered something unexpected") }.join()

            assertThat(scope.isActive).isTrue()
            assertThat(withTimeout(5_000) { scope.async { "still working" }.await() }).isEqualTo("still working")
        } finally {
            scope.cancel()
        }
    }
}
