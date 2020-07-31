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

import java.util.function.BiPredicate;

/**
 * The most abstract kind of bitmask.
 * @author m.t.b.carroll@dundee.ac.uk
 * @since v0.1.6
 */
public interface Bitmask {
    /**
     * @param dimension one of <q>XYZCT</q>
     * @return if the value of this dimension may affect the result from any of the other methods
     */
    boolean isSignificant(char dimension);

    /**
     * @param z a <em>Z</em> plane
     * @param c a <em>C</em> plane
     * @param t a <em>T</em> plane
     * @return an <em>X</em>, <em>Y</em> mask reader if the mask exists on the given plane, otherwise {@code null}
     */
    BiPredicate<Integer, Integer> getMaskReader(int z, int c, int t);

    /**
     * @return an estimate of the memory consumption of this mask, to guide cache management
     */
    int size();
}
