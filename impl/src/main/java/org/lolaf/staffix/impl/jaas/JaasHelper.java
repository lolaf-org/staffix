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
package org.lolaf.staffix.impl.jaas;

import lombok.experimental.UtilityClass;
import org.lolaf.staffix.api.fields.CoreFields;
import org.lolaf.staffix.api.fields.FieldLocation;
import org.lolaf.staffix.api.fields.FieldType;
import org.lolaf.staffix.api.fields.FixField;
import org.lolaf.staffix.api.msg.DecodedFixMessage;
import org.lolaf.staffix.api.session.FixSession;
import org.lolaf.staffix.codec.serde.StringSerde;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.login.AppConfigurationEntry;
import javax.security.auth.login.Configuration;
import javax.security.auth.login.LoginContext;
import javax.security.auth.login.LoginException;
import javax.security.auth.spi.LoginModule;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Helper class to use JAAS for logon requests auth credentials validation
 */
@UtilityClass
public class JaasHelper {

    public static final FixField USERNAME = FixField.of(CoreFields.USERNAME, FieldType.STRING, FieldLocation.BODY);
    public static final FixField PASSWORD = FixField.of(CoreFields.PASSWORD, FieldType.STRING, FieldLocation.BODY);

    /**
     * Creates a {@code CompletableFuture<Optional<String>>} to be returned by {@link org.lolaf.staffix.api.application.FixApplication#validateLogon(FixSession, DecodedFixMessage, Executor)} implementation
     * that will be backed by a JAAS login module. Implementation will also set the correct JAAS Subject upon correct identification in {@link FixSession#getAuthenticatedSubject()}
     *
     * @param fixSession          the fix session for which the auth is being processed
     * @param callbackHandler     a callback handler to setup correctly callbacks and map username and password taken from the logon fix Message
     * @param executor            the executor to use to process asynchronously the login
     * @param loginModuleClass    the JAAS login module class name
     * @param loginModuleOptions  the JAAS login module options
     * @param invalidLoginMessage the reject message to be returned by the {@code Optional<String>} in case of failed authentication
     */
    public static CompletableFuture<Optional<String>> jaasLogin(FixSession fixSession, CallbackHandler callbackHandler, Executor executor,
                                                                Class<? extends LoginModule> loginModuleClass, Map<String, ?> loginModuleOptions, String invalidLoginMessage) {
        return CompletableFuture.supplyAsync(() -> {
            LoginContext loginContext;
            try {
                loginContext = new LoginContext("FIXJaasLoginContext", null, callbackHandler, getConfiguration(loginModuleClass, loginModuleOptions));
            } catch (LoginException e) {
                throw new IllegalStateException("Failure to setup JAAS LoginContext", e);
            }
            try {
                loginContext.login();
                fixSession.setAuthenticatedSubject(loginContext.getSubject());
                return Optional.empty();
            } catch (LoginException e) {
                fixSession.setAuthenticatedSubject(null);
                return Optional.of(invalidLoginMessage);
            }
        }, executor);
    }

    /**
     * Creates a {@code CompletableFuture<Optional<String>>} to be returned by {@link org.lolaf.staffix.api.application.FixApplication#validateLogon(FixSession, DecodedFixMessage, Executor)} implementation
     * that will be backed by a JAAS login module. Implementation will also set the correct JAAS Subject upon correct identification in {@link FixSession#getAuthenticatedSubject()}
     * This implementation will use a generic {@link CallbackHandler} that is compatible with login modules using an {@link NameCallback} and {@link PasswordCallback} and will map them to
     * regular FIX logon credentials fields {@link #USERNAME} and {@link #PASSWORD}
     *
     * @param fixSession          the fix session for which the auth is being processed
     * @param logonMessage        the logon message to use for retrieving auth credentials
     * @param executor            the executor to use to process asynchronously the login
     * @param loginModuleClass    the JAAS login module class name
     * @param loginModuleOptions  the JAAS login module options
     * @param invalidLoginMessage the reject message to be returned by the {@code Optional<String>} in case of failed authentication
     */
    public static CompletableFuture<Optional<String>> jaasLogin(FixSession fixSession, DecodedFixMessage logonMessage, Executor executor,
                                                                Class<? extends LoginModule> loginModuleClass, Map<String, ?> loginModuleOptions, String invalidLoginMessage) {
        return jaasLogin(fixSession, getCallbackHandler(logonMessage), executor, loginModuleClass, loginModuleOptions, invalidLoginMessage);
    }

    private static Configuration getConfiguration(Class<? extends LoginModule> loginModuleClass, Map<String, ?> loginModuleOptions) {
        return new Configuration() {
            @Override
            public AppConfigurationEntry[] getAppConfigurationEntry(String name) {
                return new AppConfigurationEntry[]{new AppConfigurationEntry(loginModuleClass.getName(),
                        AppConfigurationEntry.LoginModuleControlFlag.REQUIRED, loginModuleOptions == null ? Map.of() : loginModuleOptions)};
            }
        };
    }

    private static CallbackHandler getCallbackHandler(DecodedFixMessage logonMessage) {
        return callbacks -> {
            for (Callback cb : callbacks) {
                if (cb instanceof NameCallback) {
                    NameCallback ncb = (NameCallback) cb;
                    ncb.setName(findLogonMessageFieldValue(logonMessage, USERNAME));
                } else if (cb instanceof PasswordCallback) {
                    PasswordCallback pcb = (PasswordCallback) cb;
                    String password = findLogonMessageFieldValue(logonMessage, PASSWORD);
                    if (password != null) {
                        pcb.setPassword(password.toCharArray());
                    }
                }
            }
        };
    }

    public static String findLogonMessageFieldValue(DecodedFixMessage logonMessage, FixField field) {
        return logonMessage.getValue(field.getCode(), StringSerde.instance(), null);
    }
}