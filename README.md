[![Actions Status](https://github.com/ome/omero-ms-zarr/workflows/Gradle/badge.svg)](https://github.com/ome/omero-ms-zarr/actions)

# OMERO Zarr Microservice

An OMERO.server microservice that serves OME.zarr images and metadata
based on the specification available at https://ngff.openmicroscopy.org/latest/.
A list of implementations that can load data from omero-ms-zarr is available
in the specification.


## Summary

This microservice fetchs images from OMERO.server and converts them to OME.zarr images on the fly. It should be run alongside OMERO.server, and requires access to the OMERO PostgreSQL database and the OMERO data directory.

Clients can request an OMERO image over HTTP, and will receive an OME Zarr compliant image including metadata.

This microservice is still under heavy development, and does not yet support authentication.
It is not suitable for production use.


## Build

    gradle build

if minio is not running, you can exclude the corresponding tests

    gradle build -PexcludeTests=**/s3*

## Run

Copy your OMERO.server configuration `etc/omero.properties` to the
microservice then,

    gradle run --args=etc/omero.properties

and try

    src/scripts/fetch-ms-zarr.py --endpoint-url http://localhost:8080/ --url-format '{url}image/{image}.zarr/' 1234

or

    curl http://localhost:8080/image/1234.zarr/.zattrs

where `1234` is an image ID.


### Configuration

In addition to your usual OMERO.server configuration, the microservice's
`etc/omero.properties` may also include:

`omero.ms.zarr.buffer-cache.size`
: pixel buffer cache size, default 16

`omero.ms.zarr.chunk.size.adjust`
: ordered list of dimensions to adjust to increase chunk size, default *XYZ*; *C* and *T* are not offered

`omero.ms.zarr.chunk.size.min`
: minimum chunk size (not guaranteed), default 1048576; applies before compression

`omero.ms.zarr.compress.zlib.level`
: zlib compression level for chunks, default 6

`omero.ms.zarr.folder.layout`
: for directory listings, default `nested` chunks, can be `flattened`; `none` disables directory listings

`omero.ms.zarr.mask.split.enable`
: if masks split by ROI should be offered; default is `false`, can be set to `true`

`omero.ms.zarr.mask.overlap.color`
: color to use for overlapping region in labeled masks, as a signed integer as in the OME Model; not set by default

`omero.ms.zarr.mask.overlap.value`
: value to set for overlapping region in labeled masks, as a signed integer or "LOWEST" or "HIGHEST" (the default); setting to null disables overlap support

`omero.ms.zarr.mask-cache.size`
: mask cache size in megabytes, default 250

`omero.ms.zarr.net.path.image`
: URI template path for getting image data, default `/image/{image}.zarr/` where `{image}` signifies the image ID and is mandatory

`omero.ms.zarr.net.port`
: the TCP port on which the HTTP server should listen, default 8080


## Build jar with dependencies

    gradle shadowJar
    java -jar build/libs/omero-ms-zarr-0.1.0-SNAPSHOT-all.jar etc/omero.properties


## OMERO S3 Token Creator

To run as a standalone application you must define your access credentials using Java properties, environment variables, credentials files, or mechanisms specific to running on AWS infrastructure.
See https://docs.aws.amazon.com/sdk-for-java/v2/developer-guide/credentials.html

For example, using Java properties, this will create a token for all objects (`*`) in bucket `tmp` :

    java -Daws.accessKeyId=stsadmin -Daws.secretAccessKey=stsadmin-secret \
        -cp build/libs/omero-ms-zarr-0.1.0-SNAPSHOT-all.jar \
        org.openmicroscopy.s3.S3TokenCreator \
        -endpoint http://localhost:9000 -bucket tmp -prefix '*'

Example output:

    {
      "endpoint_url": "http://localhost:9000",
      "region_name": "",
      "aws_access_key_id": "1234567890ABCDEFGHIJ",
      "aws_secret_access_key": "1234567890abcefghijklmnopqrdstuvxwzABCDE"
      "aws_session_token": "1234567890abcefghijklmnopqrdstuvxwzA.BCDEFGHIJKLMNOPQRSTUVWXYZ",
      "expiration": "2020-04-14T17:00:00Z"
    }
