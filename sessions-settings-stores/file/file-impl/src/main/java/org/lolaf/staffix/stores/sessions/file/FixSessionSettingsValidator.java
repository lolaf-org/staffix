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
package org.lolaf.staffix.stores.sessions.file;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import lombok.experimental.UtilityClass;
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Runs Jakarta Bean Validation against a {@link YamlFixSessionSettings} loaded from (or about to be written to) disk,
 * turning constraint violations into a single, readable error instead of a value surfacing as {@code null} much later.
 * <p>
 * Hibernate Validator is configured with a {@link ParameterMessageInterpolator} so that no Expression Language (EL)
 * implementation is required on the classpath.
 */
@UtilityClass
public class FixSessionSettingsValidator {

    // Held for the lifetime of the process: the Validator is thread-safe and cheap to reuse, and the factory must
    // outlive the Validator obtained from it.
    private static final ValidatorFactory FACTORY = Validation.byDefaultProvider()
            .configure()
            .messageInterpolator(new ParameterMessageInterpolator())
            .buildValidatorFactory();

    private static final Validator VALIDATOR = FACTORY.getValidator();

    /**
     * Validates the given settings, throwing {@link IllegalStateException} listing every violation if any constraint
     * is broken.
     *
     * @param settings the settings to validate
     * @param source   a human-readable origin (e.g. the file path) included in the error message
     */
    public static void validate(YamlFixSessionSettings settings, String source) {
        Set<ConstraintViolation<YamlFixSessionSettings>> violations = VALIDATOR.validate(settings);
        if (!violations.isEmpty()) {
            String details = violations.stream()
                    .map(v -> v.getPropertyPath() + " " + v.getMessage())
                    .sorted()
                    .collect(Collectors.joining("\n  - ", "\n  - ", ""));
            throw new IllegalStateException("Invalid FIX session settings in " + source + ":" + details);
        }
    }
}
