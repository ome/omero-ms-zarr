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

import ome.io.nio.PixelBuffer;
import ome.io.nio.PixelsService;
import ome.model.core.Pixels;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.collect.ImmutableMap;

import org.hibernate.Query;
import org.hibernate.SessionFactory;
import org.hibernate.classic.Session;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

/**
 * Check that the {@link PixelBufferCache} behaves as intended and that it calls
 * {@link PixelsService#getPixelBuffer(Pixels, boolean)} no more than expected.
 * @author m.t.b.carroll@dundee.ac.uk
 */
public class PixelBufferCacheTest {

    /* How many resolution levels mock images should have. */
    private static final int RESOLUTION_LEVELS = 3;

    /* How many pixel buffers to cache. */
    private static final int CACHE_SIZE = 20;

    @Mock
    private Query query;

    @Mock
    private Session sessionMock;

    @Mock
    private SessionFactory sessionFactoryMock;

    @Mock
    private PixelsService pixelsServiceMock;

    private PixelBufferCache cache;

    /**
     * Set up the pixel buffer cache atop mock services.
     */
    @BeforeEach
    public void mockSetup() {
        MockitoAnnotations.initMocks(this);
        Mockito.when(query.uniqueResult()).thenReturn(new Pixels());
        Mockito.when(sessionMock.createQuery(Mockito.anyString())).thenReturn(query);
        Mockito.when(sessionFactoryMock.openSession()).thenReturn(sessionMock);
        Mockito.when(pixelsServiceMock.getPixelBuffer(Mockito.any(Pixels.class), Mockito.eq(false))).thenAnswer(
                new Answer<PixelBuffer>() {
            @Override
            public PixelBuffer answer(InvocationOnMock invocation) {
                /* Construct a new mock buffer each time to make them distinguishable */
                final PixelBuffer buffer = Mockito.mock(PixelBuffer.class);
                Mockito.when(buffer.getResolutionLevels()).thenReturn(RESOLUTION_LEVELS);
                return buffer;
            }});
        final Map<String, String> settings = ImmutableMap.of(Configuration.CONF_BUFFER_CACHE_SIZE, Integer.toString(CACHE_SIZE));
        final Configuration configuration = new Configuration(settings);
        final OmeroDao dao = new OmeroDao(sessionFactoryMock);
        cache = new PixelBufferCache(configuration, pixelsServiceMock, dao);
    }

    /**
     * Check that the pixel buffer accepts the configured size.
     */
    @Test
    public void testCacheSizeConfiguration() {
        Assertions.assertEquals(CACHE_SIZE, cache.getCapacity());
    }

    /**
     * Check that a pixel buffer is returned for valid resolutions and {@code null} for an invalid resolution.
     */
    @Test
    public void testValidResolutions() {
        int resolution;
        for (resolution = 0; resolution < RESOLUTION_LEVELS; resolution++) {
            final PixelBuffer buffer = cache.getPixelBuffer(1, resolution);
            Assertions.assertNotNull(buffer);
            cache.releasePixelBuffer(buffer);
        }
        final PixelBuffer buffer = cache.getPixelBuffer(1, resolution);
        Assertions.assertNull(buffer);
        /* Check that the use of mocks was as expected. */
        Mockito.verify(pixelsServiceMock,
                Mockito.times(RESOLUTION_LEVELS + 1)).getPixelBuffer(Mockito.any(Pixels.class), Mockito.eq(false));
    }

    /**
     * For various images and resolutions check that their same corresponding buffer is returned on later cache queries.
     */
    @Test
    public void testReuse() {
        Assertions.assertTrue(cache.getCapacity() >= 4);
        Assertions.assertTrue(RESOLUTION_LEVELS >= 2);
        final Map<String, PixelBuffer> buffers = new HashMap<>();
        final int repeats = 3;
        for (int i = 0; i < repeats; i++) {
            for (int image = 0; image < 2; image++) {
                for (int resolution = 0; resolution < 2; resolution++) {
                    final String key = String.format("%d:%d", image, resolution);
                    final PixelBuffer expectedBuffer = buffers.get(key);
                    final PixelBuffer actualBuffer = cache.getPixelBuffer(image, resolution);
                    Assertions.assertNotNull(actualBuffer);
                    if (expectedBuffer == null) {
                        buffers.put(key, actualBuffer);
                    } else {
                        Assertions.assertEquals(expectedBuffer, actualBuffer);
                    }
                    cache.releasePixelBuffer(actualBuffer);
                }
            }
        }
        Assertions.assertEquals(4, buffers.size());
        /* Check that the use of mocks was as expected. */
        Mockito.verify(pixelsServiceMock, Mockito.times(4)).getPixelBuffer(Mockito.any(Pixels.class), Mockito.eq(false));
    }

    /**
     * Check that the cache expires only those buffers that were fetched the longest time ago.
     * @throws IOException unexpected
     */
    @Test
    public void testExhaustion() throws IOException {
        final int bufferCapacity = cache.getCapacity();
        final int expiryCount = 4;
        Assertions.assertTrue(bufferCapacity >= expiryCount * 2);
        final int imageCount = bufferCapacity + expiryCount;
        final int totalFetches = imageCount + expiryCount;
        /* Fetch an image for every available buffer cache slot. */
        final List<PixelBuffer> originalBuffers = new ArrayList<>();
        for (int image = 0; image < bufferCapacity; image++) {
            final PixelBuffer actualBuffer = cache.getPixelBuffer(image, 0);
            Assertions.assertFalse(originalBuffers.contains(actualBuffer));
            originalBuffers.add(actualBuffer);
            cache.releasePixelBuffer(actualBuffer);
        }
        /* Re-fetch half of the images, those should not be among those whose buffers expire subsequently. */
        Assertions.assertEquals(bufferCapacity, new HashSet<>(originalBuffers).size());
        for (int image = 0; image < bufferCapacity; image += 2) {
            final PixelBuffer actualBuffer = cache.getPixelBuffer(image, 0);
            final PixelBuffer expectedBuffer = originalBuffers.get(image);
            Assertions.assertEquals(expectedBuffer, actualBuffer);
            cache.releasePixelBuffer(actualBuffer);
        }
        /* Figure which images' buffers should now expire. */
        final Set<Integer> imagesExpired = new HashSet<>();
        for (int image = 1; imagesExpired.size() < expiryCount; image += 2) {
            imagesExpired.add(image);
        }
        /* Fetch some more images, that should expire some buffers from the other initial half. */
        for (int image = bufferCapacity; image < imageCount; image++) {
            final PixelBuffer actualBuffer = cache.getPixelBuffer(image, 0);
            Assertions.assertFalse(originalBuffers.contains(actualBuffer));
            originalBuffers.add(image, actualBuffer);
            cache.releasePixelBuffer(actualBuffer);
        }
        /* Check that for the non-expired buffers the original one is still returned. */
        for (int image = 0; image < imageCount; image++) {
            if (!imagesExpired.contains(image)) {
                final PixelBuffer actualBuffer = cache.getPixelBuffer(image, 0);
                final PixelBuffer expectedBuffer = originalBuffers.get(image);
                Assertions.assertEquals(expectedBuffer, actualBuffer);
                cache.releasePixelBuffer(actualBuffer);
            }
        }
        /* Check that for the expired buffers a new one is returned. */
        for (final int image : imagesExpired) {
            final PixelBuffer actualBuffer = cache.getPixelBuffer(image, 0);
            Assertions.assertFalse(originalBuffers.contains(actualBuffer));
            cache.releasePixelBuffer(actualBuffer);
        }
        /* Check that the use of mocks was as expected. */
        Mockito.verify(pixelsServiceMock, Mockito.times(totalFetches)).getPixelBuffer(Mockito.any(Pixels.class), Mockito.eq(false));
        for (int image = 0; image < expiryCount * 2; image++) {
            /* The even-numbered images' buffers were expired just above in replacing the odd-numbered's already-expired ones. */
            final PixelBuffer buffer = originalBuffers.get(image);
            Mockito.verify(buffer, Mockito.times(1)).close();
        }
        for (int image = expiryCount * 2; image < imageCount; image++) {
            /* The images' buffers falling later in the above loops remain unexpired. */
            final PixelBuffer buffer = originalBuffers.get(image);
            Mockito.verify(buffer, Mockito.never()).close();
        }
    }
}
