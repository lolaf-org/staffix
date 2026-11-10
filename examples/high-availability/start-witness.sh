#!/bin/sh
#
# Copyright © 2024-2026 Lolaf.org
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
#

. "$(dirname "$0")/common.sh"
if witness_running; then
  echo "witness is already running, container $WITNESS_CONTAINER" >&2
  exit 1
fi
chmod go-rwx witness/witness.properties witness/applications/*.properties
mkdir -p target/logs target/witness
# --user: the votes in target/witness belong to whoever runs the example, so mvn clean can remove them.
nohup docker run --rm --name "$WITNESS_CONTAINER" \
  --user "$(id -u):$(id -g)" \
  -p 127.0.0.1:7420:7420/udp \
  -v "$PWD/target/witness:/var/lib/primos-witness" \
  -v "$PWD/witness:/etc/primos-witness:ro" \
  --read-only --tmpfs /tmp \
  --memory 512m \
  --stop-timeout 30 \
  "$WITNESS_IMAGE" >> target/logs/witness.log 2>&1 &
echo "witness started, container $WITNESS_CONTAINER, logging to target/logs/witness.log"
