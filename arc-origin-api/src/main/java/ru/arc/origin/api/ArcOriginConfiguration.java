package ru.arc.origin.api;

import java.nio.file.Path;

/** Host configuration required by the Origin plugin. Implemented by the ARC host. */
public interface ArcOriginConfiguration {
    /** Path of the historical ARC data directory, preserving existing Origin config locations. */
    Path legacyDataPath();

    /** Current network server name, or an empty string when not configured. */
    String serverName();

    /** Whether the host currently exposes its shared primary Redis configuration. */
    boolean sharedRedisAvailable();

    /** Changes whenever the shared Redis configuration changes, including disable/enable transitions. */
    long sharedRedisGeneration();
}
