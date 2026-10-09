#!/bin/bash
#*******************************************************************************
# Copyright 2018-2021 Zebrunner (https://zebrunner.com/).
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#*******************************************************************************

ROOT=/opt/selenium
CONF=$ROOT/config.json

log() {
  printf '{"timestamp":"%s","level":"INFO","logger":"entrypoint","component":"mcloud-grid","category":"grid","message":"%s"}\n' \
    "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$1"
}

/opt/bin/generate_config > "$CONF"

log "Starting Selenium hub"

function shutdown {
  log "Shutting down hub"
  kill -s SIGTERM $NODE_PID
  wait $NODE_PID
  log "shutdown complete"
}

trap shutdown SIGTERM SIGINT

# JAVA_HEAP_OPTS, JAVA_OPTS and SE_OPTS hold several space-separated options, so they are intentionally unquoted
# shellcheck disable=SC2086
java ${JAVA_HEAP_OPTS:--Xms1G -Xmx4G} ${JAVA_OPTS} -Djava.net.preferIPv4Stack=true -Djava.net.preferIPv6Stack=false -XX:+UseG1GC -XX:+UseStringDeduplication -Djava.util.logging.config.file=/opt/selenium/logger.properties -cp /opt/selenium/mcloud-grid-jar-with-dependencies.jar \
  org.openqa.grid.selenium.GridLauncherV3 \
  -role hub \
  -hubConfig "$CONF" \
  ${SE_OPTS} &
NODE_PID=$!

wait $NODE_PID
