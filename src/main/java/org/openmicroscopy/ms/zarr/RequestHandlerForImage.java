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

import java.io.IOException;

import org.hibernate.Query;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;

import com.google.common.collect.ImmutableMap;

import io.vertx.core.Handler;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import ome.io.nio.PixelBuffer;
import ome.io.nio.PixelsService;
import ome.model.core.Pixels;

/**
 * Provide system role IDs from JDBC to HTTP endpoint.
 * @author m.t.b.carroll@dundee.ac.uk
 */
public class RequestHandlerForImage implements Handler<RoutingContext> {

    private final PixelsService pixelsService;
    private final SessionFactory sessionFactory;

    public RequestHandlerForImage(SessionFactory sessionFactory, PixelsService pixelsService) {
        this.sessionFactory = sessionFactory;
        this.pixelsService = pixelsService;
    }

    /**
     * Add this as both GET and POST handler for the given path.
     * @param router the router for which this can handle requests
     * @param path the path on which those requests come
     */
    public void handleFor(Router router, String path) {
        router.get(path).handler(this);
        router.post(path).handler(BodyHandler.create()).handler(this);
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
     * Handle incoming requests that query OMERO system user and group IDs.
     * @param context the routing context
     */
    @Override
    public void handle(RoutingContext context) {
        final HttpServerRequest request = context.request();
        final HttpServerResponse response = request.response();
        /* get image ID from query */
        final Long imageId;
        switch (request.method()) {
            case GET:
                final String paramIdText = request.getParam("id");
                if (paramIdText == null) {
                    fail(response, 400, "must provide id parameter");
                    return;
                }
                try {
                    imageId = Long.parseLong(paramIdText);
                } catch (NumberFormatException nfe) {
                    fail(response, 400, "id must be an integer");
                    return;
                }
                break;
            case POST:
                final JsonObject body = context.getBodyAsJson();
                try {
                    imageId = body.getLong("id");
                } catch (ClassCastException cce) {
                    fail(response, 400, "id must be an integer");
                    return;
                }
                if (imageId == null) {
                    fail(response, 400, "must provide id parameter");
                    return;
                }
                break;
            default:
                fail(response, 404, "unknown path for this method");
                return;
        }
        final int x, y, z, c, t;
        Session session = null;
        PixelBuffer buffer = null;
        try {
            session = sessionFactory.openSession();
            session.setDefaultReadOnly(true);
            final Transaction tx = session.beginTransaction();
            final Query query = session.createQuery(
                    "SELECT p FROM Pixels AS p " +
                    "JOIN FETCH p.pixelsType " +
                    "LEFT OUTER JOIN FETCH p.channels AS c " +
                    "LEFT OUTER JOIN FETCH c.logicalChannel AS lc " +
                    "LEFT OUTER JOIN FETCH c.statsInfo " +
                    "WHERE p.image.id = ?");
            query.setParameter(0, imageId);
            final Pixels pixels = (Pixels) query.uniqueResult();
            tx.rollback();
            session.close();
            session = null;
            if (pixels == null) {
                fail(response, 404, "no image for that id");
                return;
            }
            buffer = pixelsService.getPixelBuffer(pixels, false);
            x = buffer.getSizeX();
            y = buffer.getSizeY();
            z = buffer.getSizeZ();
            c = buffer.getSizeC();
            t = buffer.getSizeT();
            buffer.close();
            buffer = null;
        } catch (Exception e) {
            e.printStackTrace();
            fail(response, 500, "query failed");
            return;
        } finally {
            if (session != null) {
                session.close();
            }
            if (buffer != null) {
                try {
                    buffer.close();
                } catch (IOException e) {
                    /* probably already failing anyway */
                }
            }
        }
        /* return ID in JSON by HTTP */
        final ImmutableMap.Builder<String, Object> result = ImmutableMap.builder();
        result.put("X", x);
        result.put("Y", y);
        if (z != 1) {
            result.put("Z", z);
        }
        if (c != 1) {
            result.put("C", c);
        }
        if (t != 1) {
            result.put("T", t);
        }
        final JsonObject responseJson = new JsonObject(result.build());
        final String responseText = responseJson.toString();
        response.putHeader("Content-Type", "application/json; charset=utf-8");
        response.putHeader("Content-Length", Integer.toString(responseText.length()));
        response.end(responseText);
    }
}
