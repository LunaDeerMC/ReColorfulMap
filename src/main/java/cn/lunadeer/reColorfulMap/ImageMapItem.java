package cn.lunadeer.reColorfulMap;

import cn.lunadeer.reColorfulMap.utils.ImageUtils;
import cn.lunadeer.reColorfulMap.utils.Notification;
import cn.lunadeer.reColorfulMap.utils.XLogger;
import cn.lunadeer.reColorfulMap.utils.configuration.ConfigurationPart;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataType;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import static cn.lunadeer.reColorfulMap.utils.ImageUtils.decodeBase64ToImage;

public class ImageMapItem extends ItemStack {

    public static class ImageMapItemText extends ConfigurationPart {
        public String imageCorrupt = "Image corrupted, cannot load. Please regenerate.";
        public String urlNotAllowed = "This image URL (%s) is not allowed";
        public String readImageFailed = "Failed to read image, please check if the image URL is correct or change the image host";
        public String imageSizeNotAllowed = "Image too large, resolution must not exceed %dx%d";
        public String matrixGenerateFailed = "Failed to generate image matrix";
        public String removedImageTiles = "Removed %d unused image tiles.";
    }

    private final Integer x_count;
    private final Integer y_count;
    private final byte[][][] tile_b64_matrix;   // 0,0 is top left
    private final byte[] thumb_b64;
    private final World world;
    private Integer mapview_id;

    /**
     * Constructs an ImageMapItem from a URL and scales the image.
     *
     * @param world The world where the map item will be used.
     * @param url   The URL of the image to be used for the map item.
     * @param scale The scale factor to resize the image.
     * @throws Exception If the URL is not allowed, the image cannot be read, or the image size exceeds the allowed limits.
     */
    public ImageMapItem(World world, String url, Float scale) throws Exception {
        super(Material.FILLED_MAP);
        this.world = world;
        if (!checkUrlAvailable(url)) {
            throw new Exception(Language.imageMapItem.urlNotAllowed.formatted(url));
        }
        URL _url = new URL(url);
        BufferedImage raw_image = ImageIO.read(_url);
        if (raw_image == null) {
            throw new Exception(Language.imageMapItem.readImageFailed);
        }
        BufferedImage resized_image;
        if (scale != 1.0) {
            resized_image = ImageUtils.resize(raw_image, scale);
        } else {
            resized_image = raw_image;
        }
        int image_width = resized_image.getWidth();
        int image_height = resized_image.getHeight();
        this.x_count = (int) Math.ceil(image_width / 128.0);
        this.y_count = (int) Math.ceil(image_height / 128.0);
        if (this.x_count > Configuration.maxFrameX || this.y_count > Configuration.maxFrameY) {
            throw new Exception(Language.imageMapItem.imageSizeNotAllowed.formatted(Configuration.maxFrameX * 128, Configuration.maxFrameY * 128));
        }
        int new_width = this.x_count * 128;
        int new_height = this.y_count * 128;
        BufferedImage centered_image = ImageUtils.center(resized_image, new_width, new_height);
        image_width = centered_image.getWidth();
        image_height = centered_image.getHeight();
        this.tile_b64_matrix = new byte[this.y_count][this.x_count][];
        for (int y = 0; y < this.y_count; y++) {
            for (int x = 0; x < this.x_count; x++) {
                int width = Math.min(128, image_width - x * 128);
                int height = Math.min(128, image_height - y * 128);
                BufferedImage sub_image = centered_image.getSubimage(x * 128, y * 128, width, height);
                this.tile_b64_matrix[y][x] = ImageUtils.encodeImageToBase64(sub_image, "png");
            }
        }
        if (this.tile_b64_matrix.length == 0) {
            throw new Exception(Language.imageMapItem.matrixGenerateFailed);
        }
        this.thumb_b64 = ImageUtils.encodeImageToBase64(ImageUtils.thumb(raw_image), "png");


        MapMeta meta = (MapMeta) this.getItemMeta();
        // Store image tile matrix
        meta.getPersistentDataContainer().set(THUMB_KEY(), PersistentDataType.BYTE_ARRAY, this.thumb_b64);
        meta.getPersistentDataContainer().set(MATRIX_X_KEY(), PersistentDataType.INTEGER, this.tile_b64_matrix[0].length);
        meta.getPersistentDataContainer().set(MATRIX_Y_KEY(), PersistentDataType.INTEGER, this.tile_b64_matrix.length);
        // Set size info
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("X: %d".formatted(this.x_count)));
        lore.add(Component.text("Y: %d".formatted(this.y_count)));
        meta.lore(lore);
        // Update map item meta
        this.setItemMeta(meta);
        // Render thumbnail
        this.setUpThumbnail();
        // Store image tile matrix to world PDC
        for (int y = 0; y < this.y_count; y++) {
            for (int x = 0; x < this.x_count; x++) {
                saveTileToWorldPDC(x, y);
            }
        }

        XLogger.debug("ImageMapItem created: %s".formatted(url));
        XLogger.debug("ImageMapItem size: %dx%d".formatted(this.x_count, this.y_count));
    }

    /**
     * Sets up the thumbnail for the ImageMapItem.
     * This method creates a new map view, renders the thumbnail image on the map meta,
     * and stores the thumbnail in the world's persistent data container.
     *
     * @throws Exception If there is an error during the setup process.
     */
    public void setUpThumbnail() throws Exception {
        if (this.mapview_id != null) {
            PDCImageManager.removeThumbnailFromWorldPDC(this);
        }
        MapMeta meta = (MapMeta) this.getItemMeta();
        MapView mapView = Bukkit.createMap(world);
        meta.setMapView(mapView);
        this.mapview_id = mapView.getId();
        BufferedImage thumb_image = decodeBase64ToImage(this.thumb_b64);
        if (thumb_image == null) {
            return;
        }
        ImageRenderer.renderOnMeta(meta, thumb_image);
        PDCImageManager.addThumbnailToWorldPDC(this);
        this.setItemMeta(meta);
    }

    /**
     * Constructs an ImageMapItem from an existing ItemStack.
     * <p>
     * THIS IS USED TO RECONSTRUCT AN ImageMapItem FROM AN EXISTING MAP ITEM.
     *
     * @param mapItem The ItemStack to construct the ImageMapItem from.
     * @throws Exception If the image data is not a valid ImageMapItem.
     */
    public ImageMapItem(ItemStack mapItem) throws Exception {
        super(mapItem);
        MapMeta meta = (MapMeta) mapItem.getItemMeta();
        if (!meta.getPersistentDataContainer().has(THUMB_KEY(), PersistentDataType.BYTE_ARRAY)) {
            throw new Exception("IGNORING: Not an ImageMapItem");
        }
        this.thumb_b64 = meta.getPersistentDataContainer().get(THUMB_KEY(), PersistentDataType.BYTE_ARRAY);
        this.x_count = meta.getPersistentDataContainer().get(MATRIX_X_KEY(), PersistentDataType.INTEGER);
        this.y_count = meta.getPersistentDataContainer().get(MATRIX_Y_KEY(), PersistentDataType.INTEGER);
        if (this.x_count == null || this.y_count == null) {
            throw new Exception(Language.imageMapItem.imageCorrupt);
        }
        if (meta.getMapView() == null) {
            throw new Exception(Language.imageMapItem.imageCorrupt);
        }
        this.world = meta.getMapView().getWorld();
        this.mapview_id = meta.getMapView().getId();
        this.tile_b64_matrix = new byte[this.y_count][this.x_count][];
        for (int y = 0; y < this.y_count; y++) {
            for (int x = 0; x < this.x_count; x++) {
                this.tile_b64_matrix[y][x] = getTileFromWorldPDC(x, y);
            }
        }
        this.setItemMeta(meta);
    }

    public void setMapviewId(int id) {
        this.mapview_id = id;
    }

    public World getWorld() {
        return world;
    }

    public Integer getXCount() {
        return x_count;
    }

    public Integer getYCount() {
        return y_count;
    }

    public byte[] getThumbBase64() {
        return thumb_b64;
    }

    public Integer getMapviewId() {
        return mapview_id;
    }

    public byte[] getTileBase64(int x, int y) {
        return tile_b64_matrix[y][x];
    }

    public void setTileBase64(int x, int y, byte[] base64) {
        tile_b64_matrix[y][x] = base64;
    }

    private static NamespacedKey THUMB_KEY() {
        return new NamespacedKey("re_colorful_map", "thumb");
    }

    private static NamespacedKey MATRIX_X_KEY() {
        return new NamespacedKey("re_colorful_map", "matrix_x");
    }

    private static NamespacedKey MATRIX_Y_KEY() {
        return new NamespacedKey("re_colorful_map", "matrix_y");
    }

    private static NamespacedKey IMAGE_TILE_KEY(int mapId, int x, int y) {
        return new NamespacedKey("re_colorful_map", "%d_tile_%d_%d".formatted(mapId, x, y));
    }

    public void saveTileToWorldPDC(int x, int y) {
        Objects.requireNonNull(world).getPersistentDataContainer().set(IMAGE_TILE_KEY(mapview_id, x, y), PersistentDataType.BYTE_ARRAY, tile_b64_matrix[y][x]);
    }

    public void removeTileFromWorldPDC(int x, int y) {
        Objects.requireNonNull(world).getPersistentDataContainer().remove(IMAGE_TILE_KEY(mapview_id, x, y));
    }

    public byte[] getTileFromWorldPDC(int x, int y) {
        return Objects.requireNonNull(world).getPersistentDataContainer().get(IMAGE_TILE_KEY(mapview_id, x, y), PersistentDataType.BYTE_ARRAY);
    }



    private static boolean checkUrlAvailable(String url) {
        if (Configuration.addressWhiteList.isEmpty()) {
            return true;
        }
        for (String whiteUrl : Configuration.addressWhiteList) {
            if (url.startsWith(whiteUrl)) {
                return true;
            }
            if (url.startsWith("http://" + whiteUrl)) {
                return true;
            }
            if (url.startsWith("https://" + whiteUrl)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Removes all image tiles from the persistent data containers of all worlds.
     * This method iterates through all worlds and their persistent data containers,
     * matches keys against the pattern `%d_tile_%d_%d`, and removes the matching keys.
     * <p>
     * NOTICE: This method will make all generated but not placed image tiles unusable.
     *
     * @param sender The command sender who initiated the purge.
     */
    public static void pureImageTiles(CommandSender sender) {
        AtomicInteger count = new AtomicInteger();
        ReColorfulMap.getInstance().getServer().getWorlds().forEach(world -> {
            world.getPersistentDataContainer().getKeys().forEach(key -> {
                if (key.getNamespace().equals("re_colorful_map")) {
                    // match %d_tile_%d_%d
                    String regex = "\\d+_tile_\\d+_\\d+";
                    if (key.getKey().matches(regex)) {
                        world.getPersistentDataContainer().remove(key);
                        count.getAndIncrement();
                    }
                }
            });
        });
        Notification.info(sender, Language.imageMapItem.removedImageTiles.formatted(count.get()));
    }

}
