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

import java.util.Map;

import ome.model.internal.Details;
import ome.security.SecurityFilter;
import ome.system.EventContext;

import org.hibernate.Session;

/**
 * A vacuous thread-safe Hibernate filter used by {@link NullSecurityFilterHolder} to stub out OMERO permissions checks.
 * @author m.t.b.carroll@dundee.ac.uk
 */
class NullSecurityFilter implements SecurityFilter {

    public NullSecurityFilter() {
    }

    @Override
    public String getName() {
        return getClass().getSimpleName();
    }

    @Override
    public Map<String, String> getParameterTypes() {
        return null;
    }

    @Override
    public String getDefaultCondition() {
        return "true";
    }

    @Override
    public boolean passesFilter(Session session, Details d, EventContext ec) {
        return true;
    }

    @Override
    public void enable(Session session, EventContext ec) {
    }

    @Override
    public void disable(Session session) {
    }
}
