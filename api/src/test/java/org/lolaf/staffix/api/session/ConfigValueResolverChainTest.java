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
package org.lolaf.staffix.api.session;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigValueResolverChainTest {

    private static final String PROPERTY = "staffix.test.placeholder";

    private final ConfigValueResolverChain chain = ConfigValueResolverChain.defaultChain();

    @AfterEach
    void clearProperty() {
        System.clearProperty(PROPERTY);
    }

    @Test
    void leavesAValueHoldingNoPlaceholderUntouched() {
        assertThat(chain.resolve("FIX.4.4", "where")).isEqualTo("FIX.4.4");
        assertThat(chain.resolve(null, "where")).isNull();
    }

    @Test
    void resolvesASystemPropertyByItsSourceName() {
        System.setProperty(PROPERTY, "resolved");

        assertThat(chain.resolve("${sysprop:" + PROPERTY + "}", "where")).isEqualTo("resolved");
    }

    @Test
    void resolvesASystemPropertyWithoutASourceName() {
        System.setProperty(PROPERTY, "resolved");

        assertThat(chain.resolve("${" + PROPERTY + "}", "where")).isEqualTo("resolved");
    }

    @Test
    void resolvesAnEnvironmentVariable() {
        String name = System.getenv().keySet().iterator().next();

        assertThat(chain.resolve("${env:" + name + "}", "where")).isEqualTo(System.getenv(name));
    }

    @Test
    void fallsBackOnTheDefaultWhenNothingAnswers() {
        assertThat(chain.resolve("${sysprop:" + PROPERTY + ":fallback}", "where")).isEqualTo("fallback");
        assertThat(chain.resolve("${" + PROPERTY + ":fallback}", "where")).isEqualTo("fallback");
    }

    /**
     * The default belongs to the chain rather than to a resolver: were it a resolver's, the system
     * property resolver would answer with it and the environment resolver would never be asked.
     */
    @Test
    void triesEveryResolverBeforeUsingTheDefault() {
        String name = System.getenv().keySet().iterator().next();

        assertThat(chain.resolve("${" + name + ":fallback}", "where")).isEqualTo(System.getenv(name));
    }

    @Test
    void keepsTheTextAroundAPartialPlaceholder() {
        System.setProperty(PROPERTY, "MIDDLE");

        assertThat(chain.resolve("prefix-${" + PROPERTY + "}-suffix", "where"))
                .isEqualTo("prefix-MIDDLE-suffix");
    }

    @Test
    void resolvesSeveralPlaceholdersInOneValue() {
        System.setProperty(PROPERTY, "one");

        assertThat(chain.resolve("${" + PROPERTY + "}/${" + PROPERTY + ":two}", "where"))
                .isEqualTo("one/one");
    }

    @Test
    void keepsAColonInTheDefault() {
        assertThat(chain.resolve("${sysprop:" + PROPERTY + ":a:b}", "where")).isEqualTo("a:b");
    }

    @Test
    void throwsNamingThePlaceholderAndTheFileWhenNothingAnswers() {
        assertThatThrownBy(() -> chain.resolve("${sysprop:" + PROPERTY + "}", "session.yaml"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(PROPERTY)
                .hasMessageContaining("session.yaml");
    }

    @Test
    void throwsOnAnUnterminatedPlaceholder() {
        assertThatThrownBy(() -> chain.resolve("${sysprop:x", "session.yaml"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("session.yaml");
    }

    @Test
    void throwsOnAPlaceholderNamingNoKey() {
        assertThatThrownBy(() -> chain.resolve("${}", "where"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * An unnamed resolver is offered every placeholder, including one addressed to a source it does not
     * own, so a custom implementation can define whatever syntax it likes.
     */
    @Test
    void offersAnUnnamedResolverEveryPlaceholder() {
        ConfigValueResolver custom = placeholder -> Optional.of("custom:" + placeholder.getKey());
        ConfigValueResolverChain withCustom = new ConfigValueResolverChain(List.of(
                ConfigValueResolver.SystemPropertyConfigValueResolver.getInstance(), custom));

        assertThat(withCustom.resolve("${sysprop:" + PROPERTY + "}", "where"))
                .isEqualTo("custom:" + PROPERTY);
    }

    /**
     * A named resolver keeps to its own placeholders, so a prefixed one never reaches the wrong source.
     */
    @Test
    void doesNotOfferANamedResolverAnotherSourcesPlaceholder() {
        assertThatThrownBy(() -> chain.resolve("${env:" + PROPERTY + "}", "where"))
                .isInstanceOf(IllegalStateException.class);

        System.setProperty(PROPERTY, "resolved");
        assertThat(chain.resolve("${env:" + PROPERTY + ":fallback}", "where")).isEqualTo("fallback");
    }

    @Test
    void reportsWhetherAValueHoldsAPlaceholder() {
        assertThat(ConfigValueResolverChain.holdsPlaceholder("${a}")).isTrue();
        assertThat(ConfigValueResolverChain.holdsPlaceholder("plain")).isFalse();
        assertThat(ConfigValueResolverChain.holdsPlaceholder(null)).isFalse();
    }
}
