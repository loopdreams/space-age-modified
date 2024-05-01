#!/usr/bin/env sh

DOCUMENT_ROOT="$HOME/code/projects/gemini-games"

clojure -J-Djavax.net.ssl.keyStore=keystore.pkcs12 \
        -J-Djavax.net.ssl.keyStorePassword=moonshot \
        -J-Dsni.hostname=localhost \
        -M:run-server "$DOCUMENT_ROOT"
