# 6.3.0-jre8 and 6.3.0-jre11 both result in a null pointer exception
FROM library/gradle:6.3.0-jre14 as build

WORKDIR /omero-ms-zarr
COPY LICENSE README.md build.gradle settings.gradle /omero-ms-zarr/
RUN gradle build --no-daemon || return 0 # Cache dependencies
COPY src /omero-ms-zarr/src/
RUN gradle build --no-daemon -x test -x javadoc

RUN cd build/distributions && \
    unzip omero-ms-zarr-shadow-*.zip && \
    mv omero-ms-zarr-shadow-*/ /omero-ms-zarr-shadow/


FROM adoptopenjdk/openjdk11:jre-11.0.7_10-alpine

RUN apk add --no-cache bash
COPY --from=build /omero-ms-zarr-shadow/ .

EXPOSE 8080
ENTRYPOINT ["java", "-cp", "/lib/omero-ms-zarr-0.2.1-SNAPSHOT-all.jar", "org.openmicroscopy.ms.zarr.ConfigEnv"]
