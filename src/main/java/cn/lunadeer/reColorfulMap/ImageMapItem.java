package cn.lunadeer.reColorfulMap;

import cn.lunadeer.reColorfulMap.storage.MapStorage;
import cn.lunadeer.reColorfulMap.storage.MapStorage.Artwork;
import cn.lunadeer.reColorfulMap.storage.MapStorage.ImageReference;
import cn.lunadeer.reColorfulMap.utils.configuration.ConfigurationPart;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** An artwork item carries a small file path, never PNG bytes or a global index key. */
public class ImageMapItem extends ItemStack {
    public static class ImageMapItemText extends ConfigurationPart {
        public String imageCorrupt = "Image files are missing or corrupted; check the server log.";
        public String urlNotAllowed = "This image URL (%s) is not allowed";
        public String readImageFailed = "Failed to read image, please check if the image URL is correct or change the image host";
        public String imageSizeNotAllowed = "Image too large, resolution must not exceed %dx%d";
        public String matrixGenerateFailed = "Failed to generate image matrix";
        public String removedImageTiles = "Removed %d temporary files. Saved artworks were kept.";
    }

    private final Artwork artwork;
    private final World world;

    /** Called on the server/entity thread after the artwork files have been committed. */
    public ImageMapItem(World world, Artwork artwork) throws IOException {
        this(world, artwork, ReColorfulMap.getInstance().getMapStorage().read(ImageReference.thumbnail(artwork.id())));
    }

    /** All PNG writes finish before an item referencing them is created. */
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
        MapItemData.setReference(meta, reference);
        meta.lore(List.of(Component.text("size: " + artwork.columns() + "x" + artwork.rows())));
        for (var renderer : view.getRenderers()) {
            view.removeRenderer(renderer);
        }
        view.addRenderer(new ImageRenderer(storage, reference, thumbnail));
        setItemMeta(meta);
    }

    public ImageMapItem(ItemStack mapItem) throws IOException {
        super(mapItem);
        ImageReference reference = MapItemData.requireReference(mapItem);
        if (!reference.thumbnail()) {
            throw new IOException("This map is an image tile, not a whole artwork");
        }
        this.artwork = ReColorfulMap.getInstance().getMapStorage().artwork(reference.artwork());
        MapView view = ((MapMeta) mapItem.getItemMeta()).getMapView();
        this.world = view == null ? null : view.getWorld();
    }

    public static boolean isImageMap(ItemStack item) {
        // Claim damaged tagged items too, so a failed placement cannot silently consume them.
        return MapItemData.hasImage(item) && MapItemData.reference(item).map(ImageReference::thumbnail).orElse(true);
    }

    public static Optional<UUID> identify(ItemStack item) {
        return MapItemData.reference(item).filter(ImageReference::thumbnail).map(ImageReference::artwork);
    }

    public Artwork getArtwork() {
        return artwork;
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
