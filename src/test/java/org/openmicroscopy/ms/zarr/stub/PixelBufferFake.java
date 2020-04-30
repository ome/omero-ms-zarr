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

import ome.io.nio.PixelBuffer;
import ome.util.PixelData;

import java.awt.Dimension;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import com.google.common.collect.ImmutableList;

import org.mockito.Mockito;

/**
 * A fake {@link PixelBuffer} for providing basic dimensionality and tile methods. Represents a pyramidal tiled image.
 * @author m.t.b.carroll@dundee.ac.uk
 */
public class PixelBufferFake implements PixelBuffer {

    static {
        System.setProperty(PixelData.CONFIG_KEY, "true");
    }

    private final int resolutionLevels = 3;
    private int resolutionLevel = resolutionLevels - 1;

    @Override
    public void close() {
    }

    @Override
    public PixelData getTile(Integer z, Integer c, Integer t, Integer x, Integer y, Integer w, Integer h) {
        final byte[] pixels = new byte[w * h * 2];
        for (int xi = 0; xi < w; xi++) {
            for (int yi = 0; yi < h; yi++) {
                final int wordOffset = 2 * (w * yi + xi);
                pixels[wordOffset] = (byte) xi;
                pixels[wordOffset + 1] = (byte) yi;
            }
        }
        final PixelData tile = Mockito.mock(PixelData.class);
        Mockito.when(tile.getData()).thenReturn(ByteBuffer.wrap(pixels));
        Mockito.when(tile.getOrder()).thenReturn(ByteOrder.LITTLE_ENDIAN);
        return tile;
    }

    @Override
    public int getByteWidth() {
        return 2;
    }

    @Override
    public boolean isSigned() {
        return false;
    }

    @Override
    public boolean isFloat() {
        return false;
    }

    private static int getSizeX(int resolutionLevel) {
        return 800 << resolutionLevel;
    }

    @Override
    public int getSizeX() {
        return getSizeX(resolutionLevel);
    }

    private static int getSizeY(int resolutionLevel) {
        return 640 << resolutionLevel;
    }

    @Override
    public int getSizeY() {
        return getSizeY(resolutionLevel);
    }

    @Override
    public int getSizeZ() {
        return 1;
    }

    @Override
    public int getSizeC() {
        return 3;
    }

    @Override
    public int getSizeT() {
        return 30;
    }

    @Override
    public int getResolutionLevels() {
        return resolutionLevels;
    }

    @Override
    public int getResolutionLevel() {
        return resolutionLevel;
    }

    @Override
    public void setResolutionLevel(int resolutionLevel) {
        this.resolutionLevel = resolutionLevel;
    }

    @Override
    public Dimension getTileSize() {
        return new Dimension(256, 256);
    }

    @Override
    public List<List<Integer>> getResolutionDescriptions() {
        final ImmutableList.Builder<List<Integer>> resolutions = ImmutableList.builderWithExpectedSize(resolutionLevels);
        for (int resolution = 0; resolution < resolutionLevels; resolution++) {
            resolutions.add(ImmutableList.of(getSizeX(resolution), getSizeY(resolution)));
        }
        return resolutions.build().reverse();
    }

    /* All other methods simply throw. */

    @Override
    public void checkBounds(Integer x, Integer y, Integer z, Integer c, Integer t) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Long getPlaneSize() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Integer getRowSize() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Integer getColSize() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Long getStackSize() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Long getTimepointSize() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Long getTotalSize() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Long getHypercubeSize(List<Integer> offset, List<Integer> size, List<Integer> step) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Long getRowOffset(Integer y, Integer z, Integer c, Integer t) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Long getPlaneOffset(Integer z, Integer c, Integer t) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Long getStackOffset(Integer c, Integer t) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Long getTimepointOffset(Integer t) {
        throw new UnsupportedOperationException();
    }

    @Override
    public PixelData getHypercube(List<Integer> offset, List<Integer> size, List<Integer> step) {
        throw new UnsupportedOperationException();
    }

    @Override
    public byte[] getHypercubeDirect(List<Integer> offset, List<Integer> size, List<Integer> step, byte[] buffer) {
        throw new UnsupportedOperationException();
    }

    @Override
    public byte[] getPlaneRegionDirect(Integer z, Integer c, Integer t, Integer count, Integer offset, byte[] buffer) {
        throw new UnsupportedOperationException();
    }

    @Override
    public byte[] getTileDirect(Integer z, Integer c, Integer t, Integer x, Integer y, Integer w, Integer h, byte[] buffer) {
        throw new UnsupportedOperationException();
    }

    @Override
    public PixelData getRegion(Integer size, Long offset) {
        throw new UnsupportedOperationException();
    }

    @Override
    public byte[] getRegionDirect(Integer size, Long offset, byte[] buffer) {
        throw new UnsupportedOperationException();
    }

    @Override
    public PixelData getRow(Integer y, Integer z, Integer c, Integer t) {
        throw new UnsupportedOperationException();
    }

    @Override
    public PixelData getCol(Integer x, Integer z, Integer c, Integer t) {
        throw new UnsupportedOperationException();
    }

    @Override
    public byte[] getRowDirect(Integer y, Integer z, Integer c, Integer t, byte[] buffer) {
        throw new UnsupportedOperationException();
    }

    @Override
    public byte[] getColDirect(Integer x, Integer z, Integer c, Integer t, byte[] buffer) {
        throw new UnsupportedOperationException();
    }

    @Override
    public PixelData getPlane(Integer z, Integer c, Integer t) {
        throw new UnsupportedOperationException();
    }

    @Override
    public PixelData getPlaneRegion(Integer x, Integer y, Integer width, Integer height, Integer z, Integer c, Integer t, Integer stride) {
        throw new UnsupportedOperationException();
    }

    @Override
    public byte[] getPlaneDirect(Integer z, Integer c, Integer t, byte[] buffer) {
        throw new UnsupportedOperationException();
    }

    @Override
    public PixelData getStack(Integer c, Integer t) {
        throw new UnsupportedOperationException();
    }

    @Override
    public byte[] getStackDirect(Integer c, Integer t, byte[] buffer) {
        throw new UnsupportedOperationException();
    }

    @Override
    public PixelData getTimepoint(Integer t) {
        throw new UnsupportedOperationException();
    }

    @Override
    public byte[] getTimepointDirect(Integer t, byte[] buffer) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setTile(byte[] buffer, Integer z, Integer c, Integer t, Integer x, Integer y, Integer w, Integer h) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setRegion(Integer size, Long offset, byte[] buffer) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setRegion(Integer size, Long offset, ByteBuffer buffer) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setRow(ByteBuffer buffer, Integer y, Integer z, Integer c, Integer t) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setPlane(ByteBuffer buffer, Integer z, Integer c, Integer t) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setPlane(byte[] buffer, Integer z, Integer c, Integer t) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setStack(ByteBuffer buffer, Integer z, Integer c, Integer t) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setStack(byte[] buffer, Integer z, Integer c, Integer t) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setTimepoint(ByteBuffer buffer, Integer t) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setTimepoint(byte[] buffer, Integer t) {
        throw new UnsupportedOperationException();
    }

    @Override
    public byte[] calculateMessageDigest() {
        throw new UnsupportedOperationException();
    }

    @Override
    public String getPath() {
        throw new UnsupportedOperationException();
    }

    @Override
    public long getId() {
        throw new UnsupportedOperationException();
    }
}
