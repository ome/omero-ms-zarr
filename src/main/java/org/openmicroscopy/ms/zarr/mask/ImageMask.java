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

package org.openmicroscopy.ms.zarr.mask;

import ome.model.roi.Mask;

import java.awt.Rectangle;
import java.util.OptionalInt;
import java.util.function.BiPredicate;

/**
 * Represents a simple planar bitmask.
 * @author m.t.b.carroll@dundee.ac.uk
 * @since v0.1.7
 */
public class ImageMask implements Bitmask {

    final OptionalInt z, c, t;
    final Rectangle pos;
    final byte[] bitmask;

    /**
     * Copy a byte array.
     * @param src a byte array
     * @return a copy of the byte array
     */
    private static byte[] copyBitmask(byte[] src) {
        final byte[] dst = new byte[src.length];
        System.arraycopy(src, 0, dst, 0, src.length);
        return dst;
    }

    /**
     * Construct an image mask.
     * @param x the leftmost extent of the mask
     * @param y the topmost extent of the mask
     * @param width the width of the mask
     * @param height the height of the mask
     * @param z the <em>Z</em> plane of the mask, or {@code null} if it extends to all <em>Z</em> planes
     * @param c the <em>C</em> plane of the mask, or {@code null} if it extends to all <em>C</em> planes
     * @param t the <em>T</em> plane of the mask, or {@code null} if it extends to all <em>T</em> planes
     * @param bitmask the actual bitmask, as packed bits ranging over <em>X</em> before <em>Y</em>
     */
    public ImageMask(int x, int y, int width, int height, Integer z, Integer c, Integer t, byte[] bitmask) {
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("dimensions of mask must be strictly positive");
        }
        if (bitmask == null) {
            throw new IllegalArgumentException("bitmask cannot be null");
        }
        if (bitmask.length != width * height + 7 >> 3) {
            throw new IllegalArgumentException(
                    bitmask.length + "-byte mask not expected for " + width + '×' + height + " mask");
        }
        this.pos = new Rectangle(x, y, width, height);
        this.z = z == null ? OptionalInt.empty() : OptionalInt.of(z);
        this.c = c == null ? OptionalInt.empty() : OptionalInt.of(c);
        this.t = t == null ? OptionalInt.empty() : OptionalInt.of(t);
        this.bitmask = bitmask;
    }

    /**
     * Construct an image mask from an OMERO mask.
     * @param mask an OMERO mask
     */
    public ImageMask(Mask mask) {
        this(mask.getX().intValue(), mask.getY().intValue(), mask.getWidth().intValue(), mask.getHeight().intValue(),
                mask.getTheZ(), mask.getTheC(), mask.getTheT(), copyBitmask(mask.getBytes()));
        if (pos.x != mask.getX() || pos.y != mask.getY() || pos.width != mask.getWidth() || pos.height != mask.getHeight()) {
            throw new IllegalArgumentException("mask position must be specified as integers");
        }
    }

    /**
     * Construct an image mask, populating the fields with exactly the given object instances, with no validation.
     * @param pos the position of the mask
     * @param z the optional <em>Z</em> plane of the mask
     * @param c the optional <em>C</em> plane of the mask
     * @param t the optional <em>T</em> plane of the mask
     * @param bitmask the byte array to adopt as the bitmask
     */
    ImageMask(Rectangle pos, OptionalInt z, OptionalInt c, OptionalInt t, byte[] bitmask) {
        this.pos = pos;
        this.z = z;
        this.c = c;
        this.t = t;
        this.bitmask = bitmask;
    }

    @Override
    public boolean isSignificant(char dimension) {
        switch (Character.toUpperCase(dimension)) {
        case 'X':
            return true;
        case 'Y':
            return true;
        case 'Z':
            return z.isPresent();
        case 'C':
            return c.isPresent();
        case 'T':
            return t.isPresent();
        default:
            throw new IllegalArgumentException();
        }
    }

    @Override
    public BiPredicate<Integer, Integer> getMaskReader(int z, int c, int t) {
        if (this.z.isPresent() && this.z.getAsInt() != z ||
            this.c.isPresent() && this.c.getAsInt() != c ||
            this.t.isPresent() && this.t.getAsInt() != t) {
            return null;
        }
        return new BiPredicate<Integer, Integer>() {
            @Override
            public boolean test(Integer x, Integer y) {
                if (!pos.contains(x, y)) {
                    return false;
                }
                final int bitPosition = (x - pos.x) + (y - pos.y) * pos.width;
                final int bytePosition = bitPosition >> 3;
                final int bitRemainder = 7 - (bitPosition & 7);
                final int bitState = bitmask[bytePosition] & 1 << bitRemainder;
                return bitState != 0;
            }
        };
    }

    @Override
    public int size() {
        return bitmask.length;
    }

    /**
     * Test if this mask overlaps another.
     * @param mask a mask
     * @return if this mask overlaps the given mask
     */
    public boolean isOverlap(ImageMask mask) {
        /* Same planes? */
        if (z.isPresent() && mask.z.isPresent() && z.getAsInt() != mask.z.getAsInt() ||
            c.isPresent() && mask.c.isPresent() && c.getAsInt() != mask.c.getAsInt() ||
            t.isPresent() && mask.t.isPresent() && t.getAsInt() != mask.t.getAsInt()) {
            return false;
        }
        if (pos.equals(mask.pos)) {
            /* If same position then the packing is aligned so compare byte-by-byte. */
            for (int index = 0; index < bitmask.length; index++) {
                if ((bitmask[index] & mask.bitmask[index]) != 0) {
                    return true;
                }
            }
        } else {
            /* Different positions so check bit-by-bit. */
            final int z = this.z.orElse(mask.z.orElse(1));
            final int c = this.c.orElse(mask.c.orElse(1));
            final int t = this.t.orElse(mask.t.orElse(1));
            final BiPredicate<Integer, Integer> isMasked1 = this.getMaskReader(z, c, t);
            final BiPredicate<Integer, Integer> isMasked2 = mask.getMaskReader(z, c, t);
            final Rectangle intersection = pos.intersection(mask.pos);
            for (int y = intersection.y; y < intersection.y + intersection.height; y++) {
                for (int x = intersection.x; x < intersection.x + intersection.width; x++) {
                    if (isMasked1.test(x, y) && isMasked2.test(x, y)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Calculate the union of this mask with another.
     * @param mask a mask
     * @return the union of the masks, or {@code null} if the union requires a mask larger than either of the pair
     */
    public ImageMask union(ImageMask mask) {
        if (z.equals(mask.z) && c.equals(mask.c) && t.equals(mask.t)) {
            if (pos.contains(mask.pos)) {
                /* Can use this mask's position for the union of the two. */
                final byte[] bitmask = copyBitmask(this.bitmask);
                if (pos.equals(mask.pos)) {
                    /* If same position then the packing is aligned so combine byte-by-byte. */
                    for (int index = 0; index < bitmask.length; index++) {
                        bitmask[index] |= mask.bitmask[index];
                    }
                } else {
                    /* Different positions so combine bit-by-bit. */
                    final BiPredicate<Integer, Integer> isMasked = mask.getMaskReader(z.orElse(1), c.orElse(1), t.orElse(1));
                    for (int y = mask.pos.y; y < mask.pos.y + mask.pos.height; y++) {
                        for (int x = mask.pos.x; x < mask.pos.x + mask.pos.width; x++) {
                            if (isMasked.test(x, y)) {
                                final int bitPosition = (x - pos.x) + (y - pos.y) * pos.width;
                                final int bytePosition = bitPosition >> 3;
                                final int bitRemainder = 7 - (bitPosition & 7);
                                bitmask[bytePosition] |= 1 << bitRemainder;
                            }
                        }
                    }
                }
                return new ImageMask(pos, z, c, t, bitmask);
            } else if (mask.pos.contains(pos)) {
                /* Combine this mask onto the other. */
                return mask.union(this);
            }
        }
        /* No efficient combination. */
        return null;
    }
}
