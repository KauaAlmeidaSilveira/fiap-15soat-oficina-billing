#!/bin/sh
set -e

JAVA_OPTS=""
if [ -n "$NEW_RELIC_LICENSE_KEY" ]; then
    echo "New Relic: license key encontrada, ativando o agente APM."
    JAVA_OPTS="-javaagent:/app/newrelic/newrelic.jar"
else
    echo "New Relic: NEW_RELIC_LICENSE_KEY ausente, subindo sem o agente APM."
fi

exec java $JAVA_OPTS -jar /app/app.jar
