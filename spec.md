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
        └── 0                 # Chunks are stored with the nested directory layout.
            └── 0             #
                └── 0         # All image arrays are 5-dimensional
                    └── 0     # with dimension order (t, c, z, y, x).
                        └── 0 #
```


see also:
 - [Multiscale metadata spec](https://github.com/zarr-developers/zarr-specs/issues/50)

| Revision | Date       | Description                              |
|----------|------------|------------------------------------------|
| 0.1      | 2020-04-20 | First version for internal demo          |
