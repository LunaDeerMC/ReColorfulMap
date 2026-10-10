package cn.lunadeer.reColorfulMap;

import cn.lunadeer.reColorfulMap.storage.MapStorage;
import cn.lunadeer.reColorfulMap.storage.MapStorage.Artwork;
import cn.lunadeer.reColorfulMap.storage.MapStorage.ImageReference;
import cn.lunadeer.reColorfulMap.storage.MapStorage.MapKey;
import cn.lunadeer.reColorfulMap.utils.configuration.ConfigurationPart;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A portable reference to an artwork. All image bytes live in the maps directory. */
public class ImageMapItem extends ItemStack {
    public static class ImageMapItemText extends ConfigurationPart {
        public String imageCorrupt = "Image files are missing or corrupted; check the server log.";
        public String urlNotAllowed = "This image URL (%s) is not allowed";
        public String readImageFailed = "Failed to read image, please check if the image URL is correct or change the image host";
        public String imageSizeNotAllowed = "Image too large, resolution must not exceed %dx%d";
        public String matrixGenerateFailed = "Failed to generate image matrix";
        public String removedImageTiles = "Removed %d temporary files. Saved artworks were kept.";
    }

    private static final Pattern LEGACY_SIZE = Pattern.compile("size: ([1-9][0-9]*)x([1-9][0-9]*)");
    private final Artwork artwork;
    private final World world;

    /** Called on the server/entity thread after the artwork files have been committed. */
    public ImageMapItem(World world, Artwork artwork) throws IOException {
        this(world, artwork, ReColorfulMap.getInstance().getMapStorage().read(ImageReference.thumbnail(artwork.id())));
        ReColorfulMap.getInstance().getMapStorage().register(references());
    }

    /** Prepares the item on the owning thread. Persist references() before giving it to a player. */
    public ImageMapItem(World world, Artwork artwork, BufferedImage thumbnail) throws IOException {
        super(Material.FILLED_MAP);
        if (world == null) {
            throw new IOException("The map's world is not available");
        }
        this.world = world;
        this.artwork = artwork;
        MapStorage storage = ReColorfulMap.getInstance().getMapStorage();
        ImageReference reference = ImageReference.thumbnail(artwork.id());
        MapView view = Bukkit.createMap(world);
        MapMeta meta = (MapMeta) getItemMeta();
        meta.setMapView(view);
        // Keep ColorfulMap 2.3's small, portable item format; no image data in PDC.
        meta.lore(List.of(Component.text("size: " + artwork.columns() + "x" + artwork.rows())
                .hoverEvent(Component.text(artwork.id().toString()))));
        for (var renderer : view.getRenderers()) {
            view.removeRenderer(renderer);
        }
        view.addRenderer(new ImageRenderer(storage, reference, thumbnail));
        setItemMeta(meta);
    }

    /** Reconstruct a new or ColorfulMap 2.3 thumbnail without reading all of its pixels. */
    public ImageMapItem(ItemStack mapItem) throws IOException {
        super(mapItem);
        MapStorage storage = ReColorfulMap.getInstance().getMapStorage();
        UUID id = identify(mapItem, storage)
                .orElseThrow(() -> new IOException("Not an image map thumbnail"));
        this.artwork = storage.artwork(id);
        MapMeta meta = (MapMeta) mapItem.getItemMeta();
        MapView view = meta.getMapView();
        this.world = view == null ? null : view.getWorld();
    }

    public static boolean isImageMap(ItemStack item) {
        return identify(item, ReColorfulMap.getInstance().getMapStorage()).isPresent();
    }

    /** Indexed thumbnails also work after renaming/removing lore. Indexed tiles are never thumbnails. */
    public static Optional<UUID> identify(ItemStack item, MapStorage storage) {
        if (item == null || item.getType() != Material.FILLED_MAP || !(item.getItemMeta() instanceof MapMeta meta)) {
            return Optional.empty();
        }
        MapView view = meta.getMapView();
        if (view != null && view.getWorld() != null) {
            Optional<ImageReference> reference = storage.find(view.getWorld().getName(), view.getId());
            if (reference.isPresent()) {
                return reference.get().thumbnail() ? Optional.of(reference.get().artwork()) : Optional.empty();
            }
        }
        return legacyArtwork(meta.lore());
    }

    /** ColorfulMap 2.3 stored its UUID in the SHOW_TEXT hover event of its single lore line. */
    public static Optional<UUID> legacyArtwork(List<Component> lore) {
        if (lore == null || lore.size() != 1) {
            return Optional.empty();
        }
        Matcher matcher = LEGACY_SIZE.matcher(PlainTextComponentSerializer.plainText().serialize(lore.get(0)));
        if (!matcher.matches()) {
            return Optional.empty();
        }
        try {
            Integer.parseInt(matcher.group(1));
            Integer.parseInt(matcher.group(2));
            return artworkHover(lore.get(0));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static Optional<UUID> artworkHover(Component component) {
        HoverEvent<?> hover = component.hoverEvent();
        if (hover != null && hover.action() == HoverEvent.Action.SHOW_TEXT && hover.value() instanceof Component value) {
            String text = PlainTextComponentSerializer.plainText().serialize(value);
            try {
                UUID id = UUID.fromString(text);
                if (id.toString().equalsIgnoreCase(text)) {
                    return Optional.of(id);
                }
            } catch (IllegalArgumentException ignored) {
                // A component wrapper may carry unrelated hover text; also inspect its children.
            }
        }
        for (Component child : component.children()) {
            Optional<UUID> id = artworkHover(child);
            if (id.isPresent()) {
                return id;
            }
        }
        return Optional.empty();
    }

    public Artwork getArtwork() {
        return artwork;
    }

    public Map<MapKey, ImageReference> references() {
        MapView view = ((MapMeta) getItemMeta()).getMapView();
        if (view == null || view.getWorld() == null) {
            throw new IllegalStateException("The thumbnail has no world/map ID");
        }
        return Map.of(new MapKey(view.getWorld().getName(), view.getId()), ImageReference.thumbnail(artwork.id()));
    }

    public UUID getArtworkId() {
        return artwork.id();
    }

    public World getWorld() {
        return world;
    }

    public Integer getXCount() {
        return artwork.columns();
    }

    public Integer getYCount() {
        return artwork.rows();
    }
}
