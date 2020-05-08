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

import java.io.IOException;
import java.util.Map;
import java.util.Properties;

public class ConfigEnv {
    /**
     * Converts configuration environment variables beginning with "CONFIG_" to OMERO configuration properties
     * and runs the microservice.
     * Since "." is not allowed in a variable name "." must be replaced by "_", and "_" by "__".
     * For example "CONFIG_omero_data_dir=/OMERO" will become "omero.data.dir=/OMERO"
     *
     * @param argv filename(s) from which to read configuration beyond current Java system properties
     * @throws IOException if the configuration could not be loaded
     */
    public static void main(String[] argv) throws IOException {
        Properties overrides = new Properties();
        for (final Map.Entry<String, String> e : System.getenv().entrySet()) {
            if (e.getKey().startsWith("CONFIG_")) {
                String key = e.getKey().substring(7);
                key = key.replaceAll("([^_])_([^_])", "$1.$2").replaceAll("__", "_");
                overrides.put(key, e.getValue());
            }
        }
        ZarrDataService.mainVerticle(argv, overrides);
    }
}
