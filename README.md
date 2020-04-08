This is a simple microservice that shows how a web endpoint can be provided
for querying role IDs from an OMERO database.
Put your OMERO server configuration into etc/omero.properties then run:

java -jar target/omero-ms-zarr-0.1.0-SNAPSHOT-jar-with-dependencies.jar etc/omero.properties

and try

src/scripts/post.py
