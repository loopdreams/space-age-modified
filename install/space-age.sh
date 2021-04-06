#!/bin/sh

SPACE_AGE_KEYSTORE=../resources/keystore.jks
SPACE_AGE_KEYSTORE_PASSWORD=moonshot
SPACE_AGE_HOSTNAME=localhost
SPACE_AGE_JAR=./space-age-20210406.jar

java -Djavax.net.ssl.keyStore=$SPACE_AGE_KEYSTORE \
     -Djavax.net.ssl.keyStorePassword=$SPACE_AGE_KEYSTORE_PASSWORD \
     -Dsni.hostname=$SPACE_AGE_HOSTNAME \
     -jar $SPACE_AGE_JAR \
     $@
