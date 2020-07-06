# 6.3.0-jre8 and 6.3.0-jre11 both result in a null pointer exception
FROM library/gradle:6.3.0-jre14 as build

WORKDIR /omero-ms-zarr
COPY LICENSE README.md build.gradle settings.gradle /omero-ms-zarr/
COPY src /omero-ms-zarr/src/
RUN gradle build -x test

RUN cd build/distributions && \
    unzip omero-ms-zarr-shadow-*.zip && \
    mv omero-ms-zarr-shadow-*/ /omero-ms-zarr-shadow/


FROM adoptopenjdk/openjdk11:jre-11.0.7_10-alpine

RUN apk add --no-cache bash
COPY --from=build /omero-ms-zarr-shadow/ .

EXPOSE 8080
ENTRYPOINT ["java", "-cp", "/lib/omero-ms-zarr-0.1.5-SNAPSHOT-all.jar", "org.openmicroscopy.ms.zarr.ConfigEnv"]
