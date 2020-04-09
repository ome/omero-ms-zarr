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

import ome.security.SecurityFilter;
import ome.security.SecurityFilterHolder;
import ome.security.basic.CurrentDetails;

/**
 * A vacuous security filter holder that stubs out OMERO permissions checks.
 * @author m.t.b.carroll@dundee.ac.uk
 */
public class NullSecurityFilterHolder extends SecurityFilterHolder {

    private static final SecurityFilter SECURITY_FILTER = new NullSecurityFilter();

    public NullSecurityFilterHolder(CurrentDetails cd) {
        super(cd, null, null, null);
    }

    @Override
    public SecurityFilter choose() {
        return SECURITY_FILTER;
    }
}
