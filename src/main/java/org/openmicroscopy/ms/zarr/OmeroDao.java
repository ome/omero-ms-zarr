package org.openmicroscopy.ms.zarr;

import ome.io.nio.PixelsService;
import ome.model.core.Pixels;

import org.hibernate.Query;
import org.hibernate.Session;
import org.hibernate.SessionFactory;

/**
 * Data Access Object for OMERO's database.
 * @author m.t.b.carroll@dundee.ac.uk
 */
class OmeroDao {

    private final SessionFactory sessionFactory;

    /**
     * @param sessionFactory the Hibernate session factory
     */
    OmeroDao(SessionFactory sessionFactory) {
        this.sessionFactory = sessionFactory;
    }

    /**
     * Get a pixels instance that can be used with {@link PixelsService#getPixelBuffer(Pixels, boolean)}.
     * Includes extra {@code JOIN}s for {@link RequestHandlerForImage}.
     * @param imageId the ID of an image
     * @return that image's pixels instance, or {@code null} if one could not be found
     */
    Pixels getPixels(long imageId) {
        Session session = null;
        try {
            session = sessionFactory.openSession();
            session.setDefaultReadOnly(true);
            final Query query = session.createQuery(
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
                    "WHERE i.id = ?");
            query.setParameter(0, imageId);
            return (Pixels) query.uniqueResult();
        } finally {
            if (session != null) {
                session.close();
            }
        }
    }
}
