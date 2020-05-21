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
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Deflater;

import com.google.common.collect.ImmutableList;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

import ome.io.nio.PixelBuffer;
import ome.io.nio.PixelsService;
import ome.model.core.Channel;
import ome.model.core.Image;
import ome.model.core.Pixels;
import ome.model.display.ChannelBinding;
import ome.model.display.CodomainMapContext;
import ome.model.display.RenderingDef;
import ome.model.display.ReverseIntensityContext;
import ome.model.enums.RenderingModel;
import ome.model.stats.StatsInfo;
import ome.util.PixelData;

/**
 * Provide OMERO image as Zarr via HTTP endpoint.
 * @author m.t.b.carroll@dundee.ac.uk
 */
public class RequestHandlerForImage implements HttpHandler {

    /**
     * Contains the dimensionality of an image.
     * @author m.t.b.carroll@dundee.ac.uk
     */
    static class DataShape {
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

    private final PixelsService pixelsService;
    private final PixelBufferCache cache;
    private final OmeroDao omeroDao;

    private final int chunkSize;
    private final int deflateLevel;

    private final Pattern patternForGroup;
    private final Pattern patternForAttrs;
    private final Pattern patternForArray;
    private final Pattern patternForChunk;

    /**
     * Create the HTTP request handler.
     * @param configuration the configuration of this microservice
     * @param pixelsService the OMERO pixels service
     * @param pixelBufferCache the pixel buffer cache
     * @param omeroDao the OMERO data access object
     */
    public RequestHandlerForImage(Configuration configuration, PixelsService pixelsService, PixelBufferCache pixelBufferCache,
            OmeroDao omeroDao) {
        this.pixelsService = pixelsService;
        this.cache = pixelBufferCache;
        this.omeroDao = omeroDao;

        this.chunkSize = configuration.getMinimumChunkSize();
        this.deflateLevel = configuration.getDeflateLevel();

        final String path = configuration.getPathRegex();
        this.patternForGroup = Pattern.compile(path + "\\.zgroup");
        this.patternForAttrs = Pattern.compile(path + "\\.zattrs");
        this.patternForArray = Pattern.compile(path + "(\\d+)/\\.zarray");
        this.patternForChunk = Pattern.compile(path + "(\\d+)/(\\d+([/.]\\d+)*)");
    }

    @Override
    public void handleFor(Router router) {
        router.getWithRegex(patternForGroup.pattern()).handler(this);
        router.getWithRegex(patternForAttrs.pattern()).handler(this);
        router.getWithRegex(patternForArray.pattern()).handler(this);
        router.getWithRegex(patternForChunk.pattern()).handler(this);
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
            final String requestPath = request.path();
            try {
                Matcher matcher;
                matcher = patternForGroup.matcher(requestPath);
                if (matcher.matches()) {
                    final long imageId = Long.parseLong(matcher.group(1));
                    returnGroup(response, imageId);
                }
                matcher = patternForAttrs.matcher(requestPath);
                if (matcher.matches()) {
                    final long imageId = Long.parseLong(matcher.group(1));
                    returnAttrs(response, imageId);
                }
                matcher = patternForArray.matcher(requestPath);
                if (matcher.matches()) {
                    final long imageId = Long.parseLong(matcher.group(1));
                    final int resolutionId = Integer.parseInt(matcher.group(2));
                    returnArray(response, imageId, resolutionId);
                }
                matcher = patternForChunk.matcher(requestPath);
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
        final Pixels pixels = omeroDao.getPixels(imageId);
        if (pixels == null) {
            fail(response, 404, "no image for that id");
            return;
        }
        final Map<String, Object> result = new HashMap<>();
        result.put("zarr_format", 2);
        respondWithJson(response, new JsonObject(result));
    }

    /**
     * Build OMERO metadata for {@link #returnAttrs(HttpServerResponse, long)} to include with a {@code "omero"} key.
     * @param pixels the {@link Pixels} instance for which to build the metadata
     * @return the nested metadata
     */
    private Map<String, Object> buildOmeroMetadata(Pixels pixels) {
        final Map<String, Object> omero = new HashMap<>();
        final Image image = pixels.getImage();
        omero.put("id", image.getId());
        omero.put("name", image.getName());
        final long ownerId = pixels.getDetails().getOwner().getId();
        RenderingDef rdef = null;
        final Iterator<RenderingDef> settingsIterator = pixels.iterateSettings();
        while (rdef == null && settingsIterator.hasNext()) {
            final RenderingDef setting = settingsIterator.next();
            if (ownerId == setting.getDetails().getOwner().getId()) {
                /* settings owned by the image owner ... */
                rdef = setting;
            }
        }
        if (rdef == null) {
            if (pixels.sizeOfSettings() > 0) {
                /* ... or fall back to other settings */
                rdef = pixels.iterateSettings().next();
            }
        }
        if (rdef != null) {
            final Map<String, Object> rdefs = new HashMap<>();
            rdefs.put("defaultZ", rdef.getDefaultZ());
            rdefs.put("defaultT", rdef.getDefaultT());
            if (RenderingModel.VALUE_GREYSCALE.equals(rdef.getModel().getValue())) {
                rdefs.put("model", "greyscale");
            } else {
                /* probably "rgb" */
                rdefs.put("model", "color");
            }
            omero.put("rdefs", rdefs);
            final int channelCount = pixels.sizeOfChannels();
            if (channelCount == rdef.sizeOfWaveRendering()) {
                final List<Map<String, Object>> channels = new ArrayList<>(channelCount);
                for (int index = 0; index < channelCount; index++) {
                    final Channel channel = pixels.getChannel(index);
                    final ChannelBinding binding = rdef.getChannelBinding(index);
                    final Map<String, Object> channelsElem = new HashMap<>();
                    final String name = channel.getLogicalChannel().getName();
                    if (name != null) {
                        channelsElem.put("label", channel.getLogicalChannel().getName());
                    }
                    channelsElem.put("active", binding.getActive());
                    channelsElem.put("coefficient", binding.getCoefficient());
                    channelsElem.put("family", binding.getFamily().getValue());
                    boolean isInverted = false;
                    final Iterator<CodomainMapContext> sdeIterator = binding.iterateSpatialDomainEnhancement();
                    while (sdeIterator.hasNext()) {
                        final CodomainMapContext sde = sdeIterator.next();
                        if (sde instanceof ReverseIntensityContext) {
                            isInverted ^= ((ReverseIntensityContext) sde).getReverse();
                        }
                    }
                    channelsElem.put("inverted", isInverted);
                    channelsElem.put("color",
                            String.format("%02X", binding.getRed()) +
                            String.format("%02X", binding.getGreen()) +
                            String.format("%02X", binding.getBlue()));
                    final Map<String, Object> window = new HashMap<>();
                    final StatsInfo stats = channel.getStatsInfo();
                    if (stats != null) {
                        window.put("min", stats.getGlobalMin());
                        window.put("max", stats.getGlobalMax());
                    }
                    window.put("start", binding.getInputStart());
                    window.put("end", binding.getInputEnd());
                    channelsElem.put("window", window);
                    channels.add(channelsElem);
                }
                omero.put("channels", channels);
            }
        }
        return omero;
    }

    /**
     * Handle a request for {@code .zattrs}.
     * @param response the HTTP server response to populate
     * @param imageId the ID of the image being queried
     */
    private void returnAttrs(HttpServerResponse response, long imageId) {
        final Pixels pixels = omeroDao.getPixels(imageId);
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
                /* dataset.put("scale", scale);
                 * scale is omitted while it is being rethought */
            }
            datasets.add(dataset);
        }
        final Map<String, Object> omero = buildOmeroMetadata(pixels);
        final Map<String, Object> multiscale = new HashMap<>();
        multiscale.put("version", "0.1");
        multiscale.put("name", "default");
        multiscale.put("datasets", datasets);
        final JsonArray multiscales = new JsonArray();
        multiscales.add(multiscale);
        final Map<String, Object> result = new HashMap<>();
        result.put("multiscales", multiscales);
        if (omero != null) {
            result.put("omero", omero);
        }
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
            shape = new DataShape(buffer).adjustTileSize(chunkSize);
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
        compressor.put("level", deflateLevel);
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
            final DataShape shape = new DataShape(buffer).adjustTileSize(chunkSize);
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
    private Buffer compress(byte[] uncompressed) {
        final Deflater deflater = new Deflater(deflateLevel);
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
