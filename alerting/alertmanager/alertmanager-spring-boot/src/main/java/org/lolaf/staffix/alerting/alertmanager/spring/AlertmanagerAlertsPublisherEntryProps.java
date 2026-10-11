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
package org.lolaf.staffix.alerting.alertmanager.spring;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationPropertiesSource;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One configured instance of the Alertmanager alerts publisher.
 */
@Data
@ConfigurationPropertiesSource
public class AlertmanagerAlertsPublisherEntryProps {
    /**
     * Base URLs of the Alertmanagers, every one of a highly available cluster. Required.
     */
    private List<URI> urls = new ArrayList<>();
    /**
     * How often every ongoing alert is posted again, one minute by default.
     */
    private Duration resendInterval;
    /**
     * Labels added to every alert, such as the environment.
     */
    private Map<String, String> staticLabels = new LinkedHashMap<>();
    /**
     * Headers sent with every post, such as an authorization.
     */
    private Map<String, String> headers = new LinkedHashMap<>();
    /**
     * Name of a Spring Boot SSL bundle whose truststore trusts the Alertmanagers' certificates; the JVM's default
     * otherwise.
     */
    private String sslBundle;
    /**
     * Timeout for establishing a connection to an Alertmanager.
     */
    private Duration connectTimeout;
    /**
     * Timeout for a whole post.
     */
    private Duration requestTimeout;
    /**
     * Spring bean name of a Function from HttpSenderSettings to HttpSender building the HTTP sender; the JDK client
     * by default.
     */
    private String httpSenderFactoryBean;
    /**
     * Spring bean name of ProxySelector for the Alertmanager HTTP client.
     */
    private String proxySelectorBean;
    /**
     * Spring bean name of Authenticator for the Alertmanager HTTP client.
     */
    private String authenticatorBean;
}
