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

# The spring-boot plugin names the repackaged jar after the project version, so resolve it
# rather than hard-coding it. It is produced by `mvn install`, not by `mvn package`.
BOOT_JAR=$(ls target/staffix-examples-spring-boot-starter-*-spring-boot.jar 2>/dev/null | head -1)
if [ -z "$BOOT_JAR" ]; then
  echo "No repackaged jar in target/. Build it first with: mvn install -pl examples/spring-boot-starter-example -am" >&2
  exit 1
fi

$JAVA_CMD -Xmx1g -Xms1g \
-XX:+UnlockDiagnosticVMOptions \
-XX:+DebugNonSafepoints \
-XX:-RestrictContended \
-XX:ContendedPaddingWidth=64 \
--enable-native-access=ALL-UNNAMED \
--add-opens java.base/jdk.internal.ref=ALL-UNNAMED \
--add-opens java.base/jdk.internal.misc=ALL-UNNAMED \
--add-opens java.base/java.lang.reflect=ALL-UNNAMED \
--add-opens java.base/sun.nio.ch=ALL-UNNAMED \
--add-opens java.base/java.nio.channels.spi=ALL-UNNAMED \
-jar "$BOOT_JAR" $@