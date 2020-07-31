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

import ome.io.nio.PixelsService;
import ome.model.core.Pixels;
import ome.model.roi.Mask;
import ome.model.roi.Roi;

import java.util.Iterator;
import java.util.SortedSet;
import java.util.function.Function;

import com.google.common.collect.ImmutableSortedSet;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Data Access Object for OMERO's database.
 * @author m.t.b.carroll@dundee.ac.uk
 */
class OmeroDao {

    private static final Logger LOGGER = LoggerFactory.getLogger(OmeroDao.class);

    private final SessionFactory sessionFactory;

    /**
     * @param sessionFactory the Hibernate session factory
     */
    OmeroDao(SessionFactory sessionFactory) {
        this.sessionFactory = sessionFactory;
    }

    /**
     * Provide a Hibernate session to a means of getting data from it.
     * @param <X> the type of data to be gotten
     * @param getter a getter for the data
     * @return the sought data
     */
    private <X> X withSession(Function<Session, X> getter) {
        Session session = null;
        try {
            session = sessionFactory.openSession();
            session.setDefaultReadOnly(true);
            return getter.apply(session);
        } finally {
            if (session != null) {
                session.close();
            }
        }
    }

    /**
     * Get a pixels instance that can be used with {@link PixelsService#getPixelBuffer(Pixels, boolean)}.
     * Includes extra {@code JOIN}s for {@link RequestHandlerForImage}.
     * @param imageId the ID of an image
     * @return that image's pixels instance, or {@code null} if one could not be found
     */
    Pixels getPixels(long imageId) {
        LOGGER.debug("fetch Pixels for Image:{}", imageId);
        final String hql =
                "SELECT p FROM Pixels AS p " +
                "JOIN FETCH p.pixelsType " +
                "LEFT OUTER JOIN FETCH p.channels AS c " +
                "LEFT OUTER JOIN FETCH p.image AS i " +
                "LEFT OUTER JOIN FETCH p.settings AS r " +
                "LEFT OUTER JOIN FETCH c.logicalChannel " +
                "LEFT OUTER JOIN FETCH c.statsInfo " +
                "LEFT OUTER JOIN FETCH r.model " +
                "LEFT OUTER JOIN FETCH r.waveRendering AS cb " +
                "LEFT OUTER JOIN FETCH cb.family " +
                "LEFT OUTER JOIN FETCH cb.spatialDomainEnhancement " +
                "WHERE i.id = ?";
        return withSession(session ->
            (Pixels) session.createQuery(hql).setParameter(0, imageId).uniqueResult());
    }

    long getRoiCountOfImage(long imageId) {
        LOGGER.debug("fetch Roi count for Image:{}", imageId);
        final String hql =
                "SELECT COUNT(id) FROM Roi " +
                "WHERE image.id = ?";
        return withSession(session ->
            (Long) session.createQuery(hql).setParameter(0, imageId).uniqueResult());
    }

    SortedSet<Long> getRoiIdsOfImage(long imageId) {
        LOGGER.debug("fetch Roi IDs for Image:{}", imageId);
        final String hql =
                "SELECT id FROM Roi " +
                "WHERE image.id = ?";
        return withSession(session ->
            ImmutableSortedSet.copyOf((Iterator<Long>) session.createQuery(hql).setParameter(0, imageId).iterate()));
    }

    long getMaskCountOfRoi(long roiId) {
        LOGGER.debug("fetch Mask count for Roi:{}", roiId);
        final String hql =
                "SELECT COUNT(id) FROM Mask " +
                "WHERE roi.id = ?";
        return withSession(session ->
            (Long) session.createQuery(hql).setParameter(0, roiId).uniqueResult());
    }

    SortedSet<Long> getMaskIdsOfRoi(long roiId) {
        LOGGER.debug("fetch Mask IDs for Roi:{}", roiId);
        final String hql =
                "SELECT id FROM Mask " +
                "WHERE roi.id = ?";
        return withSession(session ->
            ImmutableSortedSet.copyOf((Iterator<Long>) session.createQuery(hql).setParameter(0, roiId).iterate()));
    }

    Roi getRoi(long roiId) {
        LOGGER.debug("fetch Roi:{}", roiId);
        return withSession(session ->
            (Roi) session.get(Roi.class, roiId));
    }

    Mask getMask(long maskId) {
        LOGGER.debug("fetch Mask:{}", maskId);
        return withSession(session ->
            (Mask) session.get(Mask.class, maskId));
    }
}
