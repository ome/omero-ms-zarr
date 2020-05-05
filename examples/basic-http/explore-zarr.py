#!/usr/bin/env python

import zarr
from zarr.storage import FSStore

store = FSStore("http://localhost:8000")
group = zarr.group(store=store)
print(group.attrs["example"])
test = group.test
print(test.attrs["image"])
print(test[:])
