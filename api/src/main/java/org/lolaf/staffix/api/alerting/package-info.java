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
 * Stateful alerts: a {@link org.lolaf.staffix.api.alerting.Probe} watches one thing of its
 * {@link org.lolaf.staffix.api.alerting.ProbeScope}, such as a FIX session; the probes thread evaluates it periodically
 * and hands each change of alert state to the scope's {@link org.lolaf.staffix.api.alerting.AlertsPublisher}.
 */
package org.lolaf.staffix.api.alerting;
