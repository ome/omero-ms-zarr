/*
 * Copyright (C) 2018 University of Dundee & Open Microscopy Environment.
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

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import com.google.common.collect.ImmutableMap;

import io.vertx.core.Handler;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;

/**
 * Provide system role IDs from JDBC to HTTP endpoint.
 * @author m.t.b.carroll@dundee.ac.uk
 */
public class RequestHandlerForId implements Handler<RoutingContext> {

    private final Connection connection;

    public RequestHandlerForId(Connection connection) {
        this.connection = connection;
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
        /* get role and type from query */
        final String role, type;
        switch (request.method()) {
            case GET:
                role = request.getParam("role");
                type = request.getParam("type");
                break;
            case POST:
                final JsonObject body = context.getBodyAsJson();
                role = body.getString("role");
                type = body.getString("type");
                break;
            default:
                fail(response, 404, "unknown path for this method");
                return;
        }
        /* validate query parameters */
        if (role == null || type == null) {
            fail(response, 400, "must provide both role and type parameters");
            return;
        }
        for (final char character : (role + type).toCharArray()) {
            if (!Character.isLetter(character)) {
                fail(response, 400, "role and type must each be a single word");
                return;
            }
        }
        /* query ID from database */
        final long id;
        try (final Statement statement = connection.createStatement()) {
            final ResultSet rows = statement.executeQuery("SELECT " + role + "_" + type + "_id FROM _roles");
            if (rows.next()) {
                id = rows.getLong(1);
            } else {
                fail(response, 500, "database query returned no data");
                return;
            }
        } catch (SQLException sqle) {
            fail(response, 500, "database query failed");
            return;
        }
        /* return ID in JSON by HTTP */
        final JsonObject responseJson = new JsonObject(ImmutableMap.of("id", id));
        final String responseText = responseJson.toString();
        response.putHeader("Content-Type", "application/json; charset=utf-8");
        response.putHeader("Content-Length", Integer.toString(responseText.length()));
        response.end(responseText);
    }
}
