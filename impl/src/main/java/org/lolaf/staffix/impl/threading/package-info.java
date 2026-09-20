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
 * Which thread runs a method, for the methods where the answer is not the obvious one.
 *
 * <p>A FIX session is owned by the IO thread of its connection: that is the rule, and an unannotated method follows
 * it. These annotations mark the exceptions, so a reader looking at session state can tell at a glance whether it is
 * being touched by the owner. They sit on the method the other thread <b>enters by</b>, not on everything reachable
 * from it, which no one could keep true; what that method calls runs on the same thread.
 *
 * <p>They document, they do not enforce. Nothing reads them at runtime, the retention being
 * {@link java.lang.annotation.RetentionPolicy#CLASS}.
 */
package org.lolaf.staffix.impl.threading;
