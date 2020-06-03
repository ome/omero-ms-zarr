package org.openmicroscopy.ms.zarr;

import ome.io.nio.PixelBuffer;
import ome.io.nio.PixelsService;
import ome.model.core.Pixels;

import java.io.IOException;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cache open {@link PixelBuffer} instances given the likelihood of repeated calls.
 * @author m.t.b.carroll@dundee.ac.uk
 */
class PixelBufferCache {

    private static final Logger LOGGER = LoggerFactory.getLogger(PixelBufferCache.class);

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

    /* How many pixel buffers to keep open. Different resolutions count as different buffers. */
    private final int capacity;

    private final PixelsService pixelsService;
    private final OmeroDao omeroDao;

    private Deque<Entry> cache = new LinkedList<>();
    private Map<PixelBuffer, Integer> references = new HashMap<>();

    /**
     * Construct a new pixel buffer cache.
     * @param configuration the configuration of this microservice
     * @param pixelsService the OMERO pixels service
     * @param omeroDao the OMERO data access object
     */
    PixelBufferCache(Configuration configuration, PixelsService pixelsService, OmeroDao omeroDao) {
        this.capacity = configuration.getBufferCacheSize();
        this.pixelsService = pixelsService;
        this.omeroDao = omeroDao;

        LOGGER.info("configured with capacity {}", this.capacity);
    }

    /**
     * @return the capacity of this cache
     */
    int getCapacity() {
        return capacity;
    }

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
        LOGGER.debug("fetch pixel buffer for resolution {} of Image:{}", resolution, imageId);
        for (final Entry entry : cache) {
            if (entry.imageId == imageId && entry.resolution == resolution) {
                /* a cache hit */
                LOGGER.debug("cache hit");
                cache.remove(entry);
                cache.addFirst(entry);
                final int count = references.get(entry.buffer);
                references.put(entry.buffer, count + 1);
                return entry.buffer;
            }
        }
        /* a cache miss */
        LOGGER.debug("cache miss");
        if (cache.size() == capacity) {
            /* make room for the new cache entry */
            LOGGER.debug("expiring oldest entry");
            final Entry entry = cache.removeLast();
            releasePixelBuffer(entry.buffer);
        }
        /* open the buffer at the desired resolution */
        final Pixels pixels = omeroDao.getPixels(imageId);
        if (pixels == null) {
            LOGGER.debug("failed to fetch pixels");
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
            LOGGER.debug("no such resolution, only {} available", resolutions);
            return null;
        }
        buffer.setResolutionLevel(resolutions - resolution - 1);
        /* enter the buffer into the cache */
        final Entry entry = new Entry(imageId, resolution, buffer);
        cache.addFirst(entry);
        final int count = references.getOrDefault(entry.buffer, 0);
        references.put(entry.buffer, count + 2);
        LOGGER.debug("recorded new entry");
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
