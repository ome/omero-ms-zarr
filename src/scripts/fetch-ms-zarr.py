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
import argparse

parser = argparse.ArgumentParser(
    formatter_class=argparse.ArgumentDefaultsHelpFormatter)
parser.add_argument(
    "--dry-run", action="store_true",
    help="Don't actually download. Only check for existence")
parser.add_argument(
    "--endpoint_url",
    default="https://s3.embassy.ebi.ac.uk/",
    help="Choose which service for download")
parser.add_argument(
    "--url_format",
    default="{url}idr/zarr/v0.1/{image}.zarr/",
    help="Format for the layout of URLs on the given service")
parser.add_argument("image", type=int)
args = parser.parse_args()

image = args.image
url = args.endpoint_url
base_uri = args.url_format.format(image=image, url=url)

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

multiscales = zattrs['multiscales']
is_multiscale = len(multiscales) > 0
if is_multiscale:
    # Use only the first
    multiscale = multiscales[0]
    datasets = multiscale["datasets"]


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
        chunk_name_server = '.'.join(map(str, chunk))  # flat remotely
        chunk_name_client = '.'.join(map(str, chunk))  # flat locally
        if args.dry_run:
            response = requests.head(dataset_uri + chunk_name_server)
            if response.status_code != 200:
                print('check failed for chunk {}'.format(chunk_name_server))
                sys.exit(2)
            continue


        response = requests.get(dataset_uri + chunk_name_server)
        if response.status_code == 200:
            filename = local_prefix + chunk_name_client
            parent_dir = os.path.dirname(filename)
            if parent_dir:
                os.makedirs(parent_dir, exist_ok=True)
            with open(filename, 'wb') as file:
                file.write(response.content)
        else:
            print('failed to fetch chunk {}'.format(chunk_name_server))
            sys.exit(2)

    print(json.dumps(zarray), file=open(local_prefix + '.zarray', 'w'))

if is_multiscale:
    print(json.dumps(zgroup), file=open('.zgroup', 'w'))
    print(json.dumps(zattrs), file=open('.zattrs', 'w'))
