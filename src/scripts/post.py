#!/usr/bin/env python

# -*- coding: utf-8 -*-

# Copyright (C) 2018-2020 University of Dundee & Open Microscopy Environment.
# All rights reserved.
#
# This program is free software; you can redistribute it and/or modify
# it under the terms of the GNU General Public License as published by
# the Free Software Foundation; either version 2 of the License, or
# (at your option) any later version.
#
# This program is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
# GNU General Public License for more details.
#
# You should have received a copy of the GNU General Public License along
# with this program; if not, write to the Free Software Foundation, Inc.,
# 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.

# POST JSON requests to Zarr microservice
# author: m.t.b.carroll@dundee.ac.uk

import requests

queries = [
    {
        'role': 'guest',
        'type': 'user'
    }, {
        'role': 'system',
        'type': 'group'
    }
]

for query in queries:
    response = requests.post('http://localhost:8080/id', json=query)

    if response.status_code != 200:
        print('{}: {}'.format(response.status_code, response.reason))
    else:
        print(response.json()['id'])
        print('elapsed time: {}\n'.format(response.elapsed))
