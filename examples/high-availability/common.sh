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

# Sourced by the start and stop scripts: each process runs in the background from the shaded jar, its output in
# target/logs/<name>.log and its pid in target/run/<name>.pid.

cd "$(dirname "$0")"

if [ -n "$JAVA_HOME" ]; then
  JAVA_CMD="$JAVA_HOME/bin/java"
else
  JAVA_CMD="java"
fi

JVM_FLAGS="-XX:+UnlockDiagnosticVMOptions \
-XX:+DebugNonSafepoints \
-XX:-RestrictContended \
-XX:ContendedPaddingWidth=64 \
--enable-native-access=ALL-UNNAMED \
--add-opens java.base/jdk.internal.ref=ALL-UNNAMED \
--add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
--add-opens java.base/java.lang.reflect=ALL-UNNAMED \
--add-opens java.base/sun.nio.ch=ALL-UNNAMED \
--add-opens java.base/java.nio.channels.spi=ALL-UNNAMED"

# start <name> <main class> [args...]
start() {
  name=$1
  shift
  if [ ! -f target/staffix-examples.jar ]; then
    echo "target/staffix-examples.jar is missing: run mvn package -pl examples/high-availability -am from the repository root" >&2
    exit 1
  fi
  if running "$name"; then
    echo "$name is already running, pid $(cat "target/run/$name.pid")" >&2
    exit 1
  fi
  mkdir -p target/logs target/run
  touch "target/logs/$name.log"
  wc -l < "target/logs/$name.log" > "target/run/$name.from"
  nohup $JAVA_CMD -cp target/staffix-examples.jar $JVM_FLAGS "$@" >> "target/logs/$name.log" 2>&1 &
  echo $! > "target/run/$name.pid"
  echo "$name started, pid $!, logging to target/logs/$name.log"
}

# stop <name> [-9]: SIGTERM lets the process stop cleanly, -9 kills it as a crash would.
stop() {
  name=$1
  if ! running "$name"; then
    echo "$name is not running" >&2
    rm -f "target/run/$name.pid"
    return
  fi
  pid=$(cat "target/run/$name.pid")
  if [ "$2" = "-9" ]; then
    kill -9 "$pid"
  else
    kill "$pid"
  fi
  while kill -0 "$pid" 2>/dev/null; do
    sleep 0.2
  done
  rm -f "target/run/$name.pid"
  echo "$name stopped"
}

# role <1|2>: what this node last said it is since it started, or STOPPED.
role() {
  if ! running "node-$1"; then
    echo STOPPED
    return
  fi
  tail -n +$(($(cat "target/run/node-$1.from" 2>/dev/null || echo 0) + 1)) "target/logs/node-$1.log" \
    | sed -n "s/.* Node $1 is now \([A-Z]*\) at epoch.*/\1/p" | tail -1 | grep . || echo STARTING
}

# node_with_role <LEADER|FOLLOWER>: the node in that role, if one is.
node_with_role() {
  for id in 1 2; do
    if [ "$(role $id)" = "$1" ]; then
      echo $id
      return
    fi
  done
  echo "no node is $1 now: ./status.sh shows each node's role" >&2
  exit 1
}

running() {
  [ -f "target/run/$1.pid" ] && kill -0 "$(cat "target/run/$1.pid")" 2>/dev/null
}
