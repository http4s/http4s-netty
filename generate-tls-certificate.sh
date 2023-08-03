#!/bin/bash


function generate() {
  keytool -genkeypair -keystore "$1/src/test/resources/teststore.p12" \
   -dname "CN=test, OU=Unknown, O=Unknown, L=Unknown, ST=Unknown, C=Unknown" \
   -validity 999 \
   -keypass password -storetype PKCS12 -storepass password -keyalg RSA -alias unknown \
   -ext SAN=dns:localhost,ip:127.0.0.1,ip:::1
}

generate "client"
generate "server"