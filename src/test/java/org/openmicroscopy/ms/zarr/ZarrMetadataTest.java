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

import java.awt.Dimension;
import java.io.IOException;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Check that the metadata served from the microservice endpoints are of the expected form.
 * @author m.t.b.carroll@dundee.ac.uk
 */
public class ZarrMetadataTest extends ZarrEndpointsTestBase {

    private final List<Dimension> resolutionSizes = new ArrayList<>();

    /**
     * Check that there are no unexpected keys among the JSON.
     * @param json a JSON object
     * @param keys the only permitted keys for the object, fails test if violated
     */
    private static void assertNoExtraKeys(JsonObject json, String... keys) {
        final Set<String> actualKeys = json.fieldNames();
        final Set<String> permittedKeys = ImmutableSet.copyOf(keys);
        Assertions.assertTrue(Sets.difference(actualKeys, permittedKeys).isEmpty());
    }

    /**
     * Check that the {@code .zgroup} from the microservice is as expected.
     */
    @Test
    public void testZarrGroup() {
        final JsonObject response = getResponseAsJson(0, ".zgroup");
        assertNoExtraKeys(response, "zarr_format");
        Assertions.assertEquals(2, response.getInteger("zarr_format"));
    }

    /**
     * Check that the {@code .zattrs} from the microservice is as expected.
     */
    @Test
    public void testZarrAttrs() {
        final JsonObject response = getResponseAsJson(0, ".zattrs");
        assertNoExtraKeys(response, "multiscales", "omero");
        final JsonArray multiscales = response.getJsonArray("multiscales");
        Assertions.assertEquals(1, multiscales.size());
        final JsonObject multiscale = multiscales.getJsonObject(0);
        assertNoExtraKeys(multiscale, "datasets", "version");
        final JsonArray datasets = multiscale.getJsonArray("datasets");
        Assertions.assertEquals(pixelBuffer.getResolutionLevels(), datasets.size());
        boolean isScale1 = true;
        final Set<String> paths = new HashSet<>();
        for (final Object element : datasets) {
            final JsonObject dataset = (JsonObject) element;
            assertNoExtraKeys(dataset, "path", "scale");
            final String path = dataset.getString("path");
            Assertions.assertNotNull(path);
            @SuppressWarnings("unused")
            final Double scale = dataset.getDouble("scale");
            if (isScale1) {
                /* The first group listed is for the full-size image. */
                /* Assertions.assertEquals(1, scale);
                 * scale is omitted while it is being rethought */
                isScale1 = false;
            }
            paths.add(path);
        }
        Assertions.assertEquals(pixelBuffer.getResolutionLevels(), paths.size());
        Assertions.assertEquals("0.1", multiscale.getString("version"));
    }

    /**
     * Prepare to note the <em>X</em>-<em>Y</em> size of each resolution.
     */
    @BeforeAll
    public void clearResolutionSizes() {
        resolutionSizes.clear();
    }

    /**
     * Check that the {@code .zarray}s from the microservice are as expected.
     * @param resolution the pixel buffer resolution corresponding to the currently tested group
     * @param path the URI path component that selects the currently tested group
     * @param scale any scale listed among the {@code .zattrs} for the the currently tested group, may be {@code null}
     * @throws IOException unexpected
     */
    @ParameterizedTest
    @MethodSource("provideGroupDetails")
    public void testZarrArray(int resolution, String path, Double scale) throws IOException {
        final JsonObject response = getResponseAsJson(0, path, ".zarray");
        assertNoExtraKeys(response, "chunks", "compressor", "dtype", "fill_value", "filters", "order", "shape", "zarr_format");
        Assertions.assertEquals(2, response.getInteger("zarr_format"));
        Assertions.assertTrue(response.containsKey("fill_value"));
        final JsonArray shape = response.getJsonArray("shape");
        final JsonArray chunks = response.getJsonArray("chunks");
        Assertions.assertEquals(5, shape.size());
        Assertions.assertEquals(5, chunks.size());
        final int shapeX = shape.getInteger(4);
        final int shapeY = shape.getInteger(3);
        final int shapeZ = shape.getInteger(2);
        final int shapeC = shape.getInteger(1);
        final int shapeT = shape.getInteger(0);
        final int chunkX = chunks.getInteger(4);
        final int chunkY = chunks.getInteger(3);
        final int chunkZ = chunks.getInteger(2);
        final int chunkC = chunks.getInteger(1);
        final int chunkT = chunks.getInteger(0);
        resolutionSizes.add(new Dimension(shapeX, shapeY));
        if (scale != null) {
            pixelBuffer.setResolutionLevel(pixelBuffer.getResolutionLevels() - 1);
            final long expectShapeX = Math.round(pixelBuffer.getSizeX() * scale);
            final long expectShapeY = Math.round(pixelBuffer.getSizeY() * scale);
            Assertions.assertEquals(expectShapeX, shapeX);
            Assertions.assertEquals(expectShapeY, shapeY);
        }
        pixelBuffer.setResolutionLevel(resolution);
        Assertions.assertEquals(pixelBuffer.getSizeX(), shapeX);
        Assertions.assertEquals(pixelBuffer.getSizeY(), shapeY);
        Assertions.assertEquals(pixelBuffer.getSizeZ(), shapeZ);
        Assertions.assertEquals(pixelBuffer.getSizeC(), shapeC);
        Assertions.assertEquals(pixelBuffer.getSizeT(), shapeT);
        Assertions.assertEquals(1, chunkZ);
        Assertions.assertEquals(1, chunkC);
        Assertions.assertEquals(1, chunkT);
        final Dimension tileSize = pixelBuffer.getTileSize();
        if (chunkX != shapeX) {
            Assertions.assertEquals(0, chunkX % tileSize.width);
        }
        if (chunkY != shapeY) {
            Assertions.assertEquals(0, chunkY % tileSize.height);
        }
        Assertions.assertEquals(ByteOrder.LITTLE_ENDIAN, pixelBuffer.getTile(0, 0, 0, 0, 0, 1, 1).getOrder());
        Assertions.assertEquals(false, pixelBuffer.isFloat());
        Assertions.assertEquals(false, pixelBuffer.isSigned());
        Assertions.assertEquals(2, pixelBuffer.getByteWidth());
        Assertions.assertEquals("<u2", response.getString("dtype"));
        Assertions.assertNotEquals(-1, "CF".indexOf(response.getString("order")));
        Assertions.assertTrue(response.containsKey("compressor"));
        final JsonObject compressor = response.getJsonObject("compressor");
        if (compressor != null) {
            Assertions.assertNotNull(compressor.getString("id"));
        }
        Assertions.assertTrue(response.containsKey("filters"));
        final JsonArray filters = response.getJsonArray("filters");
        if (filters != null) {
            for (final Object element : filters) {
                final JsonObject filter = (JsonObject) element;
                Assertions.assertNotNull(filter.getString("id"));
            }
        }
    }

    /**
     * Check that the <em>X</em>-<em>Y</em> size of the resolutions decreases monotonically.
     */
    @AfterAll
    public void checkResolutionSizesDescend() {
        Assertions.assertEquals(pixelBuffer.getResolutionLevels(), resolutionSizes.size());
        Dimension previous = new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE);
        for (final Dimension current : resolutionSizes) {
            Assertions.assertFalse(current.width > previous.width);
            Assertions.assertFalse(current.height > previous.height);
            Assertions.assertTrue(current.width < previous.width || current.height < previous.height);
            previous = current;
        }
    }
}
