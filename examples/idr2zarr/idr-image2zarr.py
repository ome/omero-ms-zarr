from glob import glob
import itertools
import numpy as np
import os
import re
import sys
import zarr
from omero.gateway import BlitzGateway

if len(sys.argv) != 2:
    print('IDR image ID required')
    sys.exit(2)

iid = int(sys.argv[1])

# Download all image planes individually as npy arrays
# so that if there's an error we can continue
print('Downloading IDR image ID: {}'.format(iid))
conn = BlitzGateway(
    host='idr.openmicroscopy.org', username='public', passwd='public',
    secure=True)
assert conn.connect()
im = conn.getObject('Image', iid)

shape = [getattr(im, 'getSize' + d)() for d in 'XYZCT']
print(shape)

px = im.getPrimaryPixels()
zcts = itertools.product(*map(range, shape[2:]))
os.makedirs(str(iid), mode=511, exist_ok=True)

for (z, c, t) in zcts:
    filename = '{}/{:03d}-{:03d}-{:03d}.npy'.format(iid, z, c, t)
    if os.path.exists(filename):
        print('{} exists, skipping'.format(filename))
        continue
    print('Saving {}'.format(filename))
    p = px.getPlane(z, c, t)
    np.save(filename, p)

conn.close()


# Now convert the downloaded planes
srcglob = '{}/*.npy'.format(iid)
output = '{}.zarr'.format(iid)
print('Converting {} to {}'.format(srcglob, output))
files = sorted(glob(srcglob))
lastd = re.match(
    r'(?P<iid>\d+)/(?P<z>\d+)-(?P<c>\d+)-(?P<t>\d+).npy', files[-1]
    ).groupdict()
for k, v in lastd.items():
    lastd[k] = int(v)

first = np.load(files[0])
chunksize = (first.shape[0], first.shape[0], 5, 1, 5)

za = zarr.open(
    output,
    mode='w',
    shape=(first.shape[0], first.shape[0],
           lastd['z'] + 1, lastd['c'] + 1, lastd['t'] + 1),
    chunks=chunksize,
    dtype=first.dtype)

for z in range(lastd['z'] + 1):
    print(z)
    for c in range(lastd['c'] + 1):
        for t in range(lastd['t'] + 1):
            f = '{}/{:03d}-{:03d}-{:03d}.npy'.format(iid, z, c, t)
            za[:, :, z, c, t] = np.load(f)

print(za)
