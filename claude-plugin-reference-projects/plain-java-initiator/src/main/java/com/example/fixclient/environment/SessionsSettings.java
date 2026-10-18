package com.example.fixclient.environment;

import lombok.experimental.UtilityClass;
import org.lolaf.staffix.stores.sessions.file.FileSessionsSettingsStoreSettings;

import java.net.URI;
import java.net.URISyntaxException;

@UtilityClass
class SessionsSettings {

    static FileSessionsSettingsStoreSettings fromClasspath() {
        return FileSessionsSettingsStoreSettings.builder()
                .fixSessionSettingsUri(classpathUri("/sessions/order-entry.yaml"))
                .build();
    }

    private static URI classpathUri(String resource) {
        try {
            return SessionsSettings.class.getResource(resource).toURI();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(resource, e);
        }
    }
}
