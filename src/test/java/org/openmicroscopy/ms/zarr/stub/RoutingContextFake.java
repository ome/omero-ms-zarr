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
import java.util.Map;
import java.util.Set;

import io.vertx.codegen.annotations.Nullable;
import io.vertx.core.AsyncResult;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.User;
import io.vertx.ext.web.Cookie;
import io.vertx.ext.web.FileUpload;
import io.vertx.ext.web.Locale;
import io.vertx.ext.web.ParsedHeaderValues;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.Session;

/**
 * A fake routing context allowing unit tests to verify the handling of mock HTTP requests.
 * @author m.t.b.carroll@dundee.ac.uk
 * @since v0.1.5
 */
public class RoutingContextFake implements RoutingContext {

    private final HttpServerRequest request;

    public RoutingContextFake(HttpServerRequest request) {
        this.request = request;
    }

    @Override
    public HttpServerRequest request() {
        return request;
    }

    @Override
    public HttpServerResponse response() {
        return request.response();
    }

    /* All other methods simply throw. */

    @Override
    public void next() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void fail(int statusCode) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void fail(Throwable throwable) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void fail(int statusCode, Throwable throwable) {
        throw new UnsupportedOperationException();
    }

    @Override
    public RoutingContext put(String key, Object obj) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <T> T get(String key) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <T> T remove(String key) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Map<String, Object> data() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Vertx vertx() {
        throw new UnsupportedOperationException();
    }

    @Override
    public @Nullable String mountPoint() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Route currentRoute() {
        throw new UnsupportedOperationException();
    }

    @Override
    public String normalisedPath() {
        throw new UnsupportedOperationException();
    }

    @Override
    public @Nullable Cookie getCookie(String name) {
        throw new UnsupportedOperationException();
    }

    @Override
    public RoutingContext addCookie(io.vertx.core.http.Cookie cookie) {
        throw new UnsupportedOperationException();
    }

    @Override
    public RoutingContext addCookie(Cookie cookie) {
        throw new UnsupportedOperationException();
    }

    @Override
    public @Nullable Cookie removeCookie(String name, boolean invalidate) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int cookieCount() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Set<Cookie> cookies() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Map<String, io.vertx.core.http.Cookie> cookieMap() {
        throw new UnsupportedOperationException();
    }

    @Override
    public @Nullable String getBodyAsString() {
        throw new UnsupportedOperationException();
    }

    @Override
    public @Nullable String getBodyAsString(String encoding) {
        throw new UnsupportedOperationException();
    }

    @Override
    public @Nullable JsonObject getBodyAsJson() {
        throw new UnsupportedOperationException();
    }

    @Override
    public @Nullable JsonArray getBodyAsJsonArray() {
        throw new UnsupportedOperationException();
    }

    @Override
    public @Nullable Buffer getBody() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Set<FileUpload> fileUploads() {
        throw new UnsupportedOperationException();
    }

    @Override
    public @Nullable Session session() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isSessionAccessed() {
        throw new UnsupportedOperationException();
    }

    @Override
    public @Nullable User user() {
        throw new UnsupportedOperationException();
    }

    @Override
    public @Nullable Throwable failure() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int statusCode() {
        throw new UnsupportedOperationException();
    }

    @Override
    public @Nullable String getAcceptableContentType() {
        throw new UnsupportedOperationException();
    }

    @Override
    public ParsedHeaderValues parsedHeaders() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int addHeadersEndHandler(Handler<Void> handler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean removeHeadersEndHandler(int handlerID) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int addBodyEndHandler(Handler<Void> handler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean removeBodyEndHandler(int handlerID) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int addEndHandler(Handler<AsyncResult<Void>> handler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean removeEndHandler(int handlerID) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean failed() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setBody(Buffer body) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setSession(Session session) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setUser(User user) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void clearUser() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setAcceptableContentType(@Nullable String contentType) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void reroute(HttpMethod method, String path) {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<Locale> acceptableLocales() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Map<String, String> pathParams() {
        throw new UnsupportedOperationException();
    }

    @Override
    public @Nullable String pathParam(String name) {
        throw new UnsupportedOperationException();
    }

    @Override
    public MultiMap queryParams() {
        throw new UnsupportedOperationException();
    }

    @Override
    public List<String> queryParam(String name) {
        throw new UnsupportedOperationException();
    }
}
