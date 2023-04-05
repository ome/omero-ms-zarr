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

export MINIO_ROOT_USER=minio MINIO_ROOT_PASSWORD=minio123
mkdir 1
touch 1/hello.txt
echo "hello" >> 1/hello.txt
./minio server --address localhost:$PORT . &
sleep 2;

./mc config host add $CONFIGNAME http://localhost:$PORT ${MINIO_ROOT_USER} ${MINIO_ROOT_PASSWORD}

./mc admin user add $CONFIGNAME stsadmin stsadmin-secret
./mc admin policy create $CONFIGNAME readall s3-policy-readall.json
./mc admin policy attach $CONFIGNAME readall --user=stsadmin

./mc mb -p $CONFIGNAME/bucketa/1/
./mc cp --recursive 1 $CONFIGNAME/bucketa/1
