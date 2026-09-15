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
package org.lolaf.staffix.api.grpc;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GrpcStatusTest {

    @Test
    void retriesExactlyTheStatusesTheOtlpSpecificationCallsRetryable() {
        List<Integer> retryable = new ArrayList<>();
        for (int status = 0; status <= 20; status++) {
            if (GrpcStatus.isRetryable(status)) {
                retryable.add(status);
            }
        }

        assertThat(retryable).containsExactly(
                GrpcStatus.CANCELLED, GrpcStatus.DEADLINE_EXCEEDED, GrpcStatus.RESOURCE_EXHAUSTED,
                GrpcStatus.ABORTED, GrpcStatus.OUT_OF_RANGE, GrpcStatus.UNAVAILABLE, GrpcStatus.DATA_LOSS);
        assertThat(retryable).containsExactly(1, 4, 8, 10, 11, 14, 15);
    }

    @Test
    void doesNotRetryASuccessOrARequestThatIsSimplyWrong() {
        assertThat(GrpcStatus.isRetryable(GrpcStatus.OK)).isFalse();
        assertThat(GrpcStatus.isRetryable(GrpcStatus.INVALID_ARGUMENT)).isFalse();
        assertThat(GrpcStatus.isRetryable(GrpcStatus.UNAUTHENTICATED)).isFalse();
    }

    @Test
    void namesEveryStatusItKnows() {
        assertThat(GrpcStatus.nameOf(GrpcStatus.OK)).isEqualTo("OK");
        assertThat(GrpcStatus.nameOf(GrpcStatus.UNAVAILABLE)).isEqualTo("UNAVAILABLE");
        assertThat(GrpcStatus.nameOf(GrpcStatus.UNAUTHENTICATED)).isEqualTo("UNAUTHENTICATED");
    }

    @Test
    void fallsBackToTheNumberForAStatusItDoesNot() {
        assertThat(GrpcStatus.nameOf(99)).isEqualTo("99");
        assertThat(GrpcStatus.nameOf(-1)).isEqualTo("-1");
    }
}
