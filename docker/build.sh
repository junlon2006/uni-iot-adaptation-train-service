#!/bin/bash
cd ../src/jni
echo "start compile JNI libWrapperEngine.so"
./build.sh
echo "compile JNI libWrapperEngine.so done"
cp libWrapperEngine.so ../../lib/libWrapperEngine.so
cd ../..

echo "start mvn compile package"
mvn  clean compile package -DskipTests -Deditorconfig.skip
echo "mvn compile package done"
cd docker

cp ../lib/libWrapperEngine.so lib/libWrapperEngine.so
cp ../target/adaptation-train-1.0.0.jar .
cp ../src/main/resources/application-prod.properties .
echo "start docker build"
docker build -t junlon2006/unios-adaptation-train-service:1.0.0 .
echo "docker build done"
