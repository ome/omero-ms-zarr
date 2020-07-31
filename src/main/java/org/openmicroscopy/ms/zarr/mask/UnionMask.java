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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;

/**
 * Represents a mask that may combine multiple masks,
 * as for the union of multiple {@link ome.model.roi.Mask}s in the same {@link ome.model.roi.Roi}.
 * @author m.t.b.carroll@dundee.ac.uk
 * @since v0.1.7
 */
public class UnionMask implements Bitmask {

    private final Collection<Bitmask> masks;

    /**
     * Construct a union mask from the given set of masks.
     * @param masks the masks to combine
     */
    UnionMask(Collection<Bitmask> masks) {
        this.masks = masks;
    }

    @Override
    public boolean isSignificant(char dimension) {
        return masks.stream().anyMatch(mask -> mask.isSignificant(dimension));
    }

    @Override
    public BiPredicate<Integer, Integer> getMaskReader(int z, int c, int t) {
        final List<BiPredicate<Integer, Integer>> applicableMasks = masks.stream()
                .map(mask -> mask.getMaskReader(z, c, t)).filter(obj -> obj != null).collect(Collectors.toList());
        return applicableMasks.isEmpty() ? null : (x, y) -> applicableMasks.stream().anyMatch(p -> p.test(x, y));
    }

    @Override
    public int size() {
        return masks.stream().map(Bitmask::size).reduce(0, Math::addExact);
    }

    /**
     * Test if this mask overlaps another.
     * @param mask a mask
     * @return if this mask overlaps the given mask
     */
    public boolean isOverlap(ImageMask mask) {
        for (final Bitmask myMask : masks) {
            if (myMask instanceof ImageMask) {
                if (mask.isOverlap((ImageMask) myMask)) {
                    return true;
                }
            } else if (myMask instanceof UnionMask) {
                if (((UnionMask) myMask).isOverlap(mask)) {
                    return true;
                }
            } else {
                throw new IllegalStateException();
            }
        }
        return false;
    }

    /**
     * Test if this mask overlaps another.
     * @param mask a mask
     * @return if this mask overlaps the given mask
     */
    public boolean isOverlap(UnionMask mask) {
        for (final Bitmask myMask : masks) {
            if (myMask instanceof ImageMask) {
                if (mask.isOverlap((ImageMask) myMask)) {
                    return true;
                }
            } else if (myMask instanceof UnionMask) {
                if (((UnionMask) myMask).isOverlap(mask)) {
                    return true;
                }
            } else {
                throw new IllegalStateException();
            }
        }
        return false;
    }

    /**
     * Calculate the union of a set of masks.
     * @param masks some masks
     * @return the union of the masks
     */
    public static Bitmask union(Iterable<? extends Bitmask> masks) {
        final List<Bitmask> masksAbstract = new ArrayList<>();
        final List<ImageMask> masksConcrete = new ArrayList<>();
        for (final Bitmask maskNewAbstract : masks) {
            if (maskNewAbstract instanceof ImageMask) {
                /* Where possible, combine masks into a single new one. */
                boolean isAdded = false;
                final ImageMask maskNewConcrete = (ImageMask) maskNewAbstract;
                final Iterator<ImageMask> masksConcreteIter = masksConcrete.iterator();
                while (masksConcreteIter.hasNext()) {
                    final ImageMask maskOldConcrete = masksConcreteIter.next();
                    final ImageMask maskUnion = maskOldConcrete.union(maskNewConcrete);
                    if (maskUnion != null) {
                        masksConcreteIter.remove();
                        masksConcrete.add(maskUnion);
                        isAdded = true;
                        break;
                    }
                }
                if (!isAdded) {
                    masksConcrete.add(maskNewConcrete);
                }
            } else {
                masksAbstract.add(maskNewAbstract);
            }
        }
        final int maskCount = masksAbstract.size() + masksConcrete.size();
        final List<Bitmask> union = new ArrayList<>(maskCount);
        union.addAll(masksAbstract);
        union.addAll(masksConcrete);
        return maskCount == 1 ? union.get(0) : new UnionMask(union);
    }
}
