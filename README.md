# OMERO Zarr Microservice

This is a simple microservice that shows how a web endpoint can be provided
for querying role IDs from an OMERO database.


## Build

    gradle build


## Run

Copy your OMERO.server configuration `etc/omero.properties` to the
microservice then,

    gradle run --args=etc/omero.properties

and try

    src/scripts/fetch-ms-zarr.py --endpoint-url http://localhost:8080/ --url-format '{url}image/{image}.zarr/' 1234


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
