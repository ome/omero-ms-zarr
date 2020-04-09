# OMERO Zarr Microservice

This is a simple microservice that shows how a web endpoint can be provided
for querying role IDs from an OMERO database.


## Build

    gradle build


## Run

Put your OMERO server configuration into `etc/omero.properties`, run

    gradle run --args='etc/omero.properties'

and try

    src/scripts/post.py


## Build jar with dependencies

    gradle shadowJar
    java -jar build/libs/omero-ms-zarr-0.1.0-SNAPSHOT-all.jar etc/omero.properties


## OMERO S3 Token Creator

To run as a standalone application you must define your access credentials using Java properties, environment variables, credentials files, or mechanisms specific to running on AWS infrastructure.
See https://docs.aws.amazon.com/sdk-for-java/v2/developer-guide/credentials.html

For example, using Java properties:

    java -Daws.accessKeyId=stsadmin -Daws.secretKey=stsadmin-secret -cp build/libs/omero-ms-zarr-0.1.0-SNAPSHOT-all.jar org.openmicroscopy.s3.S3TokenCreator -endpoint http://localhost:9000 -bucket tmp -prefix '*'
