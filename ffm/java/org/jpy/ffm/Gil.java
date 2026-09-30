package org.jpy.ffm;

import java.lang.foreign.MemorySegment;
import java.util.concurrent.locks.ReentrantLock;

/**
 * GIL handling. Two directions:
 * - {@link #ensure()}: a Java thread that must call into Python takes the GIL.
 * - {@link #release()}: code that already holds the GIL (every upcall) drops it
 *   while running Java, like the C jpy's Py_BEGIN_ALLOW_THREADS around each
 *   Java call. Without it, a Java method that calls back into Python from
 *   another thread deadlocks.
 */
public final class Gil {

    public static final class Ensured implements AutoCloseable {
        private final int state;

        Ensured(int state) {
            this.state = state;
        }

        @Override
        public void close() {
            try {
                CPython.PyGILState_Release.invokeExact(state);
            } catch (Throwable t) {
                throw CPython.rethrow(t);
            }
        }
    }

    public static final class Released implements AutoCloseable {
        private final MemorySegment threadState;

        Released(MemorySegment threadState) {
            this.threadState = threadState;
        }

        @Override
        public void close() {
            try {
                CPython.PyEval_RestoreThread.invokeExact(threadState);
            } catch (Throwable t) {
                throw CPython.rethrow(t);
            }
        }
    }

    public static Ensured ensure() {
        try {
            return new Ensured((int) CPython.PyGILState_Ensure.invokeExact());
        } catch (Throwable t) {
            throw CPython.rethrow(t);
        }
    }

    /** Must be called with the GIL held; the GIL is re-taken on close. */
    public static Released release() {
        try {
            return new Released((MemorySegment) CPython.PyEval_SaveThread.invokeExact());
        } catch (Throwable t) {
            throw CPython.rethrow(t);
        }
    }

    /**
     * A reentrant lock for code that holds the GIL. If the lock is busy, the
     * GIL is released while waiting, so the current owner (which may need the
     * GIL to finish) can make progress.
     */
    public static final class PyAwareLock {
        private final ReentrantLock lock = new ReentrantLock();

        public void lock() {
            if (lock.tryLock()) {
                return;
            }
            try (Released r = release()) {
                lock.lock();
            }
        }

        public void unlock() {
            lock.unlock();
        }
    }

    private Gil() {
    }
}
