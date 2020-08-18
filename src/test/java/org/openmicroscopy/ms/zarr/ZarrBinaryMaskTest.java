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

import org.openmicroscopy.ms.zarr.mask.ImageMask;

import ome.model.core.Image;
import ome.model.core.Pixels;
import ome.model.roi.Mask;
import ome.model.roi.Roi;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.zip.DataFormatException;

import com.google.common.collect.ImmutableSortedSet;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import org.mockito.Mockito;

/**
 * Check that the binary data served from the microservice endpoints has the expected mask values.
 * @author m.t.b.carroll@dundee.ac.uk
 * @since v0.1.7
 */
public class ZarrBinaryMaskTest extends ZarrEndpointsTestBase {

    private Image image;
    private Roi roi1, roi2;
    private Mask mask1, mask2, mask3;
    private BiPredicate<Integer, Integer> isMasked1, isMasked2, isMasked3;

    /**
     * Create an test image with masks.
     */
    @BeforeAll
    private void maskSetup() {
        pixelBuffer.setResolutionLevel(pixelBuffer.getResolutionLevels() - 1);
        final int size = (int) Math.sqrt(pixelBuffer.getSizeX() * pixelBuffer.getSizeY() >> 3) & -4;
        final byte[] bitmask1 = new byte[size * size];
        final byte[] bitmask2 = new byte[size * size >> 2];
        final byte[] bitmask3 = new byte[size * size >> 2];
        int factor = 3;
        for (final byte[] bitmask : new byte[][] {bitmask1, bitmask2, bitmask3}) {
            for (int index = 0; index < bitmask.length; index += factor) {
                bitmask[index] = -1;
            }
            factor += 2;
        }
        long id = 0;
        mask1 = new Mask(++id, true);
        mask1.setX(0.0);
        mask1.setY(0.0);
        mask1.setWidth((double) (size << 1));
        mask1.setHeight((double) (size << 2));
        mask1.setBytes(bitmask1);
        mask2 = new Mask(++id, true);
        mask2.setX(0.0);
        mask2.setY(0.0);
        mask2.setWidth((double) (size << 1));
        mask2.setHeight((double) (size));
        mask2.setBytes(bitmask2);
        mask3 = new Mask(++id, true);
        mask3.setX(mask1.getWidth() / 2);
        mask3.setY(mask1.getHeight() / 2);
        mask3.setWidth((double) (size));
        mask3.setHeight((double) (size << 1));
        mask3.setBytes(bitmask3);
        roi1 = new Roi(++id, true);
        roi2 = new Roi(++id, true);
        image = new Image(++id, true);
        roi1.addShape(mask1);
        roi1.addShape(mask2);
        roi2.addShape(mask3);
        image.addRoiSet(Arrays.asList(roi1, roi2));
        isMasked1 = new ImageMask(mask1).getMaskReader(1, 1, 1);
        isMasked2 = new ImageMask(mask2).getMaskReader(1, 1, 1);
        isMasked3 = new ImageMask(mask3).getMaskReader(1, 1, 1);
    }

    @Override
    protected OmeroDao daoSetup() {
        final Pixels pixels = constructMockPixels();
        final OmeroDao dao = Mockito.mock(OmeroDao.class);
        Mockito.when(dao.getPixels(Mockito.eq(image.getId()))).thenReturn(pixels);
        Mockito.when(dao.getMask(Mockito.eq(mask1.getId()))).thenReturn(mask1);
        Mockito.when(dao.getMask(Mockito.eq(mask2.getId()))).thenReturn(mask2);
        Mockito.when(dao.getMask(Mockito.eq(mask3.getId()))).thenReturn(mask3);
        Mockito.when(dao.getRoi(Mockito.eq(roi1.getId()))).thenReturn(roi1);
        Mockito.when(dao.getRoi(Mockito.eq(roi2.getId()))).thenReturn(roi2);
        Mockito.when(dao.getMaskCountOfRoi(Mockito.eq(roi1.getId()))).thenReturn((long) roi1.sizeOfShapes());
        Mockito.when(dao.getMaskCountOfRoi(Mockito.eq(roi2.getId()))).thenReturn((long) roi2.sizeOfShapes());
        Mockito.when(dao.getRoiCountOfImage(Mockito.eq(image.getId()))).thenReturn((long) image.sizeOfRois());
        Mockito.when(dao.getMaskIdsOfRoi(Mockito.eq(roi1.getId()))).thenReturn(ImmutableSortedSet.of(mask1.getId(), mask2.getId()));
        Mockito.when(dao.getMaskIdsOfRoi(Mockito.eq(roi2.getId()))).thenReturn(ImmutableSortedSet.of(mask3.getId()));
        Mockito.when(dao.getRoiIdsOfImage(Mockito.eq(image.getId()))).thenReturn(ImmutableSortedSet.of(roi1.getId(), roi2.getId()));
        Mockito.when(dao.getRoiIdsWithMaskOfImage(Mockito.eq(image.getId())))
        .thenReturn(ImmutableSortedSet.of(roi1.getId(), roi2.getId()));
        return dao;
    }

    /**
     * Check that the chunks for a split mask from the microservice are as expected.
     * @throws DataFormatException unexpected
     * @throws IOException unexpected
     */
    @Test
    public void testMaskChunks() throws DataFormatException, IOException {
        final Set<Byte> seenIsMasked = new HashSet<>();
        final JsonObject response = getResponseAsJson(image.getId(), "masks", roi1.getId(), ".zarray");
        final JsonArray shape = response.getJsonArray("shape");
        final int maskSizeX = shape.getInteger(4);
        final int maskSizeY = shape.getInteger(3);
        Assertions.assertEquals(pixelBuffer.getSizeX(), maskSizeX);
        Assertions.assertEquals(pixelBuffer.getSizeY(), maskSizeY);
        final JsonArray chunks = response.getJsonArray("chunks");
        final int chunkSizeX = chunks.getInteger(4);
        final int chunkSizeY = chunks.getInteger(3);
        int chunkIndexY = 0;
        for (int y = 0; y < maskSizeY; y += chunkSizeY) {
            int chunkIndexX = 0;
            for (int x = 0; x < maskSizeX; x += chunkSizeX) {
                mockSetup();
                final byte[] chunkZipped =
                        getResponseAsBytes(image.getId(), "masks", roi1.getId(), 0, 0, 0, chunkIndexY, chunkIndexX);
                final byte[] chunk = uncompress(chunkZipped);
                for (int cx = 0; cx < chunkSizeX; cx++) {
                    for (int cy = 0; cy < chunkSizeY; cy++) {
                        final boolean isMasked = isMasked1.test(x + cx, y + cy) || isMasked2.test(x + cx, y + cy);
                        final int index = chunkSizeX * cy + cx;
                        if (isMasked) {
                            Assertions.assertNotEquals(0, chunk[index]);
                        } else {
                            Assertions.assertEquals(0, chunk[index]);
                        }
                        seenIsMasked.add(chunk[index]);
                    }
                }
                chunkIndexX++;
            }
            chunkIndexY++;
        }
        Assertions.assertEquals(2, seenIsMasked.size());
    }

    /**
     * Check that the chunks for a labeled mask from the microservice are as expected.
     * @throws DataFormatException unexpected
     * @throws IOException unexpected
     */
    @Test
    public void testLabeledMaskChunks() throws DataFormatException, IOException {
        final Set<Long> seenLabels = new HashSet<>();
        final JsonObject response = getResponseAsJson(image.getId(), "masks", "labeled", ".zarray");
        final JsonArray shape = response.getJsonArray("shape");
        final int maskSizeX = shape.getInteger(4);
        final int maskSizeY = shape.getInteger(3);
        Assertions.assertEquals(pixelBuffer.getSizeX(), maskSizeX);
        Assertions.assertEquals(pixelBuffer.getSizeY(), maskSizeY);
        final JsonArray chunks = response.getJsonArray("chunks");
        final int chunkSizeX = chunks.getInteger(4);
        final int chunkSizeY = chunks.getInteger(3);
        int chunkIndexY = 0;
        for (int y = 0; y < maskSizeY; y += chunkSizeY) {
            int chunkIndexX = 0;
            for (int x = 0; x < maskSizeX; x += chunkSizeX) {
                mockSetup();
                final byte[] chunkZipped = getResponseAsBytes(image.getId(), "masks", "labeled", 0, 0, 0, chunkIndexY, chunkIndexX);
                final ByteBuffer chunk = ByteBuffer.wrap(uncompress(chunkZipped));
                for (int cx = 0; cx < chunkSizeX; cx++) {
                    for (int cy = 0; cy < chunkSizeY; cy++) {
                        final boolean isRoi1 = isMasked1.test(x + cx, y + cy) || isMasked2.test(x + cx, y + cy);
                        final boolean isRoi2 = isMasked3.test(x + cx, y + cy);
                        final long expectedLabel;
                        if (isRoi1) {
                            expectedLabel = isRoi2 ? ZarrEndpointsTestBase.MASK_OVERLAP_VALUE : roi1.getId();
                        } else {
                            expectedLabel = isRoi2 ? roi2.getId() : 0;
                        }
                        final int index = Long.BYTES * (chunkSizeX * cy + cx);
                        final long actualLabel = chunk.getLong(index);
                        Assertions.assertEquals(expectedLabel, actualLabel);
                        seenLabels.add(actualLabel);
                    }
                }
                chunkIndexX++;
            }
            chunkIndexY++;
        }
        Assertions.assertEquals(4, seenLabels.size());
    }
}
