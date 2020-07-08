# Overview of OME Zarr projects
We will eventually have comprehensive documentation on OME Zarr, but for now this is an overview of all the related projects.


## omero-ms-zarr (this repository)
A microservice for OMERO.server that converts images stored in OMERO to OME Zarr files on the fly, served via a web API.

This also contains the official specification of the [OME Zarr format](https://github.com/ome/omero-ms-zarr/blob/master/spec.md).


## [idr-zarr-tools](https://github.com/IDR/idr-zarr-tools)
A full workflow demonstrating the conversion of IDR images to OME Zarr images on S3.


## [OMERO CLI Zarr plugin](https://github.com/ome/omero-cli-zarr)
An OMERO CLI plugin that converts images stored in OMERO.server into a local Zarr file.


## [ome-zarr-py](https://github.com/ome/ome-zarr-py)
A napari plugin for reading ome-zarr files.


## Global diagram
See this diagram for a global view of how the Zarr format and utilities, storage technologies, and analysis tools fit together: https://downloads.openmicroscopy.org/presentations/2020/Dundee/Workshops/NGFF/zarr_diagram/
