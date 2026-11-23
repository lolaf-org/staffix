/*
 * Copyright © 2024-2026 Lolaf.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
/**
 * Replicates an application's streams from a leader to its followers. An application builds a
 * {@link org.lolaf.staffix.ha.replication.ReplicationNode} from its implementations of the
 * interfaces of the {@code spi} package, tells it which role to take, and appends through a
 * {@link org.lolaf.staffix.ha.replication.StreamWriter}. The other packages are its implementation.
 */
package org.lolaf.staffix.ha.replication;
