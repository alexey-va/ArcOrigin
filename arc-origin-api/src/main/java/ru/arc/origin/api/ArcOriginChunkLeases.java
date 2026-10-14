package ru.arc.origin.api;

import java.util.UUID;

/** Acquires plugin-scoped leases backed by the host's shared Paper chunk-ticket registry. */
public interface ArcOriginChunkLeases {
    /**
     * Acquires a lease for a chunk that is already loaded. Call on the server thread. The caller
     * owns the returned lease and must close it on the server thread when it no longer needs the
     * ticket. The host retains ownership of the underlying registry.
     */
    AcquireResult acquire(UUID worldId, int chunkX, int chunkZ);

    record AcquireResult(Status status, AutoCloseable lease) {
        public enum Status {
            ACQUIRED,
            WORLD_UNAVAILABLE,
            REGISTRY_CLOSED,
            FAILED
        }

        public AcquireResult {
            if ((status == Status.ACQUIRED) != (lease != null)) {
                throw new IllegalArgumentException("ACQUIRED results require exactly one lease");
            }
        }

        public static AcquireResult acquired(AutoCloseable lease) {
            return new AcquireResult(Status.ACQUIRED, lease);
        }

        public static AcquireResult unavailable(Status status) {
            if (status == Status.ACQUIRED) throw new IllegalArgumentException("Use acquired() for ACQUIRED");
            return new AcquireResult(status, null);
        }
    }
}
