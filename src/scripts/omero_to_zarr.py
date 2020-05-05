import argparse
import sys
import os

import omero.clients
from omero.cli import cli_login
from omero.gateway import BlitzGateway

import numpy
import zarr

# Uses CLI login to load Image from OMERO and save as zarr:

# $ python omero_to_zarr.py 123
# will create 123.zarr


def image_to_zarr(image):

    size_c = image.getSizeC()
    size_z = image.getSizeZ()
    size_x = image.getSizeX()
    size_y = image.getSizeY()
    size_t = image.getSizeT()

    # dir for caching .npy planes
    os.makedirs(str(image.id), mode=511, exist_ok=True)
    name = "%s.zarr" % image.id
    za = None
    pixels = image.getPrimaryPixels()

    zct_list = []
    for t in range(size_t):
        for c in range(size_c):
            for z in range(size_z):
                # We only want to load from server if not cached locally
                filename = "{}/{:03d}-{:03d}-{:03d}.npy".format(
                    image.id, z, c, t
                )
                if not os.path.exists(filename):
                    zct_list.append((z, c, t))

    def planeGen():
        planes = pixels.getPlanes(zct_list)
        for p in planes:
            yield p

    planes = planeGen()

    for t in range(size_t):
        for c in range(size_c):
            for z in range(size_z):
                filename = "{}/{:03d}-{:03d}-{:03d}.npy".format(
                    image.id, z, c, t
                )
                if os.path.exists(filename):
                    print("plane (from disk) c:%s, t:%s, z:%s" % (c, t, z))
                    plane = numpy.load(filename)
                else:
                    print("loading plane c:%s, t:%s, z:%s" % (c, t, z))
                    plane = next(planes)
                    numpy.save(filename, plane)
                if za is None:
                    store = zarr.DirectoryStore(name)
                    root = zarr.group(store=store, overwrite=True)
                    za = root.create(
                        "0",
                        shape=(size_t, size_c, size_z, size_y, size_x),
                        chunks=(1, 1, 1, size_y, size_x),
                        dtype=plane.dtype,
                    )
                print("t, c, z,", t, c, z, plane.shape)
                za[t, c, z, :, :] = plane
    print("Created", name)


def main(argv):
    parser = argparse.ArgumentParser()
    parser.add_argument("image_id", help="Image ID")
    args = parser.parse_args(argv)

    with cli_login() as cli:
        conn = BlitzGateway(client_obj=cli._client)
        image = conn.getObject("Image", args.image_id)
        image_to_zarr(image)


if __name__ == "__main__":
    main(sys.argv[1:])
