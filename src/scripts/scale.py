#!/usr/bin/env python
import argparse
import os
import zarr
from skimage.transform import pyramid_gaussian, pyramid_laplacian

parser = argparse.ArgumentParser()
parser.add_argument("input_array")
parser.add_argument("output_directory")
parser.add_argument(
    "--method", choices=("gaussian", "laplacian"), default="gaussian"
)
parser.add_argument("--downscale", type=int, default=2)
parser.add_argument("--max_layer", type=int, default=4)
ns = parser.parse_args()

if ns.method == "gaussian":
    method = pyramid_gaussian
else:
    method = pyramid_laplacian


# 1. Graph
base = zarr.open_array(ns.input_array)
pyramid = [base]
pyramid.extend(method(base, downscale=2, max_layer=4, multichannel=False))

assert not os.path.exists(ns.output_directory)
store = zarr.DirectoryStore(ns.output_directory)
grp = zarr.group(store)
grp.create_dataset("base", data=base)


# 2. Generate datasets
series = []
for i, dataset in enumerate(pyramid):
    if i == 0:
        path = "base"
    else:
        path = "%s" % i
        grp.create_dataset(path, data=pyramid[i])
    series.append({"path": path})

# 3. Generate metadata block
multiscales = []
multiscale = {
    "version": "0.1",
    "name": "default",
    "datasets": series,
    "type": ns.method,
}
multiscales.append(multiscale)
grp.attrs["multiscales"] = multiscales
