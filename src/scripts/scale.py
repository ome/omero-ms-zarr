#!/usr/bin/env python

#
# conda packages required:
# - opencv
# - py-opencv
# - scipy
# - scikit-image
# - zarr
#

import argparse
import os

import cv2
import zarr
import numpy as np
from scipy.ndimage import zoom
from skimage.transform import (
    downscale_local_mean,
    pyramid_gaussian,
    pyramid_laplacian,
)

METHODS = (
    "nearest",
    "zoom",
    "local_mean",
    "gaussian",
    "laplacian",
)

parser = argparse.ArgumentParser()
parser.add_argument("input_array")
parser.add_argument("output_directory")
parser.add_argument(
    "--labeled", action="store_true",
    help="assert that the list of unique pixel values doesn't change",
)
parser.add_argument(
    "--copy-metadata", action="store_true",
    help="copies the array metadata to the new group",
)
parser.add_argument("--method", choices=METHODS, default="nearest")
parser.add_argument(
    "--in-place",
    action="store_true",
    help="if true, don't write the base array"
)
parser.add_argument("--downscale", type=int, default=2)
parser.add_argument("--max_layer", type=int, default=4)
ns = parser.parse_args()

if ns.method == "nearest":

    def method(base):
        rv = [base]

        for i in range(ns.max_layer):
            fiveD = rv[-1]
            # FIXME: fix hard-coding of dimensions
            T, C, Z, Y, X = fiveD.shape

            smaller = None
            for t in range(T):
                for c in range(C):
                    for z in range(Z):
                        out = cv2.resize(
                            fiveD[t][c][z][:],
                            dsize=(Y//ns.downscale, X//ns.downscale),
                            interpolation=cv2.INTER_NEAREST)
                        if smaller is None:
                            smaller = np.zeros((T, C, Z, out.shape[0], out.shape[1]))
                        smaller[t][c][z] = out
            rv.append(smaller)
        return rv

if ns.method == "gaussian":

    def method(base):
        return list(pyramid_gaussian(base, downscale=ns.downscale, max_layer=ns.max_layer, multichannel=False))

elif ns.method == "laplacian":

    def method(base):
        return list(pyramid_laplacian(base, downscale=ns.downscale, max_layer=ns.max_layer, multichannel=False))

elif ns.method == "local_mean":

    def method(base):
        # FIXME: fix hard-coding
        rv = [base]
        for i in range(ns.max_layer):
            rv.append(downscale_local_mean(rv[-1], factors=(1, 1, 1, ns.downscale, ns.downscale)))
        return rv

elif ns.method == "zoom":

    def method(base):
        rv = [base]
        print(base.shape)
        for i in range(ns.max_layer):
            print(i, ns.downscale)
            rv.append(zoom(base, ns.downscale**i))
            print(rv[-1].shape)
        return list(reversed(rv))

else:
    assert f"unknown method: {ns.method}"


# 0. check that the output doesn't exist
assert not os.path.exists(ns.output_directory)
store = zarr.DirectoryStore(ns.output_directory)


# 1. open and create the pyramid
base = zarr.open_array(ns.input_array)
pyramid = method(base)


# 2. assert values
if ns.labeled:
    expected = set(np.unique(pyramid[0]))
    print(f"level 0 {pyramid[0].shape} = {len(expected)} labels")
    for i in range(1, len(pyramid)):
        level = pyramid[i]
        print(f"level {i}", pyramid[i].shape, len(expected))
        found = set(np.unique(level))
        if not expected.issuperset(found):
            raise Exception(f"{len(found)} found values are not a subset of {len(expected)} values")


# 3. prepare the output store
grp = zarr.group(store)
grp.create_dataset("base", data=base)

if ns.copy_metadata:
    print(f"copying attribute keys: {list(base.attrs.keys())}")
    grp.attrs.update(base.attrs)


# 4. generate datasets
series = []
for i, dataset in enumerate(pyramid):
    if i == 0:
        path = "base"
    else:
        path = "%s" % i
        grp.create_dataset(path, data=pyramid[i])
    series.append({"path": path})

# 4. generate metadata
multiscales = []
multiscale = {
    "version": "0.1",
    "name": "default",
    "datasets": series,
    "type": ns.method,
}
multiscales.append(multiscale)
grp.attrs["multiscales"] = multiscales
