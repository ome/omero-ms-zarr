# Zarr Specification

This document specifies one layout for images within Zarr files. The APIs and
scripts provided by this repository will support one or more versions of this
file, but they should all be considered internal investigations, not intended
for public re-use.

The key words “MUST”, “MUST NOT”, “REQUIRED”, “SHALL”, “SHALL NOT”, “SHOULD”,
“SHOULD NOT”, “RECOMMENDED”, “MAY”, and “OPTIONAL” in this document are to be
interpreted as described in [RFC 2119](https://www.ietf.org/rfc/rfc2119.txt).

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
    └── labels
        │
        ├── .zgroup           # The labels group is a container which holds a list of labels to make the objects easily discoverable
        │
        ├── .zattrs           # All labels will be listed in `.zattrs` e.g. `{ "labels": [ "original/0" ] }`
        │                     # Each dimension of the label `(t, c, z, y, x)` should be either the same as the
        │                     # corresponding dimension of the image, or `1` if that dimension of the label
        │                     # is irrelevant.
        │
        └── original          # Intermediate folders are permitted but not necessary and currently contain no extra metadata.
            │
            └── 0             # Multiscale, labeled image. The name is unimportant but is registered in the "labels" group above.
                ├── .zgroup   # Zarr Group which is both a multiscaled image as well as a labeled image.
                ├── .zattrs   # Metadata of the related image and as well as display information under the "image-label" key.
                │
                ├── 0         # Each multiscale level is stored as a separate Zarr array, as above, but only integer values
                │   ...       # are supported.
                └── n


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

```python
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

```json
"id": 1,                              # ID in OMERO
"name": "example.tif",                # Name as shown in the UI
"version": "0.1",                     # Current version
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

### "labels" metadata

The special group "labels" found under an image Zarr contains the key `labels` containing
the paths to label objects which can be found underneath the group:

```json
{
  "labels": [
    "orphaned/0"
  ]
}
```

Unlisted groups MAY be labels.

### "image-label" metadata

Groups containing the `image-label` dictionary represent an image segmentation
in which each unique pixel value represents a separate segmented object.
`image-label` groups MUST also contain `multiscales` metadata and the two
"datasets" series MUST have the same number of entries.

The `colors` key defines a list of JSON objects describing the unique label
values. Each entry in the list MUST contain the key "label-value" with the
pixel value for that label. Additionally, the "rgba" key MAY be present, the
value for which is an RGBA unsigned-int 4-tuple: `[uint8, uint8, uint8, uint8]`
All `label-value`s must be unique. Clients who choose to not throw an error
should ignore all except the _last_ entry.

Some implementations may represent overlapping labels by using a specially assigned
value, for example the highest integer available in the pixel range.

The `source` key is an optional dictionary which contains information on the
image the label is associated with. If included it MAY include a key `image`
whose value is the relative path to a Zarr image group. The default value is
"../../" since most labels are stored under a subgroup named "labels/" (see
above).


```json
"image-label":
  {
    "version": "0.1",
    "colors": [
      {
        "label-value": 1,
        "rgba": [255, 255, 255, 0]
      },
      {
        "label-value": 4,
        "rgba": [0, 255, 255, 128]
      },
      ...
      ]
    },
    "source": {
      "image": "../../"
    }
]
```


| Revision   | Date         | Description                                |
| ---------- | ------------ | ------------------------------------------ |
| 0.1.3-dev4 | 2020-09-14   | Add the image-label object                 |
| 0.1.3-dev3 | 2020-09-01   | Convert labels to multiscales              |
| 0.1.3-dev2 | 2020-08-18   | Rename masks as labels                     |
| 0.1.3-dev1 | 2020-07-07   | Add mask metadata                          |
| 0.1.2      | 2020-05-07   | Add description of "omero" metadata        |
| 0.1.1      | 2020-05-06   | Add info on the ordering of resolutions    |
| 0.1.0      | 2020-04-20   | First version for internal demo            |
