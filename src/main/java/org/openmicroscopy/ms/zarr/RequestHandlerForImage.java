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

import org.openmicroscopy.ms.zarr.mask.Bitmask;
import org.openmicroscopy.ms.zarr.mask.ImageMask;
import org.openmicroscopy.ms.zarr.mask.UnionMask;

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
import ome.model.roi.Mask;
import ome.model.roi.Roi;
import ome.model.stats.StatsInfo;
import ome.util.PixelData;

import java.awt.Dimension;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.Deflater;

import com.google.common.base.Joiner;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.cache.Weigher;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
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
         * Construct the shape of image data.
         * @param buffer the pixel buffer from which to extract the dimensionality
         */
        DataShape(PixelBuffer buffer) {
            xSize = buffer.getSizeX();
            ySize = buffer.getSizeY();
            cSize = buffer.getSizeC();
            zSize = buffer.getSizeZ();
            tSize = buffer.getSizeT();

            byteWidth = buffer.getByteWidth();

            applyTileSize(buffer.getTileSize());
        }

        /**
         * Construct the shape of a mask.
         * @param buffer the pixel buffer from which to extract the dimensionality
         * @param mask the mask to use to limit dimensionality based on {@link Bitmask#isSignificant(char)}
         * @param byteWidth the applicable byte width, typically {@code 1} for a split mask
         */
        DataShape(PixelBuffer buffer, Bitmask mask, int byteWidth) {
            this(buffer, Collections.singleton(mask), byteWidth);
        }

        /**
         * Construct the shape of a mask.
         * @param buffer the pixel buffer from which to extract the dimensionality
         * @param masks the masks to use to limit dimensionality based on {@link Bitmask#isSignificant(char)}
         * @param byteWidth the applicable byte width, typically {@link Long#BYTES} for a labeled mask
         */
        DataShape(PixelBuffer buffer, Collection<Bitmask> masks, int byteWidth) {
            xSize = buffer.getSizeX();
            ySize = buffer.getSizeY();
            cSize = masks.stream().anyMatch(mask -> mask.isSignificant('C')) ? buffer.getSizeC() : 1;
            zSize = masks.stream().anyMatch(mask -> mask.isSignificant('Z')) ? buffer.getSizeZ() : 1;
            tSize = masks.stream().anyMatch(mask -> mask.isSignificant('T')) ? buffer.getSizeT() : 1;

            this.byteWidth = byteWidth;

            applyTileSize(buffer.getTileSize());
        }

        /**
         * Helper for constructors.
         * @param tileSize the tile size to set for this shape
         */
        private void applyTileSize(Dimension tileSize) {
            if (tileSize == null) {
                xTile = xSize;
                yTile = ySize;
            } else {
                xTile = tileSize.width;
                yTile = tileSize.height;
            }
            zTile = 1;
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
    private final Long maskOverlapValue;

    private final Pattern patternForImageDir;
    private final Pattern patternForImageGroupDir;
    private final Pattern patternForImageChunkDir;
    private final Pattern patternForImageMasksDir;
    private final Pattern patternForMaskDir;
    private final Pattern patternForMaskChunkDir;
    private final Pattern patternForMaskLabeledDir;
    private final Pattern patternForMaskLabeledChunkDir;

    private final Pattern patternForImageGroup;
    private final Pattern patternForImageAttrs;
    private final Pattern patternForImageMasksGroup;
    private final Pattern patternForImageMasksAttrs;
    private final Pattern patternForMaskAttrs;
    private final Pattern patternForMaskLabeledAttrs;
    private final Pattern patternForImageArray;
    private final Pattern patternForMaskArray;
    private final Pattern patternForMaskLabeledArray;
    private final Pattern patternForImageChunk;
    private final Pattern patternForMaskChunk;
    private final Pattern patternForMaskLabeledChunk;

    /* Labeled masks may be expensive to construct so here they are cached. */
    private final LoadingCache<Long, Optional<Map<Long, Bitmask>>> labeledMaskCache;

    /**
     * Build the cache for labeled masks.
     * @param maximumWeight the maximum weight to set for the cache
     * @return the cache
     */
    private LoadingCache<Long, Optional<Map<Long, Bitmask>>> buildLabeledMaskCache(long maximumWeight) {
        LOGGER.info("weight is " + maximumWeight);
        return CacheBuilder.newBuilder()
                .weigher(new Weigher<Long, Optional<Map<Long, Bitmask>>>() {
                    @Override
                    public int weigh(Long imageId, Optional<Map<Long, Bitmask>> labeledMasks) {
                        if (labeledMasks.isPresent()) {
                            return labeledMasks.get().values().stream().map(Bitmask::size).reduce(0, Math::addExact);
                        } else {
                            return 0;
                        }
                    }
                })
                .maximumWeight(maximumWeight)
                .expireAfterAccess(1, TimeUnit.MINUTES)
                .build(new CacheLoader<Long, Optional<Map<Long, Bitmask>>>() {
                    @Override
                    public Optional<Map<Long, Bitmask>> load(Long key) {
                        return Optional.ofNullable(getLabeledMasksForCache(key));
                    }
                });
    }

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
        this.labeledMaskCache = buildLabeledMaskCache(configuration.getMaskCacheSize());
        this.maskOverlapValue = configuration.getMaskOverlapValue();

        final String path = configuration.getPathRegex();

        this.patternForImageDir = Pattern.compile(path);
        this.patternForImageGroupDir = Pattern.compile(path + "(\\d+)/");
        this.patternForImageChunkDir = Pattern.compile(path + "(\\d+)/(\\d+([/.]\\d+)*)/");
        this.patternForImageMasksDir = Pattern.compile(path + "masks/");
        this.patternForMaskDir = Pattern.compile(path + "masks/(\\d+)/");
        this.patternForMaskChunkDir = Pattern.compile(path + "masks/(\\d+)/(\\d+([/.]\\d+)*)/");
        this.patternForMaskLabeledDir = Pattern.compile(path + "masks/labell?ed/");
        this.patternForMaskLabeledChunkDir = Pattern.compile(path + "masks/labell?ed/(\\d+([/.]\\d+)*)/");

        this.patternForImageGroup = Pattern.compile(path + "\\.zgroup");
        this.patternForImageAttrs = Pattern.compile(path + "\\.zattrs");
        this.patternForImageMasksGroup = Pattern.compile(path + "masks/\\.zgroup");
        this.patternForImageMasksAttrs = Pattern.compile(path + "masks/\\.zattrs");
        this.patternForMaskAttrs = Pattern.compile(path + "masks/(\\d+)/\\.zattrs");
        this.patternForMaskLabeledAttrs = Pattern.compile(path + "masks/labell?ed/\\.zattrs");
        this.patternForImageArray = Pattern.compile(path + "(\\d+)/\\.zarray");
        this.patternForMaskArray = Pattern.compile(path + "masks/(\\d+)/\\.zarray");
        this.patternForMaskLabeledArray = Pattern.compile(path + "masks/labell?ed/\\.zarray");
        this.patternForImageChunk = Pattern.compile(path + "(\\d+)/(\\d+([/.]\\d+)*)");
        this.patternForMaskChunk = Pattern.compile(path + "masks/(\\d+)/(\\d+([/.]\\d+)*)");
        this.patternForMaskLabeledChunk = Pattern.compile(path + "masks/labell?ed/(\\d+([/.]\\d+)*)");
    }

    /**
     * Add the given method as a GET handler for the given paths.
     * For convenience, if the method throws {@link IllegalArgumentException} then the response is ended with 4xx status.
     * @param router the router for which this should handle requests
     * @param pattern a regular expression defining the paths that should be handled
     * @param handler the handler for the given paths
     */
    private void handleFor(Router router, Pattern pattern, BiConsumer<HttpServerResponse, List<String>> handler) {
        router.getWithRegex(pattern.pattern()).handler(new Handler<RoutingContext>() {
            @Override
            public void handle(RoutingContext context) {
                final String requestPath = context.request().path();
                final HttpServerResponse response = context.response();
                try {
                    final Matcher matcher = pattern.matcher(requestPath);
                    if (matcher.matches()) {
                        final List<String> groups = new ArrayList<String>(matcher.groupCount());
                        for (int groupIndex = 1; groupIndex <= matcher.groupCount(); groupIndex++) {
                            groups.add(matcher.group(groupIndex));
                        }
                        handler.accept(response, groups);
                    } else {
                        fail(response, 500, "regular expression failure in routing HTTP request");
                    }
                } catch (NumberFormatException nfe) {
                    fail(response, 400, "failed to parse integer");
                } catch (IllegalArgumentException iae) {
                    fail(response, 404, iae.getMessage());
                } catch (Throwable t) {
                    LOGGER.warn("unexpected failure handling path: {}", requestPath, t);
                    throw t;
                }
            }
        });
    }

    @Override
    public void handleFor(Router router) {
        LOGGER.info("handling GET requests for router");
        if (foldersNested != null) {
            handleFor(router, patternForImageDir, this::returnImageDirectory);
            if (foldersNested) {
                handleFor(router, patternForImageGroupDir, this::returnImageGroupDirectoryNested);
                handleFor(router, patternForMaskDir, this::returnMaskDirectoryNested);
                handleFor(router, patternForMaskLabeledDir, this::returnMaskLabeledDirectoryNested);
                handleFor(router, patternForImageChunkDir, this::returnImageChunkDirectory);
                handleFor(router, patternForMaskChunkDir, this::returnMaskChunkDirectory);
                handleFor(router, patternForMaskLabeledChunkDir, this::returnMaskLabeledChunkDirectory);
            } else {
                handleFor(router, patternForImageGroupDir, this::returnImageGroupDirectoryFlattened);
                handleFor(router, patternForMaskDir, this::returnMaskDirectoryFlattened);
                handleFor(router, patternForMaskLabeledDir, this::returnMaskLabeledDirectoryFlattened);
            }
            handleFor(router, patternForImageMasksDir, this::returnImageMasksDirectory);
        }
        handleFor(router, patternForImageGroup, this::returnGroup);
        handleFor(router, patternForImageMasksGroup, this::returnGroup);
        handleFor(router, patternForImageAttrs, this::returnImageAttrs);
        handleFor(router, patternForImageMasksAttrs, this::returnImageMasksAttrs);
        handleFor(router, patternForMaskAttrs, this::returnMaskAttrs);
        handleFor(router, patternForMaskLabeledAttrs, this::returnMaskLabeledAttrs);
        handleFor(router, patternForImageArray, this::returnImageArray);
        handleFor(router, patternForMaskArray, this::returnMaskArray);
        handleFor(router, patternForMaskLabeledArray, this::returnMaskLabeledArray);
        handleFor(router, patternForImageChunk, this::returnImageChunk);
        handleFor(router, patternForMaskChunk, this::returnMaskChunk);
        handleFor(router, patternForMaskLabeledChunk, this::returnMaskLabeledChunk);
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
     * Helper for building directory contents: adds entries for flattened chunks.
     * @param contents the directory contents
     * @param shape the dimensionality of the data
     */
    private static void addChunkEntriesFlattened(ImmutableCollection.Builder<String> contents, DataShape shape) {
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
    }

    /**
     * Helper for building directory contents: adds entries for nested chunks.
     * @param contents the directory contents
     * @param shape the dimensionality of the data
     * @param depth the depth <q>into</q> the chunk, ranging from zero to four inclusive
     */
    private static void addChunkEntriesNested(ImmutableCollection.Builder<String> contents, DataShape shape, int depth) {
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
    }

    /**
     * Handle a request for the image directory.
     * @param response the HTTP server response to populate
     * @param parameters the parameters of the request to handle
     */
    private void returnImageDirectory(HttpServerResponse response, List<String> parameters) {
        /* parse parameters from path */
        final long imageId = Long.parseLong(parameters.get(0));
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
        for (final long roiId : omeroDao.getRoiIdsOfImage(imageId)) {
            if (omeroDao.getMaskCountOfRoi(roiId) > 0) {
                contents.add("masks/");
                break;
            }
        }
        for (int resolution = 0; resolution < resolutions; resolution++) {
            contents.add(Integer.toString(resolution) + '/');
        }
        respondWithDirectory(response, "Image #" + imageId, contents.build());
    }

    /**
     * Handle a request for the group directory in flattened mode.
     * @param response the HTTP server response to populate
     * @param parameters the parameters of the request to handle
     */
    private void returnImageGroupDirectoryFlattened(HttpServerResponse response, List<String> parameters) {
        /* parse parameters from path */
        final long imageId = Long.parseLong(parameters.get(0));
        final int resolution = Integer.parseInt(parameters.get(1));
        LOGGER.debug("providing flattened directory listing for resolution {} of Image:{}", resolution, imageId);
        /* gather data from pixels service */
        final DataShape shape = getDataShape(response, imageId, resolution);
        if (response.ended()) {
            return;
        }
        /* package data for client */
        final ImmutableList.Builder<String> contents = ImmutableList.builder();
        contents.add(".zarray");
        addChunkEntriesFlattened(contents, shape);
        respondWithDirectory(response, "Image #" + imageId + ", resolution " + resolution + " (flattened)", contents.build());
    }

    /**
     * Handle a request for the group directory in nested mode.
     * @param response the HTTP server response to populate
     * @param parameters the parameters of the request to handle
     */
    private void returnImageGroupDirectoryNested(HttpServerResponse response, List<String> parameters) {
        /* parse parameters from path */
        final long imageId = Long.parseLong(parameters.get(0));
        final int resolution = Integer.parseInt(parameters.get(1));
        returnImageGroupDirectoryNested(response, imageId, resolution, 0);
    }

    /**
     * Handle a request for the chunk directory in nested mode.
     * @param response the HTTP server response to populate
     * @param parameters the parameters of the request to handle
     */
    private void returnImageChunkDirectory(HttpServerResponse response, List<String> parameters) {
        /* parse parameters from path */
        final long imageId = Long.parseLong(parameters.get(0));
        final int resolution = Integer.parseInt(parameters.get(1));
        final List<Integer> chunkDir = getIndicesFromPath(parameters.get(2));
        if (chunkDir.size() >= 5) {
            throw new IllegalArgumentException("chunks must have five dimensions");
        }
        returnImageGroupDirectoryNested(response, imageId, resolution, chunkDir.size());
    }

    /**
     * Handle a request for the group directory in nested mode.
     * @param response the HTTP server response to populate
     * @param imageId the ID of the image being queried
     * @param resolution the resolution to query
     * @param depth how many directory levels inside the group
     */
    private void returnImageGroupDirectoryNested(HttpServerResponse response, long imageId, int resolution, int depth) {
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
        addChunkEntriesNested(contents, shape, depth);
        respondWithDirectory(response, "Image #" + imageId + ", resolution " + resolution, contents.build());
    }

    /**
     * Get the IDs of the ROIs that have masks.
     * @param imageId the image whose ROIs should be queried
     * @return the IDs of the ROIs that have masks, may be an empty collection
     */
    private Collection<Long> getRoiIdsWithMask(long imageId) {
        return omeroDao.getRoiIdsOfImage(imageId).stream()
                .filter(roiId -> omeroDao.getMaskCountOfRoi(roiId) > 0)
                .collect(Collectors.toList());
    }

    /**
     * Handle a request for the masks of an image directory.
     * @param response the HTTP server response to populate
     * @param parameters the parameters of the request to handle
     */
    private void returnImageMasksDirectory(HttpServerResponse response, List<String> parameters) {
        /* parse parameters from path */
        final long imageId = Long.parseLong(parameters.get(0));
        LOGGER.debug("providing directory listing for Masks of Image:{}", imageId);
        /* gather data from database */
        final Collection<Long> roiIdsWithMask = getRoiIdsWithMask(imageId);
        final Map<Long, Bitmask> labeledMasks = roiIdsWithMask.isEmpty() ? null : getLabeledMasks(imageId);
        if (roiIdsWithMask.isEmpty()) {
            fail(response, 404, "no image masks for that id");
            return;
        }
        /* package data for client */
        final ImmutableList.Builder<String> contents = ImmutableList.builder();
        contents.add(".zattrs");
        if (labeledMasks != null) {
            contents.add("labeled/");
        }
        for (long roiId : roiIdsWithMask) {
            contents.add(Long.toString(roiId) + '/');
        }
        respondWithDirectory(response, "Masks of Image #" + imageId, contents.build());
    }

    /**
     * Handle a request for the group directory in flattened mode.
     * @param response the HTTP server response to populate
     * @param parameters the parameters of the request to handle
     */
    private void returnMaskDirectoryFlattened(HttpServerResponse response, List<String> parameters) {
        /* parse parameters from path */
        final long imageId = Long.parseLong(parameters.get(0));
        final long roiId = Long.parseLong(parameters.get(1));
        LOGGER.debug("providing flattened directory listing for Mask:{}", roiId);
        /* gather data */
        final Pixels pixels = omeroDao.getPixels(imageId);
        final Roi roi = omeroDao.getRoi(roiId);
        if (roi == null || roi.getImage() == null || roi.getImage().getId() != imageId) {
            throw new IllegalArgumentException("image has no such mask");
        }
        final SortedSet<Long> maskIds = omeroDao.getMaskIdsOfRoi(roiId);
        if (maskIds.isEmpty()) {
            throw new IllegalArgumentException("image has no such mask");
        }
        final Bitmask imageMask = UnionMask.union(maskIds.stream()
                .map(omeroDao::getMask).map(ImageMask::new).collect(Collectors.toList()));
        final DataShape shape = getDataFromPixels(response, pixels, buffer ->  new DataShape(buffer, imageMask, 1))
                .adjustTileSize(chunkSizeAdjust, chunkSizeMin);
        if (response.ended()) {
            return;
        }
        /* package data for client */
        final ImmutableList.Builder<String> contents = ImmutableList.builder();
        contents.add(".zarray");
        contents.add(".zattrs");
        addChunkEntriesFlattened(contents, shape);
        respondWithDirectory(response, "Mask #" + roiId + " (flattened)", contents.build());
    }

    /**
     * Handle a request for the mask directory in nested mode.
     * @param response the HTTP server response to populate
     * @param parameters the parameters of the request to handle
     * @param parameters the parameters of the request to handle
     */
    private void returnMaskDirectoryNested(HttpServerResponse response, List<String> parameters) {
        /* parse parameters from path */
        final long imageId = Long.parseLong(parameters.get(0));
        final long roiId = Long.parseLong(parameters.get(1));
        returnMaskDirectoryNested(response, imageId, roiId, 0);
    }

    /**
     * Handle a request for the chunk directory in nested mode.
     * @param response the HTTP server response to populate
     * @param parameters the parameters of the request to handle
     */
    private void returnMaskChunkDirectory(HttpServerResponse response, List<String> parameters) {
        /* parse parameters from path */
        final long imageId = Long.parseLong(parameters.get(0));
        final long roiId = Long.parseLong(parameters.get(1));
        final List<Integer> chunkDir = getIndicesFromPath(parameters.get(2));
        if (chunkDir.size() >= 5) {
            throw new IllegalArgumentException("chunks must have five dimensions");
        }
        returnMaskDirectoryNested(response, imageId, roiId, chunkDir.size());
    }

    /**
     * Handle a request for the mask directory in nested mode.
     * @param response the HTTP server response to populate
     * @param roiId the ID of the mask being queried
     * @param depth how many directory levels inside the group
     */
    private void returnMaskDirectoryNested(HttpServerResponse response, long imageId, long roiId, int depth) {
        LOGGER.debug("providing directory listing for Mask:{}", roiId);
        /* gather data */
        final Roi roi = omeroDao.getRoi(roiId);
        if (roi == null || roi.getImage() == null || roi.getImage().getId() != imageId) {
            throw new IllegalArgumentException("image has no such mask");
        }
        final SortedSet<Long> maskIds = omeroDao.getMaskIdsOfRoi(roiId);
        if (maskIds.isEmpty()) {
            throw new IllegalArgumentException("image has no such mask");
        }
        final Pixels pixels = omeroDao.getPixels(imageId);
        final Bitmask imageMask = UnionMask.union(maskIds.stream()
                .map(omeroDao::getMask).map(ImageMask::new).collect(Collectors.toList()));
        final DataShape shape = getDataFromPixels(response, pixels, buffer ->  new DataShape(buffer, imageMask, 1))
                .adjustTileSize(chunkSizeAdjust, chunkSizeMin);
        if (response.ended()) {
            return;
        }
        /* package data for client */
        final ImmutableList.Builder<String> contents = ImmutableList.builder();
        if (depth == 0) {
            contents.add(".zarray");
            contents.add(".zattrs");
        }
        addChunkEntriesNested(contents, shape, depth);
        respondWithDirectory(response, "Mask #" + roiId, contents.build());
    }

    /**
     * Handle a request for the group directory in flattened mode.
     * @param response the HTTP server response to populate
     * @param parameters the parameters of the request to handle
     */
    private void returnMaskLabeledDirectoryFlattened(HttpServerResponse response, List<String> parameters) {
        /* parse parameters from path */
        final long imageId = Long.parseLong(parameters.get(0));
        LOGGER.debug("providing flattened directory listing for labeled Mask of Image:{}", imageId);
        final Map<Long, Bitmask> labeledMasks = getLabeledMasks(imageId);
        if (labeledMasks == null) {
            fail(response, 404, "the image for that id does not have a labeled mask");
            return;
        }
        /* gather data */
        final Pixels pixels = omeroDao.getPixels(imageId);
        final Collection<Bitmask> imageMasks = labeledMasks.values();
        final DataShape shape = getDataFromPixels(response, pixels, buffer ->  new DataShape(buffer, imageMasks, Long.BYTES))
                .adjustTileSize(chunkSizeAdjust, chunkSizeMin);
        if (response.ended()) {
            return;
        }
        /* package data for client */
        final ImmutableList.Builder<String> contents = ImmutableList.builder();
        contents.add(".zarray");
        contents.add(".zattrs");
        addChunkEntriesFlattened(contents, shape);
        respondWithDirectory(response, "Labeled Mask of Image #" + imageId + " (flattened)", contents.build());
    }

    /**
     * Handle a request for the mask directory in nested mode.
     * @param response the HTTP server response to populate
     * @param parameters the parameters of the request to handle
     * @param parameters the parameters of the request to handle
     */
    private void returnMaskLabeledDirectoryNested(HttpServerResponse response, List<String> parameters) {
        /* parse parameters from path */
        final long imageId = Long.parseLong(parameters.get(0));
        returnMaskLabeledDirectoryNested(response, imageId, 0);
    }

    /**
     * Handle a request for the chunk directory in nested mode.
     * @param response the HTTP server response to populate
     * @param parameters the parameters of the request to handle
     */
    private void returnMaskLabeledChunkDirectory(HttpServerResponse response, List<String> parameters) {
        /* parse parameters from path */
        final long imageId = Long.parseLong(parameters.get(0));
        final List<Integer> chunkDir = getIndicesFromPath(parameters.get(1));
        if (chunkDir.size() >= 5) {
            throw new IllegalArgumentException("chunks must have five dimensions");
        }
        returnMaskLabeledDirectoryNested(response, imageId, chunkDir.size());
    }

    /**
     * Handle a request for the mask directory in nested mode.
     * @param response the HTTP server response to populate
     * @param roiId the ID of the mask being queried
     * @param depth how many directory levels inside the group
     */
    private void returnMaskLabeledDirectoryNested(HttpServerResponse response, long imageId, int depth) {
        LOGGER.debug("providing directory listing for labeled Mask of Image:{}", imageId);
        final Map<Long, Bitmask> labeledMasks = getLabeledMasks(imageId);
        if (labeledMasks == null) {
            fail(response, 404, "the image for that id does not have a labeled mask");
            return;
        }
        /* gather data */
        final Pixels pixels = omeroDao.getPixels(imageId);
        final Collection<Bitmask> imageMasks = labeledMasks.values();
        final DataShape shape = getDataFromPixels(response, pixels, buffer ->  new DataShape(buffer, imageMasks, Long.BYTES))
                .adjustTileSize(chunkSizeAdjust, chunkSizeMin);
        if (response.ended()) {
            return;
        }
        /* package data for client */
        final ImmutableList.Builder<String> contents = ImmutableList.builder();
        if (depth == 0) {
            contents.add(".zarray");
            contents.add(".zattrs");
        }
        addChunkEntriesNested(contents, shape, depth);
        respondWithDirectory(response, "Labeled Mask of Image #" + imageId, contents.build());
    }

    /**
     * Handle a request for {@code .zgroup}.
     * @param response the HTTP server response to populate
     * @param parameters the parameters of the request to handle
     */
    private void returnGroup(HttpServerResponse response, List<String> parameters) {
        /* parse parameters from path */
        final long imageId = Long.parseLong(parameters.get(0));
        LOGGER.debug("providing .zgroup for Image:{}", imageId);
        /* package data for client */
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
     * Build OMERO metadata for {@link #returnImageAttrs(HttpServerResponse, long)} to include with a {@code "omero"} key.
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
     * Handle a request for {@code .zattrs} of an image.
     * @param response the HTTP server response to populate
     * @param parameters the parameters of the request to handle
     */
    private void returnImageAttrs(HttpServerResponse response, List<String> parameters) {
        /* parse parameters from path */
        final long imageId = Long.parseLong(parameters.get(0));
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
     * Handle a request for {@code .zattrs} of an image's masks.
     * @param response the HTTP server response to populate
     * @param parameters the parameters of the request to handle
     */
    private void returnImageMasksAttrs(HttpServerResponse response, List<String> parameters) {
        /* parse parameters from path */
        final long imageId = Long.parseLong(parameters.get(0));
        LOGGER.debug("providing .zattrs for Masks of Image:{}", imageId);
        /* gather data */
        final Collection<Long> roiIdsWithMask = getRoiIdsWithMask(imageId);
        final Map<Long, Bitmask> labeledMasks = roiIdsWithMask.isEmpty() ? null : getLabeledMasks(imageId);
        if (roiIdsWithMask.isEmpty()) {
            fail(response, 404, "no image masks for that id");
            return;
        }
        final List<String> masks = new ArrayList<>(roiIdsWithMask.size() + 1);
        if (labeledMasks != null) {
            masks.add("labeled");
        }
        for (final long roiId : roiIdsWithMask) {
            masks.add(Long.toString(roiId));
        }
        /* package data for client */
        final Map<String, Object> result = new HashMap<>();
        result.put("masks", masks);
        respondWithJson(response, new JsonObject(result));
    }

    /**
     * Return a color for the given set of masks if all those with a color concur on which.
     * @param maskIds some mask IDs
     * @return a color for the masks, or {@code null} if no mask sets a color or masks differ on the color
     */
    private Integer getConsensusColor(Iterable<Long> maskIds) {
        Integer roiColor = null;
        for (final long maskId : maskIds) {
            final Mask mask = omeroDao.getMask(maskId);
            final Integer maskColor = mask.getFillColor();
            if (maskColor != null) {
                if (roiColor == null) {
                    roiColor = maskColor;
                } else if (!roiColor.equals(maskColor)) {
                    return null;
                }
            }
        }
        return roiColor;
    }

    /**
     * Handle a request for {@code .zattrs} for a mask of an image.
     * @param response the HTTP server response to populate
     * @param parameters the parameters of the request to handle
     */
    private void returnMaskAttrs(HttpServerResponse response, List<String> parameters) {
        /* parse parameters from path */
        final long imageId = Long.parseLong(parameters.get(0));
        final long roiId = Long.parseLong(parameters.get(1));
        LOGGER.debug("providing .zattrs for Mask:{}", roiId);
        /* gather data */
        final Roi roi = omeroDao.getRoi(roiId);
        if (roi == null || roi.getImage() == null || roi.getImage().getId() != imageId) {
            throw new IllegalArgumentException("image has no such mask");
        }
        final SortedSet<Long> maskIds = omeroDao.getMaskIdsOfRoi(roiId);
        if (maskIds.isEmpty()) {
            throw new IllegalArgumentException("image has no such mask");
        }
        final Integer maskColor = getConsensusColor(maskIds);
        /* package data for client */
        final Map<String, Object> color = new HashMap<>();
        if (maskColor != null) {
            color.put(Boolean.toString(true), maskColor);
        }
        final Map<String, Object> image = new HashMap<>();
        image.put("array", "../../0/");
        final Map<String, Object> result = new HashMap<>();
        if (!color.isEmpty()) {
            result.put("color", color);
        }
        result.put("image", image);
        respondWithJson(response, new JsonObject(result));
    }

    /**
     * Provide a labeled mask for the given image. May be refused if masks overlap.
     * @param imageId an image ID
     * @return a labeled mask, or {@code null} if there are no masks or
     * overlapping masks with {@link Configuration#CONF_MASK_OVERLAP_VALUE} not set
     */
    private Map<Long, Bitmask> getLabeledMasks(long imageId) {
        final Collection<Long> roiIds = getRoiIdsWithMask(imageId);
        switch (roiIds.size()) {
        case 0:
            return null;
        case 1:
            return getLabeledMasksForCache(imageId);
        default:
            try {
                final Optional<Map<Long, Bitmask>> labeledMasks = labeledMaskCache.get(imageId);
                return labeledMasks.isPresent() ? labeledMasks.get() : null;
            } catch (ExecutionException ee) {
                LOGGER.warn("failed to get labeled masks for image {}", imageId, ee.getCause());
                return null;
            }
        }
    }

    /**
     * Provide a labeled mask for the given image. May be refused if masks overlap.
     * Intended to be used <em>only</em> by {@link #labeledMaskCache} because
     * other callers should benefit from the cache by using {@link #getLabeledMasks(long)}.
     * @param imageId an image ID
     * @return a labeled mask, or {@code null} if there are no masks or
     * overlapping masks with {@link Configuration#CONF_MASK_OVERLAP_VALUE} not set
     */
    private Map<Long, Bitmask> getLabeledMasksForCache(long imageId) {
        final List<Long> roiIds = new ArrayList<>();
        for (final long roiId : getRoiIdsWithMask(imageId)) {
            roiIds.add(roiId);
        }
        if (roiIds.isEmpty()) {
            return null;
        }
        final Map<Long, Bitmask> labeledMasks = new HashMap<>();
        for (final long roiId : roiIds) {
            final Collection<Long> maskIds = omeroDao.getMaskIdsOfRoi(roiId);
            final Bitmask imageMask = UnionMask.union(maskIds.stream()
                    .map(omeroDao::getMask).map(ImageMask::new).collect(Collectors.toList()));
            labeledMasks.put(roiId, imageMask);
        }
        if (maskOverlapValue == null) {
            /* Check that there are no overlaps. */
            for (final Map.Entry<Long, Bitmask> labeledMask1 : labeledMasks.entrySet()) {
                final long label1 = labeledMask1.getKey();
                final Bitmask mask1 = labeledMask1.getValue();
                if (mask1 instanceof ImageMask) {
                    final ImageMask imageMask1 = (ImageMask) mask1;
                    for (final Map.Entry<Long, Bitmask> labeledMask2 : labeledMasks.entrySet()) {
                        final long label2 = labeledMask2.getKey();
                        final Bitmask mask2 = labeledMask2.getValue();
                        if (label1 < label2) {
                            if (mask2 instanceof ImageMask) {
                                final ImageMask imageMask2 = (ImageMask) mask2;
                                if (imageMask2.isOverlap(imageMask1)) {
                                    return null;
                                }
                            } else if (mask2 instanceof UnionMask) {
                                final UnionMask unionMask2 = (UnionMask) mask2;
                                if (unionMask2.isOverlap(imageMask1)) {
                                    return null;
                                }
                            } else {
                                throw new IllegalStateException();
                            }
                        }
                    }
                } else if (mask1 instanceof UnionMask) {
                    final UnionMask unionMask1 = (UnionMask) mask1;
                    for (final Map.Entry<Long, Bitmask> labeledMask2 : labeledMasks.entrySet()) {
                        final long label2 = labeledMask2.getKey();
                        final Bitmask mask2 = labeledMask2.getValue();
                        if (label1 < label2) {
                            if (mask2 instanceof ImageMask) {
                                final ImageMask imageMask2 = (ImageMask) mask2;
                                if (unionMask1.isOverlap(imageMask2)) {
                                    return null;
                                }
                            } else if (mask2 instanceof UnionMask) {
                                final UnionMask unionMask2 = (UnionMask) mask2;
                                if (unionMask1.isOverlap(unionMask2)) {
                                    return null;
                                }
                            } else {
                                throw new IllegalStateException();
                            }
                        }
                    }
                } else {
                    throw new IllegalStateException();
                }
            }
        }
        return labeledMasks;
    }

    /**
     * Handle a request for {@code .zattrs} for the labeled mask of an image.
     * @param response the HTTP server response to populate
     * @param parameters the parameters of the request to handle
     */
    private void returnMaskLabeledAttrs(HttpServerResponse response, List<String> parameters) {
        /* parse parameters from path */
        final long imageId = Long.parseLong(parameters.get(0));
        LOGGER.debug("providing .zattrs for labeled Mask of Image:{}", imageId);
        /* gather data */
        final Map<Long, Bitmask> labeledMasks = getLabeledMasks(imageId);
        if (labeledMasks == null) {
            fail(response, 404, "the image for that id does not have a labeled mask");
            return;
        }
        final SortedMap<Long, Integer> maskColors = new TreeMap<>();
        for (final long roiId : getRoiIdsWithMask(imageId)) {
            final Collection<Long> maskIds = omeroDao.getMaskIdsOfRoi(roiId);
            final Integer maskColor = getConsensusColor(maskIds);
            if (maskColor != null) {
                maskColors.put(roiId, maskColor);
            }
        }
        /* package data for client */
        final Map<String, Integer> color = maskColors.entrySet().stream()
                .collect(Collectors.toMap(entry -> Long.toString(entry.getKey()), Map.Entry::getValue,
                        (v1, v2) -> { throw new IllegalStateException(); }, LinkedHashMap::new));
        final Map<String, Object> image = new HashMap<>();
        image.put("array", "../../0/");
        final Map<String, Object> result = new HashMap<>();
        if (!color.isEmpty()) {
            result.put("color", color);
        }
        result.put("image", image);
        respondWithJson(response, new JsonObject(result));
    }

    /**
     * Handle a request for {@code .zarray} for an image.
     * @param response the HTTP server response to populate
     * @param parameters the parameters of the request to handle
     */
    private void returnImageArray(HttpServerResponse response, List<String> parameters) {
        /* parse parameters from path */
        final long imageId = Long.parseLong(parameters.get(0));
        final int resolution = Integer.parseInt(parameters.get(1));
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
     * Handle a request for {@code .zarray} for a mask of an image.
     * @param response the HTTP server response to populate
     * @param parameters the parameters of the request to handle
     */
    private void returnMaskArray(HttpServerResponse response, List<String> parameters) {
        /* parse parameters from path */
        final long imageId = Long.parseLong(parameters.get(0));
        final long roiId = Long.parseLong(parameters.get(1));
        LOGGER.debug("providing .zarray for Mask:{}", roiId);
        /* gather data */
        final Roi roi = omeroDao.getRoi(roiId);
        if (roi == null || roi.getImage() == null || roi.getImage().getId() != imageId) {
            throw new IllegalArgumentException("image has no such mask");
        }
        final SortedSet<Long> maskIds = omeroDao.getMaskIdsOfRoi(roiId);
        if (maskIds.isEmpty()) {
            throw new IllegalArgumentException("image has no such mask");
        }
        /* gather data */
        final Pixels pixels = omeroDao.getPixels(imageId);
        final Bitmask imageMask = UnionMask.union(maskIds.stream()
                .map(omeroDao::getMask).map(ImageMask::new).collect(Collectors.toList()));
        final DataShape shape = getDataFromPixels(response, pixels, buffer ->  new DataShape(buffer, imageMask, 1))
                .adjustTileSize(chunkSizeAdjust, chunkSizeMin);
        if (response.ended()) {
            return;
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
        result.put("fill_value", false);
        result.put("dtype", "|b1");
        result.put("filters", null);
        result.put("compressor", compressor);
        respondWithJson(response, new JsonObject(result));
    }

    /**
     * Handle a request for {@code .zarray} for the labeled mask of an image.
     * @param response the HTTP server response to populate
     * @param parameters the parameters of the request to handle
     */
    private void returnMaskLabeledArray(HttpServerResponse response, List<String> parameters) {
        /* parse parameters from path */
        final long imageId = Long.parseLong(parameters.get(0));
        LOGGER.debug("providing .zarray for labeled Mask of Image:{}", imageId);
        /* gather data */
        final Map<Long, Bitmask> labeledMasks = getLabeledMasks(imageId);
        if (labeledMasks == null) {
            fail(response, 404, "the image for that id does not have a labeled mask");
            return;
        }
        /* gather data */
        final Pixels pixels = omeroDao.getPixels(imageId);
        final Collection<Bitmask> imageMasks = labeledMasks.values();
        final DataShape shape = getDataFromPixels(response, pixels, buffer ->  new DataShape(buffer, imageMasks, Long.BYTES))
                .adjustTileSize(chunkSizeAdjust, chunkSizeMin);
        if (response.ended()) {
            return;
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
        result.put("fill_value", false);
        result.put("dtype", ">u8");
        result.put("filters", null);
        result.put("compressor", compressor);
        respondWithJson(response, new JsonObject(result));
    }

    /**
     * Constructs tiles on request.
     * @author m.t.b.carroll@dundee.ac.uk
     * @since v0.1.7
     */
    private interface TileGetter {
        /**
         * Construct the tile at the given position.
         * @param x the leftmost extent of the tile
         * @param y the topmost extent of the tile
         * @param w the width of the tile
         * @param h the height of the tile
         * @return the tile
         * @throws IOException from the OMERO pixel buffer
         */
        byte[] getTile(int x, int y, int w, int h) throws IOException;
    }

    /**
     * Transfers pixel data from a tile into the given chunk array.
     * @param chunk the chunk into which to write
     * @param offset the byte offset from which to write
     * @param tileGetter the source of pixel data for the tile
     * @param shape the dimensionality of the chunk and the tile
     * @param x the leftmost extent of the tile
     * @param y the topmost extent of the tile
     * @throws IOException from {@link TileGetter#getTile(int, int, int, int)}
     */
    private static void getTileIntoChunk(byte[] chunk, int offset, TileGetter tileGetter, DataShape shape, int x, int y)
            throws IOException {
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
            final byte[] chunkSrc = tileGetter.getTile(x, y, xd, yd);
            /* must now assemble row-by-row into a plane in the chunk */
            for (int row = 0; row < yd; row++) {
                final int srcIndex = row * xd * shape.byteWidth;
                final int dstIndex = row * shape.xTile * shape.byteWidth + offset;
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
            final byte[] chunkSrc = tileGetter.getTile(x, y, shape.xTile, yd);
            /* simply copy into the plane */
            System.arraycopy(chunkSrc, 0, chunk, offset, chunkSrc.length);
        }
    }

    /**
     * Construct a tile getter that obtains pixel data from an OMERO pixel buffer.
     * @param buffer the pixel buffer
     * @param z the <em>Z</em> index of the plane from which to fetch pixel data
     * @param c the <em>C</em> index of the plane from which to fetch pixel data
     * @param t the <em>T</em> index of the plane from which to fetch pixel data
     * @return a tile getter for that plane
     */
    private TileGetter buildTileGetter(PixelBuffer buffer, int z, int c, int t) {
        return new TileGetter() {
            @Override
            public byte[] getTile(int x, int y, int w, int h) throws IOException {
                final PixelData tile = buffer.getTile(z, c, t, x, y, w, h);
                final byte[] tileData = tile.getData().array();
                tile.dispose();
                return tileData;
            }
        };
    }

    /**
     * Construct a tile getter for a split mask.
     * @param isMasked the source of mask data
     * @return a tile for the split mask, each pixel occupying one byte set to zero or non-zero
     */
    private TileGetter buildTileGetter(BiPredicate<Integer, Integer> isMasked) {
        return new TileGetter() {
            @Override
            public byte[] getTile(int x, int y, int w, int h) {
                final byte[] tile = new byte[w * h];
                int tilePosition = 0;
                for (int yCurr = y; yCurr < y + h; yCurr++) {
                    for (int xCurr = x; xCurr < x + w; xCurr++) {
                        if (isMasked.test(xCurr, yCurr)) {
                            tile[tilePosition] = -1;
                        }
                        tilePosition++;
                    }
                }
                return tile;
            }
        };
    }

    /**
     * Construct a tile getter for a labeled mask.
     * @param isMasked the source of mask data
     * @return a tile for the split mask, each pixel occupying {@link Long#BYTES} set to zero or the label
     */
    private TileGetter buildTileGetter(Map<Long, BiPredicate<Integer, Integer>> imageMasksByLabel) {
        return new TileGetter() {
            @Override
            public byte[] getTile(int x, int y, int w, int h) {
                final ByteBuffer tile = ByteBuffer.allocate(w * h * Long.BYTES);
                int tilePosition = 0;
                for (int yCurr = y; yCurr < y + h; yCurr++) {
                    for (int xCurr = x; xCurr < x + w; xCurr++) {
                        Long labelCurrent = null;
                        for (final Map.Entry<Long, BiPredicate<Integer, Integer>> imageMaskWithLabel :
                            imageMasksByLabel.entrySet()) {
                            final long label = imageMaskWithLabel.getKey();
                            final BiPredicate<Integer, Integer> isMasked = imageMaskWithLabel.getValue();
                            if (isMasked.test(xCurr, yCurr)) {
                                if (maskOverlapValue == null) {
                                    labelCurrent = label;
                                    break;
                                } if (labelCurrent == null) {
                                    labelCurrent = label;
                                } else {
                                    labelCurrent = maskOverlapValue;
                                    break;
                                }
                            }
                        }
                        if (labelCurrent != null) {
                            tile.putLong(tilePosition, labelCurrent);
                        }
                        tilePosition += Long.BYTES;
                    }
                }
                return tile.array();
            }
        };
    }

    /**
     * Handle a request for a chunk of the pixel data of an image.
     * @param response the HTTP server response to populate
     * @param parameters the parameters of the request to handle
     */
    private void returnImageChunk(HttpServerResponse response, List<String> parameters) {
        /* parse parameters from path */
        final long imageId = Long.parseLong(parameters.get(0));
        final int resolution = Integer.parseInt(parameters.get(1));
        final List<Integer> chunkId = getIndicesFromPath(parameters.get(2));
        if (chunkId.size() != 5) {
            throw new IllegalArgumentException("chunks must have five dimensions");
        }
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
                final TileGetter tileGetter = buildTileGetter(buffer, z + plane, c, t);
                getTileIntoChunk(chunk, planeOffset, tileGetter, shape, x, y);
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
     * Handle a request for a chunk of the mask of an image.
     * @param response the HTTP server response to populate
     * @param parameters the parameters of the request to handle
     */
    private void returnMaskChunk(HttpServerResponse response, List<String> parameters) {
        /* parse parameters from path */
        final long imageId = Long.parseLong(parameters.get(0));
        final long roiId = Long.parseLong(parameters.get(1));
        final List<Integer> chunkId = getIndicesFromPath(parameters.get(2));
        if (chunkId.size() != 5) {
            throw new IllegalArgumentException("chunks must have five dimensions");
        }
        LOGGER.debug("providing chunk {} of Mask:{}", chunkId, roiId);
        /* gather data */
        final Roi roi = omeroDao.getRoi(roiId);
        if (roi == null || roi.getImage() == null || roi.getImage().getId() != imageId) {
            throw new IllegalArgumentException("image has no such mask");
        }
        final SortedSet<Long> maskIds = omeroDao.getMaskIdsOfRoi(roiId);
        if (maskIds.isEmpty()) {
            throw new IllegalArgumentException("image has no such mask");
        }
        /* gather data */
        final byte[] chunk;
        try {
            final Pixels pixels = omeroDao.getPixels(imageId);
            final Bitmask imageMask = UnionMask.union(maskIds.stream()
                    .map(omeroDao::getMask).map(ImageMask::new).collect(Collectors.toList()));
            final DataShape shape = getDataFromPixels(response, pixels, buffer ->  new DataShape(buffer, imageMask, 1))
                    .adjustTileSize(chunkSizeAdjust, chunkSizeMin);
            if (response.ended()) {
                return;
            }
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
                final BiPredicate<Integer, Integer> isMasked = imageMask.getMaskReader(z + plane, c, t);
                if (isMasked != null)  {
                    final int planeOffset = plane * (chunk.length / shape.zTile);
                    final TileGetter tileGetter = buildTileGetter(isMasked);
                    getTileIntoChunk(chunk, planeOffset, tileGetter, shape, x, y);
                }
            }
        } catch (Exception e) {
            LOGGER.debug("pixel buffer failure", e);
            fail(response, 500, "query failed");
            return;
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
     * Handle a request for a chunk of the labeled mask of an image.
     * @param response the HTTP server response to populate
     * @param parameters the parameters of the request to handle
     */
    private void returnMaskLabeledChunk(HttpServerResponse response, List<String> parameters) {
        /* parse parameters from path */
        final long imageId = Long.parseLong(parameters.get(0));
        final List<Integer> chunkId = getIndicesFromPath(parameters.get(1));
        if (chunkId.size() != 5) {
            throw new IllegalArgumentException("chunks must have five dimensions");
        }
        LOGGER.debug("providing chunk {} of labeled Mask of Image:{}", chunkId, imageId);
        /* gather data */
        final Map<Long, Bitmask> labeledMasks = getLabeledMasks(imageId);
        if (labeledMasks == null) {
            fail(response, 404, "the image for that id does not have a labeled mask");
            return;
        }
        final byte[] chunk;
        try {
            final Pixels pixels = omeroDao.getPixels(imageId);
            final Collection<Bitmask> imageMasks = labeledMasks.values();
            final DataShape shape = getDataFromPixels(response, pixels, buffer ->  new DataShape(buffer, imageMasks, Long.BYTES))
                    .adjustTileSize(chunkSizeAdjust, chunkSizeMin);
            if (response.ended()) {
                return;
            }
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
                final Map<Long, BiPredicate<Integer, Integer>> applicableMasks = new HashMap<>();
                for (final Map.Entry<Long, Bitmask> labeledMask : labeledMasks.entrySet()) {
                    final long label = labeledMask.getKey();
                    final Bitmask mask = labeledMask.getValue();
                    final BiPredicate<Integer, Integer> isMasked = mask.getMaskReader(z + plane, c, t);
                    if (isMasked != null) {
                        applicableMasks.put(label, isMasked);
                    }
                }
                if (!applicableMasks.isEmpty()) {
                    final int planeOffset = plane * (chunk.length / shape.zTile);
                    final TileGetter tileGetter = buildTileGetter(applicableMasks);
                    getTileIntoChunk(chunk, planeOffset, tileGetter, shape, x, y);
                }
            }
        } catch (Exception e) {
            LOGGER.debug("pixel buffer failure", e);
            fail(response, 500, "query failed");
            return;
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
