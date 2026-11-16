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

# log <level> <message>: a JSON log line like the ones of the hub (JsonLogFormatter); the message may contain the values
# of the configuration, so it is escaped
log() {
  local message="${2//\\/\\\\}"
  message="${message//\"/\\\"}"
  message="${message//[[:cntrl:]]/ }"
  printf '{"timestamp":"%s","level":"%s","logger":"entrypoint","component":"mcloud-grid","category":"grid","message":"%s"}\n' \
    "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$1" "$message"
}

#### Configuration

invalidConfig=false

# checkNumber <name> <value> <minimum>: the value goes into config.json as a JSON number: a whole number (no leading
# zeros, at most 9 digits) of at least the minimum
checkNumber() {
  if [[ ! "$2" =~ ^-?(0|[1-9][0-9]{0,8})$ ]] || (($2 < $3)); then
    log "SEVERE" "$1 '$2' is not a whole number of at least $3."
    invalidConfig=true
  fi
}

# checkBoolean <name> <value>: the value goes into config.json as a JSON boolean
checkBoolean() {
  if [[ ! "$2" =~ ^(true|false)$ ]]; then
    log "SEVERE" "$1 '$2' is not true or false."
    invalidConfig=true
  fi
}

# checkClassName <name> <value>: the value goes into config.json as the name of a Java class
checkClassName() {
  if [[ ! "$2" =~ ^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_$]*)*$ ]]; then
    log "SEVERE" "$1 '$2' is not the name of a Java class."
    invalidConfig=true
  fi
}

checkNumber GRID_NEW_SESSION_WAIT_TIMEOUT "$GRID_NEW_SESSION_WAIT_TIMEOUT" -1
checkNumber GRID_TIMEOUT "$GRID_TIMEOUT" 0
checkNumber GRID_BROWSER_TIMEOUT "$GRID_BROWSER_TIMEOUT" 0
checkNumber GRID_CLEAN_UP_CYCLE "$GRID_CLEAN_UP_CYCLE" 1
checkNumber GRID_JETTY_MAX_THREADS "$GRID_JETTY_MAX_THREADS" -1
checkBoolean GRID_THROW_ON_CAPABILITY_NOT_PRESENT "$GRID_THROW_ON_CAPABILITY_NOT_PRESENT"
checkBoolean GRID_DEBUG "$GRID_DEBUG"
checkClassName GRID_CAPABILITY_MATCHER "$GRID_CAPABILITY_MATCHER"
# a restart does not fix the configuration: exit 0 stops the restarts of the container
if [[ "$invalidConfig" == true ]]; then
  log "SEVERE" "The configuration is invalid, the hub is not started: fix the variables above (README.md#configuration) and start the container again."
  exit 0
fi

if ! /opt/bin/generate_config > "$CONF"; then
  log "SEVERE" "Could not write ${CONF}."
  exit 1
fi

log "INFO" "Starting Selenium hub"

# shutdown: stops the hub on SIGTERM and SIGINT
shutdown() {
  log "INFO" "Shutting down hub"
  kill -s SIGTERM "$hubPid"
  wait "$hubPid"
  log "INFO" "shutdown complete"
}

trap shutdown SIGTERM SIGINT

# JAVA_HEAP_OPTS, JAVA_OPTS and SE_OPTS hold several space-separated options, so they are intentionally unquoted
# shellcheck disable=SC2086
java ${JAVA_HEAP_OPTS:--Xms1G -Xmx4G} ${JAVA_OPTS} -Djava.net.preferIPv4Stack=true -Djava.net.preferIPv6Stack=false -XX:+UseG1GC -XX:+UseStringDeduplication -Djava.util.logging.config.file=/opt/selenium/logger.properties -cp /opt/selenium/mcloud-grid-jar-with-dependencies.jar \
  org.openqa.grid.selenium.GridLauncherV3 \
  -role hub \
  -hubConfig "$CONF" \
  ${SE_OPTS} &
hubPid=$!

wait "$hubPid"
