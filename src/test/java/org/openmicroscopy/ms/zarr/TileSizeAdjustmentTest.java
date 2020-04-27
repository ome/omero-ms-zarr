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
import java.util.stream.Stream;

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
     */
    @ParameterizedTest
    @MethodSource("provideDataShapes")
    public void testAdjustTileSize(DataShape shape, int targetChunkSize) {
        /* Determine the sizes, both before and after adjustment for comparison. */
        final int beforeWidth = shape.xTile;
        final int beforeHeight = shape.yTile;
        final int beforeChunkSize = beforeWidth * beforeHeight * shape.byteWidth;
        shape.adjustTileSize(targetChunkSize);
        final int afterWidth = shape.xTile;
        final int afterHeight = shape.yTile;
        final int afterChunkSize = afterWidth * afterHeight * shape.byteWidth;
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
        }
        /* If the adjusted tile size is not large enough then it should be at least the image size. */
        if (afterChunkSize < targetChunkSize) {
            if (beforeWidth > shape.xSize) {
                Assertions.assertEquals(beforeWidth, afterWidth);
            } else {
                Assertions.assertEquals(shape.xSize, afterWidth);
            }
            if (beforeHeight > shape.ySize) {
                Assertions.assertEquals(beforeHeight, afterHeight);
            } else {
                Assertions.assertEquals(shape.ySize, afterHeight);
            }
        }
        /* Tile size changes must be increases and either to a multiple of the previous or to the image size. */
        if (beforeWidth != afterWidth) {
            Assertions.assertTrue(beforeWidth < afterWidth);
            if (afterWidth != shape.xSize) {
                Assertions.assertEquals(0, afterWidth % beforeWidth);
            }
        }
        if (beforeHeight != afterHeight) {
            Assertions.assertTrue(beforeHeight < afterHeight);
            if (afterHeight != shape.ySize) {
                Assertions.assertEquals(0, afterHeight % beforeHeight);
            }
        }
    }

    /**
     * @return a set of various sizes for tile size adjustment to work on
     */
    private static Stream<Arguments> provideDataShapes() {
        final Stream.Builder<Arguments> arguments = Stream.builder();
        for (int bytes : new int[] {1, 2, 4}) {
            for (final int chunkSide : new int[] {1000, 1024}) {
                for (int xDeciFactor : new int[] {1, 3, 7, 10, 15, 25, 45}) {
                    final int x = chunkSide * xDeciFactor / 10;
                    for (int yDeciFactor : new int[] {1, 3, 7, 10, 15, 25, 45}) {
                        final int y = chunkSide * yDeciFactor / 10;
                        for (int wDeciFactor : new int[] {1, 3, 7, 10, 15}) {
                            final int w = chunkSide * wDeciFactor / 10;
                            for (int hDeciFactor : new int[] {1, 3, 7, 10, 15}) {
                                final int h = chunkSide * hDeciFactor / 10;
                                final DataShape shape = getDataShape(x, y, bytes);
                                shape.xTile = w;
                                shape.yTile = h;
                                arguments.add(Arguments.of(shape, chunkSide * chunkSide));
                            }
                        }
                    }
                }
            }
        }
        return arguments.build();
    }
}
