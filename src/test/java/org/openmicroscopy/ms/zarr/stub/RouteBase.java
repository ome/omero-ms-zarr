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

import java.util.List;
import java.util.Set;

import io.vertx.codegen.annotations.Nullable;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;

/**
 * A base class for implementing fake routes.
 * @author m.t.b.carroll@dundee.ac.uk
 * @since v0.1.5
 */
public abstract class RouteBase implements Route {

    @Override
    public Route method(HttpMethod method) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Route path(String path) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Route pathRegex(String path) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Route produces(String contentType) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Route consumes(String contentType) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Route order(int order) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Route last() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Route handler(Handler<RoutingContext> requestHandler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Route blockingHandler(Handler<RoutingContext> requestHandler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Route subRouter(Router subRouter) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Route blockingHandler(Handler<RoutingContext> requestHandler, boolean ordered) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Route failureHandler(Handler<RoutingContext> failureHandler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Route remove() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Route disable() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Route enable() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Route useNormalisedPath(boolean useNormalisedPath) {
        throw new UnsupportedOperationException();
    }

    @Override
    public @Nullable String getPath() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isRegexPath() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Set<HttpMethod> methods() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Route setRegexGroupsNames(List<String> groups) {
        throw new UnsupportedOperationException();
    }
}
