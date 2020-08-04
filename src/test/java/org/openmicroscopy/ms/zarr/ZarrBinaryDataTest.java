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

import java.io.IOException;
import java.util.zip.DataFormatException;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Check that the binary data served from the microservice endpoints has the expected pixel values.
 * @author m.t.b.carroll@dundee.ac.uk
 */
public class ZarrBinaryDataTest extends ZarrEndpointsTestBase {

    boolean isSomeChunkFitsWithin;
    boolean isSomeChunkOverlapsRight;
    boolean isSomeChunkOverlapsBottom;

    @BeforeAll
    private void clearChunkTypes() {
        isSomeChunkFitsWithin = false;
        isSomeChunkOverlapsRight = false;
        isSomeChunkOverlapsBottom = false;
    }

    @AfterAll
    private void checkChunkTypes() {
        Assertions.assertTrue(isSomeChunkFitsWithin);
        Assertions.assertTrue(isSomeChunkOverlapsRight);
        Assertions.assertTrue(isSomeChunkOverlapsBottom);
    }

    /**
     * Updates notes of test coverage regarding how chunks span the image.
     * @param sizeX the image width
     * @param sizeY the image height
     * @param x the horizontal start of the current chunk
     * @param y the vertical start of the current chunk
     * @param w the horizontal extent of the current chunk
     * @param h the vertical extent of the current chunk
     */
    private void assessChunkType(int sizeX, int sizeY, int x, int y, int w, int h) {
        final boolean isThisChunkOverlapsRight = x + w > sizeX;
        final boolean isThisChunkOverlapsBottom = y + h > sizeY;
        if (!(isThisChunkOverlapsRight || isThisChunkOverlapsBottom)) {
            isSomeChunkFitsWithin = true;
        } else {
            isSomeChunkOverlapsRight |= isThisChunkOverlapsRight;
            isSomeChunkOverlapsBottom |= isThisChunkOverlapsBottom;
        }
    }

    /**
     * Check that the chunks from the microservice are as expected. Check fill value beyond image borders.
     * @param resolution the pixel buffer resolution corresponding to the currently tested group
     * @param path the URI path component that selects the currently tested group
     * @param scale any scale listed among the {@code .zattrs} for the the currently tested group, may be {@code null}
     * @throws DataFormatException unexpected
     * @throws IOException unexpected
     */
    @ParameterizedTest
    @MethodSource("provideGroupDetails")
    public void testZarrChunks(int resolution, String path, Double scale) throws DataFormatException, IOException {
        final JsonObject response = getResponseAsJson(0, path, ".zarray");
        final JsonArray chunks = response.getJsonArray("chunks");
        final int chunkSizeX = chunks.getInteger(4);
        final int chunkSizeY = chunks.getInteger(3);
        pixelBuffer.setResolutionLevel(resolution);
        final int imageSizeX = pixelBuffer.getSizeX();
        final int imageSizeY = pixelBuffer.getSizeY();
        int chunkIndexY = 0;
        for (int y = 0; y < imageSizeY; y += chunkSizeY) {
            int chunkIndexX = 0;
            for (int x = 0; x < imageSizeX; x += chunkSizeX) {
                mockSetup();
                final byte[] chunkZipped = getResponseAsBytes(0, path, 0, 0, 0, chunkIndexY, chunkIndexX);
                final byte[] chunk = uncompress(chunkZipped);
                for (int cx = 0; cx < chunkSizeX; cx++) {
                    for (int cy = 0; cy < chunkSizeY; cy++) {
                        final int index = 2 * (chunkSizeX * cy + cx);
                        if (x + cx < imageSizeX && y + cy < imageSizeY) {
                            /* within image borders, see PixelBufferFake.getTile */
                            Assertions.assertEquals((byte) cx, chunk[index]);
                            Assertions.assertEquals((byte) cy, chunk[index + 1]);
                        } else {
                            /* .zarray specifies "fill_value": 0 */
                            Assertions.assertEquals(0, chunk[index]);
                            Assertions.assertEquals(0, chunk[index + 1]);
                        }
                    }
                }
                assessChunkType(imageSizeX, imageSizeY, x, y, chunkSizeX, chunkSizeY);
                chunkIndexX++;
            }
            chunkIndexY++;
        }
    }
}
