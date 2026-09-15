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
package org.lolaf.staffix.monitoring.micrometer.spring;

import lombok.Data;
import org.lolaf.staffix.monitoring.micrometer.MicrometerMonitoringManagerSettings.TimerSettings;
import org.springframework.boot.context.properties.ConfigurationPropertiesSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@code staffix.*} properties for the Micrometer monitoring plugin.
 */
@Data
@ConfigurationPropertiesSource
public class TimerSettingsProps {
    /**
     * Whether a histogram is published as well as the summary statistics, which is what lets a backend compute
     * percentiles across instances rather than per instance.
     */
    private Boolean publishPercentileHistogram;
    /**
     * The percentiles computed locally.
     */
    private List<Double> histogramsPercentiles = new ArrayList<>();
    /**
     * How long a measurement counts towards the rolling distribution.
     */
    private Duration distributionStatisticExpiry;
    /**
     * How many expiry windows are kept, so the statistics do not reset to nothing at each boundary.
     */
    private Integer distributionStatisticBufferLength;
    /**
     * Digits of precision for the local percentiles, traded against the memory the histogram takes.
     */
    private Integer percentilePrecision;
    /**
     * Boundaries to publish counts for, for alerting on "how many exceeded this" rather than on a percentile.
     */
    private List<Duration> serviceLevelObjectives = new ArrayList<>();
    /**
     * The bottom of the histogram range. Measurements below it are still counted, but not resolved.
     */
    private Duration minExpectedValue;
    /**
     * The top of the histogram range, which should be set above the worst latency worth distinguishing.
     */
    private Duration maxExpectedValue;

    public TimerSettings toSettings() {
        TimerSettings.TimerSettingsBuilder b = TimerSettings.builder();
        if (publishPercentileHistogram != null) b.publishPercentileHistogram(publishPercentileHistogram);
        if (histogramsPercentiles != null && !histogramsPercentiles.isEmpty())
            b.histogramsPercentiles(histogramsPercentiles);
        if (distributionStatisticExpiry != null) b.distributionStatisticExpiry(distributionStatisticExpiry);
        if (distributionStatisticBufferLength != null)
            b.distributionStatisticBufferLength(distributionStatisticBufferLength);
        if (percentilePrecision != null) b.percentilePrecision(percentilePrecision);
        if (serviceLevelObjectives != null) serviceLevelObjectives.forEach(b::serviceLevelObjective);
        if (minExpectedValue != null) b.minExpectedValue(minExpectedValue);
        if (maxExpectedValue != null) b.maxExpectedValue(maxExpectedValue);
        return b.build();
    }
}
