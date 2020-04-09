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
