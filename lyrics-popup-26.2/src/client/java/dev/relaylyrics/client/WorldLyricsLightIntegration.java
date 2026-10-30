package dev.relaylyrics.client;

import dev.lambdaurora.lambdynlights.api.DynamicLightsContext;
import dev.lambdaurora.lambdynlights.api.DynamicLightsInitializer;
import dev.lambdaurora.lambdynlights.api.item.ItemLightSourceManager;
import dev.lambdaurora.lambdynlights.api.behavior.DynamicLightBehavior;
import dev.lambdaurora.lambdynlights.api.behavior.DynamicLightBehaviorManager;
import dev.relaylyrics.client.music.WorldLyricsLights;
import net.minecraft.core.BlockPos;
import java.util.*;

/** Optional API entrypoint. Fabric never initializes this class without LambDynamicLights. */
public final class WorldLyricsLightIntegration implements DynamicLightsInitializer {
    // API 4.8 still declares the legacy initializer abstract; 4.9+ calls the context overload.
    public void onInitializeDynamicLights(ItemLightSourceManager ignored) { }
    @Override public void onInitializeDynamicLights(DynamicLightsContext context) {
        WorldLyricsLights.install(new Backend(context.dynamicLightBehaviorManager()));
        dev.relaylyrics.FanDroidMod.LOGGER.info("Lyrica world-word terrain lighting connected to LambDynamicLights");
    }
    private static final class Backend implements WorldLyricsLights.Backend {
        private final DynamicLightBehaviorManager manager;
        private final List<Source> sources = new ArrayList<>();
        Backend(DynamicLightBehaviorManager manager) { this.manager=manager; }
        public void update(Object world, List<WorldLyricsLights.Point> points) {
            while (sources.size() > points.size()) {
                Source old=sources.removeLast(); old.removed=true; manager.remove(old);
            }
            for (int i=0; i<points.size(); i++) {
                if (i==sources.size()) { Source source=new Source(points.get(i)); sources.add(source); manager.add(source); }
                else sources.get(i).set(points.get(i));
            }
        }
        public void clear() {
            for (Source source : sources) { source.removed=true; manager.remove(source); }
            sources.clear();
        }
    }
    private static final class Source implements DynamicLightBehavior {
        private volatile WorldLyricsLights.Point point;
        private volatile boolean removed;
        private volatile long revision;
        private long consumed=-1;
        Source(WorldLyricsLights.Point point) { set(point); }
        void set(WorldLyricsLights.Point p) {
            var next = new WorldLyricsLights.Point(Math.rint(p.x()*8)/8, Math.rint(p.y()*8)/8,
                    Math.rint(p.z()*8)/8, Math.rint(p.luminance()*2)/2);
            if (!next.equals(point)) { point=next; revision++; }
        }
        public double lightAtPos(BlockPos pos, double falloffRatio) {
            var p=point;
            return removed ? 0 : p.lightAt(pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5,falloffRatio);
        }
        public BoundingBox getBoundingBox() {
            var p=point; int x=(int)Math.floor(p.x()),y=(int)Math.floor(p.y()),z=(int)Math.floor(p.z());
            return new BoundingBox(x,y,z,x+1,y+1,z+1);
        }
        public boolean hasChanged() { long current=revision; boolean changed=current!=consumed; consumed=current; return changed; }
        public boolean isRemoved() { return removed; }
    }
}
