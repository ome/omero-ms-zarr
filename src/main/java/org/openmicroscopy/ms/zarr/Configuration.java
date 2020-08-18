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

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.SortedSet;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stores the configuration of this microservice.
 * @author m.t.b.carroll@dundee.ac.uk
 */
public class Configuration {

    private static final Logger LOGGER = LoggerFactory.getLogger(Configuration.class);

    public final static String PLACEHOLDER_IMAGE_ID = "{image}";

    private final static SortedSet<Character> VALID_DIMENSIONS =
            ImmutableSortedSet.copyOf(RequestHandlerForImage.DataShape.ADJUSTERS.keySet());

    /* Configuration keys for the map provided to the constructor. */
    public final static String CONF_BUFFER_CACHE_SIZE = "buffer-cache.size";
    public final static String CONF_CHUNK_SIZE_ADJUST = "chunk.size.adjust";
    public final static String CONF_CHUNK_SIZE_MIN = "chunk.size.min";
    public final static String CONF_COMPRESS_ZLIB_LEVEL = "compress.zlib.level";
    public final static String CONF_FOLDER_LAYOUT = "folder.layout";
    public final static String CONF_MASK_CACHE_SIZE = "mask-cache.size";
    public final static String CONF_MASK_SPLIT_ENABLE = "mask.split.enable";
    public final static String CONF_MASK_OVERLAP_COLOR = "mask.overlap.color";
    public final static String CONF_MASK_OVERLAP_VALUE = "mask.overlap.value";
    public final static String CONF_NET_PATH_IMAGE = "net.path.image";
    public final static String CONF_NET_PORT = "net.port";

    /* Configuration initialized to default values. */
    private int bufferCacheSize = 16;
    private long maskCacheSize = 250 * 1000000L;
    private List<Character> chunkSizeAdjust = ImmutableList.of('X', 'Y', 'Z');
    private int chunkSizeMin = 0x100000;
    private int zlibLevel = 6;
    private Boolean foldersNested = false;
    private String netPath = getRegexForNetPath("/image/" + PLACEHOLDER_IMAGE_ID + ".zarr/");
    private int netPort = 8080;
    private boolean maskSplitEnable = false;
    private Integer maskOverlapColor = null;
    private Long maskOverlapValue = Long.MAX_VALUE;

    /**
     * Convert the given URI path to a regular expression in which {@link #PLACEHOLDER_IMAGE_ID} matches the image ID.
     * @param netPath a URI path, must contain {@link #PLACEHOLDER_IMAGE_ID}
     * @return a corresponding regular expression
     */
    private static final String getRegexForNetPath(String netPath) {
        final int imageIndex = netPath.indexOf(PLACEHOLDER_IMAGE_ID);
        final StringBuilder netPathBuilder = new StringBuilder();
        netPathBuilder.append("\\Q");
        netPathBuilder.append(netPath.substring(0, imageIndex));
        netPathBuilder.append("\\E");
        netPathBuilder.append("(\\d+)");
        netPathBuilder.append("\\Q");
        netPathBuilder.append(netPath.substring(imageIndex + PLACEHOLDER_IMAGE_ID.length()));
        netPathBuilder.append("\\E");
        return netPathBuilder.toString();
    }

    /**
     * Construct a new read-only configuration drawn from system properties of the form {@code omero.ms.zarr.*}.
     */
    public Configuration() {
        final Properties propertiesSystem = System.getProperties();
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
        setConfiguration(configuration.build());
    }

    /**
     * Construct a new read-only configuration.
     * @param configuration a map of configuration keys and values, may be empty but must not be {@code null}
     */
    public Configuration(Map<String, String> configuration) {
        setConfiguration(configuration);
    }

    /**
     * @param configuration the configuration keys and values to apply over the current state.
     */
    private void setConfiguration(Map<String, String> configuration) {
        final String bufferCacheSize = configuration.get(CONF_BUFFER_CACHE_SIZE);
        final String chunkSizeAdjust = configuration.get(CONF_CHUNK_SIZE_ADJUST);
        final String chunkSizeMin = configuration.get(CONF_CHUNK_SIZE_MIN);
        final String zlibLevel = configuration.get(CONF_COMPRESS_ZLIB_LEVEL);
        final String folderLayout = configuration.get(CONF_FOLDER_LAYOUT);
        final String maskCacheSize = configuration.get(CONF_MASK_CACHE_SIZE);
        final String maskSplitEnable = configuration.get(CONF_MASK_SPLIT_ENABLE);
        final String maskOverlapColor = configuration.get(CONF_MASK_OVERLAP_COLOR);
        final String maskOverlapValue = configuration.get(CONF_MASK_OVERLAP_VALUE);
        final String netPath = configuration.get(CONF_NET_PATH_IMAGE);
        final String netPort = configuration.get(CONF_NET_PORT);

        if (bufferCacheSize != null) {
            try {
                this.bufferCacheSize = Integer.parseInt(bufferCacheSize);
            } catch (NumberFormatException nfe) {
                final String message = "buffer cache size must be an integer, not " + bufferCacheSize;
                LOGGER.error(message);
                throw new IllegalArgumentException(message);
            }
        }

        if (chunkSizeAdjust != null) {
            final Set<Character> validDimensionsRemaining = new HashSet<>(VALID_DIMENSIONS);
            final ImmutableList.Builder<Character> dimensions = ImmutableList.builder();
            for (int index = 0; index < chunkSizeAdjust.length();) {
                final int codePoint = Character.toUpperCase(chunkSizeAdjust.codePointAt(index));
                final int charCount = Character.charCount(codePoint);
                if (charCount == 1) {
                    final char dimension = Character.toChars(codePoint)[0];
                    if (validDimensionsRemaining.remove(dimension)) {
                        dimensions.add(dimension);
                    } else if (Character.isAlphabetic(codePoint)) {
                        final StringBuilder message = new StringBuilder("chunk size adjustment may contain (without repeats): ");
                        for (final char validDimension : VALID_DIMENSIONS) {
                            message.append(validDimension);
                        }
                        LOGGER.error(message.toString());
                        throw new IllegalArgumentException(message.toString());
                    }
                }
                index += charCount;
            }
            this.chunkSizeAdjust = dimensions.build();
        }

        if (chunkSizeMin != null) {
            try {
                this.chunkSizeMin = Integer.parseInt(chunkSizeMin);
            } catch (NumberFormatException nfe) {
                final String message = "minimum chunk size must be an integer, not " + chunkSizeMin;
                LOGGER.error(message);
                throw new IllegalArgumentException(message);
            }
        }

        if (zlibLevel != null) {
            try {
                this.zlibLevel = Integer.parseInt(zlibLevel);
            } catch (NumberFormatException nfe) {
                final String message = "deflate compression level must be an integer, not " + zlibLevel;
                LOGGER.error(message);
                throw new IllegalArgumentException(message);
            }
        }

        if (folderLayout != null) {
            switch (folderLayout.toLowerCase()) {
            case "nested":
                this.foldersNested = true;
                break;
            case "flattened":
                this.foldersNested = false;
                break;
            case "none":
                this.foldersNested = null;
                break;
            default:
                final String message = "folder layout may be nested, flattened or none";
                LOGGER.error(message);
                throw new IllegalArgumentException(message);
            }
        }

        if (maskCacheSize != null) {
            try {
                this.maskCacheSize = Long.parseLong(maskCacheSize) * 1000000L;
            } catch (NumberFormatException nfe) {
                final String message = "mask cache size must be an integer, not " + maskCacheSize;
                LOGGER.error(message);
                throw new IllegalArgumentException(message);
            }
        }

        if (maskSplitEnable != null) {
            this.maskSplitEnable = Boolean.parseBoolean(maskSplitEnable);
        }

        if (maskOverlapColor != null) {
            try {
                this.maskOverlapColor = Integer.parseInt(maskOverlapColor);
            } catch (NumberFormatException nfe) {
                final String message = "mask overlap color must be an integer, not " + maskOverlapColor;
                LOGGER.error(message);
                throw new IllegalArgumentException(message);
            }
        }

        if (maskOverlapValue != null) {
            try {
                this.maskOverlapValue = Long.parseLong(maskOverlapValue);
            } catch (NumberFormatException nfe) {
                switch (maskOverlapValue.toLowerCase()) {
                case "highest":
                    this.maskOverlapValue = Long.MAX_VALUE;
                    break;
                case "lowest":
                    this.maskOverlapValue = Long.MIN_VALUE;
                    break;
                default:
                    final String message = "mask overlap value must be an integer or HIGHEST or LOWEST, not " + maskOverlapValue;
                    LOGGER.error(message);
                    throw new IllegalArgumentException(message);
                }
            }
        }

        if (netPath != null) {
            if (netPath.indexOf('\\') == -1) {
                if (netPath.contains(PLACEHOLDER_IMAGE_ID)) {
                    this.netPath = getRegexForNetPath(netPath);
                } else {
                    final String message = "URI path must contain " + PLACEHOLDER_IMAGE_ID + " placeholder for image ID";
                    LOGGER.error(message);
                    throw new IllegalArgumentException(message);
                }
            } else {
                final String message = "URI path cannot contain backslashes";
                LOGGER.error(message);
                throw new IllegalArgumentException(message);
            }
        }

        if (netPort != null) {
            try {
                this.netPort = Integer.parseInt(netPort);
            } catch (NumberFormatException nfe) {
                final String message = "TCP port number must be an integer, not " + netPort;
                LOGGER.error(message);
                throw new IllegalArgumentException(message);
            }
        }

        if (LOGGER.isInfoEnabled()) {
            for (final Map.Entry<String, String> setting : configuration.entrySet()) {
                LOGGER.info("configured: {} = {}", setting.getKey(), setting.getValue());
            }
        }
    }

    /**
     * @return the configured pixel buffer cache size
     */
    public int getBufferCacheSize() {
        return bufferCacheSize;
    }

    /**
     * @return an ordered list of chunk dimensions to adjust
     */
    public List<Character> getAdjustableChunkDimensions() {
        return chunkSizeAdjust;
    }

    /**
     * @return the configured minimum chunk size
     */
    public int getMinimumChunkSize() {
        return chunkSizeMin;
    }

    /**
     * @return the configured decompression level for zlib
     */
    public int getDeflateLevel() {
        return zlibLevel;
    }

    /**
     * @return if the folder layout should be nested ({@code null} for no folders at all)
     */
    public Boolean getFoldersNested() {
        return foldersNested;
    }

    /**
     * @return the configured mask cache size
     */
    public long getMaskCacheSize() {
        return maskCacheSize;
    }

    /**
     * @return if split masks are enabled
     */
    public boolean isSplitMasksEnabled() {
        return maskSplitEnable;
    }

    /**
     * @return the configured mask overlap color ({@code null} for no color)
     */
    public Integer getMaskOverlapColor() {
        return maskOverlapColor;
    }

    /**
     * @return the configured mask overlap value ({@code null} for no overlaps)
     */
    public Long getMaskOverlapValue() {
        return maskOverlapValue;
    }

    /**
     * @return the configured URI path as a regular expression
     */
    public String getPathRegex() {
        return netPath;
    }

    /**
     * @return the configured TCP port for HTTP
     */
    public int getServerPort() {
        return netPort;
    }
}
