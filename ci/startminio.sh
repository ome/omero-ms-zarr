#!/bin/sh
set -eux

PORT=9000
PLATFORM=`uname | tr '[:upper:]' '[:lower:]'`
CONFIGNAME=test-omero-ms-zarr

[ -f minio ] || \
    curl -sfSo minio "https://dl.minio.io/server/minio/release/$PLATFORM-amd64/minio"
[ -f mc ] || \
    curl -sfSo mc "https://dl.minio.io/client/mc/release/$PLATFORM-amd64/mc"
chmod +x minio mc
./minio --version
./mc --version

export MINIO_ACCESS_KEY=minio MINIO_SECRET_KEY=minio123
./minio server --address localhost:$PORT . &
sleep 2;

./mc config host add $CONFIGNAME http://localhost:$PORT ${MINIO_ACCESS_KEY} ${MINIO_SECRET_KEY}

./mc admin user add $CONFIGNAME stsadmin stsadmin-secret
./mc admin policy add $CONFIGNAME readall s3-policy-readall.json
./mc admin policy set $CONFIGNAME readall user=stsadmin
