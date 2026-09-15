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
package org.lolaf.staffix.impl.utils;

import javax.security.auth.Subject;
import javax.security.auth.callback.*;
import javax.security.auth.spi.LoginModule;
import java.io.IOException;
import java.util.Map;

public class TestingLoginModule implements LoginModule {

    public static final String TEST_USER = "testUser";
    public static final String TEST_PASSWORD = "testPassword";
    private CallbackHandler callbackHandler;
    private Subject subject;

    @Override
    public void initialize(Subject subject, CallbackHandler callbackHandler, Map<String, ?> sharedState, Map<String, ?> options) {
        this.subject = subject;
        this.callbackHandler = callbackHandler;
    }

    @Override
    public boolean login() {
        NameCallback nameCallback = new NameCallback("username: ");
        PasswordCallback passwordCallback = new PasswordCallback("password: ", false);
        try {
            callbackHandler.handle(new Callback[]{nameCallback, passwordCallback});
            String username = nameCallback.getName();
            if (passwordCallback.getPassword() != null && nameCallback.getName() != null) {
                return TEST_USER.equals(username) && TEST_PASSWORD.equals(new String(passwordCallback.getPassword()));
            }
        } catch (IOException | UnsupportedCallbackException e) {
            throw new IllegalStateException("Should have never happened");
        }
        return false;
    }

    @Override
    public boolean commit() {
        subject.getPrincipals().add(() -> "testPrincipal");
        return true;
    }

    @Override
    public boolean abort() {
        return true;
    }

    @Override
    public boolean logout() {
        return true;
    }
}
