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

import java.util.List;

import io.vertx.core.AsyncResult;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Verticle;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Set up HTTP endpoint.
 * @author m.t.b.carroll@dundee.ac.uk
 */
public class ZarrDataVerticle implements Verticle {

    private static final Logger LOGGER = LoggerFactory.getLogger(ZarrDataVerticle.class);

    /**
     * Log the outcome of an asynchronous action then set a {@link Promise} accordingly.
     * @author m.t.b.carroll@dundee.ac.uk
     * @param <X> the value type of the asynchronous action
     */
    private static class EventHandler<X> implements Handler<AsyncResult<X>> {

        private final Promise<?> promise;
        private final String action;

        /**
         * Construct a new event handler.
         * @param promise the promise to set on completion
         * @param action a description of the action, for use in the log message
         */
        EventHandler(Promise<?> promise, String action) {
            this.promise = promise;
            this.action = action;
        }

        @Override
        public void handle(AsyncResult<X> result) {
            if (result.succeeded()) {
                LOGGER.info("succeeded: {}", action);
                promise.complete();
            } else {
                final Throwable cause = result.cause();
                LOGGER.error("failed: {}", action, cause);
                promise.fail(cause);
            }
        }
    }

    private final Configuration configuration;
    private final List<HttpHandler> requestHandlers;

    private Vertx vertx;

    @SuppressWarnings("unused")
    private Context context;

    private HttpServer server;

    /**
     * Construct a new verticle.
     * @param configuration the configuration to be set for this verticle
     * @param requestHandlers the handlers that respond to HTTP requests
     */
    public ZarrDataVerticle(Configuration configuration, List<HttpHandler> requestHandlers) {
        this.configuration = configuration;
        this.requestHandlers = requestHandlers;
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
        final Router router = Router.router(vertx);
        server = vertx.createHttpServer().requestHandler(router);
        for (final HttpHandler requestHandler : requestHandlers) {
            requestHandler.handleFor(router);
        }
        final int port = configuration.getServerPort();
        server.listen(port, new EventHandler<>(promise, "listen on TCP port " + port));
    }

    /**
     * Stops the HTTP server.
     * @param promise for reporting the outcome
     */
    @Override
    public void stop(Promise<Void> promise) {
        server.close(new EventHandler<>(promise, "stop server"));
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
