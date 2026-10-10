package cn.lunadeer.reColorfulMap;

import cn.lunadeer.reColorfulMap.storage.MapStorage.ImageReference;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.persistence.PersistentDataType;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

/** The only persistent identity of an image map is its small relative PNG path. */
public final class MapItemData {
    public static final NamespacedKey IMAGE_PATH = new NamespacedKey("re_colorful_map", "image_path");

    private MapItemData() {
    }

    /** Also claims damaged path values, allowing callers to report corrupt image maps safely. */
    public static boolean hasImage(ItemStack item) {
        return item != null && item.getType() == Material.FILLED_MAP
                && item.getItemMeta() instanceof MapMeta meta
                && meta.getPersistentDataContainer().has(IMAGE_PATH, PersistentDataType.STRING);
    }

    public static Optional<ImageReference> reference(ItemStack item) {
        if (item == null || item.getType() != Material.FILLED_MAP
                || !(item.getItemMeta() instanceof MapMeta meta)) {
            return Optional.empty();
        }
        var pdc = meta.getPersistentDataContainer();
        if (!pdc.has(IMAGE_PATH, PersistentDataType.STRING)) {
            return Optional.empty();
        }
        return ImageReference.parse(pdc.get(IMAGE_PATH, PersistentDataType.STRING));
    }

    public static ImageReference requireReference(ItemStack item) throws IOException {
        return reference(item).orElseThrow(() -> new IOException("Missing or invalid image map path"));
    }

    public static void setReference(MapMeta meta, ImageReference reference) {
        Objects.requireNonNull(meta, "meta").getPersistentDataContainer()
                .set(IMAGE_PATH, PersistentDataType.STRING, Objects.requireNonNull(reference, "reference").path());
    }
}
