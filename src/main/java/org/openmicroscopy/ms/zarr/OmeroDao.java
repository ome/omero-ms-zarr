package org.openmicroscopy.ms.zarr;

import ome.io.nio.PixelsService;
import ome.model.core.Pixels;

import java.util.function.Function;

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
}
