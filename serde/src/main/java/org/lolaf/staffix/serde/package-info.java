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
 * The length-specialised readers and writers for every FIX field type, working on a byte buffer in place.
 *
 * <p>Three of them come in more than one flavour, and the choice is about allocation rather than correctness:
 * a plain serde builds a value per field, a {@code Cached} one returns the same instance for bytes it has seen
 * before, and a {@code ThreadLocal} one reuses a single instance for the duration of a callback.
 *
 * <p>Prefer {@link org.lolaf.staffix.api.serde.DecimalFloat} over {@code double} for prices and quantities:
 * a double cannot hold most decimal fractions exactly, and the digits a counterparty sent are the ones that
 * have to come back.
 */
package org.lolaf.staffix.serde;
