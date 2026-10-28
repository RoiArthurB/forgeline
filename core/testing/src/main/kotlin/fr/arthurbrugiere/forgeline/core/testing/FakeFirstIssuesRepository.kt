package fr.arthurbrugiere.forgeline.core.testing

import fr.arthurbrugiere.forgeline.core.data.firstissues.FirstIssues
import fr.arthurbrugiere.forgeline.core.data.firstissues.FirstIssuesRepository
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import kotlinx.coroutines.flow.MutableStateFlow

class FakeFirstIssuesRepository(initial: FirstIssues? = null) : FirstIssuesRepository {
    val stored = MutableStateFlow(initial)

    /** Whether each refresh asked was forced. */
    val refreshes = mutableListOf<Boolean>()

    var answer: ForgeResult<Unit> = ForgeResult.Success(Unit)

    /** What a refresh stores before answering, when set. */
    var next: FirstIssues? = null

    override fun observe() = stored

    override suspend fun refresh(force: Boolean): ForgeResult<Unit> {
        refreshes += force
        next?.let { stored.value = it }
        return answer
    }
}
