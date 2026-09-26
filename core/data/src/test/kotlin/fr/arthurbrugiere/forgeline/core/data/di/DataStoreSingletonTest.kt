package fr.arthurbrugiere.forgeline.core.data.di

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Regression: DataStore allows one active instance per file per process. Providers created a new
 * one per Hilt component, so on a device the second test's instance could never load and the
 * splash screen waited forever (a 40-minute CI hang). Robolectric can't reproduce the hang itself
 * (it gives each test fresh storage), so this pins the invariant instead.
 */
@RunWith(RobolectricTestRunner::class)
class DataStoreSingletonTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun the_settings_store_is_one_instance_per_process() {
        assertThat(DataModule.provideSettingsDataStore(context)).isSameInstanceAs(DataModule.provideSettingsDataStore(context))
    }

    @Test
    fun the_accounts_store_is_one_instance_per_process() {
        assertThat(DataModule.provideAccountsDataStore(context)).isSameInstanceAs(DataModule.provideAccountsDataStore(context))
    }
}
