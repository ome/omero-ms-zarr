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

import org.openmicroscopy.ms.zarr.stub.PixelBufferFake;
import org.openmicroscopy.ms.zarr.stub.RouterFake;

import ome.io.nio.PixelBuffer;
import ome.io.nio.PixelsService;
import ome.model.core.Channel;
import ome.model.core.Image;
import ome.model.core.LogicalChannel;
import ome.model.core.Pixels;
import ome.model.display.ChannelBinding;
import ome.model.display.RenderingDef;
import ome.model.enums.Family;
import ome.model.enums.RenderingModel;
import ome.model.internal.Details;
import ome.model.meta.Experimenter;
import ome.model.stats.StatsInfo;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;

import org.hibernate.Query;
import org.hibernate.SessionFactory;
import org.hibernate.classic.Session;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.provider.Arguments;

import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

/**
 * Base class providing mocks, fakes and utilities for testing microservice endpoints.
 * @author m.t.b.carroll@dundee.ac.uk
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class ZarrEndpointsTestBase {

    protected static final String MEDIA_TYPE_BINARY = "application/octet-stream";
    protected static final String MEDIA_TYPE_JSON = "application/json; charset=utf-8";

    protected static final String URI_PATH_PREFIX = "test";

    @Mock
    private Query query;

    @Mock
    private Session sessionMock;

    @Mock
    private SessionFactory sessionFactoryMock;

    protected PixelBuffer pixelBuffer = new PixelBufferFake();

    @Mock
    private PixelsService pixelsServiceMock;

    @Mock
    private HttpServerRequest httpRequest;

    @Mock
    private HttpServerResponse httpResponse;

    private Router router;

    /* A note of property values for later comparison. */
    protected String imageName;
    protected String channelName1, channelName2, channelName3;
    protected int defaultZ, defaultT;

    /**
     * Set up a mock pixels object from which to source OMERO metadata.
     * @return a mock pixels object
     */
    private Pixels constructMockPixels() {
        /* Create skeleton objects. */
        final Channel channel1 = new Channel(1L, true);
        final Channel channel2 = new Channel(2L, true);
        final Channel channel3 = new Channel(3L, true);
        final LogicalChannel logicalChannel1 = new LogicalChannel(1L, true);
        final LogicalChannel logicalChannel2 = new LogicalChannel(2L, true);
        final LogicalChannel logicalChannel3 = new LogicalChannel(3L, true);
        logicalChannel1.setName("Белобог");
        logicalChannel2.setName("Чернобог");
        logicalChannel3.setName("Марена");
        channel1.setLogicalChannel(logicalChannel1);
        channel2.setLogicalChannel(logicalChannel2);
        channel3.setLogicalChannel(logicalChannel3);
        final ChannelBinding binding1 = new ChannelBinding(1L, true);
        final ChannelBinding binding2 = new ChannelBinding(2L, true);
        final ChannelBinding binding3 = new ChannelBinding(3L, true);
        final Family family = new Family(Family.VALUE_LINEAR);
        for (final ChannelBinding binding : Arrays.asList(binding1, binding2, binding3)) {
            binding.setActive(true);
            binding.setCoefficient(1.0);
            binding.setFamily(family);
            binding.setInputStart(100.0);
            binding.setInputEnd(1000.0);
        }
        binding1.setRed(255); binding1.setGreen(0); binding1.setBlue(0);
        binding2.setRed(0); binding2.setGreen(255); binding2.setBlue(0);
        binding3.setRed(0); binding3.setGreen(0); binding3.setBlue(255);
        final StatsInfo range = new StatsInfo(0.0, 65535.0);
        channel1.setStatsInfo(range);
        channel2.setStatsInfo(range);
        channel3.setStatsInfo(range);
        final Experimenter owner = new Experimenter(1L, false);
        final Image image = new Image(1L, true);
        image.setName("test image for " + getClass().getSimpleName());
        /* Construct mock objects. */
        final Pixels pixels = Mockito.mock(Pixels.class);
        final RenderingDef rdef = Mockito.mock(RenderingDef.class);
        final Details details = Mockito.mock(Details.class);
        Mockito.when(pixels.getImage()).thenReturn(image);
        Mockito.when(pixels.getDetails()).thenReturn(details);
        Mockito.when(pixels.iterateSettings()).thenReturn(Collections.singleton(rdef).iterator());
        Mockito.when(pixels.sizeOfChannels()).thenReturn(3);
        Mockito.when(pixels.getChannel(Mockito.eq(0))).thenReturn(channel1);
        Mockito.when(pixels.getChannel(Mockito.eq(1))).thenReturn(channel2);
        Mockito.when(pixels.getChannel(Mockito.eq(2))).thenReturn(channel3);
        Mockito.when(rdef.getDetails()).thenReturn(details);
        Mockito.when(rdef.sizeOfWaveRendering()).thenReturn(3);
        Mockito.when(rdef.getChannelBinding(Mockito.eq(0))).thenReturn(binding1);
        Mockito.when(rdef.getChannelBinding(Mockito.eq(1))).thenReturn(binding2);
        Mockito.when(rdef.getChannelBinding(Mockito.eq(2))).thenReturn(binding3);
        Mockito.when(rdef.getDefaultZ()).thenReturn(pixelBuffer.getSizeZ() / 2);
        Mockito.when(rdef.getDefaultT()).thenReturn(pixelBuffer.getSizeT() / 2);
        Mockito.when(rdef.getModel()).thenReturn(new RenderingModel(RenderingModel.VALUE_RGB));
        Mockito.when(details.getOwner()).thenReturn(owner);
        /* Note property values for later comparison. */
        imageName = image.getName();
        channelName1 = channel1.getLogicalChannel().getName();
        channelName2 = channel2.getLogicalChannel().getName();
        channelName3 = channel3.getLogicalChannel().getName();
        defaultZ = rdef.getDefaultZ();
        defaultT = rdef.getDefaultT();
        Assertions.assertEquals(3, ImmutableSet.of(channelName1, channelName2, channelName3).size());
        Assertions.assertNotEquals(defaultZ, defaultT);
        return pixels;
    }

    /**
     * Set up the HTTP request handler atop mock services. Can be used to reset the mocks in between requests.
     * @throws IOException unexpected
     */
    @BeforeEach
    protected void mockSetup() throws IOException {
        MockitoAnnotations.initMocks(this);
        final Pixels pixels = constructMockPixels();
        Mockito.when(query.uniqueResult()).thenReturn(pixels);
        Mockito.when(query.setParameter(Mockito.eq(0), Mockito.anyLong())).thenReturn(query);
        Mockito.when(sessionMock.createQuery(Mockito.anyString())).thenReturn(query);
        Mockito.when(sessionFactoryMock.openSession()).thenReturn(sessionMock);
        Mockito.when(pixelsServiceMock.getPixelBuffer(Mockito.any(Pixels.class), Mockito.eq(false))).thenReturn(pixelBuffer);
        Mockito.when(httpRequest.method()).thenReturn(HttpMethod.GET);
        Mockito.when(httpRequest.response()).thenReturn(httpResponse);
        final String URI = URI_PATH_PREFIX + '/' + Configuration.PLACEHOLDER_IMAGE_ID + '/';
        final Map<String, String> settings = ImmutableMap.of(Configuration.CONF_NET_PATH_IMAGE, URI);
        final Configuration configuration = new Configuration(settings);
        final OmeroDao dao = new OmeroDao(sessionFactoryMock);
        final PixelBufferCache cache = new PixelBufferCache(configuration, pixelsServiceMock, dao);
        final HttpHandler handler = new RequestHandlerForImage(configuration, pixelsServiceMock, cache, dao);
        router = new RouterFake();
        handler.handleFor(router);
    }

    /**
     * Construct an endpoint URI for the given query arguments.
     * @param query the query arguments for the microservice
     * @return the URI at which the answer is found
     */
    private static String getUriPath(Object... query) {
        final StringBuilder path = new StringBuilder(URI_PATH_PREFIX);
        for (final Object element : query) {
            path.append('/');
            path.append(element);
        }
        return path.toString();
    }

    /**
     * Obtain a JSON object from the mock HTTP response.
     * @param query arguments for the microservice query
     * @return the microservice's response as JSON
     */
    protected JsonObject getResponseAsJson(Object... query) {
        Mockito.when(httpRequest.path()).thenReturn(getUriPath(query));
        router.handle(httpRequest);
        final ArgumentCaptor<String> responseLengthCaptor = ArgumentCaptor.forClass(String.class);
        final ArgumentCaptor<String> responseContentCaptor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(httpResponse, Mockito.never()).setStatusCode(Mockito.anyInt());
        Mockito.verify(httpResponse, Mockito.times(1)).putHeader(Mockito.eq("Content-Type"), Mockito.eq(MEDIA_TYPE_JSON));
        Mockito.verify(httpResponse, Mockito.times(1)).putHeader(Mockito.eq("Content-Length"), responseLengthCaptor.capture());
        Mockito.verify(httpResponse, Mockito.times(1)).end(responseContentCaptor.capture());
        final int responseLength = Integer.parseInt(responseLengthCaptor.getValue());
        final String responseContent = responseContentCaptor.getValue();
        Assertions.assertEquals(responseLength, responseContent.length());
        return new JsonObject(responseContent);
    }

    /**
     * Obtain a byte array from the mock HTTP response.
     * @param query arguments for the microservice query
     * @return the microservice's response as bytes
     */
    protected byte[] getResponseAsBytes(Object... query) {
        Mockito.when(httpRequest.path()).thenReturn(getUriPath(query));
        router.handle(httpRequest);
        final ArgumentCaptor<String> responseLengthCaptor = ArgumentCaptor.forClass(String.class);
        final ArgumentCaptor<Buffer> responseContentCaptor = ArgumentCaptor.forClass(Buffer.class);
        Mockito.verify(httpResponse, Mockito.times(1)).putHeader(Mockito.eq("Content-Type"), Mockito.eq(MEDIA_TYPE_BINARY));
        Mockito.verify(httpResponse, Mockito.times(1)).putHeader(Mockito.eq("Content-Length"), responseLengthCaptor.capture());
        Mockito.verify(httpResponse, Mockito.times(1)).end(responseContentCaptor.capture());
        final int responseLength = Integer.parseInt(responseLengthCaptor.getValue());
        final Buffer responseContent = responseContentCaptor.getValue();
        Assertions.assertEquals(responseLength, responseContent.length());
        return responseContent.getBytes();
    }

    /**
     * Provide arguments for tests iterating over the groups.
     * @return the pixel buffer resolutions, URI path components and any scales for the testable groups
     * @throws IOException unexpected
     */
    protected Stream<Arguments> provideGroupDetails() throws IOException {
        mockSetup();
        final Stream.Builder<Arguments> details = Stream.builder();
        final JsonObject response = getResponseAsJson(0, ".zattrs");
        final JsonArray multiscales = response.getJsonArray("multiscales");
        final JsonObject multiscale = multiscales.getJsonObject(0);
        final JsonArray datasets = multiscale.getJsonArray("datasets");
        int resolution = pixelBuffer.getResolutionLevels();
        for (final Object element : datasets) {
            final JsonObject dataset = (JsonObject) element;
            details.add(Arguments.of(--resolution, dataset.getString("path"), dataset.getDouble("scale")));
        }
        return details.build();
    }

    /**
     * Uncompress the given byte array.
     * @param compressed a byte array
     * @return the uncompressed bytes
     * @throws DataFormatException unexpected
     */
    protected static byte[] uncompress(byte[] compressed) throws DataFormatException {
        final Inflater inflater = new Inflater();
        inflater.setInput(compressed);
        final Buffer uncompressed = Buffer.factory.buffer(2 * compressed.length);
        final byte[] batch = new byte[8192];
        int batchSize;
        do {
            batchSize = inflater.inflate(batch);
            uncompressed.appendBytes(batch, 0, batchSize);
        } while (batchSize > 0);
        Assertions.assertFalse(inflater.needsDictionary());
        inflater.end();
        return uncompressed.getBytes();
    }
}
