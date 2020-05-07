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

import java.io.FileInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.Map;
import java.util.Properties;

import com.google.common.collect.ImmutableMap;

import io.vertx.core.AsyncResult;
import io.vertx.core.Verticle;
import io.vertx.core.Vertx;

import org.hibernate.SessionFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

/**
 * Microservice providing image data over a HTTP endpoint.
 * @author m.t.b.carroll@dundee.ac.uk
 */
public class ZarrDataService {

    /**
     * Obtains the OMERO data source configured from Java system properties (may be loaded from configuration files named in
     * arguments) then starts a verticle that listens on HTTP for queries, checks the database then responds in JSON.
     * @param argv filename(s) from which to read configuration beyond current Java system properties
     * @throws IOException if the configuration could not be loaded
     */
    public static void main(String[] argv) throws IOException {
        /* set system properties from named configuration files */
        final Properties propertiesSystem = System.getProperties();
        for (final String filename : argv) {
            final Properties propertiesNew = new Properties();
            try (final InputStream filestream = new FileInputStream(filename)) {
                propertiesNew.load(filestream);
            }
            propertiesSystem.putAll(propertiesNew);
        }
        /* determine microservice configuration from system properties */
        final ImmutableMap.Builder<String, String> configuration = ImmutableMap.builder();
        final String configurationPrefix = "omero.ms.zarr.";
        for (final Map.Entry<Object, Object> property : propertiesSystem.entrySet()) {
            final Object keyObject = property.getKey();
            final Object valueObject = property.getValue();
            if (keyObject instanceof String && valueObject instanceof String) {
                final String key = (String) keyObject;
                final String value = (String) valueObject;
                if (key.startsWith(configurationPrefix)) {
                    configuration.put(key.substring(configurationPrefix.length()), value);
                }
            }
        }
        /* start up enough of OMERO.server to operate the pixels service */
        final AbstractApplicationContext zarrContext = new ClassPathXmlApplicationContext("zarr-context.xml");
        final ApplicationContext omeroContext = zarrContext.getBean("zarr.data", ApplicationContext.class);
        final SessionFactory sessionFactory = omeroContext.getBean("sessionFactory", SessionFactory.class);
        final PixelsService pixelsService = omeroContext.getBean("/OMERO/Pixels", PixelsService.class);
        /* deploy the verticle which uses the pixels service */
        final Vertx vertx = Vertx.vertx();
        final Verticle verticle = new ZarrDataVerticle(configuration.build(), sessionFactory, pixelsService);
        vertx.deployVerticle(verticle, (AsyncResult<String> result) -> {
            zarrContext.close();
        });
    }
}
