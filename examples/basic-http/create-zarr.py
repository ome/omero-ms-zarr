#!/usr/bin/env python
import zarr
store = zarr.DirectoryStore('dir.zarr')
group = zarr.group(store=store, overwrite=True)
group.attrs["example"] = True
z = group.zeros("test", shape=(4, 4))
z.attrs["image"] = False
z[:] = 4
