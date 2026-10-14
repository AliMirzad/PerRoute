package app.perroute.runtime

import app.perroute.model.ProxyNode
import app.perroute.quality.QualityHistoryStore
import app.perroute.quality.QualitySample
import app.perroute.quality.QualityScore
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Collects quality evidence for every server and answers score queries. Evidence comes from what PerRoute
 * measures anyway (pings, real tunnel tests, checks of live routes) and, when switched on, from a bounded
 * background pass: at most [BATCH] servers every [INTERVAL_MILLIS], four at a time, plain TCP port checks,
 * plus at most [VERIFY_PER_PASS] tunnel verifications of Auto candidates that have none yet.
 */
class QualityService(
    file: Path,
    private val clock: () -> Long = System::currentTimeMillis
) : AutoCloseable {
    private val store = QualityHistoryStore(file)
    private val lock = ReentrantLock()
    private val wake = lock.newCondition()
    @Volatile private var running = false
    @Volatile private var runRequested = false
    @Volatile var lastPassAt: Long = 0L
        private set
    private var thread: Thread? = null

    fun recordReachability(key: String, latencyMillis: Long) =
        store.record(key, QualitySample.reachability(clock(), latencyMillis))

    fun recordTunnel(key: String, success: Boolean, latencyMillis: Long, detail: String = "") =
        store.record(key, QualitySample.tunnel(clock(), success, if (success) latencyMillis.coerceAtLeast(1L) else -1L, detail))

    fun recordRecovery(key: String) = store.record(key, QualitySample.recovery(clock()))

    fun score(key: String): QualityScore = store.score(key, clock())

    fun scores(keys: Collection<String>): Map<String, QualityScore> = store.scores(keys, clock())

    fun samples(key: String): List<QualitySample> = store.samples(key)

    fun lastMeasured(key: String): Long = store.lastMeasured(key)

    fun retain(keys: Collection<String>) = store.retain(keys)

    fun clear() = store.clear()

    /**
     * Starts the background pass. [targets] returns the servers worth measuring, most important first;
     * [verify] opens a real tunnel to one server (expensive, so used sparingly).
     */
    fun startBackground(
        enabled: () -> Boolean,
        targets: () -> List<ProxyNode>,
        verifyCandidates: () -> List<ProxyNode>,
        ping: (ProxyNode) -> Long,
        verify: (ProxyNode) -> Pair<Boolean, Long>
    ) {
        if (running) return
        running = true
        thread = Thread.ofVirtual().name("perroute-quality").start {
            var nextAt = clock() + FIRST_PASS_DELAY_MILLIS
            while (running) {
                lock.withLock {
                    while (running && !runRequested && clock() < nextAt) {
                        wake.await((nextAt - clock()).coerceIn(1L, 60_000L), TimeUnit.MILLISECONDS)
                    }
                    runRequested = false
                }
                if (!running) break
                nextAt = clock() + INTERVAL_MILLIS
                if (!enabled()) continue
                runCatching { pass(targets().take(BATCH), verifyCandidates(), ping, verify) }
                lastPassAt = clock()
            }
        }
    }

    /** Asks for a pass soon, for example right after Auto was turned on for an application. */
    fun requestPass() {
        lock.withLock { runRequested = true; wake.signalAll() }
    }

    private fun pass(targets: List<ProxyNode>, candidates: List<ProxyNode>, ping: (ProxyNode) -> Long, verify: (ProxyNode) -> Pair<Boolean, Long>) {
        if (targets.isNotEmpty()) {
            val executor = Executors.newFixedThreadPool(PARALLEL, Thread.ofVirtual().factory())
            try {
                targets.map { node -> executor.submit { recordReachability(node.key(), runCatching { ping(node) }.getOrDefault(-1L)) } }
                    .forEach { runCatching { it.get(10, TimeUnit.SECONDS) } }
            } finally {
                executor.shutdownNow()
            }
        }
        candidates.filter { !score(it.key()).tunnelVerified() }.take(VERIFY_PER_PASS).forEach { node ->
            if (!running) return
            val (ok, latency) = runCatching { verify(node) }.getOrDefault(false to -1L)
            recordTunnel(node.key(), ok, latency, if (ok) "" else "Background verification failed")
        }
    }

    override fun close() {
        running = false
        lock.withLock { wake.signalAll() }
        store.close()
    }

    companion object {
        const val BATCH = 24
        const val PARALLEL = 4
        const val VERIFY_PER_PASS = 2
        const val INTERVAL_MILLIS = 10 * 60_000L
        const val FIRST_PASS_DELAY_MILLIS = 90_000L
    }
}
