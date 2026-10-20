package fr.arthurbrugiere.forgeline.core.data.actions

import fr.arthurbrugiere.forgeline.core.data.account.AccountRepository
import fr.arthurbrugiere.forgeline.core.data.account.tokenOn
import fr.arthurbrugiere.forgeline.core.data.di.DiskIo
import fr.arthurbrugiere.forgeline.core.data.di.JobLogCache
import fr.arthurbrugiere.forgeline.core.forge.ActionsApi
import fr.arthurbrugiere.forgeline.core.forge.ForgeClients
import fr.arthurbrugiere.forgeline.core.forge.ForgeError
import fr.arthurbrugiere.forgeline.core.forge.ForgeResult
import fr.arthurbrugiere.forgeline.core.forge.JobLogLocator
import fr.arthurbrugiere.forgeline.core.forge.JobLogReader
import fr.arthurbrugiere.forgeline.core.forge.JobLogSteps
import fr.arthurbrugiere.forgeline.core.forge.JobLogWindow
import fr.arthurbrugiere.forgeline.core.forge.StepLines
import fr.arthurbrugiere.forgeline.core.model.LogFormat
import fr.arthurbrugiere.forgeline.core.model.LogPart
import fr.arthurbrugiere.forgeline.core.model.LogStep
import fr.arthurbrugiere.forgeline.core.model.RepoId
import fr.arthurbrugiere.forgeline.core.model.RunConclusion
import fr.arthurbrugiere.forgeline.core.model.RunJob
import fr.arthurbrugiere.forgeline.core.model.RunStep
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A finished job's log, read a step at a time: a log of 60,000 lines is never held whole, nor read whole when the
 * forge can serve parts of it.
 */
interface JobLogRepository {
    /** The steps of finished [job]'s log, each with where its bytes are. */
    suspend fun steps(repo: RepoId, job: RunJob): ForgeResult<List<LogStep>>

    /** The last lines of [step] before byte [until]: its end for the first of them, then where those began. */
    suspend fun lines(repo: RepoId, job: RunJob, step: LogStep, until: Long): ForgeResult<StepLines>
}

/**
 * GitHub's logs are read in parts, its steps found from the times its API gives them ([JobLogLocator]). Forgejo's and
 * GitLab's APIs don't list steps: their logs are downloaded once into [cache], a file read a part at a time, and their
 * steps read from the log itself ([JobLogSteps]); so are GitHub's when it can't be read in parts.
 */
@Singleton
class DefaultJobLogRepository @Inject constructor(
    private val clients: ForgeClients,
    private val accounts: AccountRepository,
    @param:JobLogCache private val cache: File,
    @param:DiskIo private val io: CoroutineDispatcher = Dispatchers.IO,
) : JobLogRepository {

    private class Opened(val reader: JobLogReader, val format: LogFormat, val steps: List<LogStep>)

    private val lock = Mutex()

    /** How much [cache] may hold before the logs read least recently go. */
    internal var cacheBytes = CACHE_BYTES

    /** The last few logs opened, most recent last: going back and forth between a job's steps reads nothing again. */
    private val opened = object : LinkedHashMap<String, Opened>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Opened>) = size > KEPT_OPEN
    }

    override suspend fun steps(repo: RepoId, job: RunJob): ForgeResult<List<LogStep>> = when (val log = open(repo, job)) {
        is ForgeResult.Failure -> log
        is ForgeResult.Success -> ForgeResult.Success(log.value.steps)
    }

    override suspend fun lines(repo: RepoId, job: RunJob, step: LogStep, until: Long): ForgeResult<StepLines> = when (val log = open(repo, job)) {
        is ForgeResult.Failure -> log
        is ForgeResult.Success -> JobLogWindow.before(log.value.reader, step, until, log.value.format)
    }

    private suspend fun open(repo: RepoId, job: RunJob): ForgeResult<Opened> = lock.withLock {
        val key = key(repo, job)
        opened[key]?.let { return ForgeResult.Success(it) }
        val api = clients.actions(repo.forge) ?: return ForgeResult.Failure(ForgeError.Unsupported)
        val token = accounts.tokenOn(repo.forge)
        val log = if (api.logFormat == LogFormat.GITHUB && job.steps.any { it.startedAt != null }) inParts(api, token, repo, job) else null
        (log ?: downloaded(api, token, repo, job)).also { if (it is ForgeResult.Success) opened[key] = it.value }
    }

    /** The log read in parts, its steps where the API's times put them; null when it has to be read whole instead. */
    private suspend fun inParts(api: ActionsApi, token: String?, repo: RepoId, job: RunJob): ForgeResult<Opened>? {
        val reader = when (val opened = api.openJobLog(token, repo, job.id)) {
            is ForgeResult.Failure -> return if (opened.error == ForgeError.Unsupported) null else opened
            is ForgeResult.Success -> opened.value.takeIf { it.size > SMALL_LOG_BYTES }
                // A small log costs less read at once than searched: one part, then searched in memory.
                ?: when (val whole = opened.value.read(0 until opened.value.size)) {
                    is ForgeResult.Failure -> return whole
                    is ForgeResult.Success -> whole.value.bytes.let { bytes ->
                        JobLogReader(bytes.size.toLong()) { range -> ForgeResult.Success(LogPart(bytes.copyOfRange(range.first.toInt(), range.last.toInt() + 1), range.first, bytes.size.toLong())) }
                    }
                }
        }
        val ran = job.steps.sortedBy { it.number }.filter { it.ran }
        if (ran.isEmpty()) return ForgeResult.Success(Opened(reader, api.logFormat, listOf(LogStep(null, 0, reader.size))))
        val starts = when (val found = JobLogLocator(reader, api.logFormat).locate(ran.drop(1).map { it.startedAt!! })) {
            is ForgeResult.Failure -> return if (found.error == ForgeError.Unreadable) null else found
            is ForgeResult.Success -> listOf(0L) + found.value
        }
        if (starts.zipWithNext().any { (a, b) -> b < a }) return null
        return ForgeResult.Success(Opened(reader, api.logFormat, placed(job.steps, ran.map { it.number }.zip(starts).toMap(), reader.size)))
    }

    /** The log downloaded once (kept in [cache]), its steps read from it. */
    private suspend fun downloaded(api: ActionsApi, token: String?, repo: RepoId, job: RunJob): ForgeResult<Opened> = withContext(io) {
        val file = File(cache, "${key(repo, job).sha256()}.log")
        if (file.exists()) {
            file.setLastModified(System.currentTimeMillis())
        } else {
            cache.mkdirs()
            val part = File.createTempFile("job", ".part", cache)
            try {
                val downloaded = part.outputStream().buffered().use { api.downloadJobLog(token, repo, job.id, it) }
                if (downloaded is ForgeResult.Failure) return@withContext downloaded
                part.renameTo(file)
            } finally {
                part.delete()
            }
            trim(keep = file)
        }
        val read = file.inputStream().buffered().use { JobLogSteps.index(it, api.logFormat) }
        val size = file.length()
        val reader = JobLogReader(size) { range -> ForgeResult.Success(readFile(file, range, size)) }
        ForgeResult.Success(Opened(reader, api.logFormat, named(read, job.steps)))
    }

    private suspend fun readFile(file: File, range: LongRange, size: Long): LogPart = withContext(io) {
        RandomAccessFile(file, "r").use { raf ->
            val bytes = ByteArray((range.last - range.first + 1).toInt())
            raf.seek(range.first)
            raf.readFully(bytes)
            LogPart(bytes, range.first, size)
        }
    }

    /** Keeps [cache] under [cacheBytes], letting the logs read least recently go first. */
    private fun trim(keep: File) {
        val logs = cache.listFiles { file -> file.name.endsWith(".log") }.orEmpty().sortedBy { it.lastModified() }
        var total = logs.sumOf { it.length() }
        for (log in logs) {
            if (total <= cacheBytes) break
            if (log == keep) continue
            total -= log.length()
            log.delete()
        }
    }

    companion object {
        const val KEPT_OPEN = 4
        const val SMALL_LOG_BYTES = 512L * 1024
        const val CACHE_BYTES = 64L * 1024 * 1024

        private fun key(repo: RepoId, job: RunJob) = "${repo.forge.host}/${repo.fullName}/${job.id}"

        private fun String.sha256() = MessageDigest.getInstance("SHA-256").digest(toByteArray()).joinToString("") { "%02x".format(it) }

        private val RunStep.ran get() = startedAt != null && conclusion != RunConclusion.SKIPPED

        /**
         * Every step the API lists, in its order: one that ran from where it was found until the next that ran, one that
         * didn't (skipped) empty where the next begins.
         */
        internal fun placed(steps: List<RunStep>, startOf: Map<Int, Long>, size: Long): List<LogStep> {
            val ordered = steps.sortedBy { it.number }
            return ordered.mapIndexed { index, step ->
                val next = ordered.drop(index + 1).firstNotNullOfOrNull { startOf[it.number] } ?: size
                val start = startOf[step.number] ?: next
                LogStep(step.name, start, next, step.conclusion)
            }
        }

        /**
         * The steps read from a log, under the names and outcomes the API gives them when they are the same steps (as
         * many as ran, in order): GitHub's log says "Post job cleanup" where its API says which.
         */
        internal fun named(read: List<LogStep>, steps: List<RunStep>): List<LogStep> {
            val ordered = steps.sortedBy { it.number }
            val ran = ordered.filter { it.ran }
            if (ran.size != read.size) return read
            val startOf = ran.map { it.number }.zip(read.map { it.start }).toMap()
            val size = read.lastOrNull()?.end ?: 0
            val byNumber = ran.map { it.number }.zip(read).toMap()
            return placed(ordered, startOf, size).mapIndexed { index, step ->
                byNumber[ordered[index].number]?.let { found -> step.copy(lines = found.lines, errors = found.errors) } ?: step
            }
        }
    }
}
