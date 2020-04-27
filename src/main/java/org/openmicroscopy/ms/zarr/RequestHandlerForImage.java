/*
 * Copyright (C) 2018-2020 University of Dundee & Open Microscopy Environment.
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
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Deflater;

import org.hibernate.Query;
import org.hibernate.Session;
import org.hibernate.SessionFactory;

import com.google.common.collect.ImmutableList;

import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

import ome.io.nio.PixelBuffer;
import ome.io.nio.PixelsService;
import ome.model.core.Pixels;
import ome.util.PixelData;

/**
 * Provide OMERO image as Zarr via HTTP endpoint.
 * @author m.t.b.carroll@dundee.ac.uk
 */
public class RequestHandlerForImage implements Handler<RoutingContext> {

    /**
     * Cache open {@link PixelBuffer} instances given the likelihood of repeated calls.
     * @author m.t.b.carroll@dundee.ac.uk
     */
    private class PixelBufferCache {

        /* How many pixel buffers to keep open. Different resolutions count as different buffers. */
        private static final int CAPACITY = 16;

        /* An open pixel buffer set to a specific image and resolution. */
        private class Entry {

            final long imageId;
            final int resolution;
            final PixelBuffer buffer;

            Entry(long imageId, int resolution, PixelBuffer buffer) {
                this.imageId = imageId;
                this.resolution = resolution;
                this.buffer = buffer;
            }
        }

        private Deque<Entry> cache = new LinkedList<>();
        private Map<PixelBuffer, Integer> references = new HashMap<>();

        /**
         * Get a pixel buffer from the cache, opening it if necessary.
         * Call {@link #releasePixelBuffer(PixelBuffer)} on the return value exactly once when done,
         * do not call {@link PixelBuffer#close()} directly.
         * May cause expiry of another buffer from the cache.
         * @param imageId an image ID
         * @param resolution a resolution of that image
         * @return an open pixel buffer
         */
        synchronized PixelBuffer getPixelBuffer(long imageId, int resolution) {
            for (final Entry entry : cache) {
                if (entry.imageId == imageId && entry.resolution == resolution) {
                    /* a cache hit */
                    cache.remove(entry);
                    cache.addFirst(entry);
                    final int count = references.get(entry.buffer);
                    references.put(entry.buffer, count + 1);
                    return entry.buffer;
                }
            }
            /* a cache miss */
            if (cache.size() == CAPACITY) {
                /* make room for the new cache entry */
                final Entry entry = cache.removeLast();
                releasePixelBuffer(entry.buffer);
            }
            /* open the buffer at the desired resolution */
            final Pixels pixels = getPixels(imageId);
            if (pixels == null) {
                return null;
            }
            final PixelBuffer buffer = pixelsService.getPixelBuffer(pixels, false);
            final int resolutions = buffer.getResolutionLevels();
            if (resolution >= resolutions) {
                try {
                    buffer.close();
                } catch (IOException ioe) {
                    /* probably closed anyway */
                }
                return null;
            }
            buffer.setResolutionLevel(resolutions - resolution - 1);
            /* enter the buffer into the cache */
            final Entry entry = new Entry(imageId, resolution, buffer);
            cache.addFirst(entry);
            final int count = references.getOrDefault(entry.buffer, 0);
            references.put(entry.buffer, count + 2);
            return entry.buffer;
        }

        /**
         * Notify the cache that the buffer previously obtained from {@link #getPixelBuffer(long, int)} is no longer in use.
         * @param buffer the buffer that can now be closed
         */
        synchronized void releasePixelBuffer(PixelBuffer buffer) {
            final int count = references.get(buffer);
            if (count == 1) {
                try {
                    references.remove(buffer);
                    buffer.close();
                } catch (IOException ioe) {
                    /* probably closed anyway */
                }
            } else {
                references.put(buffer, count - 1);
            }
        }
    }

    /**
     * Contains the dimensionality of an image.
     * @author m.t.b.carroll@dundee.ac.uk
     */
    private static class DataShape {
        final int xSize, ySize, cSize, zSize, tSize;
        final int byteWidth;

        int xTile, yTile;

        /**
         * @param buffer the pixel buffer from which to extract the dimensionality
         */
        DataShape(PixelBuffer buffer) {
            xSize = buffer.getSizeX();
            ySize = buffer.getSizeY();
            cSize = buffer.getSizeC();
            zSize = buffer.getSizeZ();
            tSize = buffer.getSizeT();

            final Dimension tileSize = buffer.getTileSize();
            if (tileSize == null) {
                xTile = xSize;
                yTile = ySize;
            } else {
                xTile = tileSize.width;
                yTile = tileSize.height;
            }

            byteWidth = buffer.getByteWidth();
        }

        /**
         * Attempt to increase the <em>X</em>, <em>Y</em> tile size so the tile occupies at least the given number of bytes.
         * @param target the minimum target size, in bytes
         * @return this, for method chaining
         */
        DataShape adjustTileSize(int target) {
            final int pixelTarget = target / byteWidth;
            while ((long) xTile * yTile < pixelTarget) {
                final boolean isIncreaseX = xTile < xSize;
                final boolean isIncreaseY = yTile < ySize;
                if (isIncreaseX && xTile * 3 >= xSize) {
                    xTile = xSize;
                } else if (isIncreaseY && yTile * 3 >= ySize) {
                    yTile = ySize;
                } else if (isIncreaseX) {
                    xTile <<= 1;
                } else if (isIncreaseY) {
                    yTile <<= 1;
                } else {
                    break;
                }
            }
            return this;
        }
    }

    private static final int CHUNK_SIZE_MINIMUM = 0x100000;
    private static final int DEFLATER_LEVEL = 6;

    private static final String REGEX_GROUP = "/(\\d+)/\\.zgroup";
    private static final String REGEX_ATTRS = "/(\\d+)/\\.zattrs";
    private static final String REGEX_ARRAY = "/(\\d+)/(\\d+)/\\.zarray";
    private static final String REGEX_CHUNK = "/(\\d+)/(\\d+)/(\\d+([/.]\\d+)*)";

    private static final Pattern PATTERN_GROUP = Pattern.compile(REGEX_GROUP);
    private static final Pattern PATTERN_ATTRS = Pattern.compile(REGEX_ATTRS);
    private static final Pattern PATTERN_ARRAY = Pattern.compile(REGEX_ARRAY);
    private static final Pattern PATTERN_CHUNK = Pattern.compile(REGEX_CHUNK);

    private final PixelBufferCache cache = new PixelBufferCache();

    private final PixelsService pixelsService;
    private final SessionFactory sessionFactory;
    private final String prefix;

    /**
     * Create the HTTP request handler.
     * @param sessionFactory the Hibernate session factory
     * @param pixelsService the OMERO pixels service
     * @param uriPathPrefix the path prefix for the URIs to handle
     */
    public RequestHandlerForImage(SessionFactory sessionFactory, PixelsService pixelsService, String uriPathPrefix) {
        this.sessionFactory = sessionFactory;
        this.pixelsService = pixelsService;
        this.prefix = uriPathPrefix;
    }

    /**
     * Add this instance as GET handler for the API paths.
     * @param router the router for which this can handle requests
     */
    public void handleFor(Router router) {
        router.getWithRegex(prefix + REGEX_GROUP).handler(this);
        router.getWithRegex(prefix + REGEX_ATTRS).handler(this);
        router.getWithRegex(prefix + REGEX_ARRAY).handler(this);
        router.getWithRegex(prefix + REGEX_CHUNK).handler(this);
    }

    /**
     * Construct a HTTP failure response.
     * @param response the HTTP response that is to bear the failure
     * @param code the HTTP response code
     * @param message a message that describes the failure
     */
    private static void fail(HttpServerResponse response, int code, String message) {
        response.setStatusCode(code);
        response.setStatusMessage(message);
        response.end();
    }

    /**
     * Handle incoming requests that query OMERO images.
     * @param context the routing context
     */
    @Override
    public void handle(RoutingContext context) {
        final HttpServerRequest request = context.request();
        final HttpServerResponse response = request.response();
        if (request.method() == HttpMethod.GET) {
            final String requestPath = request.path().substring(prefix.length());
            try {
                Matcher matcher;
                matcher = PATTERN_GROUP.matcher(requestPath);
                if (matcher.matches()) {
                    final long imageId = Long.parseLong(matcher.group(1));
                    returnGroup(response, imageId);
                }
                matcher = PATTERN_ATTRS.matcher(requestPath);
                if (matcher.matches()) {
                    final long imageId = Long.parseLong(matcher.group(1));
                    returnAttrs(response, imageId);
                }
                matcher = PATTERN_ARRAY.matcher(requestPath);
                if (matcher.matches()) {
                    final long imageId = Long.parseLong(matcher.group(1));
                    final int resolutionId = Integer.parseInt(matcher.group(2));
                    returnArray(response, imageId, resolutionId);
                }
                matcher = PATTERN_CHUNK.matcher(requestPath);
                if (matcher.matches()) {
                    final long imageId = Long.parseLong(matcher.group(1));
                    final int resolution = Integer.parseInt(matcher.group(2));
                    final ImmutableList.Builder<Integer> chunkId = ImmutableList.builder();
                    for (final String integerText : matcher.group(3).split("[/.]")) {
                        chunkId.add(Integer.parseInt(integerText));
                    }
                    returnChunk(response, imageId, resolution, chunkId.build());
                }
            } catch (NumberFormatException nfe) {
                fail(response, 400, "failed to parse integers");
                return;
            }
        } else {
            fail(response, 404, "unknown path for this method");
            return;
        }
    }

    /**
     * Get a pixels instance that can be used with {@link PixelsService#getPixelBuffer(Pixels, boolean)}.
     * @param imageId the ID of an image
     * @return that image's pixels instance, or {@code null} if one could not be found
     */
    private Pixels getPixels(long imageId) {
        Session session = null;
        try {
            session = sessionFactory.openSession();
            session.setDefaultReadOnly(true);
            final Query query = session.createQuery(
                    "SELECT p FROM Pixels AS p " +
                    "JOIN FETCH p.pixelsType " +
                    "LEFT OUTER JOIN FETCH p.channels AS c " +
                    "LEFT OUTER JOIN FETCH c.logicalChannel AS lc " +
                    "LEFT OUTER JOIN FETCH c.statsInfo " +
                    "WHERE p.image.id = ?");
            query.setParameter(0, imageId);
            return (Pixels) query.uniqueResult();
        } finally {
            if (session != null) {
                session.close();
            }
        }
    }

    /**
     * Set the given JSON data as the given HTTP response.
     * @param response a HTTP response
     * @param data some JSON data
     */
    private static void respondWithJson(HttpServerResponse response, JsonObject data) {
        final String responseText = data.toString();
        response.putHeader("Content-Type", "application/json; charset=utf-8");
        response.putHeader("Content-Length", Integer.toString(responseText.length()));
        response.end(responseText);
    }

    /**
     * Handle a request for {@code .zgroup}.
     * @param response the HTTP server response to populate
     * @param imageId the ID of the image being queried
     */
    private void returnGroup(HttpServerResponse response, long imageId) {
        final Pixels pixels = getPixels(imageId);
        if (pixels == null) {
            fail(response, 404, "no image for that id");
            return;
        }
        final Map<String, Object> result = new HashMap<>();
        result.put("zarr_format", 2);
        respondWithJson(response, new JsonObject(result));
    }

    /**
     * Handle a request for {@code .zattrs}.
     * @param response the HTTP server response to populate
     * @param imageId the ID of the image being queried
     */
    private void returnAttrs(HttpServerResponse response, long imageId) {
        final Pixels pixels = getPixels(imageId);
        if (pixels == null) {
            fail(response, 404, "no image for that id and resolution");
            return;
        }
        /* gather data from pixels service */
        final List<Double> scales;
        PixelBuffer buffer = null;
        try {
            buffer = pixelsService.getPixelBuffer(pixels, false);
            final List<List<Integer>> resolutions = buffer.getResolutionDescriptions();
            double xMax = 0, yMax = 0;
            for (final List<Integer> resolution : resolutions) {
                final int x = resolution.get(0);
                final int y = resolution.get(1);
                if (xMax < x) {
                    xMax = x;
                }
                if (yMax < y) {
                    yMax = y;
                }
            }
            scales = new ArrayList<>(resolutions.size());
            for (final List<Integer> resolution : resolutions) {
                final int x = resolution.get(0);
                final int y = resolution.get(1);
                final double xScale = x / xMax;
                final double yScale = y / yMax;
                scales.add(xScale == yScale ? xScale : null);
            }
        } catch (Exception e) {
            fail(response, 500, "query failed");
            return;
        } finally {
            if (buffer != null) {
                try {
                    buffer.close();
                } catch (IOException ioe) {
                    /* probably already failing anyway */
                }
            }
        }
        /* package data for client */
        final List<Map<String, Object>> datasets = new ArrayList<>();
        int resolution = 0;
        for (final Double scale : scales) {
            final Map<String, Object> dataset = new HashMap<>();
            dataset.put("path", Integer.toString(resolution++));
            if (scale != null) {
                dataset.put("scale", scale);
            }
            datasets.add(dataset);
        }
        final Map<String, Object> multiscales = new HashMap<>();
        multiscales.put("version", "0.1");
        multiscales.put("datasets", datasets);
        final Map<String, Object> result = new HashMap<>();
        result.put("multiscales", multiscales);
        respondWithJson(response, new JsonObject(result));
    }

    /**
     * Handle a request for {@code .zarray}.
     * @param response the HTTP server response to populate
     * @param imageId the ID of the image being queried
     * @param resolution the resolution to query
     */
    private void returnArray(HttpServerResponse response, long imageId, int resolution) {
        /* gather data from pixels service */
        final DataShape shape;
        final boolean isLittleEndian, isSigned, isFloat;
        PixelBuffer buffer = null;
        try {
            buffer = cache.getPixelBuffer(imageId, resolution);
            if (buffer == null) {
                fail(response, 404, "no image for that id and resolution");
                return;
            }
            shape = new DataShape(buffer).adjustTileSize(CHUNK_SIZE_MINIMUM);
            final int xd = Math.min(shape.xSize, shape.xTile);
            final int yd = Math.min(shape.ySize, shape.yTile);
            final PixelData tile = buffer.getTile(0, 0, 0, 0, 0, xd, yd);
            isLittleEndian = tile.getOrder() == ByteOrder.LITTLE_ENDIAN;
            tile.dispose();
            isSigned = buffer.isSigned();
            isFloat = buffer.isFloat();
        } catch (Exception e) {
            fail(response, 500, "query failed");
            return;
        } finally {
            if (buffer != null) {
                cache.releasePixelBuffer(buffer);
            }
        }
        /* package data for client */
        final Map<String, Object> compressor = new HashMap<>();
        compressor.put("id", "zlib");
        compressor.put("level", DEFLATER_LEVEL);
        final Map<String, Object> result = new HashMap<>();
        result.put("zarr_format", 2);
        result.put("order", "C");
        result.put("shape", ImmutableList.of(shape.tSize, shape.cSize, shape.zSize, shape.ySize, shape.xSize));
        result.put("chunks", ImmutableList.of(1, 1, 1, shape.yTile, shape.xTile));
        result.put("fill_value", 0);
        final char byteOrder = shape.byteWidth == 1 ? '|' : isLittleEndian ? '<' : '>';
        final char byteType = isFloat ? 'f' : isSigned ? 'i' : 'u';
        result.put("dtype", "" + byteOrder + byteType + shape.byteWidth);
        result.put("filters", null);
        result.put("compressor", compressor);
        respondWithJson(response, new JsonObject(result));
    }

    /**
     * Handle a request for a chunk of the pixel data.
     * @param response the HTTP server response to populate
     * @param imageId the ID of the image being queried
     * @param resolution the resolution to query
     * @param chunkId the chunk to query, as <em>T</em>, <em>C</em>, <em>Z</em>, <em>Y</em>, <em>X</em>
     */
    private void returnChunk(HttpServerResponse response, long imageId, int resolution, List<Integer> chunkId) {
        if (chunkId.size() != 5) {
            fail(response, 404, "no chunk with that index");
            return;
        }
        /* gather data from pixels service */
        final byte[] chunk;
        PixelBuffer buffer = null;
        try {
            buffer = cache.getPixelBuffer(imageId, resolution);
            if (buffer == null) {
                fail(response, 404, "no image for that id");
                return;
            }
            final DataShape shape = new DataShape(buffer).adjustTileSize(CHUNK_SIZE_MINIMUM);
            final int x = shape.xTile * chunkId.get(4);
            final int y = shape.yTile * chunkId.get(3);
            final int z = chunkId.get(2);
            final int c = chunkId.get(1);
            final int t = chunkId.get(0);
            if (x >= shape.xSize || y >= shape.ySize || c >= shape.cSize || z >= shape.zSize || t >= shape.tSize) {
                fail(response, 404, "no chunk with that index");
                return;
            }
            final PixelData tile;
            if (x + shape.xTile > shape.xSize) {
                /* a tile that crosses the right side of the image */
                final int xd = shape.xSize - x;
                final int yd;
                if (y + shape.yTile > shape.ySize) {
                    /* also crosses the bottom side */
                    yd = shape.ySize - y;
                } else {
                    /* does not cross the bottom side */
                    yd = shape.yTile;
                }
                tile = buffer.getTile(z, c, t, x, y, xd, yd);
                final byte[] chunkSrc = tile.getData().array();
                chunk = new byte[shape.xTile * shape.yTile * shape.byteWidth];
                /* must now assemble row-by-row into a full-sized chunk */
                for (int row = 0; row < yd; row++) {
                    final int srcIndex = row * xd * shape.byteWidth;
                    final int dstIndex = row * shape.xTile * shape.byteWidth;
                    System.arraycopy(chunkSrc, srcIndex, chunk, dstIndex, xd * shape.byteWidth);
                }
            } else if (y + shape.yTile > shape.ySize) {
                /* a tile that crosses the bottom of the image */
                final int yd = shape.ySize - y;
                tile = buffer.getTile(z, c, t, x, y, shape.xTile, yd);
                final byte[] chunkSrc = tile.getData().array();
                chunk = new byte[shape.xTile * shape.yTile * shape.byteWidth];
                /* simply copy into the first part of the chunk */
                System.arraycopy(chunkSrc, 0, chunk, 0, chunkSrc.length);
            } else {
                /* the tile fills a chunk */
                tile = buffer.getTile(z, c, t, x, y, shape.xTile, shape.yTile);
                chunk = tile.getData().array();
            }
            tile.dispose();
        } catch (Exception e) {
            fail(response, 500, "query failed");
            return;
        } finally {
            if (buffer != null) {
                cache.releasePixelBuffer(buffer);
            }
        }
        /* package data for client */
        final Buffer chunkZipped = compress(chunk);
        response.putHeader("Content-Type", "application/octet-stream");
        response.putHeader("Content-Length", Integer.toString(chunkZipped.length()));
        response.end(chunkZipped);
    }

    /**
     * Compress the given byte array.
     * @param uncompressed a byte array
     * @return the compressed bytes
     */
    private static Buffer compress(byte[] uncompressed) {
        final Deflater deflater = new Deflater(DEFLATER_LEVEL);
        deflater.setInput(uncompressed);
        deflater.finish();
        final Buffer compressed = Buffer.factory.buffer(uncompressed.length);
        final byte[] batch = new byte[8192];
        int batchSize;
        do {
            batchSize = deflater.deflate(batch);
            compressed.appendBytes(batch, 0, batchSize);
        } while (batchSize > 0);
        batchSize = deflater.deflate(batch, 0, batch.length, Deflater.SYNC_FLUSH);
        compressed.appendBytes(batch, 0, batchSize);
        deflater.end();
        return compressed;
    }
}
