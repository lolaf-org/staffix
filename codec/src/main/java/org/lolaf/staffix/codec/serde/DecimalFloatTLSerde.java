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

import org.lolaf.ringos.threading.FastThreadLocal;
import org.lolaf.staffix.api.serde.DecimalFloat;

/**
 * Zero-allocation {@link org.lolaf.staffix.api.serde.SerDe} for {@link DecimalFloat} that
 * reuses a thread-local {@link DecimalFloat.DecimalFloatImpl} instance on every deserialization.
 *
 * <p><strong>Thread-locality contract:</strong> the {@link DecimalFloat} returned by
 * {@link #deserialize} is the same mutable instance for the calling thread. It is only
 * valid until the next call to {@code deserialize} on the same thread. Do <em>not</em>
 * store or pass the returned reference to another thread — use
 * {@link DecimalFloat#of(long, byte)} to obtain an immutable copy if cross-thread
 * sharing is required.
 *
 * <p>Obtain the singleton via {@link #instance()}.
 */
public class DecimalFloatTLSerde extends AbstractDecimalFloatSerde {

    private static final DecimalFloatTLSerde INSTANCE = new DecimalFloatTLSerde();

    private final FastThreadLocal<TLDecimalFloatImpl> mutableDecimalFloat;

    private DecimalFloatTLSerde() {
        mutableDecimalFloat = FastThreadLocal.withInitial(TLDecimalFloatImpl::new);
    }

    public static DecimalFloatTLSerde instance() {
        return INSTANCE;
    }

    @Override
    protected DecimalFloat getInstance(long unscaled, byte scale) {
        return DecimalFloat.validate(mutableDecimalFloat.get().update(unscaled, scale));
    }

    private static class TLDecimalFloatImpl extends DecimalFloat.DecimalFloatImpl {

        @Override
        protected DecimalFloat update(long unscaled, byte scale) {
            return super.update(unscaled, scale);
        }
    }
}