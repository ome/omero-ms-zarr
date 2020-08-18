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

import ome.model.core.Pixels;

import org.hibernate.Query;
import org.hibernate.SessionFactory;
import org.hibernate.classic.Session;

import org.mockito.Mockito;
import org.openmicroscopy.ms.zarr.OmeroDao;

/**
 * Base class that sets up a simple DAO that always fetches the mock pixels object.
 * @author m.t.b.carroll@dundee.ac.uk
 * @since v0.1.7
 */
public abstract class ZarrEndpointsImageTestBase extends ZarrEndpointsTestBase {

    @Override
    protected OmeroDao daoSetup() {
        final Pixels pixels = constructMockPixels();
        final Query query = Mockito.mock(Query.class);
        final Session session = Mockito.mock(Session.class);
        final SessionFactory sessionFactory = Mockito.mock(SessionFactory.class);
        Mockito.when(query.uniqueResult()).thenReturn(pixels);
        Mockito.when(query.setParameter(Mockito.eq(0), Mockito.anyLong())).thenReturn(query);
        Mockito.when(session.createQuery(Mockito.anyString())).thenReturn(query);
        Mockito.when(sessionFactory.openSession()).thenReturn(session);
        return new OmeroDao(sessionFactory);
    }
}
