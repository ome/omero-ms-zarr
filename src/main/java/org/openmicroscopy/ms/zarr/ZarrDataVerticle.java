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
import java.sql.SQLException;

import javax.sql.DataSource;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Verticle;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.ext.web.Router;

/**
 * Set up HTTP endpoint with JDBC connection.
 * @author m.t.b.carroll@dundee.ac.uk
 */
public class ZarrDataVerticle implements Verticle {

    private final DataSource dataSource;

    private Vertx vertx;
    private Context context;  // unused

    private Connection connection;
    private HttpServer server;

    public ZarrDataVerticle(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public Vertx getVertx() {
        return vertx;
    }

    @Override
    public void init(Vertx vertx, Context context) {
        this.vertx = vertx;
        this.context = context;
    }

    /**
     * Starts the verticle: gets a JDBC connection and listens on HTTP port 8080.
     * The verticle includes no logic for retrying a lost JDBC connection
     * @param future for reporting the outcome
     * @throws SQLException if a JDBC connection could not be obtained
     */
    @Override
    public void start(Future<Void> future) throws SQLException {
        /* obtain JDBC connection */
        connection = dataSource.getConnection();
        /* listen for queries over HTTP */
        final Router router = Router.router(vertx);
        server = vertx.createHttpServer(new HttpServerOptions().setPort(8080));
        server.requestHandler(router::accept);
        new RequestHandlerForId(connection).handleFor(router, "/id");
        server.listen();  // does not yet handle failure
        future.complete();
    }

    /**
     * Closes the JDBC connection and the HTTP server.
     * @param future for reporting the outcome
     * @throws SQLException if the JDBC connection could not be closed
     */
    @Override
    public void stop(Future<Void> future) throws SQLException {
        connection.close();
        server.close();
        future.complete();
    }
}
