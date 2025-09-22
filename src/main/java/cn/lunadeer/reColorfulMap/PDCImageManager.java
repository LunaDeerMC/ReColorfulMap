package cn.lunadeer.reColorfulMap;

import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.persistence.PersistentDataType;

public class PDCImageManager {

    /**
     * Adds the thumbnail Base64 string of the given ImageMapItem to the world's PersistentDataContainer.
     * <p>
     * So that the thumbnail can be retrieved when the map is initialized.
     * We need to call the {@link PDCImageManager#removeThumbnailFromWorldPDC(ImageMapItem)} method
     * when the map is placed in the item frame.
     *
     * @param imageMap The ImageMapItem containing the thumbnail to add.
     */
    public static void addThumbnailToWorldPDC(ImageMapItem imageMap) {
        World world = imageMap.getWorld();
        int mapViewId = imageMap.getMapviewId();
        byte[] base64 = imageMap.getThumbBase64();
        addImageBase64WorldPDC(world, mapViewId, base64);
    }

    /**
     * Removes the thumbnail Base64 string of the given ImageMapItem from the world's PersistentDataContainer.
     * <p>
     * This method should be called when the map is placed in the item frame.
     *
     * @param imageMap The ImageMapItem containing the thumbnail to remove.
     */
    public static void removeThumbnailFromWorldPDC(ImageMapItem imageMap) {
        World world = imageMap.getWorld();
        int mapViewId = imageMap.getMapviewId();
        removeImageBase64WorldPDC(world, mapViewId);
    }

    /**
     * Adds the Base64 string of the image to the world's PersistentDataContainer.
     *
     * @param world     The world to which the image Base64 string will be added.
     * @param mapViewId The ID of the MapView associated with the image.
     * @param base64    The Base64 string of the image to add.
     */
    public static void addImageBase64WorldPDC(World world, int mapViewId, byte[] base64) {
        world.getPersistentDataContainer().set(
                new NamespacedKey("re_colorful_map", "mapview_id_%d".formatted(mapViewId)),
                PersistentDataType.BYTE_ARRAY,
                base64
        );
    }

    /**
     * Retrieves the Base64 string of the image from the world's PersistentDataContainer.
     *
     * @param world     The world from which to retrieve the image.
     * @param mapViewId The ID of the MapView to retrieve the image for.
     * @return The Base64 string of the image, or null if not found.
     */
    public static byte[] getImageBase64WorldPDC(World world, int mapViewId) {
        return world.getPersistentDataContainer().get(
                new NamespacedKey("re_colorful_map", "mapview_id_%d".formatted(mapViewId)),
                PersistentDataType.BYTE_ARRAY
        );
    }

    /**
     * Removes the Base64 string of the image from the world's PersistentDataContainer.
     *
     * @param world     The world from which to remove the image.
     * @param mapViewId The ID of the MapView associated with the image to remove.
     */
    public static void removeImageBase64WorldPDC(World world, int mapViewId) {
        world.getPersistentDataContainer().remove(
                new NamespacedKey("re_colorful_map", "mapview_id_%d".formatted(mapViewId))
        );
    }
}
