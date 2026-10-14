package app.perroute.runtime

import app.perroute.routing.RouteHealthTracker
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** A live route to watch: its id, display name, server and the loopback port that leads into it. */
data class RouteTarget(val id: String, val name: String, val nodeKey: String, val checkPort: Int)

/** The outcome of one check through a route's own port. */
data class RouteProbe(val ok: Boolean, val latencyMillis: Long, val detail: String, val startedAt: Long = 0L)

/**
 * Watches every live route on its own and drives its recovery. Each route has a [RouteHealthTracker]; a check
 * only ever goes through that route's own port, so a failing route cannot make a healthy one look failed, and
 * recovery of one route never restarts the core or touches another route.
 *
 * With [continuous] off (the version 2 behavior, kept for migrated installations until the user turns
 * monitoring on) every route is checked once after connecting and again only when the user asks.
 */
class RouteRecoveryManager(
    private val probe: (RouteTarget) -> RouteProbe,
    private val onResult: (RouteTarget, RouteProbe, RouteHealthTracker.Action, RouteHealthTracker.Snapshot) -> Unit,
    private val policy: RouteHealthTracker.Policy = RouteHealthTracker.Policy.DEFAULT,
    private val clock: () -> Long = System::currentTimeMillis,
    maxParallel: Int = 6
) : AutoCloseable {
    private class Watched(val target: RouteTarget, val tracker: RouteHealthTracker) {
        @Volatile var probing = false
        @Volatile var checkedOnce = false
        @Volatile var manualRequest = false
    }

    private val lock = ReentrantLock()
    private val wake = lock.newCondition()
    private val routes = ConcurrentHashMap<String, Watched>()
    private val generation = AtomicLong()
    private val permits = Semaphore(maxParallel.coerceAtLeast(1))
    @Volatile private var continuous = false
    @Volatile private var running = false
    private var loop: Thread? = null

    /** Replaces the watched routes, for example after connecting. */
    fun start(targets: List<RouteTarget>, continuous: Boolean) {
        val now = clock()
        lock.withLock {
            generation.incrementAndGet()
            routes.clear()
            targets.forEach { routes[it.id] = Watched(it, RouteHealthTracker(it.id, it.name, policy, now)) }
            this.continuous = continuous
            if (!running) {
                running = true
                loop = Thread.ofVirtual().name("perroute-route-recovery").start(::run)
            }
            wake.signalAll()
        }
    }

    /** A route moved to another server: it starts over with a fresh history, the others are untouched. */
    fun replace(target: RouteTarget) {
        lock.withLock {
            routes[target.id] = Watched(target, RouteHealthTracker(target.id, target.name, policy, clock()))
            wake.signalAll()
        }
    }

    fun remove(routeId: String) {
        lock.withLock { routes.remove(routeId); wake.signalAll() }
    }

    fun setContinuous(value: Boolean) {
        lock.withLock { continuous = value; wake.signalAll() }
    }

    fun stopWatching() {
        lock.withLock { generation.incrementAndGet(); routes.clear(); wake.signalAll() }
    }

    fun retryNow(routeId: String): Boolean {
        val watched = routes[routeId] ?: return false
        lock.withLock {
            watched.manualRequest = true
            watched.tracker.retryNow(clock())
            wake.signalAll()
        }
        return true
    }

    fun note(routeId: String, type: RouteHealthTracker.EventType, title: String, detail: String) {
        routes[routeId]?.tracker?.note(clock(), type, title, detail)
    }

    fun snapshot(): Map<String, RouteHealthTracker.Snapshot> = routes.mapValues { it.value.tracker.snapshot() }

    fun target(routeId: String): RouteTarget? = routes[routeId]?.target

    private fun run() {
        while (running) {
            val due = mutableListOf<Watched>()
            lock.withLock {
                val now = clock()
                var nextWake = now + 60_000L
                for (watched in routes.values) {
                    if (watched.probing) continue
                    val wanted = !watched.checkedOnce || continuous || watched.manualRequest
                    if (!wanted) continue
                    if (watched.tracker.due(now)) due += watched
                    else nextWake = minOf(nextWake, watched.tracker.nextCheckAt())
                }
                if (due.isEmpty()) wake.await((nextWake - now).coerceIn(50L, 60_000L), TimeUnit.MILLISECONDS)
            }
            if (!running) break
            val session = generation.get()
            due.forEach { watched ->
                watched.probing = true
                Thread.ofVirtual().name("perroute-route-check").start {
                    permits.acquire()
                    try {
                        val result = runCatching { probe(watched.target) }
                            .getOrElse { RouteProbe(false, -1, it.message ?: it.javaClass.simpleName) }
                        if (generation.get() != session || routes[watched.target.id] !== watched) return@start
                        val action = watched.tracker.record(result.ok, result.latencyMillis, result.detail, clock())
                        watched.checkedOnce = true
                        if (watched.tracker.state() == RouteHealthTracker.State.HEALTHY ||
                            watched.tracker.state() == RouteHealthTracker.State.FAILED) watched.manualRequest = false
                        runCatching { onResult(watched.target, result, action, watched.tracker.snapshot()) }
                    } finally {
                        watched.probing = false
                        permits.release()
                        lock.withLock { wake.signalAll() }
                    }
                }
            }
        }
    }

    override fun close() {
        running = false
        lock.withLock { routes.clear(); wake.signalAll() }
    }
}
