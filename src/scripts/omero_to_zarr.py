
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


def get_data(img, c=0):
    """
    Get n-dimensional numpy array of pixel data for the OMERO image.

    :param  img:        omero.gateway.ImageWrapper
    :c      int:        Channel index
    """
    sz = img.getSizeZ()
    st = img.getSizeT()
    # get all planes we need
    zct_list = [(z, c, t) for t in range(st) for z in range(sz)]
    pixels = img.getPrimaryPixels()
    planes = []
    for p in pixels.getPlanes(zct_list):
        planes.append(p)
    # If we don't have multi-Z AND multi-T, return a 1D array of 2D planes
    if sz == 1 or st == 1:
        return numpy.array(planes)
    # arrange plane list into 2D numpy array of planes
    z_stacks = []
    for t in range(st):
        z_stacks.append(numpy.array(planes[t * sz: (t + 1) * sz]))
    return numpy.array(z_stacks)


def image_to_xarray(image):
    
    name = '%s_xarray.zarr' % image.id
    if os.path.exists(name):
        # If exists, ds.to_zarr(name) will fail with: ValueError: path '' contains a group
        print("%s already exists!" % name)
        return
    xr_data = {}

    # we create an xarrary.Dataset: each key is channel index (as string)
    for idx in range(image.getSizeC()):
        print(idx)
        # get e.g. 3D np array for each channel
        data = get_data(image, c=idx)
        print(data.shape)
        xr_data[str(idx)] = (('z', 'y', 'x'), data)

    ds = xr.Dataset(xr_data)
    ds.to_zarr(name)


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

