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

if [ -n "$JAVA_HOME" ]; then
  JAVA_CMD="$JAVA_HOME/bin/java"
else
  JAVA_CMD="java"
fi

# Both are ${...} placeholders in the session YAML files, each with a default, so the example runs
# unchanged if you delete these two lines. Change them without editing any YAML.
HEARTBEAT="${HEARTBEAT:-PT2S}"
INITIATOR_PREFIX="${INITIATOR_PREFIX:-}"

$JAVA_CMD -cp target/staffix-examples.jar \
-Dstaffix.example.heartbeat="$HEARTBEAT" \
-Dstaffix.example.initiator.prefix="$INITIATOR_PREFIX" \
-XX:+UnlockDiagnosticVMOptions \
-XX:+DebugNonSafepoints \
-XX:-RestrictContended \
-XX:ContendedPaddingWidth=64 \
--enable-native-access=ALL-UNNAMED \
--add-opens java.base/jdk.internal.ref=ALL-UNNAMED \
--add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
--add-opens java.base/java.lang.reflect=ALL-UNNAMED \
--add-opens java.base/sun.nio.ch=ALL-UNNAMED \
org.lolaf.staffix.examples.FileSessionSettingsExample $@
