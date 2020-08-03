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

import java.awt.Rectangle;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.stream.Stream;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Test that unions of bitmasks work correctly.
 * @author m.t.b.carroll@dundee.ac.uk
 * @since v0.1.7
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class TestUnionMasks {

    private static BiPredicate<Integer, Integer> ALWAYS_NOTHING = getMaskPredicate(new Rectangle(), 0, 0);

    private final Set<Integer> maskCountsObserved = new HashSet<>();
    private final Multimap<Character, Boolean> dimensionSignificancesObserved = HashMultimap.create();

    /**
     * Construct a mask predicate that has pixels set at a regular interval.
     * @param pos the position of the mask
     * @param xInterval the horizontal distance from one set pixel to the next
     * @param yInterval the vertical distance from one set pixel to the next
     * @return a new mask predicate
     */
    private static BiPredicate<Integer, Integer> getMaskPredicate(Rectangle pos, int xInterval, int yInterval) {
        return new BiPredicate<Integer, Integer>() {
            @Override
            public boolean test(Integer x, Integer y) {
                return pos.contains(x, y) && (x - pos.x) % xInterval == 0 && (y - pos.y) % yInterval == 0;
            }
        };
    }

    /**
     * Construct a mask whose bitmask is set according to a predicate.
     * @param pos the position of the mask
     * @param z the <em>Z</em> plane of the mask, or {@code null} if it extends to all <em>Z</em> planes
     * @param c the <em>C</em> plane of the mask, or {@code null} if it extends to all <em>C</em> planes
     * @param t the <em>T</em> plane of the mask, or {@code null} if it extends to all <em>T</em> planes
     * @param maskPredicate the predicate defining the mask's pixel data
     * @return a new mask
     */
    private static ImageMask constructMask(Rectangle pos, Integer z, Integer c, Integer t,
            BiPredicate<Integer, Integer> maskPredicate) {
        final byte[] bitmask = new byte[pos.width * pos.height + 7 >> 3];
        for (int y = pos.y; y < pos.y + pos.height; y++) {
            for (int x = pos.x; x < pos.x + pos.width; x++) {
                if (maskPredicate.test(x, y)) {
                    final int bitPosition = (x - pos.x) + (y - pos.y) * pos.width;
                    final int bytePosition = bitPosition >> 3;
                    final int bitRemainder = 7 - (bitPosition & 7);
                    bitmask[bytePosition] |= 1 << bitRemainder;
                }
            }
        }
        return new ImageMask(pos.x, pos.y, pos.width, pos.height, z, c, t, bitmask);
    }

    /**
     * Test the union of three masks.
     * @param x1 the <em>X</em> offset of the second mask with respect to the first
     * @param z1 the <em>Z</em> plane of the second mask, or {@code null} if it extends to all <em>Z</em> planes
     * @param c1 the <em>C</em> plane of the second mask, or {@code null} if it extends to all <em>C</em> planes
     * @param t1 the <em>T</em> plane of the second mask, or {@code null} if it extends to all <em>T</em> planes
     * @param x2 the <em>X</em> offset of the third mask with respect to the first
     * @param z2 the <em>Z</em> plane of the third mask, or {@code null} if it extends to all <em>Z</em> planes
     * @param c2 the <em>C</em> plane of the third mask, or {@code null} if it extends to all <em>C</em> planes
     * @param t2 the <em>T</em> plane of the third mask, or {@code null} if it extends to all <em>T</em> planes
     * @param isReverse if the mask union operation should be performed in the order of third, second, first
     */
    @ParameterizedTest
    @MethodSource("provideMaskPlacements")
    public void testCombiningMasks(int x1, Integer z1, Integer c1, Integer t1, int x2, Integer z2, Integer c2, Integer t2,
            boolean isReverse) {
        /* Define the three masks. */
        final Rectangle pos0 = new Rectangle(0, 0, 80, 80);
        final Rectangle pos1 = new Rectangle(x1, 20, 35, 35);
        final Rectangle pos2 = new Rectangle(x2, 30, 40, 25);
        final BiPredicate<Integer, Integer> isMasked0 = getMaskPredicate(pos0, 11, 13);
        final BiPredicate<Integer, Integer> isMasked1 = getMaskPredicate(pos1, 3, 7);
        final BiPredicate<Integer, Integer> isMasked2 = getMaskPredicate(pos2, 5, 9);
        final Bitmask mask0 = constructMask(pos0, null, null, null, isMasked0);
        final Bitmask mask1 = constructMask(pos1, z1, c1, t1, isMasked1);
        final Bitmask mask2 = constructMask(pos2, z2, c2, t2, isMasked2);
        /* Determine how many masks should be required for the union. */
        int expectedMaskCount = 3;
        if (z1 == null && c1 == null && t1 == null && pos0.contains(pos1)) {
            expectedMaskCount--;
        }
        if (z2 == null && c2 == null && t2 == null && pos0.contains(pos2)) {
            expectedMaskCount--;
        }
        /* Determine the union mask. */
        final List<Bitmask> masks = Lists.newArrayList(mask0, mask1, mask2);
        if (isReverse) {
            Collections.reverse(masks);
        }
        final Bitmask unionMask = UnionMask.union(masks);
        /* Check that the union mask has the expected properties. */
        if (expectedMaskCount == 1) {
            /* Should be just as the first mask except in pixel data. */
            Assertions.assertTrue(unionMask instanceof ImageMask);
            Assertions.assertEquals(pos0, ((ImageMask) unionMask).pos);
            Assertions.assertEquals(mask0.size(), unionMask.size());
        } else {
            /* Should be larger than the first mask. */
            Assertions.assertTrue(unionMask instanceof UnionMask);
            Assertions.assertEquals(expectedMaskCount, ((UnionMask) unionMask).masks.size());
            Assertions.assertTrue(mask0.size() < unionMask.size());
        }
        /* Check that the significance of dimensions is accurately reported so that space can be saved. */
        Assertions.assertNotEquals(z1 == null && z2 == null, unionMask.isSignificant('Z'));
        Assertions.assertNotEquals(c1 == null && c2 == null, unionMask.isSignificant('C'));
        Assertions.assertNotEquals(t1 == null && t2 == null, unionMask.isSignificant('T'));
        /* Check that the pixel data is as one would expect from a union of the masks. */
        final Rectangle pos012 = pos0.union(pos1.union(pos2));
        for (int t = 0; t < 2; t++) {
            for (int c = 0; c < 2; c++) {
                for (int z = 0; z < 2; z++) {
                    final boolean isMask1 = (z1 == null || z1 == z) && (c1 == null || c1 == c) && (t1 == null || t1 == t);
                    final boolean isMask2 = (z2 == null || z2 == z) && (c2 == null || c2 == c) && (t2 == null || t2 == t);
                    final BiPredicate<Integer, Integer> isMaskedCurrent0 = isMasked0;
                    final BiPredicate<Integer, Integer> isMaskedCurrent1 = isMask1 ? isMasked1 : ALWAYS_NOTHING;
                    final BiPredicate<Integer, Integer> isMaskedCurrent2 = isMask2 ? isMasked2 : ALWAYS_NOTHING;
                    final BiPredicate<Integer, Integer> isMasked = unionMask.getMaskReader(z, c, t);
                    for (int y = pos012.y; y < pos012.y + pos012.height; y++) {
                        for (int x = pos012.x; x < pos012.x + pos012.width; x++) {
                            final boolean expected =
                                    isMaskedCurrent0.test(x, y) || isMaskedCurrent1.test(x, y) || isMaskedCurrent2.test(x, y);
                            final boolean actual = isMasked.test(x, y);
                            Assertions.assertEquals(expected, actual);
                        }
                    }
                }
            }
        }
        /* Note observations. */
        maskCountsObserved.add(expectedMaskCount);
        for (final char dimension : "ZCT".toCharArray()) {
            dimensionSignificancesObserved.put(dimension, unionMask.isSignificant(dimension));
        }
    }

    /**
     * @return mask placements for
     * {@link #testCombiningMasks(int, Integer, Integer, Integer, int, Integer, Integer, Integer, boolean)}
     */
    private static Stream<Arguments> provideMaskPlacements() {
        final Stream.Builder<Arguments> arguments = Stream.builder();
        final List<Integer> planes = Arrays.asList(null, 0, 1);
        for (final boolean isReverse : new boolean[] {false, true}) {
            for (final Integer t1 : planes) {
                for (final Integer c1 : planes) {
                    for (final Integer z1 : planes) {
                        if ((z1 == null ? 1 : 0) + (c1 == null ? 1 : 0) + (t1 == null ? 1 : 0) == 1) {
                            /* To save time, skip cases with two of Z, C, T set. */
                            continue;
                        }
                        for (final Integer t2 : planes.subList(0, 2)) {
                            for (final Integer c2 : planes.subList(0, 2)) {
                                for (final Integer z2 : planes.subList(0, 2)) {
                                    if ((z2 == null ? 1 : 0) + (c2 == null ? 1 : 0) + (t2 == null ? 1 : 0) == 1) {
                                        /* To save time, skip cases with two of Z, C, T set. */
                                        continue;
                                    }
                                    for (int x1 = -1; x1 < 2; x1++) {
                                        for (int x2 = -1; x2 < 2; x2++) {
                                            arguments.add(Arguments.of(x1, z1, c1, t1, x2, z2, c2, t2, isReverse));
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return arguments.build();
    }

    /**
     * Reset the observation notes ready for a test run.
     */
    @BeforeAll
    public void clearObservations() {
        maskCountsObserved.clear();
        dimensionSignificancesObserved.clear();
    }

    /**
     * After a test run check that the coverage was as intended.
     */
    @AfterAll
    public void checkObservations() {
        Assertions.assertEquals(3, maskCountsObserved.size());
        Assertions.assertEquals(6, dimensionSignificancesObserved.size());
    }
}
