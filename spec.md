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
    ├── .zattrs               # Group level metadata.
    ├── .zgroup               # Each image is a Zarr group with multscale metadata.
    └── 0                     # Each multiscale level is stored as a separate Zarr array.
        ├── .zarray           #
        ├── 0.0.0.0.0         # Chunks are stored with the flat directory layout.
        └── t.c.z.y.x         # All image arrays are 5-dimensional
                              # with dimension order (t, c, z, y, x).
```

## "multiscales" metadata

Metadata about the multiple resolution representations of the image can be
found under the "multiscales" key in the group-level metadata.
The specification for the multiscale (i.e. "resolution") metadata is provided
in [zarr-specs#50](https://github.com/zarr-developers/zarr-specs/issues/50).
If only one multiscale is provided, use it. Otherwise, the user can choose by
name, using the first multiscale as a fallback:

```
datasets = []
for named in multiscales:
    if named["name"] == "3D":
        datasets = [x["path"] for x in named["datasets"]]
        break
if not datasets:
    # Use the first by default. Or perhaps choose based on chunk size.
    datasets = [x["path"] for x in multiscales[0]["datasets"]]
```

The subresolutions in each multiscale are ordered from highest-resolution
to lowest.

##  "omero" metadata

Information specific to the channels of an image and how to render it
can be found under the "omero" key in the group-level metadata:

```
"id": 1,                              # ID in OMERO
"name": "example.tif",                # Name as shown in the UI
"channels": [                         # Array matching the c dimension size
    {
        "active": true,
        "coefficient": 1,
        "color": "0000FF",
        "family": "linear",
        "inverted": false,
        "label": "LaminB1",
        "window": {
            "end": 1500,
            "max": 65535,
            "min": 0,
            "start": 0
        }
    }
],
"rdefs": {
    "defaultT": 0,                    # First timepoint to show the user
    "defaultZ": 118,                  # First Z section to show the user
    "model": "color"                  # "color" or "greyscale"
}
```


See https://docs.openmicroscopy.org/omero/5.6.1/developers/Web/WebGateway.html#imgdata
for more information.


| Revision   | Date         | Description                                |
| ---------- | ------------ | ------------------------------------------ |
| -          | 2020-05-07   | Add description of "omero" metadata        |
| -          | 2020-05-06   | Add info on the ordering of resolutions    |
| 0.1        | 2020-04-20   | First version for internal demo            |
