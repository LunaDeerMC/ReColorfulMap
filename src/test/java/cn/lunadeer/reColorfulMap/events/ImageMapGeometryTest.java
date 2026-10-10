package cn.lunadeer.reColorfulMap.events;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ImageMapGeometryTest {
    /** Coordinates copied from the 2.3 top-left PNG convention, for a 3 by 2 artwork. */
    static Stream<Arguments> artworkWalls() {
        return Stream.of(
                Arguments.of(BlockFace.NORTH, new int[][]{
                        {10, 65, 20}, {9, 65, 20}, {8, 65, 20},
                        {10, 64, 20}, {9, 64, 20}, {8, 64, 20}}),
                Arguments.of(BlockFace.SOUTH, new int[][]{
                        {10, 65, 20}, {11, 65, 20}, {12, 65, 20},
                        {10, 64, 20}, {11, 64, 20}, {12, 64, 20}}),
                Arguments.of(BlockFace.WEST, new int[][]{
                        {10, 65, 20}, {10, 65, 21}, {10, 65, 22},
                        {10, 64, 20}, {10, 64, 21}, {10, 64, 22}}),
                Arguments.of(BlockFace.EAST, new int[][]{
                        {10, 65, 20}, {10, 65, 19}, {10, 65, 18},
                        {10, 64, 20}, {10, 64, 19}, {10, 64, 18}})
        );
    }

    @ParameterizedTest
    @MethodSource("artworkWalls")
    void findsThreeByTwoPngOrderFromLowerLeft(BlockFace facing, int[][] positions) throws Exception {
        World world = mock(World.class);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        List<Entity> entities = frames(world, facing, positions);
        when(world.getNearbyEntities(any(Location.class), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(entities);

        ItemFrame[][] actual = ImageMapEvent.getItemFrameMatrix((ItemFrame) entities.get(3), 3, 2);

        assertSame(entities.get(0), actual[0][0], "0_0.png must be the upper-left frame");
        assertSame(entities.get(1), actual[1][0]);
        assertSame(entities.get(2), actual[2][0]);
        assertSame(entities.get(3), actual[0][1], "0_1.png must be the clicked lower-left frame");
        assertSame(entities.get(4), actual[1][1]);
        assertSame(entities.get(5), actual[2][1]);
    }

    @ParameterizedTest
    @MethodSource("artworkWalls")
    void refusesIncompleteArrayEvenWhenFrameAtMissingPositionFacesOtherWay(BlockFace facing, int[][] positions) {
        World world = mock(World.class);
        when(world.isChunkLoaded(anyInt(), anyInt())).thenReturn(true);
        List<Entity> entities = frames(world, facing, positions);
        when(((ItemFrame) entities.get(1)).getFacing()).thenReturn(facing.getOppositeFace());
        when(world.getNearbyEntities(any(Location.class), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(entities);

        assertThrows(Exception.class,
                () -> ImageMapEvent.getItemFrameMatrix((ItemFrame) entities.get(3), 3, 2));
    }

    @ParameterizedTest
    @MethodSource("artworkWalls")
    void anyTileFindsTheSameLowerLeftOrigin(BlockFace facing, int[][] positions) {
        World world = mock(World.class);
        Location origin = new Location(world, 10, 64, 20);
        for (int index = 0; index < positions.length; index++) {
            int[] position = positions[index];
            Location clicked = new Location(world, position[0], position[1], position[2]);
            assertEquals(origin, ImageMapEvent.originLocation(clicked, facing, index % 3, index / 3, 2));
            assertEquals(new Location(world, position[0], position[1], position[2]), clicked,
                    "Locating the origin must not mutate the entity's location");
        }
    }

    private static List<Entity> frames(World world, BlockFace facing, int[][] positions) {
        List<Entity> frames = new ArrayList<>();
        for (int[] position : positions) {
            ItemFrame frame = mock(ItemFrame.class);
            when(frame.getWorld()).thenReturn(world);
            when(frame.getFacing()).thenReturn(facing);
            when(frame.getLocation()).thenAnswer(invocation ->
                    new Location(world, position[0], position[1], position[2]));
            frames.add(frame);
        }
        return frames;
    }
}
