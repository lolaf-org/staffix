package com.example.gateway.environment;

import lombok.experimental.UtilityClass;
import org.lolaf.staffix.stores.sessions.file.FileSessionsSettingsStoreSettings;

import java.net.URI;
import java.net.URISyntaxException;

@UtilityClass
class SessionsSettings {

    /**
     * One file per counterparty: a new counterparty is a new file and a new entry here.
     */
    static FileSessionsSettingsStoreSettings fromClasspath() {
        return FileSessionsSettingsStoreSettings.builder()
                .fixSessionSettingsUri(classpathUri("/sessions/client-a.yaml"))
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
