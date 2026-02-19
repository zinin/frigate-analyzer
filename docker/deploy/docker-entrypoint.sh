#!/bin/sh
set -- --enable-native-access=ALL-UNNAMED -XX:MaxRAMPercentage=75.0 -XX:AOTCache=application.aot

if [ -f /application/config/log4j2.yaml ]; then
  echo "Using external log4j2 config: /application/config/log4j2.yaml"
  set -- "$@" -Dlogging.config=/application/config/log4j2.yaml
else
  echo "Using built-in log4j2 config (console only)"
fi

exec java "$@" -jar application.jar
