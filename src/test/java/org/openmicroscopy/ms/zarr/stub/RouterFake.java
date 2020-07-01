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

package org.openmicroscopy.ms.zarr.stub;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import io.vertx.codegen.annotations.Nullable;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

import org.junit.jupiter.api.Assertions;
import org.mockito.Mockito;

/**
 * A fake router allowing unit tests to verify the handling of mock HTTP requests.
 * @author m.t.b.carroll@dundee.ac.uk
 * @since v0.1.5
 */
public class RouterFake implements Router {

    private final Map<String, Handler<RoutingContext>> routes = new LinkedHashMap<>();

    @Override
    public Route getWithRegex(String regex) {
        if (routes.containsKey(regex)) {
            throw new IllegalArgumentException("route already exists");
        } else {
            routes.put(regex, null);
        }
        return new RouteBase() {
            @Override
            public Route handler(Handler<RoutingContext> requestHandler) {
                routes.put(regex, requestHandler);
                return this;
            }
        };
    }

    @Override
    public void handle(HttpServerRequest httpRequest) {
        for (final Map.Entry<String, Handler<RoutingContext>> route : routes.entrySet()) {
            if (Pattern.matches(route.getKey(), httpRequest.path())) {
                final RoutingContext context = Mockito.mock(RoutingContext.class);
                Mockito.when(context.request()).thenReturn(httpRequest);
                route.getValue().handle(context);
                return;
            }
        }
        Assertions.fail("HTTP request path is unhandled: " + httpRequest.path());
    }

    /* All other methods simply throw. */

    @Override
    public Route route() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Route route(HttpMethod method, String path) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Route route(String path) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Route routeWithRegex(HttpMethod method, String regex) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Route routeWithRegex(String regex) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Route get() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Route get(String path) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Route head() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Route head(String path) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Route headWithRegex(String regex) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Route options() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Route options(String path) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Route optionsWithRegex(String regex) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Route put() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Route put(String path) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Route putWithRegex(String regex) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Route post() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Route post(String path) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Route postWithRegex(String regex) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Route delete() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Route delete(String path) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Route deleteWithRegex(String regex) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Route trace() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Route trace(String path) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Route traceWithRegex(String regex) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Route connect() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Route connect(String path) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Route connectWithRegex(String regex) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Route patch() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Route patch(String path) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Route patchWithRegex(String regex) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<Route> getRoutes() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Router clear() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Router mountSubRouter(String mountPoint, Router subRouter) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Router exceptionHandler(@Nullable Handler<Throwable> exceptionHandler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Router errorHandler(int statusCode, Handler<RoutingContext> errorHandler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void handleContext(RoutingContext context) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void handleFailure(RoutingContext context) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Router modifiedHandler(Handler<Router> handler) {
        throw new UnsupportedOperationException();
    }
}
