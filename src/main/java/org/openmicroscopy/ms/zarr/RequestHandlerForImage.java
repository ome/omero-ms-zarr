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

import java.awt.Dimension;
import java.io.IOException;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Deflater;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

import j2html.TagCreator;
import j2html.tags.DomContent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Provide OMERO image as Zarr via HTTP endpoint.
 * @author m.t.b.carroll@dundee.ac.uk
 */
public class RequestHandlerForImage implements HttpHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(RequestHandlerForImage.class);

    /**
     * Contains the dimensionality of an image.
     * @author m.t.b.carroll@dundee.ac.uk
     */
    static class DataShape {

        static final Map<Character, Function<DataShape, Boolean>> ADJUSTERS = ImmutableMap.of(
                'X', DataShape::increaseX,
                'Y', DataShape::increaseY,
                'Z', DataShape::increaseZ);

        final int xSize, ySize, cSize, zSize, tSize;
        final int byteWidth;

        int xTile, yTile, zTile;

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
            zTile = 1;

            byteWidth = buffer.getByteWidth();
        }

        /**
         * @return if this method was able to increase the <em>X</em> tile size
         */
        private boolean increaseX() {
            if (xTile < xSize) {
                if (xTile * 3 >= xSize) {
                    xTile = xSize;
                } else {
                    xTile <<= 1;
                }
                return true;
            } else {
                return false;
            }
        }

        /**
         * @return if this method was able to increase the <em>Y</em> tile size
         */
        private boolean increaseY() {
            if (yTile < ySize) {
                if (yTile * 3 >= ySize) {
                    yTile = ySize;
                } else {
                    yTile <<= 1;
                }
                return true;
            } else {
                return false;
            }
        }

        /**
         * @return if this method was able to increase the <em>Z</em> tile size
         */
        private boolean increaseZ() {
            if (zTile < zSize) {
                zTile <<= 1;
                /* Spread planes evenly across chunks. */
                final int zCount = (zSize + zTile - 1) / zTile;
                zTile = (zSize + zCount - 1) / zCount;
                return true;
            } else {
                return false;
            }
        }

        /**
         * Attempt to increase the tile size so the tile occupies at least the given number of bytes.
         * @param adjusters the methods to use in increasing tile size, in descending order of preference
         * @param target the minimum target size, in bytes
         * @return this, for method chaining
         */
        DataShape adjustTileSize(Iterable<Function<DataShape, Boolean>> adjusters, int target) {
            final int pixelTarget = target / byteWidth;
            for (final Function<DataShape, Boolean> adjuster : adjusters) {
                while ((long) xTile * yTile * zTile < pixelTarget) {
                    if (!adjuster.apply(this)) {
                        break;
                    }
                }
            }
            return this;
        }

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder();
            sb.append("XYZCT: full=");
            sb.append(xSize);
            sb.append('×');
            sb.append(ySize);
            sb.append('×');
            sb.append(zSize);
            sb.append('×');
            sb.append(cSize);
            sb.append('×');
            sb.append(tSize);
            sb.append(" tile=");
            sb.append(xTile);
            sb.append('×');
            sb.append(yTile);
            sb.append('×');
            sb.append(zTile);
            sb.append('×');
            sb.append(1);
            sb.append('×');
            sb.append(1);
            sb.append(" bytes=");
            sb.append(byteWidth);
            return sb.toString();
        }
    }

    private final PixelsService pixelsService;
    private final PixelBufferCache cache;
    private final OmeroDao omeroDao;

    private List<Function<DataShape, Boolean>> chunkSizeAdjust;
    private final int chunkSizeMin;
    private final int deflateLevel;
    private final Boolean foldersNested;

    private final Pattern patternForImageDir;
    private final Pattern patternForGroupDir;
    private final Pattern patternForChunkDir;

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

        final ImmutableList.Builder<Function<DataShape, Boolean>> chunkSizeAdjust = ImmutableList.builder();
        for (final char dimension : configuration.getAdjustableChunkDimensions()) {
            chunkSizeAdjust.add(DataShape.ADJUSTERS.get(dimension));
        }
        this.chunkSizeAdjust = chunkSizeAdjust.build();

        this.chunkSizeMin = configuration.getMinimumChunkSize();
        this.deflateLevel = configuration.getDeflateLevel();
        this.foldersNested = configuration.getFoldersNested();

        final String path = configuration.getPathRegex();

        this.patternForImageDir = Pattern.compile(path);
        this.patternForGroupDir = Pattern.compile(path + "(\\d+)/");
        this.patternForChunkDir = Pattern.compile(path + "(\\d+)/(\\d+([/.]\\d+)*)/");

        this.patternForGroup = Pattern.compile(path + "\\.zgroup");
        this.patternForAttrs = Pattern.compile(path + "\\.zattrs");
        this.patternForArray = Pattern.compile(path + "(\\d+)/\\.zarray");
        this.patternForChunk = Pattern.compile(path + "(\\d+)/(\\d+([/.]\\d+)*)");
    }

    @Override
    public void handleFor(Router router) {
        LOGGER.info("handling GET requests for router");
        if (foldersNested != null) {
            router.getWithRegex(patternForImageDir.pattern()).handler(this);
            router.getWithRegex(patternForGroupDir.pattern()).handler(this);
            if (foldersNested) {
                router.getWithRegex(patternForChunkDir.pattern()).handler(this);
            }
        }
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
        LOGGER.debug("responding with code {} failure: {}", code, message);
        response.setStatusCode(code);
        response.setStatusMessage(message);
        response.end();
    }

    /**
     * Parse chunk index from the given path.
     * @param path indices separated by {@code '/'} or {@code '.'}
     * @return the parsed indices
     * @throws NumberFormatException if any indices could not be parsed
     */
    private static List<Integer> getIndicesFromPath(String path) {
        final ImmutableList.Builder<Integer> indices = ImmutableList.builder();
        for (final String integerText : path.split("[/.]")) {
            indices.add(Integer.parseInt(integerText));
        }
        return indices.build();
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
            LOGGER.debug("handling GET request path: {}", requestPath);
            try {
                Matcher matcher;
                if (foldersNested != null) {
                    matcher = patternForImageDir.matcher(requestPath);
                    if (matcher.matches()) {
                        final long imageId = Long.parseLong(matcher.group(1));
                        returnImageDirectory(response, imageId);
                    }
                    matcher = patternForGroupDir.matcher(requestPath);
                    if (matcher.matches()) {
                        final long imageId = Long.parseLong(matcher.group(1));
                        final int resolutionId = Integer.parseInt(matcher.group(2));
                        if (foldersNested) {
                            returnGroupDirectoryNested(response, imageId, resolutionId, 0);
                        } else {
                            returnGroupDirectoryFlattened(response, imageId, resolutionId);
                        }
                    }
                    if (foldersNested) {
                        matcher = patternForChunkDir.matcher(requestPath);
                        if (matcher.matches()) {
                            final long imageId = Long.parseLong(matcher.group(1));
                            final int resolutionId = Integer.parseInt(matcher.group(2));
                            final List<Integer> chunkDir = getIndicesFromPath(matcher.group(3));
                            if (chunkDir.size() < 5) {
                                returnGroupDirectoryNested(response, imageId, resolutionId, chunkDir.size());
                            } else {
                                fail(response, 404, "no chunk directory with that index");
                                return;
                            }
                        }
                    }
                }
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
                    final int resolutionId = Integer.parseInt(matcher.group(2));
                    final List<Integer> chunkId = getIndicesFromPath(matcher.group(3));
                    if (chunkId.size() == 5) {
                        returnChunk(response, imageId, resolutionId, chunkId);
                    } else {
                        fail(response, 404, "no chunk with that index");
                        return;
                    }
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
     * Extract data from a pixel buffer from the server.
     * @param <X> the type of data desired
     * @param response a HTTP response, will be {@link HttpServerResponse#ended()} in the event of failure
     * @param pixels the pixels instance identifying the desired buffer, if {@code null} will cause the response to be ended
     * @param getter how to extract the data from the pixel buffer
     * @return the extracted data
     */
    private <X> X getDataFromPixels(HttpServerResponse response, Pixels pixels, Function<PixelBuffer, X> getter) {
        if (pixels == null) {
            fail(response, 404, "no image for that id");
            return null;
        }
        PixelBuffer buffer = null;
        try {
            buffer = pixelsService.getPixelBuffer(pixels, false);
            return getter.apply(buffer);
        } catch (Exception e) {
            LOGGER.debug("pixel buffer failure", e);
            fail(response, 500, "query failed");
            return null;
        } finally {
            if (buffer != null) {
                try {
                    buffer.close();
                } catch (IOException ioe) {
                    /* probably already failing anyway */
                }
            }
        }
    }

    /**
     * Find the dimensionality of a given resolution.
     * @param response a HTTP response, will be {@link HttpServerResponse#ended()} in the event of failure
     * @param imageId the ID of the image being queried
     * @param resolution the resolution to query
     * @return the dimensionality of that resolution
     */
    private DataShape getDataShape(HttpServerResponse response, long imageId, int resolution) {
        PixelBuffer buffer = null;
        try {
            buffer = cache.getPixelBuffer(imageId, resolution);
            if (buffer == null) {
                fail(response, 404, "no image for that id and resolution");
                return null;
            }
            return new DataShape(buffer).adjustTileSize(chunkSizeAdjust, chunkSizeMin);
        } catch (Exception e) {
            LOGGER.debug("pixel buffer failure", e);
            fail(response, 500, "query failed");
            return null;
        } finally {
            if (buffer != null) {
                cache.releasePixelBuffer(buffer);
            }
        }
    }

    /**
     * Set the given directory contents as the given HTTP response.
     * @param response a HTTP response
     * @param entityName the name of the entity to which the directory corresponds
     * @param contents the contents of the directory
     */
    private void respondWithDirectory(HttpServerResponse response, String entityName, Collection<String> contents) {
        final DomContent listing = TagCreator.html(
                TagCreator.head(TagCreator.title(entityName)),
                TagCreator.body(
                        TagCreator.h1("Directory listing for " + entityName),
                        TagCreator.ul(TagCreator.each(contents,
                                content -> TagCreator.li(TagCreator.a(content).withHref(content))))));
        final String responseText = listing.render();
        final int responseSize = responseText.length();
        LOGGER.debug("constructed HTML response of size {}", responseSize);
        response.putHeader("Content-Type", "text/html; charset=utf-8");
        response.putHeader("Content-Length", Integer.toString(responseSize));
        response.end(responseText);

    }

    /**
     * Set the given JSON data as the given HTTP response.
     * @param response a HTTP response
     * @param data some JSON data
     */
    private static void respondWithJson(HttpServerResponse response, JsonObject data) {
        final String responseText = data.toString();
        final int responseSize = responseText.length();
        LOGGER.debug("constructed JSON response of size {}", responseSize);
        response.putHeader("Content-Type", "application/json; charset=utf-8");
        response.putHeader("Content-Length", Integer.toString(responseSize));
        response.end(responseText);
    }

    /**
     * Handle a request for the image directory.
     * @param response the HTTP server response to populate
     * @param imageId the ID of the image being queried
     */
    private void returnImageDirectory(HttpServerResponse response, long imageId) {
        LOGGER.debug("providing directory listing for Image:{}", imageId);
        /* gather data from pixels service */
        final Pixels pixels = omeroDao.getPixels(imageId);
        final int resolutions = getDataFromPixels(response, pixels, buffer ->  buffer.getResolutionLevels());
        if (response.ended()) {
            return;
        }
        /* package data for client */
        final ImmutableList.Builder<String> contents = ImmutableList.builder();
        contents.add(".zattrs");
        contents.add(".zgroup");
        for (int resolution = 0; resolution < resolutions; resolution++) {
            contents.add(Integer.toString(resolution) + '/');
        }
        respondWithDirectory(response, "Image #" + imageId, contents.build());
    }

    /**
     * Handle a request for the group directory in flattened mode.
     * @param response the HTTP server response to populate
     * @param imageId the ID of the image being queried
     * @param resolution the resolution to query
     */
    private void returnGroupDirectoryFlattened(HttpServerResponse response, long imageId, int resolution) {
        LOGGER.debug("providing flattened directory listing for resolution {} of Image:{}", resolution, imageId);
        /* gather data from pixels service */
        final DataShape shape = getDataShape(response, imageId, resolution);
        if (response.ended()) {
            return;
        }
        /* package data for client */
        final ImmutableList.Builder<String> contents = ImmutableList.builder();
        contents.add(".zarray");
        for (int t = 0; t < shape.tSize; t += 1) {
            for (int c = 0; c < shape.cSize; c += 1) {
                for (int z = 0; z < shape.zSize; z += shape.zTile) {
                    for (int y = 0; y < shape.ySize; y += shape.yTile) {
                        for (int x = 0; x < shape.xSize; x += shape.xTile) {
                            contents.add(Joiner.on('.').join(t, c, z / shape.zTile, y / shape.yTile, x / shape.xTile));
                        }
                    }
                }
            }
        }
        respondWithDirectory(response, "Image #" + imageId + ", resolution " + resolution + " (flattened)", contents.build());
    }

    /**
     * Handle a request for the group directory in nested mode.
     * @param response the HTTP server response to populate
     * @param imageId the ID of the image being queried
     * @param resolution the resolution to query
     * @param depth how many directory levels inside the group
     */
    private void returnGroupDirectoryNested(HttpServerResponse response, long imageId, int resolution, int depth) {
        LOGGER.debug("providing nested directory listing for resolution {} of Image:{} at depth {}", resolution, imageId, depth);
        /* gather data from pixels service */
        final DataShape shape = getDataShape(response, imageId, resolution);
        if (response.ended()) {
            return;
        }
        /* package data for client */
        final ImmutableList.Builder<String> contents = ImmutableList.builder();
        if (depth == 0) {
            contents.add(".zarray");
        }
        final int extent, step;
        switch (depth) {
        case 0:
            extent = shape.tSize;
            step = 1;
            break;
        case 1:
            extent = shape.cSize;
            step = 1;
            break;
        case 2:
            extent = shape.zSize;
            step = shape.zTile;
            break;
        case 3:
            extent = shape.ySize;
            step = shape.yTile;
            break;
        case 4:
            extent = shape.xSize;
            step = shape.xTile;
            break;
        default:
            throw new IllegalArgumentException("depth cannot be " + depth);
        }
        final StringBuilder content = new StringBuilder();
        for (int position = 0; position < extent; position += step) {
            content.setLength(0);
            content.append(position / step);
            if (depth < 4) {
                content.append('/');
            }
            contents.add(content.toString());
        }
        respondWithDirectory(response, "Image #" + imageId + ", resolution " + resolution + " (nested)", contents.build());
    }

    /**
     * Handle a request for {@code .zgroup}.
     * @param response the HTTP server response to populate
     * @param imageId the ID of the image being queried
     */
    private void returnGroup(HttpServerResponse response, long imageId) {
        LOGGER.debug("providing .zgroup for Image:{}", imageId);
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
        LOGGER.debug("providing .zattrs for Image:{}", imageId);
        /* gather data from pixels service */
        final Pixels pixels = omeroDao.getPixels(imageId);
        final List<List<Integer>> resolutions = getDataFromPixels(response, pixels, buffer ->  buffer.getResolutionDescriptions());
        if (response.ended()) {
            return;
        }
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
        final List<Double> scales = new ArrayList<>(resolutions.size());
        for (final List<Integer> resolution : resolutions) {
            final int x = resolution.get(0);
            final int y = resolution.get(1);
            final double xScale = x / xMax;
            final double yScale = y / yMax;
            scales.add(xScale == yScale ? xScale : null);
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
        LOGGER.debug("providing .zarray for resolution {} of Image:{}", resolution, imageId);
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
            shape = new DataShape(buffer).adjustTileSize(chunkSizeAdjust, chunkSizeMin);
            final int xd = Math.min(shape.xSize, shape.xTile);
            final int yd = Math.min(shape.ySize, shape.yTile);
            final PixelData tile = buffer.getTile(0, 0, 0, 0, 0, xd, yd);
            isLittleEndian = tile.getOrder() == ByteOrder.LITTLE_ENDIAN;
            tile.dispose();
            isSigned = buffer.isSigned();
            isFloat = buffer.isFloat();
        } catch (Exception e) {
            LOGGER.debug("pixel buffer failure", e);
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
        result.put("chunks", ImmutableList.of(1, 1, shape.zTile, shape.yTile, shape.xTile));
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
        LOGGER.debug("providing chunk {} of resolution {} of Image:{}", chunkId, resolution, imageId);
        /* gather data from pixels service */
        final byte[] chunk;
        PixelBuffer buffer = null;
        try {
            buffer = cache.getPixelBuffer(imageId, resolution);
            if (buffer == null) {
                fail(response, 404, "no image for that id and resolution");
                return;
            }
            final DataShape shape = new DataShape(buffer).adjustTileSize(chunkSizeAdjust, chunkSizeMin);
            final int x = shape.xTile * chunkId.get(4);
            final int y = shape.yTile * chunkId.get(3);
            final int z = shape.zTile * chunkId.get(2);
            final int c = chunkId.get(1);
            final int t = chunkId.get(0);
            if (x >= shape.xSize || y >= shape.ySize || c >= shape.cSize || z >= shape.zSize || t >= shape.tSize) {
                fail(response, 404, "no chunk with that index");
                return;
            }
            chunk = new byte[shape.xTile * shape.yTile * shape.zTile * shape.byteWidth];
            for (int plane = 0; plane < shape.zTile && z + plane < shape.zSize; plane++) {
                final int planeOffset = plane * (chunk.length / shape.zTile);
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
                    tile = buffer.getTile(z + plane, c, t, x, y, xd, yd);
                    final byte[] chunkSrc = tile.getData().array();
                    /* must now assemble row-by-row into a plane in the chunk */
                    for (int row = 0; row < yd; row++) {
                        final int srcIndex = row * xd * shape.byteWidth;
                        final int dstIndex = row * shape.xTile * shape.byteWidth + planeOffset;
                        System.arraycopy(chunkSrc, srcIndex, chunk, dstIndex, xd * shape.byteWidth);
                    }
                } else {
                    final int yd;
                    if (y + shape.yTile > shape.ySize) {
                        /* a tile that crosses the bottom of the image */
                        yd = shape.ySize - y;
                    } else {
                        /* the tile fills a plane in the chunk */
                        yd = shape.yTile;
                    }
                    tile = buffer.getTile(z + plane, c, t, x, y, shape.xTile, yd);
                    final byte[] chunkSrc = tile.getData().array();
                    /* simply copy into the plane */
                    System.arraycopy(chunkSrc, 0, chunk, planeOffset, chunkSrc.length);
                }
                tile.dispose();
            }
        } catch (Exception e) {
            LOGGER.debug("pixel buffer failure", e);
            fail(response, 500, "query failed");
            return;
        } finally {
            if (buffer != null) {
                cache.releasePixelBuffer(buffer);
            }
        }
        /* package data for client */
        final Buffer chunkZipped = compress(chunk);
        final int responseSize = chunkZipped.length();
        LOGGER.debug("constructed binary response of size {}", responseSize);
        response.putHeader("Content-Type", "application/octet-stream");
        response.putHeader("Content-Length", Integer.toString(responseSize));
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
