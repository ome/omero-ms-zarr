# Zarr Specification

This document specifies one layout for images within Zarr files. The APIs and
scripts provided by this repository will support one or more versions of this
file, but they should all be considered internal investigations, not intended
for public re-use.

## Basic layout

```
.                             # Root folder, potentially in S3,
├── 123.zarr                  # with a flat list of images by image ID.
└── 456.zarr                  #
    ├── .zgroup               # Each image is a Zarr group with multscale metadata.
    └── 0                     # Each multiscale level is stored as a separate Zarr array.
        ├── .zarray           #
        ├── 0.0.0.0.0         # Chunks are stored with the flat directory layout.
        └── t.c.z.y.x         # All image arrays are 5-dimensional
                              # with dimension order (t, c, z, y, x).
```

## Multiscale metadata

The specification for the multiscale (i.e. "resolution") metadata is provided
in [zarr-specs#50](https://github.com/zarr-developers/zarr-specs/issues/50).
If only one multiscale is provided, use it. Otherwise, the user can choose by
name, using the first multiscale as a fallback:

```
datasets = []
for named in multiscales:
    if named['name'] == '3D':
        datasets = [x['path'] for x in named["datasets"]]
        break
if not datasets:
    # Use the first by default. Or perhaps choose based on chunk size.
    datasets = [x['path'] for x in multiscales[0]["datasets"]]
```

The subresolutions in each multiscale are ordered from highest-resolution
to lowest.


| Revision | Date       | Description                              |
|----------|------------|------------------------------------------|
| -        | 2020-05-06 | Add info on the ordering of resolutions  |
| 0.1      | 2020-04-20 | First version for internal demo          |
