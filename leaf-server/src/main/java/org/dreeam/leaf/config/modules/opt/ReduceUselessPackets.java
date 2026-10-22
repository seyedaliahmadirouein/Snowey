package org.dreeam.leaf.config.modules.opt;

import org.dreeam.leaf.config.ConfigModule;
import org.dreeam.leaf.config.ConfigCategory;

public class ReduceUselessPackets extends ConfigModule {

    public String basePath() {
        return ConfigCategory.PERF.basePath() + ".reduce-packets";
    }

    public static boolean reduceUselessEntityMovePackets = false;
    public static boolean filterClientboundSetEntityMotionPacket = false;
    public static boolean disableUselessParticles = false;
    // Leaf start - cache ClientboundSetEntityMotionPacket in ServerEntity
    // When true, ServerEntity reuses a single ClientboundSetEntityMotionPacket instance per
    // entity between actual motion changes (identity-checked against lastSentMovement, which
    // SparklyPaper patch 0101 guarantees is reassigned — never mutated — when motion changes).
    // Defaults to false for ProtocolLib compatibility: ProtocolLib intercepts Connection#send
    // and may mutate the packet object on interception, which would leak across players if a
    // single instance is shared. Enable on servers without such interceptors for a meaningful
    // reduction in tracker-path allocations under entity-dense workloads.
    public static boolean cacheMotionPacket = false;
    // Leaf end - cache ClientboundSetEntityMotionPacket in ServerEntity

    @Override
    public void onLoaded() {
        reduceUselessEntityMovePackets = globalConfig.getBoolean(basePath() + ".reduce-entity-move-packets", reduceUselessEntityMovePackets);
        filterClientboundSetEntityMotionPacket = globalConfig.getBoolean(basePath() + ".reduce-entity-motion-packets", filterClientboundSetEntityMotionPacket);
        disableUselessParticles = globalConfig.getBoolean(basePath() + ".disable-useless-particles", disableUselessParticles);
        cacheMotionPacket = globalConfig.getBoolean(basePath() + ".cache-entity-motion-packet", cacheMotionPacket);
    }
}
