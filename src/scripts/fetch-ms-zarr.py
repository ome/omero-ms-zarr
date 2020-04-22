#!/usr/bin/env python

# Copyright (C) 2020 University of Dundee & Open Microscopy Environment.
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

# Get nested Zarr files by HTTP and save them locally.
# author: m.t.b.carroll@dundee.ac.uk

import itertools
import json
import os
import requests
import sys

[_, server, port, image] = sys.argv

base_uri = 'http://{}:{}/image/{}/'.format(server, port, image)

response = requests.get(base_uri + '.zgroup')
if response.status_code == 200:
    zgroup = response.json()
else:
    print('no image found at {}'.format(base_uri))
    sys.exit(2)

response = requests.get(base_uri + '.zattrs')
if response.status_code == 200:
    zattrs = response.json()
else:
    print('no image found at {}'.format(base_uri))
    sys.exit(2)

datasets = zattrs['multiscales']['datasets']
is_multiscale = len(datasets) > 1

for dataset in datasets:
    dataset_path = dataset['path'] + '/'
    dataset_uri = base_uri + dataset_path
    local_prefix = dataset_path if is_multiscale else ''

    response = requests.get(dataset_uri + '.zarray')
    if response.status_code == 200:
        zarray = response.json()
    else:
        print('no resolution found at {}'.format(dataset_uri))
        sys.exit(2)

    shape = zarray['shape']
    chunks = zarray['chunks']
    ranges = [range(0, -(-s // c)) for (s, c) in zip(shape, chunks)]
    for chunk in itertools.product(*ranges):
        response = requests.get(dataset_uri + '/'.join(map(str, chunk)))
        if response.status_code == 200:
            filename = local_prefix + '/'.join(map(str, chunk))
            os.makedirs(os.path.dirname(filename), exist_ok=True)
            with open(filename, 'wb') as file:
                file.write(response.content)
        else:
            print('failed to fetch chunk {}'.format('.'.join(chunk)))
            sys.exit(2)

    print(json.dumps(zarray), file=open(local_prefix + '.zarray', 'w'))

if is_multiscale:
    print(json.dumps(zgroup), file=open('.zgroup', 'w'))
    print(json.dumps(zattrs), file=open('.zattrs', 'w'))
