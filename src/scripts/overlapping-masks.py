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

# Copy masks from one server to another.
# author: m.t.b.carroll@dundee.ac.uk

from omero.gateway import BlitzGateway
from omero.model import MaskI, RoiI
from omero.rtypes import rstring, rdouble
from omero.sys import ParametersI

import numpy as np

import argparse

parser = argparse.ArgumentParser(
    description="generate fake masks that overlap"
)
parser.add_argument("--to-host", default="localhost")
parser.add_argument("--to-user", default="root")
parser.add_argument("--to-pass", default="omero")
parser.add_argument("target_image", type=int, help="output image")
ns = parser.parse_args()

image_id_dst = ns.target_image

local = BlitzGateway(ns.to_user, ns.to_pass, host=ns.to_host, secure=True)

local.connect()

query_service = local.getQueryService()
update_service = local.getUpdateService()

query = "FROM Image WHERE id = :id"

params = ParametersI()
params.addId(image_id_dst)

count = 0
image = query_service.findByQuery(query, params)


def make_circle(h, w):
    x = np.arange(0, w)
    y = np.arange(0, h)
    arr = np.zeros((y.size, x.size), dtype=bool)

    cx = w // 2
    cy = h // 2
    r = min(w, h) // 2

    mask = (x[np.newaxis, :] - cx) ** 2 + (y[:, np.newaxis] - cy) ** 2 < r ** 2
    arr[mask] = 1
    arr = np.packbits(arr)
    return arr


def make_mask(x, y, h, w):
    mask = MaskI()
    mask.x = rdouble(x)
    mask.y = rdouble(y)
    mask.height = rdouble(h)
    mask.width = rdouble(w)
    mask.bytes = make_circle(h, w)

    roi = RoiI()
    roi.description = rstring("created by overlapping-masks.py")
    roi.addShape(mask)
    roi.image = image
    roi = update_service.saveAndReturnObject(roi)
    print(f"Roi:{roi.id.val}")


make_mask(20, 20, 40, 40)
make_mask(30, 30, 50, 50)
local._closeSession()
