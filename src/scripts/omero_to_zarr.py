
import argparse
import sys
import os

import omero.clients
from omero.cli import cli_login
from omero.gateway import BlitzGateway

import xarray as xr
import numpy
import zarr

# Uses CLI login to load Image from OMERO and save as zarr:

# $ python omero_to_zarr.py 123
# will create 123.zarr

# $ python omero_to_zarr.py 123 --xarray
# creates 123_xarray.zarr

def get_planes(image):

    size_c = image.getSizeC()
    size_z = image.getSizeZ()
    size_t = image.getSizeT()
    pixels = image.getPrimaryPixels()

    zct_list = []
    for c in range(size_c):
        for t in range(size_t):
            for z in range(size_z):
                zct_list.append( (z,c,t) )

    def plane_gen():
        planes = pixels.getPlanes(zct_list)
        for p in planes:
            yield p
    
    return plane_gen()

def image_to_xarray(image):

    size_c = image.getSizeC()
    size_z = image.getSizeZ()
    size_t = image.getSizeT()

    name = '%s_xarray.zarr' % image.id
    if os.path.exists(name):
        # If exists, ds.to_zarr(name) will fail with: ValueError: path '' contains a group
        print("%s already exists!" % name)
        return
    xr_data = {}

    planes = get_planes(image)

    xr_data = {}
    for c in range(size_c):
        t_stacks = []
        for t in range(size_t):
            z_stack = []
            for z in range(size_z):
                print('plane c:%s, t:%s, z:%s' % (c, t, z))
                z_stack.append(next(planes))
            t_stacks.append(numpy.array(z_stack))
        t_stacks = numpy.array(t_stacks)
        print('t_stacks', t_stacks.shape)
        xr_data[str(c)] = (('t', 'z', 'y', 'x'), t_stacks)
    ds = xr.Dataset(xr_data)
    ds.to_zarr(name)
    print("Created", name)


def image_to_zarr(image):

    size_c = image.getSizeC()
    size_z = image.getSizeZ()
    size_x = image.getSizeX()
    size_y = image.getSizeY()
    size_t = image.getSizeT()

    name = '%s.zarr' % image.id
    za = None
    pixels = image.getPrimaryPixels()

    zct_list = []
    for z in range(size_z):
        for c in range(size_c):
            for t in range(size_t):
                zct_list.append( (z,c,t) )

    def planeGen():
        planes = pixels.getPlanes(zct_list)
        for p in planes:
            yield p

    planes = planeGen()

    for z in range(size_z):
        print(z)
        for c in range(size_c):
            for t in range(size_t):
                plane = next(planes)
                if za is None:
                    za = zarr.open(
                        name,
                        mode='w',
                        shape=(size_c, size_z, size_t, size_y, size_x),
                        chunks=(1, 1, 1, size_y, size_x),
                        dtype=plane.dtype
                    )
                print(plane.shape)
                za[c, z, t, :, :] = plane
    print("Created", name)


def main(argv):
    parser = argparse.ArgumentParser()
    parser.add_argument('image_id', help='Image ID')
    parser.add_argument(
        "--xarray",
        action="store_true",
        help=("Save as xarray, suitable for xpublish")
    )
    args = parser.parse_args(argv)

    with cli_login() as cli:
        conn = BlitzGateway(client_obj=cli._client)
        image = conn.getObject('Image', args.image_id)
        if args.xarray:
            image_to_xarray(image)
        else:
            image_to_zarr(image)

if __name__ == '__main__':
    main(sys.argv[1:])

