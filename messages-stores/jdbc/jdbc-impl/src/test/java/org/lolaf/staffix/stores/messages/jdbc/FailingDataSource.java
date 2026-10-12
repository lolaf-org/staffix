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
package org.lolaf.staffix.stores.messages.jdbc;

import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.experimental.Delegate;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;

/**
 * A database the test takes down, or whose {@code DELETE} statements it makes fail, for the store's outage paths.
 */
@RequiredArgsConstructor
class FailingDataSource implements DataSource {

    @Delegate(excludes = ConnectionSource.class)
    private final DataSource dataSource;
    @Setter
    private volatile boolean down;
    @Setter
    private volatile boolean failingDeletes;

    @Override
    public Connection getConnection() throws SQLException {
        if (down) {
            throw new SQLTransientConnectionException("the database is down");
        }
        Connection connection = dataSource.getConnection();
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    Object result = invoke(connection, method, args);
                    if (result instanceof PreparedStatement && ((String) args[0]).startsWith("DELETE")) {
                        return failingWhenAsked((PreparedStatement) result);
                    }
                    return result;
                });
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return getConnection();
    }

    private PreparedStatement failingWhenAsked(PreparedStatement statement) {
        return (PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{PreparedStatement.class},
                (proxy, method, args) -> {
                    if (failingDeletes && method.getName().startsWith("execute")) {
                        throw new SQLException("the DELETE fails");
                    }
                    return invoke(statement, method, args);
                });
    }

    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private interface ConnectionSource {

        Connection getConnection() throws SQLException;

        Connection getConnection(String username, String password) throws SQLException;
    }
}
