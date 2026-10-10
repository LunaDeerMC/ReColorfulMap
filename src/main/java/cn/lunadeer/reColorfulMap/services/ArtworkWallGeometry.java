package cn.lunadeer.reColorfulMap.services;

import cn.lunadeer.reColorfulMap.Language;
import cn.lunadeer.reColorfulMap.utils.SafeMessageFormat;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;

/** Matrix geometry and region/chunk boundaries, independent of file preparation and transactions. */
public final class ArtworkWallGeometry {
    private ArtworkWallGeometry() { }
    private static final boolean FOLIA = isFolia();
    /** Coordinates use the image's top-left origin; the wall is anchored at its bottom-left frame. */
    public static Location tileLocation(Location origin, BlockFace facing, int x, int y, int rows) {
        validateCoordinates(facing, x, y, rows);
        return origin.clone().add(facing.getModZ() * x, rows - 1 - y, -facing.getModX() * x);
    }

    /** Inverse of tileLocation, also works for ColorfulMap 2.3 frames with no item metadata. */
    public static Location originLocation(Location tile, BlockFace facing, int x, int y, int rows) {
        validateCoordinates(facing, x, y, rows);
        return tile.clone().add(-facing.getModZ() * x, y - rows + 1, facing.getModX() * x);
    }

    private static void validateCoordinates(BlockFace facing, int x, int y, int rows) {
        if (facing != BlockFace.NORTH && facing != BlockFace.SOUTH && facing != BlockFace.WEST && facing != BlockFace.EAST) {
            throw new IllegalArgumentException(Language.imageMapEvent.notSupportedDirection);
        }
        if (rows <= 0 || x < 0 || y < 0 || y >= rows) {
            throw new IllegalArgumentException("Invalid image tile coordinates.");
        }
    }

    public static ItemFrame[][] getItemFrameMatrix(ItemFrame leftBottom, Integer columns, Integer rows) throws Exception {
        if (columns == null || rows == null || columns <= 0 || rows <= 0) {
            throw new IllegalArgumentException("Invalid image dimensions.");
        }
        ItemFrame[][] frames = new ItemFrame[columns][rows];
        for (int x = 0; x < columns; x++) {
            for (int y = 0; y < rows; y++) {
                Location location = tileLocation(leftBottom.getLocation(), leftBottom.getFacing(), x, y, rows);
                ItemFrame frame = getItemFrame(location, leftBottom.getFacing());
                if (frame == null) {
                    throw new IllegalStateException(SafeMessageFormat.format(java.util.logging.Logger.getLogger("ReColorfulMap"),
                            Language.imageMapEvent.incompleteItemFrameArray, columns, rows));
                }
                frames[x][y] = frame;
            }
        }
        return frames;
    }

    static ItemFrame getItemFrame(Location location, BlockFace facing) {
        requireOwned(location);
        if (location.getWorld() == null) {
            return null;
        }
        if (!location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
            throw new UnavailableFrameAreaException(Language.imageMapEvent.unloadedChunk);
        }
        for (Entity entity : location.getWorld().getNearbyEntities(location, 0.25, 0.25, 0.25)) {
            if (entity instanceof ItemFrame frame && frame.getFacing() == facing
                    && entity.getLocation().getBlockX() == location.getBlockX()
                    && entity.getLocation().getBlockY() == location.getBlockY()
                    && entity.getLocation().getBlockZ() == location.getBlockZ()) {
                return frame;
            }
        }
        return null;
    }

    static void requireOwned(Location location) {
        if (FOLIA && !Bukkit.isOwnedByCurrentRegion(location)) {
            throw new UnavailableFrameAreaException(Language.imageMapEvent.unownedRegion);
        }
    }

    static final class UnavailableFrameAreaException extends IllegalStateException {
        private UnavailableFrameAreaException(String message) {
            super(message);
        }
    }

    static boolean isFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }

}
