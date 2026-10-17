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
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * How a component of this kind is picked by its instance id. Repeated when several parts of the engine pick it: an
 * acceptor names the sessions settings stores it reads, while an initiator searches them all.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Repeatable(SelectedBy.List.class)
public @interface SelectedBy {

    By by() default By.SESSION;

    /**
     * The setting naming the instance, on the session settings or on the builder of {@link #by()}; empty when nothing
     * names it.
     */
    String setting() default "";

    /**
     * The Spring Boot property naming the instance, when it is not {@link #setting()} in a session's properties.
     */
    String springSetting() default "";

    WhenUnnamed whenUnnamed();

    /**
     * Left out of the metadata when nothing names the instance.
     */
    WhenUnknown whenUnknown() default WhenUnknown.REFUSED;

    /**
     * What names the instance.
     */
    enum By {
        SESSION,
        ACCEPTOR,
        INITIATOR
    }

    /**
     * What gets picked when nothing is named.
     */
    enum WhenUnnamed {
        /**
         * The instance registered under {@code default}.
         */
        DEFAULT_INSTANCE,
        /**
         * None.
         */
        NONE,
        /**
         * Every instance, searched in turn: the first holding the session wins.
         */
        ALL
    }

    /**
     * What happens to a name the engine does not register, when the session, acceptor or initiator is created.
     */
    enum WhenUnknown {
        REFUSED,
        /**
         * Runs without one while the engine registers none of this kind; refused once it registers any.
         */
        REFUSED_ONCE_ANY_REGISTERED
    }

    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface List {

        SelectedBy[] value();
    }
}
