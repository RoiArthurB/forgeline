package fr.arthurbrugiere.forgeline.core.testing

import fr.arthurbrugiere.forgeline.core.data.yours.YourReposRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.model.YourRepos
import kotlinx.coroutines.flow.MutableStateFlow

class FakeYourReposRepository(initial: YourRepos? = null) : YourReposRepository {
    val stored = MutableStateFlow(initial)

    /** Whether each refresh asked was forced. */
    val refreshes = mutableListOf<Boolean>()

    var answer: ForgeResult<Unit> = ForgeResult.Success(Unit)

    override fun observe() = stored

    override suspend fun refresh(force: Boolean) = answer.also { refreshes += force }
}
