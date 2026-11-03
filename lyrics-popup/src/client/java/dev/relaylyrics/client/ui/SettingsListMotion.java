package dev.relaylyrics.client.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Measured accordion slots: stable identities, reversible exits, bounded entrance staggering. */
final class SettingsListMotion {
    record Item(String id, double height) { }
    record Frame(String id, double y, double height, double opacity, boolean leaving) { }
    private static final class Node {
        final String id;
        final SettingsMotion.Tween extent = new SettingsMotion.Tween(0), opacity = new SettingsMotion.Tween(0);
        double height;
        boolean leaving;
        Node(String id) { this.id = id; }
    }
    private final LinkedHashMap<String, Node> nodes = new LinkedHashMap<>();
    private final List<String> order = new ArrayList<>();
    void retarget(List<Item> desired, long now, boolean animate, boolean replace) {
        if (replace) { nodes.clear(); order.clear(); }
        List<String> previous = List.copyOf(order);
        var wanted = new java.util.HashSet<String>();
        order.clear();
        int entering = 0;
        for (Item item : desired) {
            if (!wanted.add(item.id())) throw new IllegalArgumentException("Duplicate setting row");
            Node node = nodes.get(item.id());
            boolean fresh = node == null;
            if (fresh) { node = new Node(item.id()); nodes.put(item.id(), node); }
            node.height = Math.max(0,item.height()); node.leaving = false;
            node.extent.to(node.height, SettingsMotion.EXPAND, SettingsMotion.Curve.OUT, now, animate);
            long delay = fresh && animate ? Math.min(3,entering++) * SettingsMotion.STAGGER * 1_000_000L : 0;
            node.opacity.to(1, SettingsMotion.EXPAND, SettingsMotion.Curve.OUT, now + delay, animate);
            order.add(item.id());
        }
        for (int i = 0; i < previous.size(); i++) {
            String id = previous.get(i);
            if (wanted.contains(id)) continue;
            Node node = nodes.get(id); if (node == null) continue;
            node.leaving = true;
            node.extent.to(0, SettingsMotion.EXPAND, SettingsMotion.Curve.OUT, now, animate);
            node.opacity.to(0, SettingsMotion.PRESS, SettingsMotion.Curve.OUT, now, animate);
            int at = order.size();
            for (int j = i+1; j < previous.size(); j++) {
                int next = order.indexOf(previous.get(j)); if (next >= 0) { at = next; break; }
            }
            order.add(at,id);
        }
    }
    List<Frame> sample(long now, boolean animate) {
        List<Frame> frames = new ArrayList<>(); double y = 0;
        var iterator = order.iterator();
        while (iterator.hasNext()) {
            String id = iterator.next(); Node node = nodes.get(id);
            double height = node.extent.to(node.leaving ? 0 : node.height, SettingsMotion.EXPAND, SettingsMotion.Curve.OUT, now, animate);
            double alpha = node.opacity.to(node.leaving ? 0 : 1, SettingsMotion.EXPAND, SettingsMotion.Curve.OUT, now, animate);
            if (node.leaving && height <= .01 && alpha <= .001) { iterator.remove(); nodes.remove(id); continue; }
            frames.add(new Frame(id,y,height,alpha,node.leaving)); y += height;
        }
        return frames;
    }
    void clear() { nodes.clear(); order.clear(); }
}
