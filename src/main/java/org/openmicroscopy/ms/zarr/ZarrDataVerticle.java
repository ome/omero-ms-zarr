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

import ome.io.nio.PixelsService;

import java.util.Map;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Verticle;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.ext.web.Router;

import org.hibernate.SessionFactory;

/**
 * Set up HTTP endpoint.
 * @author m.t.b.carroll@dundee.ac.uk
 */
public class ZarrDataVerticle implements Verticle {

    private final Configuration configuration;
    private final SessionFactory sessionFactory;
    private final PixelsService pixelsService;

    private Vertx vertx;

    @SuppressWarnings("unused")
    private Context context;

    private HttpServer server;

    public ZarrDataVerticle(Map<String, String> configuration, SessionFactory sessionFactory, PixelsService pixelsService) {
        this.configuration = new Configuration(configuration);
        this.sessionFactory = sessionFactory;
        this.pixelsService = pixelsService;

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
     * Starts the verticle and listens for HTTP requests.
     * @param promise for reporting the outcome
     */
    @Override
    public void start(Promise<Void> promise) {
        /* listen for queries over HTTP */
        final Router router = Router.router(vertx);
        server = vertx.createHttpServer(new HttpServerOptions().setPort(configuration.getServerPort()));
        server.requestHandler(router);
        new RequestHandlerForImage(configuration, sessionFactory, pixelsService).handleFor(router);
        server.listen();  // TODO: does not yet handle failure
        promise.complete();
    }

    /**
     * Stops the HTTP server.
     * @param promise for reporting the outcome
     */
    @Override
    public void stop(Promise<Void> promise) {
        server.close();
        promise.complete();
    }

    @Override
    public void start(Future<Void> startFuture) {
        throw new UnsupportedOperationException("obsolete in later Vert.x");
    }

    @Override
    public void stop(Future<Void> stopFuture) {
        throw new UnsupportedOperationException("obsolete in later Vert.x");
    }
}
