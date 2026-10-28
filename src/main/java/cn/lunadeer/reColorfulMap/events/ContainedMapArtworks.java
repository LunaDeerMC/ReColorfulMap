package cn.lunadeer.reColorfulMap.events;

import cn.lunadeer.reColorfulMap.MapItemData;
import cn.lunadeer.reColorfulMap.storage.MapStorage.ImageReference;
import org.bukkit.block.Container;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** Reads item snapshots on their owning thread without retaining Bukkit objects for disk workers. */
final class ContainedMapArtworks {
    static final int MAX_DEPTH = 16;
    static final int MAX_ITEMS = 4096;

    private ContainedMapArtworks() {
    }

    record Result(Set<ImageReference> references, boolean truncated) {
        Set<UUID> artworks() {
            Set<UUID> result = new LinkedHashSet<>();
            for (ImageReference reference : references) result.add(reference.artwork());
            return Collections.unmodifiableSet(result);
        }
    }

    static Result collect(ItemStack root, boolean includeContents) {
        Set<ImageReference> references = new LinkedHashSet<>();
        if (root == null) {
            return new Result(Set.of(), false);
        }
        ArrayDeque<Entry> pending = new ArrayDeque<>();
        pending.add(new Entry(root, 0));
        int examined = 0;
        boolean truncated = false;
        while (!pending.isEmpty() && examined < MAX_ITEMS) {
            Entry entry = pending.removeFirst();
            examined++;
            MapItemData.reference(entry.item()).ifPresent(references::add);
            if (!includeContents) {
                continue;
            }
            ItemMeta meta = entry.item().getItemMeta();
            Iterable<ItemStack> contents;
            if (meta instanceof BundleMeta bundle) {
                contents = bundle.getItems();
            } else if (meta instanceof BlockStateMeta block && block.hasBlockState()
                    && block.getBlockState() instanceof Container container) {
                contents = java.util.Arrays.asList(container.getSnapshotInventory().getContents());
            } else {
                continue;
            }
            for (ItemStack child : contents) {
                if (child == null || child.getType().isAir()) {
                    continue;
                }
                if (entry.depth() >= MAX_DEPTH || examined + pending.size() >= MAX_ITEMS) {
                    // A malformed or unusually large tree must not hold up an entity region.
                    // Unvisited data is retained; only paths actually read can be deleted.
                    truncated = true;
                    break;
                }
                pending.addLast(new Entry(child, entry.depth() + 1));
            }
        }
        return new Result(Collections.unmodifiableSet(references), truncated || !pending.isEmpty());
    }

    private record Entry(ItemStack item, int depth) {
    }
}
