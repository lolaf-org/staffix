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
package org.lolaf.staffix.api;

/**
 * Names one configured instance of a component, so a session can say which of several it wants.
 *
 * <p>An engine may run two file message stores against different directories, or a file logger beside an OTLP
 * one; the instance id is how a session's settings pick one out. {@link InstanceProvider#DEFAULT_INSTANCE_ID} is
 * what a configuration that never mentions one gets.
 */
public interface InstanceIdSupplier {

    String getInstanceId();

}