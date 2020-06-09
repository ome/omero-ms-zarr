/*
 * Copyright (C) 2020 University of Dundee & Open Microscopy Environment.
 * All rights reserved.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */

package org.openmicroscopy.ms.zarr;

import org.openmicroscopy.ms.zarr.RequestHandlerForImage.DataShape;

import ome.io.nio.PixelBuffer;

import java.awt.Dimension;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

import com.google.common.collect.ImmutableList;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import org.mockito.Mockito;

/**
 * Check that adjustments to tile sizes are made usefully and on a reasonable basis.
 * @author m.t.b.carroll@dundee.ac.uk
 */
public class TileSizeAdjustmentTest {

    private static final Function<DataShape, Boolean> ADJUSTER_X = DataShape.ADJUSTERS.get('X');
    private static final Function<DataShape, Boolean> ADJUSTER_Y = DataShape.ADJUSTERS.get('Y');
    private static final Function<DataShape, Boolean> ADJUSTER_Z = DataShape.ADJUSTERS.get('Z');

    /**
     * Create a {@link DataShape} for the given image plane dimensionality.
     * @param x an image width
     * @param y an image height
     * @param bytes a byte width
     * @return a new {@link DataShape}
     */
    public static DataShape getDataShape(int x, int y, int bytes) {
        final PixelBuffer buffer = Mockito.mock(PixelBuffer.class);
        final Dimension tileSize = new Dimension(256, 256);
        Mockito.when(buffer.getSizeX()).thenReturn(x);
        Mockito.when(buffer.getSizeY()).thenReturn(y);
        Mockito.when(buffer.getSizeC()).thenReturn(3);
        Mockito.when(buffer.getSizeZ()).thenReturn(1);
        Mockito.when(buffer.getSizeT()).thenReturn(1);
        Mockito.when(buffer.getTileSize()).thenReturn(tileSize);
        Mockito.when(buffer.getByteWidth()).thenReturn(bytes);
        return new DataShape(buffer);
    }

    /**
     * Check that tile size adjustments are necessary and reasonable.
     * @param shape a data shape
     * @param targetChunkSize the chunk size to target
     * @param adjusters the tile size adjusters that may be used
     */
    @ParameterizedTest
    @MethodSource("provideDataShapes")
    public void testAdjustTileSize(DataShape shape, int targetChunkSize, List<Function<DataShape, Boolean>> adjusters) {
        /* Determine the sizes, both before and after adjustment for comparison. */
        final int beforeWidth = shape.xTile;
        final int beforeHeight = shape.yTile;
        final int beforePlanes = shape.zTile;
        final int beforeChunkSize = beforeWidth * beforeHeight * beforePlanes * shape.byteWidth;
        shape.adjustTileSize(adjusters, targetChunkSize);
        final int afterWidth = shape.xTile;
        final int afterHeight = shape.yTile;
        final int afterPlanes = shape.zTile;
        final int afterChunkSize = afterWidth * afterHeight * afterPlanes * shape.byteWidth;
        /* Tile dimensions larger than image dimensions should not be adjusted. */
        if (beforeWidth >= shape.xSize) {
            Assertions.assertEquals(beforeWidth, afterWidth);
        }
        if (beforeHeight >= shape.ySize) {
            Assertions.assertEquals(beforeHeight, afterHeight);
        }
        /* If the tile size was already large enough then it should not be adjusted. */
        if (beforeChunkSize >= targetChunkSize) {
            Assertions.assertEquals(beforeWidth, afterWidth);
            Assertions.assertEquals(beforeHeight, afterHeight);
            Assertions.assertEquals(beforePlanes, afterPlanes);
        }
        /* If the adjusted tile size is not large enough then it should be at least the image size. */
        if (afterChunkSize < targetChunkSize) {
            if (beforeWidth > shape.xSize) {
                Assertions.assertEquals(beforeWidth, afterWidth);
            } else if (adjusters.contains(ADJUSTER_X)) {
                Assertions.assertEquals(shape.xSize, afterWidth);
            } else {
                Assertions.assertEquals(beforeWidth, afterWidth);
            }
            if (beforeHeight > shape.ySize) {
                Assertions.assertEquals(beforeHeight, afterHeight);
            } else if (adjusters.contains(ADJUSTER_Y)) {
                Assertions.assertEquals(shape.ySize, afterHeight);
            } else {
                Assertions.assertEquals(beforeHeight, afterHeight);
            }
            if (adjusters.contains(ADJUSTER_Z)) {
                Assertions.assertEquals(shape.zSize, afterPlanes);
            } else {
                Assertions.assertEquals(beforePlanes, afterPlanes);
            }
        }
        /* Tile size changes must be increases and either to a multiple of the previous or to the image size. */
        if (beforeWidth != afterWidth) {
            Assertions.assertTrue(adjusters.contains(ADJUSTER_X));
            Assertions.assertTrue(beforeWidth < afterWidth);
            if (afterWidth != shape.xSize) {
                Assertions.assertEquals(0, afterWidth % beforeWidth);
            }
        }
        if (beforeHeight != afterHeight) {
            Assertions.assertTrue(adjusters.contains(ADJUSTER_Y));
            Assertions.assertTrue(beforeHeight < afterHeight);
            if (afterHeight != shape.ySize) {
                Assertions.assertEquals(0, afterHeight % beforeHeight);
            }
        }
        if (beforePlanes > afterPlanes) {
            Assertions.assertTrue(adjusters.contains(ADJUSTER_Z));
            /* Plane count reduction must not increase chunk count. */
            final int beforeCount = (shape.zSize + beforePlanes - 1) / beforePlanes;
            final int afterCount = (shape.zSize + afterPlanes - 1) / afterPlanes;
            Assertions.assertEquals(beforeCount, afterCount);
        } else {
            /* Plane count must be no greater than required by chunk size. */
            final long smallerChunkSize = (long) afterChunkSize * (shape.zTile - 1) / shape.zTile;
            Assertions.assertTrue(smallerChunkSize < targetChunkSize);
        }
    }

    /**
     * @return a set of various sizes for tile size adjustment to work on
     */
    private static Stream<Arguments> provideDataShapes() {
        final Stream.Builder<Arguments> arguments = Stream.builder();
        final boolean[] booleans = new boolean[] {false, true};
        for (final boolean isAdjustX : booleans) {
            for (final boolean isAdjustY : booleans) {
                for (final boolean isAdjustZ : booleans) {
                    final ImmutableList.Builder<Function<DataShape, Boolean>> adjusters = ImmutableList.builder();
                    if (isAdjustX) {
                        adjusters.add(ADJUSTER_X);
                    }
                    if (isAdjustY) {
                        adjusters.add(ADJUSTER_Y);
                    }
                    if (isAdjustZ) {
                        adjusters.add(ADJUSTER_Z);
                    }
                    final int[] deciFactorsPlane, deciFactorsTile;
                    if (isAdjustX && isAdjustY && isAdjustZ) {
                        deciFactorsPlane = new int[] {1, 3, 7, 10, 15, 25, 45};
                        deciFactorsTile = new int[] {1, 3, 7, 10, 15};
                    } else {
                        deciFactorsPlane = new int[] {3, 10, 25};
                        deciFactorsTile = new int[] {1, 7, 15};
                    }
                    for (int bytes : new int[] {1, 2, 4}) {
                        for (final int chunkSide : new int[] {1000, 1024}) {
                            for (int xDeciFactor : deciFactorsPlane) {
                                final int x = chunkSide * xDeciFactor / 10;
                                for (int yDeciFactor : deciFactorsPlane) {
                                    final int y = chunkSide * yDeciFactor / 10;
                                    for (int wDeciFactor : deciFactorsTile) {
                                        final int w = chunkSide * wDeciFactor / 10;
                                        for (int hDeciFactor : deciFactorsTile) {
                                            final int h = chunkSide * hDeciFactor / 10;
                                            final DataShape shape = getDataShape(x, y, bytes);
                                            shape.xTile = w;
                                            shape.yTile = h;
                                            arguments.add(Arguments.of(shape, chunkSide * chunkSide, adjusters.build()));
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return arguments.build();
    }
}
