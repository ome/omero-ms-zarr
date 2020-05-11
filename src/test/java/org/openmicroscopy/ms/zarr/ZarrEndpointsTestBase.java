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

import ome.io.nio.PixelBuffer;
import ome.io.nio.PixelsService;
import ome.model.core.Image;
import ome.model.core.Pixels;
import ome.model.internal.Details;
import ome.model.meta.Experimenter;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.stream.Stream;

import com.google.common.collect.ImmutableMap;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;

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

    @Mock
    private RoutingContext context;

    private RequestHandlerForImage handler;

    /**
     * Set up a mock pixels object from which to source OMERO metadata.
     * @return a mock pixels object
     */
    private static Pixels constructMockPixels() {
        final Pixels pixels = Mockito.mock(Pixels.class);
        final Image image = Mockito.mock(Image.class);
        final Details details = Mockito.mock(Details.class);
        final Experimenter owner = new Experimenter(1L, false);
        Mockito.when(pixels.getImage()).thenReturn(image);
        Mockito.when(pixels.getDetails()).thenReturn(details);
        Mockito.when(pixels.iterateSettings()).thenReturn(Collections.emptyIterator());
        Mockito.when(image.getId()).thenReturn(1L);
        Mockito.when(image.getName()).thenReturn("test image");
        Mockito.when(details.getOwner()).thenReturn(owner);
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
        Mockito.when(sessionMock.createQuery(Mockito.anyString())).thenReturn(query);
        Mockito.when(sessionFactoryMock.openSession()).thenReturn(sessionMock);
        Mockito.when(pixelsServiceMock.getPixelBuffer(Mockito.any(Pixels.class), Mockito.eq(false))).thenReturn(pixelBuffer);
        Mockito.when(httpRequest.method()).thenReturn(HttpMethod.GET);
        Mockito.when(httpRequest.response()).thenReturn(httpResponse);
        Mockito.when(context.request()).thenReturn(httpRequest);
        final String URI = URI_PATH_PREFIX + '/' + Configuration.PLACEHOLDER_IMAGE_ID + '/';
        final Map<String, String> configuration = ImmutableMap.of(Configuration.CONF_NET_PATH_IMAGE, URI);
        handler = new RequestHandlerForImage(new Configuration(configuration), sessionFactoryMock, pixelsServiceMock);
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
        handler.handle(context);
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
        handler.handle(context);
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
        Mockito.when(httpRequest.path()).thenReturn(getUriPath(0, ".zattrs"));
        handler.handle(context);
        final JsonObject response = getResponseAsJson();
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
}
