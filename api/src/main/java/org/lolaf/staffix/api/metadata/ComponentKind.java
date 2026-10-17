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
package org.lolaf.staffix.api.metadata;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks the settings type of a kind of component: every concrete class implementing or extending it is a component of
 * that kind.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ComponentKind {

    /**
     * The kind's name in the metadata, such as {@code messages-store}.
     */
    String value();

    /**
     * How many components of this kind an engine takes; the engine refuses to start outside it.
     */
    Cardinality cardinality() default Cardinality.ANY;

    enum Cardinality {
        ANY,
        AT_LEAST_ONE,
        AT_MOST_ONE
    }
}
