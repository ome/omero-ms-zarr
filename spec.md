# Zarr Specification

This document specifies one layout for images within Zarr files. The APIs and
scripts provided by this repository will support one or more versions of this
file, but they should all be considered internal investigations, not intended
for public re-use.

## On-disk (or in-cloud) layout

```

.                             # Root folder, potentially in S3,
│                             # with a flat list of images by image ID.
│
├── 123.zarr                  # One image (id=123) converted to Zarr.
│
└── 456.zarr                  # Another image (id=456) converted to Zarr.
    │
    ├── .zgroup               # Each image is a Zarr group, or a folder, of other groups and arrays.
    ├── .zattrs               # Group level attributes are stored in the .zattrs file and include
    │                         #  "multiscales" and "omero" below)
    │
    ├── 0                     # Each multiscale level is stored as a separate Zarr array,
    │   ...                   # which is a folder containing chunk files which compose the array.
    ├── n                     # The name of the array is arbitrary with the ordering defined by
    │   │                     # by the "multiscales" metadata, but is often a sequence starting at 0.
    │   │
    │   ├── .zarray           # All image arrays are 5-dimensional
    │   │                     # with dimension order (t, c, z, y, x).
    │   │
    │   ├── 0.0.0.0.0         # Chunks are stored with the flat directory layout.
    │   │   ...               # Each dotted component of the chunk file represents
    │   └── t.c.z.y.x         # a "chunk coordinate", where the maximum coordinate
    │                         # will be `dimension_size / chunk_size`.
    │
    └── masks
        │
        ├── .zgroup           # The masks group is a container which holds a list
        ├── .zattrs           # of masks to make the objects easily discoverable,
        │                     # All masks will be listed in `.zattrs` e.g. `{ "masks": [ "original/0" ] }`
        │
        └── original          # Intermediate folders are permitted but not necessary
            │                 # and currently contain no extra metadata.
            └── 0
                ├── .zarray   # Each mask itself is a 5D array matching the highest resolution
                └── .zattrs   # of the related image and has an extra key, "color", with display information.


```

## Metadata

The various `.zattrs` files throughout the above array hierarchy may contain metadata
keys as specified below for discovering certain types of data, especially images.

### "multiscales" metadata

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

###  "omero" metadata

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

### "masks"

The special group "masks" found under an image Zarr contains the key `masks` containing
the paths to mask objects which can be found underneath the group:

```
{
  "masks": [
    "orphaned/0"
  ]
}
```

Unlisted groups MAY be masks.

### "color"

The `color` key defines an image that is "labeled", i.e. every unique value in the image
represents a unique, non-overlapping object within the image. The value associated with
the `color` key is another JSON object in which the key is the pixel value of the image and
the value is an RGBA color (4 byte, `0-255` per channel) for representing the object:

```
{
  "color": {
    "1": 8388736,
    ...
```




| Revision   | Date         | Description                                |
| ---------- | ------------ | ------------------------------------------ |
| 0.1.3      | 2020-07-07   | Add mask metadata                          |
| 0.1.2      | 2020-05-07   | Add description of "omero" metadata        |
| 0.1.1      | 2020-05-06   | Add info on the ordering of resolutions    |
| 0.1.0      | 2020-04-20   | First version for internal demo            |
