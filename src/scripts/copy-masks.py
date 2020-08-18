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
from omero.model import ImageI, MaskI, RoiI
from omero.rtypes import rstring
from omero.sys import ParametersI

import argparse

parser = argparse.ArgumentParser(
    description="copy masks from an image on one server to another"
)
parser.add_argument("--from-host", default="idr.openmicroscopy.org")
parser.add_argument("--from-user", default="public")
parser.add_argument("--from-pass", default="public")
parser.add_argument("--to-host", default="localhost")
parser.add_argument("--to-user", default="root")
parser.add_argument("--to-pass", default="omero")
parser.add_argument("source_image", type=int, help="input image")
parser.add_argument("target_image", type=int, help="output image")
ns = parser.parse_args()

image_id_src = ns.source_image
image_id_dst = ns.target_image

idr = BlitzGateway(ns.from_user, ns.from_pass, host=ns.from_host, secure=True)
local = BlitzGateway(ns.to_user, ns.to_pass, host=ns.to_host, secure=True)

idr.connect()
local.connect()

query_service = idr.getQueryService()
update_service = local.getUpdateService()

query = "FROM Mask WHERE roi.image.id = :id"

params = ParametersI()
params.addId(image_id_src)

count = 0

for mask_src in query_service.findAllByQuery(query, params):
    mask_dst = MaskI()
    mask_dst.x = mask_src.x
    mask_dst.y = mask_src.y
    mask_dst.width = mask_src.width
    mask_dst.height = mask_src.height
    mask_dst.theZ = mask_src.theZ
    mask_dst.theC = mask_src.theC
    mask_dst.theT = mask_src.theT
    mask_dst.bytes = mask_src.bytes
    mask_dst.fillColor = mask_src.fillColor
    mask_dst.transform = mask_src.transform
    roi_dst = RoiI()
    roi_dst.description = rstring(
        "created by copy-masks script for original mask #{}".format(
            mask_src.id.val
        )
    )
    roi_dst.image = ImageI(image_id_dst, False)
    roi_dst.addShape(mask_dst)
    update_service.saveObject(roi_dst)
    count += 1

idr._closeSession()
local._closeSession()

print(
    "from image #{} to #{}, mask count = {}".format(
        image_id_src, image_id_dst, count
    )
)
