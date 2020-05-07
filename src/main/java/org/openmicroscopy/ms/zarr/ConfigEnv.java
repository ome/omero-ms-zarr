package org.openmicroscopy.ms.zarr;

import java.io.IOException;
import java.util.Iterator;
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
        for (Iterator<Map.Entry<String, String>> i = System.getenv().entrySet().iterator(); i.hasNext();) {
            Map.Entry<String, String> e = i.next();
            if (e.getKey().startsWith("CONFIG_")) {
                String key = e.getKey().substring(7);
                key = key.replaceAll("([^_])_([^_])", "$1.$2").replaceAll("__", "_");
                overrides.put(key, e.getValue());
            }
        }
        ZarrDataService.mainVerticle(argv, overrides);
    }
}