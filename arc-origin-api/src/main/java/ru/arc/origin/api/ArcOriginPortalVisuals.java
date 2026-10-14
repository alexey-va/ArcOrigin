package ru.arc.origin.api;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;

/** Host-owned renderer and gate controller for Origin's inter-server portal. */
public interface ArcOriginPortalVisuals {
    /** Returns null when the host cannot validate or create this configured gate. */
    Gate open(Location displayCenter, Settings settings);

    /** Plain Java settings passed across the plugin class-loader boundary. */
    record Settings(
            String styleId,
            Map<String, String> itemIds,
            double width,
            double height,
            double verticalOffset,
            boolean particlesEnabled,
            int streams,
            int reducedStreams,
            int pointsPerStream,
            int reducedPointsPerStream,
            double radius,
            double suctionHeight,
            double turns,
            double particleSize,
            int coreCount
    ) {
        public Settings {
            itemIds = Map.copyOf(itemIds);
        }
    }

    /** A single host-owned gate. All methods must be called on the server thread. */
    interface Gate extends AutoCloseable {
        boolean isActive();

        boolean gateSpawned();

        /** Advances opening by elapsed ticks and returns whether the opening remains active. */
        boolean tick(int elapsedTicks);

        void updateScale(float scale);

        void renderSuction(int tick, List<Player> full, List<Player> reduced);

        @Override
        void close();
    }
}
