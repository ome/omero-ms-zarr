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

import java.util.Collections;

import org.hibernate.engine.FilterDefinition;

/**
 * An empty Hibernate filter definition to stub out OMERO permissions checks.
 * @author m.t.b.carroll@dundee.ac.uk
 */
public class NullFilterDefinition extends FilterDefinition {

    private static final long serialVersionUID = 4966889875942484074L;

    public NullFilterDefinition(String name) {
        super(name, "true", Collections.emptyMap());
    }
}
