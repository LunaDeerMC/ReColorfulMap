package cn.lunadeer.reColorfulMap;

import cn.lunadeer.reColorfulMap.storage.MapStorage;
import net.kyori.adventure.text.Component;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** PNG fixture retaining the established tile ordering, with no runtime legacy index. */
public final class LegacyMapFixture {
    public static final UUID ARTWORK = UUID.fromString("b44b66b1-1260-43a6-91bb-aeb1f3dca74d");
    public static final UUID OTHER_ARTWORK = UUID.fromString("0ca1858f-3e96-40d1-913e-53960ab57f41");
    public static final int THUMBNAIL_ID = 123;

    private LegacyMapFixture() {
    }

    public static MapStorage copyTo(Path pluginDirectory) throws IOException {
        writeArtwork(pluginDirectory, ARTWORK, 0xffff0000);
        writeArtwork(pluginDirectory, OTHER_ARTWORK, 0xff0000ff);
        return new MapStorage(pluginDirectory);
    }

    /** Models Bukkit's mutable STRING PDC and independent MapMeta clones. */
    public static MapMeta mapMeta(MapView initialView) {
        return mapMeta(initialView, new HashMap<>(), null);
    }

    private static MapMeta mapMeta(MapView initialView, Map<NamespacedKey, String> values,
                                   List<Component> initialLore) {
        PersistentDataContainer pdc = mock(PersistentDataContainer.class);
        when(pdc.getKeys()).thenAnswer(call -> java.util.Set.copyOf(values.keySet()));
        when(pdc.isEmpty()).thenAnswer(call -> values.isEmpty());
        when(pdc.has(any(NamespacedKey.class), eq(PersistentDataType.STRING)))
                .thenAnswer(call -> values.containsKey(call.getArgument(0)));
        when(pdc.get(any(NamespacedKey.class), eq(PersistentDataType.STRING)))
                .thenAnswer(call -> values.get(call.getArgument(0)));
        doAnswer(call -> {
            values.put(call.getArgument(0), call.getArgument(2));
            return null;
        }).when(pdc).set(any(NamespacedKey.class), eq(PersistentDataType.STRING), anyString());
        doAnswer(call -> { values.remove(call.getArgument(0)); return null; })
                .when(pdc).remove(any(NamespacedKey.class));
        MapMeta meta = mock(MapMeta.class, withSettings().extraInterfaces(Damageable.class));
        AtomicReference<MapView> view = new AtomicReference<>(initialView);
        AtomicReference<List<Component>> lore = new AtomicReference<>(initialLore);
        when(meta.getMapView()).thenAnswer(call -> view.get());
        when(meta.getPersistentDataContainer()).thenReturn(pdc);
        when(meta.lore()).thenAnswer(call -> lore.get());
        doAnswer(call -> { view.set(call.getArgument(0)); return null; })
                .when(meta).setMapView(any(MapView.class));
        doAnswer(call -> { lore.set(call.getArgument(0)); return null; })
                .when(meta).lore(any());
        when(meta.clone()).thenAnswer(call -> mapMeta(view.get(), new HashMap<>(values), lore.get()));
        return meta;
    }

    public static boolean sameMeta(ItemMeta first, ItemMeta second) {
        if (first == second) {
            return true;
        }
        if (!(first instanceof MapMeta left) || !(second instanceof MapMeta right)) {
            return false;
        }
        return left.getMapView() == right.getMapView()
                && Objects.equals(left.lore(), right.lore())
                && Objects.equals(left.getPersistentDataContainer().get(MapItemData.IMAGE_PATH, PersistentDataType.STRING),
                right.getPersistentDataContainer().get(MapItemData.IMAGE_PATH, PersistentDataType.STRING));
    }

    private static void writeArtwork(Path pluginDirectory, UUID id, int color) throws IOException {
        Path directory = Files.createDirectories(pluginDirectory.resolve("maps").resolve(id.toString()));
        BufferedImage image = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, color);
        ImageIO.write(image, "png", directory.resolve("thumb.png").toFile());
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 3; x++) {
                ImageIO.write(image, "png", directory.resolve(x + "_" + y + ".png").toFile());
            }
        }
    }
}
