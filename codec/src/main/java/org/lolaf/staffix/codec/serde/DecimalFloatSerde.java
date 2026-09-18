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
package org.lolaf.staffix.codec.serde;

import org.lolaf.staffix.api.serde.DecimalFloat;

/**
 * The plain {@link org.lolaf.staffix.api.serde.DecimalFloat} serde: decodes into a fresh value each time.
 *
 * <p>Use {@link DecimalFloatCachedSerde} on a session whose prices repeat, which is most of them.
 */
public class DecimalFloatSerde extends AbstractDecimalFloatSerde {

    private static final DecimalFloatSerde INSTANCE = new DecimalFloatSerde();

    public static DecimalFloatSerde instance() {
        return INSTANCE;
    }

    @Override
    protected DecimalFloat getInstance(long unscaled, byte scale) {
        return DecimalFloat.of(unscaled, scale);
    }
}