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
CONF=$ROOT/config.toml

/opt/bin/generate_config >$CONF

echo "starting selenium hub with configuration:"
cat $CONF

if [ ! -z "$SE_OPTS" ]; then
  echo "appending selenium options: ${SE_OPTS}"
fi

function shutdown {
    echo "shutting down hub.."
    kill -s SIGTERM $NODE_PID
    wait $NODE_PID
    echo "shutdown complete"
}

java ${JAVA_OPTS} -Djava.security.egd=file:/dev/./urandom -XX:InitialRAMPercentage=50.0 -XX:+UseG1GC -XX:InitialHeapSize=1g -XX:MaxHeapSize=4g -XX:MaxGCPauseMillis=500 -XX:+DisableExplicitGC -XX:+UseStringDeduplication -XX:+ParallelRefProcEnabled -cp /opt/selenium/mcloud-grid-1.0.jar:/opt/selenium/mcloud-grid.jar org.openqa.selenium.grid.Bootstrap hub \
  --config $CONF \
  ${SE_OPTS} &
NODE_PID=$!

trap shutdown SIGTERM SIGINT
wait $NODE_PID
