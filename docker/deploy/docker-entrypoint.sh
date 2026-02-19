#!/bin/sh
JAVA_OPTS="--enable-native-access=ALL-UNNAMED -XX:MaxRAMPercentage=75.0 -XX:AOTCache=application.aot"

if [ -f /application/config/log4j2.yaml ]; then
  JAVA_OPTS="$JAVA_OPTS -Dlogging.config=/application/config/log4j2.yaml"
fi

exec java $JAVA_OPTS -jar application.jar "$@"
