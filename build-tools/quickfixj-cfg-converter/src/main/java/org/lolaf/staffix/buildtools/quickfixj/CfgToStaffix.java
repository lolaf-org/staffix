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
package org.lolaf.staffix.buildtools.quickfixj;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts QuickFIX/J 3.0.2 {@code .cfg} files to staffix session settings, as the migration skill's
 * {@code cfg-keys.md} describes, and writes a report of what it could not convert.
 *
 * <p>One source file with no dependency, so the skill runs it with {@code java CfgToStaffix.java} on any project
 * with a JDK 11 or later.
 */
public final class CfgToStaffix {

    static final String USAGE = "Usage: java CfgToStaffix.java [--format yaml|spring] [--out <dir>]"
            + " [--validation qfj|staffix] [--initiates-reset true|false] <file.cfg>...";

    private static final String DEFAULT_STORE = "default";
    private static final String SPRING_STORE = "staffix.sessions-settings-stores-memory.instances.";
    private static final List<String> DAYS = List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY",
            "SATURDAY", "SUNDAY");
    private static final Pattern TIME = Pattern.compile("\\s*(\\d{1,2}):(\\d{2}):(\\d{2})(?:\\s+(\\S+))?\\s*");
    private static final Pattern INDEXED = Pattern.compile("(LogonTag|SocketConnectHost|SocketConnectPort)(\\d+)");

    /**
     * Each key QuickFIX/J 3.0.2 declares that has no staffix setting, with what comes closest.
     */
    static final Map<String, String> NO_EQUIVALENT = new LinkedHashMap<>();
    /**
     * Each key that configures a component or the transport rather than a session, with where it goes in staffix.
     */
    static final Map<String, String> COMPONENT_KEYS = new LinkedHashMap<>();
    /**
     * The keys {@link #convert} maps to session, initiator or acceptor settings.
     */
    static final Set<String> SESSION_KEYS = new LinkedHashSet<>(List.of(
            "BeginString", "DefaultApplVerID", "SenderCompID", "SenderSubID", "SenderLocationID", "TargetCompID",
            "TargetSubID", "TargetLocationID", "SessionQualifier", "ConnectionType", "Description", "HeartBtInt",
            "LogonTimeout", "LogoutTimeout", "ResetOnLogon", "EnableNextExpectedMsgSeqNum", "ClosedResendInterval",
            "TimeStampPrecision", "AllowedRemoteAddresses", "LogonTag", "PersistMessages", "UseDataDictionary",
            "ValidateIncomingMessage", "ValidateChecksum", "ValidateFieldsHaveValues", "ValidateFieldsOutOfOrder",
            "ValidateUnorderedGroupFields", "ValidateUserDefinedFields", "AllowUnknownMsgFields", "CheckCompID",
            "CheckLatency", "MaxLatency", "DataDictionary", "AppDataDictionary", "TransportDataDictionary",
            "StartTime", "EndTime", "StartDay", "EndDay", "Weekdays", "NonStopSession", "TimeZone",
            "SocketConnectHost", "SocketConnectPort", "ReconnectInterval", "SocketAcceptAddress", "SocketAcceptPort",
            "SocketUseSSL", "SocketKeyStore", "SocketKeyStorePassword", "KeyStoreType", "SocketTrustStore",
            "SocketTrustStorePassword", "TrustStoreType", "EnabledProtocols", "NeedClientAuth"));

    static {
        noEquivalent("Proxy: none", "ProxyType", "ProxyVersion", "ProxyHost", "ProxyPort", "ProxyUser",
                "ProxyPassword", "ProxyDomain", "ProxyWorkstation");
        noEquivalent("none", "SocketConnectTimeout", "SocketLocalHost", "SocketLocalPort", "RefreshOnLogon",
                "DisconnectOnError", "SendRedundantResendRequests", "ForceResendWhenCorruptedStore",
                "EnableLastMsgSeqNumProcessed", "DisableHeartBeatCheck", "RejectMessageOnUnhandledException",
                "ContinueInitializationOnError", "RequiresOrigSendingTime", "FirstFieldInGroupIsDelimiter",
                "AllowPosDup", "JdbcSessionIdDefaultPropertyValue", "FileStoreMaxCachedMsgs");
        noEquivalent("TCP only", "SocketConnectProtocol", "SocketAcceptProtocol");
        noEquivalent("every session is declared", "AcceptorTemplate", "DynamicSession");
        noEquivalent("staffix resets only on logon, a non-stop day's roll or the admin API", "ResetOnLogout",
                "ResetOnDisconnect", "ResetOnError");
        noEquivalent("none; maxMessagesResentPerRequest caps the other side's requests", "ResendRequestChunkSize");
        noEquivalent("none: staffix follows the session specification's timings", "HeartBeatTimeoutMultiplier",
                "TestRequestDelayMultiplier");
        noEquivalent("the acceptor's fixSessionEventsListener sees refused sessions", "LogMessageWhenSessionNotFound");
        noEquivalent("staffix ignores a garbled message; detectGarbledMessages adds the costly checks",
                "RejectGarbledMessage");
        noEquivalent("staffix always rejects an invalid message", "RejectInvalidMessage");
        noEquivalent("staffix always checks sequence numbers", "ValidateSequenceNumbers");
        noEquivalent("IOSettings.writeHighWatermark warns, it never refuses", "MaxScheduledWriteRequests");
        noEquivalent("none, the file store or JDBC", "SleepycatDatabaseDir", "SleepycatMessageDbName",
                "SleepycatSequenceDbName");
        noEquivalent("none, the file or OTLP messages logger", "JdbcLogHeartBeats", "JdbcLogIncomingTable",
                "JdbcLogOutgoingTable", "JdbcLogEventTable");
        noEquivalent("none", "SocketOobInline", "SocketSynchronousWrites", "SocketSynchronousWriteTimeout");

        component("file store storageDirectoryPath: one file store instance per distinct path", "FileStorePath");
        component("file store syncWrites; QuickFIX/J's default is N, staffix's true", "FileStoreSync");
        component("the DataSource the JDBC store is given (Spring: spring.datasource.*)", "JdbcDataSourceName",
                "JdbcURL", "JdbcDriver", "JdbcUser", "JdbcPassword", "JdbcMaxActiveConnection",
                "JdbcMinIdleConnection", "JdbcMaxConnectionLifeTime", "JdbcConnectionTimeout",
                "JdbcConnectionIdleTimeout", "JdbcConnectionKeepaliveTime", "JdbcConnectionTestQuery",
                "JndiContextFactory", "JndiProviderURL");
        component("JDBC store tablePrefix: staffix has its own tables, the data is not migrated",
                "JdbcStoreMessagesTableName", "JdbcStoreSessionsTableName");
        component("file logger logDirectory", "FileLogPath");
        component("file logger logTimePrecision", "FileIncludeMilliseconds", "FileIncludeTimeStampForMessages");
        component("N: the logger's messageFilter drops heartbeats (Java only)", "FileLogHeartbeats",
                "ScreenLogShowHeartBeats", "SLF4JLogHeartbeats");
        component("SLF4J logger logIncoming, logOutgoing, logEvents", "ScreenLogShowIncoming",
                "ScreenLogShowOutgoing", "ScreenLogShowEvents");
        component("the logging backend's pattern", "ScreenIncludeMilliseconds");
        component("SLF4J logger loggerNameForFixSession: one name per session, the logging configuration naming"
                        + " this category changes", "SLF4JLogEventCategory", "SLF4JLogIncomingMessageCategory",
                "SLF4JLogOutgoingMessageCategory", "SLF4JLogErrorEventCategory");
        component("SLF4J logger useMDC", "SLF4JLogPrependSessionID");
        component("Java only: IOSettings.socketOptions", "SocketTcpNoDelay", "SocketKeepAlive", "SocketLinger",
                "SocketReceiveBufferSize", "SocketSendBufferSize", "SocketReuseAddress", "SocketTrafficClass");
        component("Java only: engineSetupForSecureSession", "CipherSuites", "UseSNI", "SNIHostName",
                "EndpointIdentificationAlgorithm");
        component("Java: building the SSLContext; Spring: an SSLContext bean named by ssl-context-bean",
                "KeyManagerFactoryAlgorithm", "TrustManagerFactoryAlgorithm");
    }

    private final Options options;
    final Report report = new Report();
    final List<Session> sessions = new ArrayList<>();
    private final Map<String, Acceptor> acceptors = new LinkedHashMap<>();

    CfgToStaffix(Options options) {
        this.options = options;
    }

    public static void main(String[] args) throws IOException {
        Options options;
        try {
            options = Options.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println(USAGE);
            System.exit(2);
            return;
        }
        CfgToStaffix converter = new CfgToStaffix(options);
        try {
            converter.convert(options.files);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.exit(1);
            return;
        }
        for (Path written : converter.write()) {
            System.out.println("Wrote " + written);
        }
    }

    /**
     * Converts every session of the files, named across all of them so that names are unique and stable.
     */
    void convert(List<Path> files) throws IOException {
        List<Session> supported = new ArrayList<>();
        for (Path file : files) {
            Cfg cfg = Cfg.parse(file);
            for (Cfg.Section section : cfg.sessions) {
                Session session = new Session(file, section.line, cfg.resolve(section));
                String beginString = session.take("BeginString");
                String applVerId = session.take("DefaultApplVerID");
                session.version = Version.of(session, beginString, applVerId);
                if (session.version == null) {
                    report.notConverted(session, "FIXT.1.1".equals(beginString.trim())
                            ? "DefaultApplVerID " + applVerId : "BeginString " + beginString);
                } else {
                    supported.add(session);
                }
            }
        }
        name(supported);
        for (Session session : supported) {
            convert(session);
            sessions.add(session);
        }
    }

    private static void name(List<Session> sessions) {
        Map<String, Integer> byCompIds = new LinkedHashMap<>();
        for (Session session : sessions) {
            session.name = session.values.get("SessionQualifier");
            if (session.name == null) {
                session.name = (session.values.get("SenderCompID") + "-" + session.values.get("TargetCompID"))
                        .toLowerCase(Locale.ROOT);
                byCompIds.merge(session.name, 1, Integer::sum);
            }
        }
        Set<String> taken = new LinkedHashSet<>();
        for (Session session : sessions) {
            if (byCompIds.getOrDefault(session.name, 0) > 1 && session.values.get("SessionQualifier") == null) {
                session.name += "-" + session.version.yaml.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
            }
            String name = session.name;
            for (int n = 2; !taken.add(session.name); n++) {
                session.name = name + "-" + n;
            }
        }
    }

    /**
     * Writes the session files or the properties file, and {@code report.md}, under the output directory.
     */
    List<Path> write() throws IOException {
        placeAcceptors();
        List<Path> written = new ArrayList<>();
        Files.createDirectories(options.out);
        if (options.format == Format.YAML) {
            for (Session session : sessions) {
                Path file = options.out.resolve("sessions").resolve(session.store).resolve(session.name + ".yaml");
                Files.createDirectories(file.getParent());
                Files.write(file, Yaml.session(session).getBytes(StandardCharsets.UTF_8));
                written.add(file);
            }
        } else {
            Path file = options.out.resolve("staffix-sessions.properties");
            Files.write(file, Spring.properties(sessions, acceptors.values()).getBytes(StandardCharsets.UTF_8));
            written.add(file);
        }
        Path reportFile = options.out.resolve("report.md");
        Files.write(reportFile, report.markdown(options, sessions, acceptors.values()).getBytes(StandardCharsets.UTF_8));
        written.add(reportFile);
        return written;
    }

    private void placeAcceptors() {
        Set<String> ports = new LinkedHashSet<>();
        boolean portsUnique = true;
        for (String bind : acceptors.keySet()) {
            portsUnique &= ports.add(bind.substring(bind.lastIndexOf(':') + 1));
        }
        for (Acceptor acceptor : acceptors.values()) {
            if (acceptors.size() == 1) {
                acceptor.name = "acceptor";
                acceptor.store = DEFAULT_STORE;
            } else {
                String port = acceptor.bindAddress.substring(acceptor.bindAddress.lastIndexOf(':') + 1);
                acceptor.name = "acceptor-" + (portsUnique ? port : acceptor.bindAddress.replace(':', '-'));
                acceptor.store = acceptor.name;
            }
            for (Session session : acceptor.sessions) {
                session.store = acceptor.store;
            }
        }
    }

    private void convert(Session session) {
        identity(session);
        behaviour(session);
        validation(session);
        schedule(session);
        connection(session);
        components(session);
        for (String key : session.unread()) {
            if (NO_EQUIVALENT.containsKey(key)) {
                report.noEquivalent(session, key, NO_EQUIVALENT.get(key));
            } else {
                session.applicationSettings.put(key, session.take(key));
                report.applicationSetting(session, key);
            }
        }
    }

    private void identity(Session session) {
        String type = session.take("ConnectionType");
        if ("initiator".equalsIgnoreCase(type)) {
            session.initiator = true;
        } else if (!"acceptor".equalsIgnoreCase(type)) {
            throw new IllegalArgumentException(session.where() + ": ConnectionType must be initiator or acceptor, not "
                    + type);
        }
        session.doc.put("fixSessionType", session.initiator ? "INITIATOR" : "ACCEPTOR");
        for (String key : List.of("SenderCompID", "SenderSubID", "SenderLocationID", "TargetCompID", "TargetSubID",
                "TargetLocationID")) {
            String value = session.take(key);
            if (value != null) {
                session.id.put(key.substring(0, 1).toLowerCase(Locale.ROOT) + key.substring(1), value);
            }
        }
        if (!session.id.containsKey("senderCompID") || !session.id.containsKey("targetCompID")) {
            throw new IllegalArgumentException(session.where() + ": SenderCompID and TargetCompID are required");
        }
        session.take("SessionQualifier");
        session.description = session.take("Description");
    }

    private void behaviour(Session session) {
        String heartbeat = session.take("HeartBtInt");
        if (session.initiator) {
            seconds(session, "HeartBtInt", heartbeat, "heartBeatInterval.initiatorInterval");
        } else {
            long upper = 20;
            if (heartbeat != null && !Placeholder.in(heartbeat)) {
                upper = Math.max(upper, Long.parseLong(heartbeat.trim()));
            }
            session.doc.put("heartBeatInterval.acceptorUpperBoundInterval", "PT" + upper + "S");
            report.behaviour(session, "A staffix acceptor refuses a Logon whose HeartBtInt is outside 1 to " + upper
                    + " seconds; QuickFIX/J took any. Check the counterparty's value.");
        }
        seconds(session, "LogonTimeout", session.take("LogonTimeout"), "logInOrOutResponseTimeout");
        String logoutTimeout = session.take("LogoutTimeout");
        if (logoutTimeout != null && !"2".equals(logoutTimeout.trim())) {
            report.noEquivalent(session, "LogoutTimeout", "staffix has one timeout for both, logInOrOutResponseTimeout,"
                    + " written from LogonTimeout");
        }
        if (bool(session, "ResetOnLogon", false)) {
            session.doc.put("resetSeqNumOnLogon", true);
        }
        session.doc.put("enabledLogonNextExpectedMsgSeqNum", bool(session, "EnableNextExpectedMsgSeqNum", false));
        session.doc.put("resendRequestRange", bool(session, "ClosedResendInterval", false) ? "CLOSED" : "OPEN_ENDED");
        String precision = session.take("TimeStampPrecision");
        session.doc.put("sendingTimeAccuracy", precision == null ? "MILLISECONDS" : precision(session, precision));
        String addresses = session.take("AllowedRemoteAddresses");
        if (addresses != null) {
            session.doc.put("allowedAddresses", list(addresses));
        }
        List<String> tags = new ArrayList<>();
        String tag0 = session.take("LogonTag");
        if (tag0 != null) {
            tags.add(tag0);
        }
        tags.addAll(session.takeIndexed("LogonTag"));
        for (String tag : tags) {
            report.code(session, "LogonTag", tag, "add `" + tag + "` to the Logon in the application's"
                    + " onAdminMessageEncoding (concepts.md)");
        }
        if (!bool(session, "PersistMessages", true)) {
            report.code(session, "PersistMessages", "N", "in Java, the store's messageFilter `(type, message) -> true`;"
                    + " with Spring, no equivalent");
        }
        for (String key : List.of("DataDictionary", "AppDataDictionary", "TransportDataDictionary")) {
            String file = session.take(key);
            if (file != null) {
                report.dictionary(session, key, file);
            }
        }
    }

    private void validation(Session session) {
        boolean qfj = options.validation == Validation.QFJ;
        boolean dictionaryChecks = bool(session, "UseDataDictionary", true)
                & bool(session, "ValidateIncomingMessage", true);
        String outOfOrder = session.take("ValidateFieldsOutOfOrder");
        String unorderedGroups = session.take("ValidateUnorderedGroupFields");
        String haveValues = session.take("ValidateFieldsHaveValues");
        String userDefined = session.take("ValidateUserDefinedFields");
        String unknown = session.take("AllowUnknownMsgFields");
        if (dictionaryChecks) {
            if (qfj) {
                validation(session, "validateRequiredFields", true);
                validation(session, "allowUndefinedTagsForMessage", false);
                validation(session, "validateDuplicateTags", true);
            }
            if (outOfOrder != null || unorderedGroups != null || qfj) {
                boolean fields = outOfOrder == null || yes(session, "ValidateFieldsOutOfOrder", outOfOrder);
                boolean groups = unorderedGroups == null || yes(session, "ValidateUnorderedGroupFields", unorderedGroups);
                if (fields != groups) {
                    report.behaviour(session, "staffix has one order check for fields and groups:"
                            + " validateFieldsOutOfOrder is on, where QuickFIX/J checked only "
                            + (fields ? "fields" : "groups") + ".");
                }
                validation(session, "validateFieldsOutOfOrder", fields || groups);
            }
            if (haveValues != null) {
                validation(session, "validateFieldsHaveValues", yes(session, "ValidateFieldsHaveValues", haveValues));
            }
            if (userDefined != null) {
                validation(session, "allowUserDefinedFields", !yes(session, "ValidateUserDefinedFields", userDefined));
            }
            if (unknown != null) {
                validation(session, "allowUnknownFields", yes(session, "AllowUnknownMsgFields", unknown));
            }
        } else {
            validation(session, "validateRequiredFields", false);
            validation(session, "validateDuplicateTags", false);
            validation(session, "validateFieldsOutOfOrder", false);
            validation(session, "validateFieldsHaveValues", false);
            validation(session, "allowUndefinedTagsForMessage", true);
            validation(session, "allowUserDefinedFields", true);
            validation(session, "allowUnknownFields", true);
        }
        if (qfj) {
            validation(session, "validateBeginString", true);
        }
        String checksum = session.take("ValidateChecksum");
        if (checksum != null) {
            validation(session, "validateChecksum", yes(session, "ValidateChecksum", checksum));
        }
        String compId = session.take("CheckCompID");
        if (compId != null || qfj) {
            validation(session, "validateCompId", compId == null || yes(session, "CheckCompID", compId));
        }
        String checkLatency = session.take("CheckLatency");
        String maxLatency = session.take("MaxLatency");
        boolean latency = checkLatency == null || yes(session, "CheckLatency", checkLatency);
        if (latency && (checkLatency != null || maxLatency != null || qfj)) {
            seconds(session, "MaxLatency", maxLatency == null ? "120" : maxLatency, "validationSettings.maxSendingTime");
        }
    }

    private void schedule(Session session) {
        String startTime = session.take("StartTime");
        String endTime = session.take("EndTime");
        String startDay = session.take("StartDay");
        String endDay = session.take("EndDay");
        String weekdays = session.take("Weekdays");
        String zone = session.take("TimeZone");
        if (bool(session, "NonStopSession", false)) {
            session.doc.put("sessionScheduleSettings.timeZone", zone == null ? "UTC" : zone);
            return;
        }
        if (startTime == null || endTime == null) {
            throw new IllegalArgumentException(session.where() + ": StartTime and EndTime are required unless"
                    + " NonStopSession=Y");
        }
        Matcher start = time(session, "StartTime", startTime);
        Matcher end = time(session, "EndTime", endTime);
        String sessionZone = zone == null ? "UTC" : zone;
        String startZone = start.group(4) == null ? sessionZone : start.group(4);
        String endZone = end.group(4) == null ? sessionZone : end.group(4);
        if (!startZone.equals(endZone)) {
            report.question(session, "StartTime is read in " + startZone + " and EndTime in " + endZone + ", since a"
                    + " time without a zone is in TimeZone (UTC by default): staffix has one zone per schedule, "
                    + startZone + " is written. Check the close time.");
        }
        session.doc.put("sessionScheduleSettings.timeZone", startZone);
        String open = hhmmss(start);
        String close = hhmmss(end);
        List<Object> windows = new ArrayList<>();
        if (startDay != null || endDay != null) {
            if (startDay == null || endDay == null) {
                throw new IllegalArgumentException(session.where() + ": StartDay and EndDay go together");
            }
            String first = day(session, "StartDay", startDay);
            String last = day(session, "EndDay", endDay);
            if (first.equals(last) && open.equals(close)) {
                report.question(session, "A weekly session opening and closing at the same instant: staffix has no"
                        + " such window. Write its schedule by hand, with nonStopSchedules.");
                return;
            }
            windows.add(window(first, open, last, close));
            session.doc.put("sessionScheduleSettings.sessionSchedules", windows);
            return;
        }
        List<String> days = new ArrayList<>(DAYS);
        if (weekdays != null) {
            days.clear();
            for (String day : list(weekdays)) {
                days.add(day(session, "Weekdays", day));
            }
        }
        if (open.equals(close)) {
            List<Object> nonStop = new ArrayList<>();
            for (String day : days) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("dayOfWeek", day);
                if (options.initiatesReset != null) {
                    entry.put("sequenceResetTime", open);
                    entry.put("initiatesReset", options.initiatesReset);
                }
                nonStop.add(entry);
            }
            session.doc.put("sessionScheduleSettings.nonStopSchedules", nonStop);
            if (options.initiatesReset == null) {
                report.question(session, "QuickFIX/J stays up and resets every day at " + open + ". Which side sends"
                        + " the reset Logon? Rerun with --initiates-reset true or false; until then no reset is"
                        + " written.");
            }
            return;
        }
        for (String day : days) {
            String closeDay = close.compareTo(open) > 0 ? day : DAYS.get((DAYS.indexOf(day) + 1) % DAYS.size());
            windows.add(window(day, open, closeDay, close));
        }
        session.doc.put("sessionScheduleSettings.sessionSchedules", windows);
        report.behaviour(session, "QuickFIX/J reset the sequence numbers at the first logon of each window; a staffix"
                + " window keeps them. Does the counterparty expect the daily reset? resetSeqNumOnLogon: true gives"
                + " it at every logon, intraday reconnects included.");
    }

    private void connection(Session session) {
        Tls tls = tls(session);
        if (session.initiator) {
            List<String> addresses = new ArrayList<>();
            String host = session.take("SocketConnectHost");
            String port = session.take("SocketConnectPort");
            if (host == null || port == null) {
                throw new IllegalArgumentException(session.where() + ": SocketConnectHost and SocketConnectPort are"
                        + " required for an initiator");
            }
            addresses.add(host.trim() + ":" + port.trim());
            List<String> hosts = session.takeIndexed("SocketConnectHost");
            List<String> ports = session.takeIndexed("SocketConnectPort");
            for (int i = 0; i < Math.min(hosts.size(), ports.size()); i++) {
                addresses.add(hosts.get(i).trim() + ":" + ports.get(i).trim());
            }
            if (hosts.size() != ports.size()) {
                report.question(session, "SocketConnectHost<n> and SocketConnectPort<n> do not pair up: only the"
                        + " pairs were written.");
            }
            String retry = session.take("ReconnectInterval");
            String connectionRetry = "PT30S";
            if (retry != null) {
                String first = retry.split(";")[0].replaceFirst("^\\d+[x*]", "").trim();
                if (retry.contains(";") || !first.equals(retry.trim())) {
                    report.noEquivalent(session, "ReconnectInterval", "staffix waits one interval between attempts:"
                            + " PT" + first + "S, the first of " + retry);
                }
                connectionRetry = Placeholder.in(first) ? first : "PT" + first + "S";
            }
            session.initiatorSettings = new Initiator(addresses, connectionRetry, tls);
            session.store = DEFAULT_STORE;
        } else {
            String port = session.take("SocketAcceptPort");
            if (port == null) {
                throw new IllegalArgumentException(session.where() + ": SocketAcceptPort is required for an acceptor");
            }
            String host = session.take("SocketAcceptAddress");
            String bind = (host == null ? "0.0.0.0" : host.trim()) + ":" + port.trim();
            Acceptor acceptor = acceptors.computeIfAbsent(bind, Acceptor::new);
            acceptor.sessions.add(session);
            if (acceptor.tls == null) {
                acceptor.tls = tls;
            } else if (!acceptor.tls.equals(tls)) {
                report.question(session, "Sessions on " + bind + " differ in their TLS settings: staffix has one per"
                        + " acceptor, the first session's is written.");
            }
            session.acceptor = acceptor;
        }
    }

    private Tls tls(Session session) {
        boolean on = bool(session, "SocketUseSSL", false);
        Tls tls = new Tls();
        tls.keyStore = session.take("SocketKeyStore");
        tls.keyStorePassword = session.take("SocketKeyStorePassword");
        tls.keyStoreType = session.take("KeyStoreType");
        tls.trustStore = session.take("SocketTrustStore");
        tls.trustStorePassword = session.take("SocketTrustStorePassword");
        tls.trustStoreType = session.take("TrustStoreType");
        String protocols = session.take("EnabledProtocols");
        String needClientAuth = session.take("NeedClientAuth");
        if (!on) {
            return null;
        }
        if (tls.keyStore == null && !session.initiator) {
            report.question(session, "SocketUseSSL=Y without SocketKeyStore: QuickFIX/J used its bundled test"
                    + " keystore, which must not be carried over. Provide the acceptor's keystore.");
        }
        if (tls.keyStore != null && tls.keyStoreType == null) {
            tls.keyStoreType = "JKS";
        }
        if (tls.trustStore != null && tls.trustStoreType == null) {
            tls.trustStoreType = "JKS";
        }
        if (protocols != null) {
            List<String> each = list(protocols);
            tls.protocol = each.get(0);
            if (each.size() > 1) {
                report.code(session, "EnabledProtocols", protocols, "staffix's Spring properties take one protocol, "
                        + tls.protocol + " is written; several need Java's engineSetupForSecureSession");
            }
        }
        tls.needClientAuth = needClientAuth != null && yes(session, "NeedClientAuth", needClientAuth);
        if (tls.keyStorePassword != null && !Placeholder.in(tls.keyStorePassword)) {
            tls.keyStorePassword = "${FIX_KEY_STORE_PASSWORD}";
            report.question(session, "The keystore password is in clear in the .cfg: it is written as"
                    + " ${FIX_KEY_STORE_PASSWORD}, an environment variable to set.");
        }
        if (tls.trustStorePassword != null && !Placeholder.in(tls.trustStorePassword)) {
            tls.trustStorePassword = "${FIX_TRUST_STORE_PASSWORD}";
            report.question(session, "The truststore password is in clear in the .cfg: it is written as"
                    + " ${FIX_TRUST_STORE_PASSWORD}, an environment variable to set.");
        }
        if (options.format == Format.YAML) {
            for (Map.Entry<String, Object> setting : tls.properties(!session.initiator).entrySet()) {
                report.component("SocketUseSSL", setting.getValue().toString(), "Java: "
                        + (session.initiator ? "sslSettings" : "serverSSLSettings") + ", " + setting.getKey(), session);
            }
        }
        return tls;
    }

    private void components(Session session) {
        for (String key : new ArrayList<>(session.unread())) {
            if (COMPONENT_KEYS.containsKey(key)) {
                report.component(key, session.take(key), COMPONENT_KEYS.get(key), session);
            }
        }
    }

    private void seconds(Session session, String key, String value, String path) {
        if (value == null) {
            return;
        }
        if (Placeholder.in(value)) {
            report.question(session, key + " is the placeholder `" + value + "`, a number of seconds where staffix"
                    + " takes a duration: write `" + path + "` by hand.");
            return;
        }
        try {
            session.doc.put(path, "PT" + Long.parseLong(value.trim()) + "S");
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(session.where() + ": " + key + " must be a number of seconds, not "
                    + value);
        }
    }

    private void validation(Session session, String check, boolean value) {
        session.doc.put("validationSettings." + check, value);
    }

    private boolean bool(Session session, String key, boolean absent) {
        String value = session.take(key);
        return value == null ? absent : yes(session, key, value);
    }

    private boolean yes(Session session, String key, String value) {
        String trimmed = value.trim();
        if (trimmed.equalsIgnoreCase("Y") || trimmed.equalsIgnoreCase("true")) {
            return true;
        }
        if (trimmed.equalsIgnoreCase("N") || trimmed.equalsIgnoreCase("false")) {
            return false;
        }
        throw new IllegalArgumentException(session.where() + ": " + key + " must be Y or N, not " + value);
    }

    private String precision(Session session, String value) {
        switch (value.trim()) {
            case "SECONDS":
                return "SECONDS";
            case "MILLIS":
                return "MILLISECONDS";
            case "MICROS":
                return "MICROSECONDS";
            case "NANOS":
                return "NANOSECONDS";
            default:
                throw new IllegalArgumentException(session.where() + ": TimeStampPrecision must be SECONDS, MILLIS,"
                        + " MICROS or NANOS, not " + value);
        }
    }

    private Matcher time(Session session, String key, String value) {
        Matcher matcher = TIME.matcher(value);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(session.where() + ": " + key + " must be HH:MM:SS, optionally followed"
                    + " by a time zone, not " + value);
        }
        return matcher;
    }

    private static String hhmmss(Matcher time) {
        return String.format("%02d:%s:%s", Integer.parseInt(time.group(1)), time.group(2), time.group(3));
    }

    private String day(Session session, String key, String value) {
        String lower = value.trim().toLowerCase(Locale.ROOT);
        for (String day : DAYS) {
            if (lower.length() >= 2 && day.toLowerCase(Locale.ROOT).startsWith(lower)) {
                return day;
            }
        }
        throw new IllegalArgumentException(session.where() + ": " + key + " must name a day of the week, not " + value);
    }

    private static Map<String, Object> window(String startDay, String startTime, String endDay, String endTime) {
        Map<String, Object> window = new LinkedHashMap<>();
        window.put("startDay", startDay);
        window.put("startTime", startTime);
        window.put("endDay", endDay);
        window.put("endTime", endTime);
        return window;
    }

    private static List<String> list(String value) {
        List<String> values = new ArrayList<>();
        for (String each : value.split(",")) {
            if (!each.isBlank()) {
                values.add(each.trim());
            }
        }
        return values;
    }

    private static void noEquivalent(String closest, String... keys) {
        for (String key : keys) {
            NO_EQUIVALENT.put(key, closest);
        }
    }

    private static void component(String target, String... keys) {
        for (String key : keys) {
            COMPONENT_KEYS.put(key, target);
        }
    }

    enum Format { YAML, SPRING }

    enum Validation { QFJ, STAFFIX }

    static final class Options {
        Format format = Format.YAML;
        Path out = Paths.get("target", "staffix-migration");
        Validation validation = Validation.QFJ;
        Boolean initiatesReset;
        final List<Path> files = new ArrayList<>();

        static Options parse(String... args) {
            Options options = new Options();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if (arg.startsWith("--")) {
                    if (i + 1 == args.length) {
                        throw new IllegalArgumentException(arg + " needs a value");
                    }
                    String value = args[++i];
                    switch (arg) {
                        case "--format":
                            options.format = Format.valueOf(choice(arg, value, "yaml", "spring"));
                            break;
                        case "--out":
                            options.out = Paths.get(value);
                            break;
                        case "--validation":
                            options.validation = Validation.valueOf(choice(arg, value, "qfj", "staffix"));
                            break;
                        case "--initiates-reset":
                            options.initiatesReset = Boolean.valueOf(choice(arg, value, "true", "false")
                                    .equals("TRUE"));
                            break;
                        default:
                            throw new IllegalArgumentException("Unknown option " + arg);
                    }
                } else {
                    options.files.add(Paths.get(arg));
                }
            }
            if (options.files.isEmpty()) {
                throw new IllegalArgumentException("No .cfg file given");
            }
            return options;
        }

        private static String choice(String option, String value, String... choices) {
            for (String choice : choices) {
                if (choice.equals(value)) {
                    return value.toUpperCase(Locale.ROOT);
                }
            }
            throw new IllegalArgumentException(option + " takes " + String.join(" or ", choices) + ", not " + value);
        }
    }

    /**
     * A {@code .cfg} file as QuickFIX/J's {@code SessionSettings} reads it: {@code key=value} lines under
     * {@code [DEFAULT]} and {@code [SESSION]} sections, {@code #} comments.
     */
    static final class Cfg {
        final Map<String, String> defaults = new LinkedHashMap<>();
        final List<Section> sessions = new ArrayList<>();

        static Cfg parse(Path file) throws IOException {
            Cfg cfg = new Cfg();
            Map<String, String> current = null;
            int number = 0;
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                number++;
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                if (trimmed.equalsIgnoreCase("[DEFAULT]")) {
                    current = cfg.defaults;
                } else if (trimmed.equalsIgnoreCase("[SESSION]")) {
                    Section section = new Section(number);
                    cfg.sessions.add(section);
                    current = section.values;
                } else if (trimmed.startsWith("[")) {
                    throw new IllegalArgumentException(file + ":" + number + ": unknown section " + trimmed);
                } else {
                    int equals = trimmed.indexOf('=');
                    if (equals <= 0 || current == null) {
                        throw new IllegalArgumentException(file + ":" + number + ": expected key=value in a section,"
                                + " found " + trimmed);
                    }
                    current.put(trimmed.substring(0, equals).trim(), trimmed.substring(equals + 1).trim());
                }
            }
            return cfg;
        }

        Map<String, String> resolve(Section section) {
            Map<String, String> resolved = new LinkedHashMap<>(defaults);
            resolved.putAll(section.values);
            return resolved;
        }

        static final class Section {
            final int line;
            final Map<String, String> values = new LinkedHashMap<>();

            Section(int line) {
                this.line = line;
            }
        }
    }

    static final class Session {
        final Path file;
        final int line;
        final Map<String, String> values;
        private final Set<String> read = new LinkedHashSet<>();
        final Map<String, String> id = new LinkedHashMap<>();
        final Map<String, String> applicationSettings = new LinkedHashMap<>();
        final Doc doc = new Doc();
        String name;
        String description;
        Version version;
        boolean initiator;
        String store;
        Initiator initiatorSettings;
        Acceptor acceptor;

        Session(Path file, int line, Map<String, String> values) {
            this.file = file;
            this.line = line;
            this.values = values;
        }

        String take(String key) {
            read.add(key);
            String value = values.get(key);
            return value == null || value.isEmpty() ? null : value;
        }

        List<String> takeIndexed(String key) {
            Map<Integer, String> indexed = new TreeMap<>();
            for (Map.Entry<String, String> entry : values.entrySet()) {
                Matcher matcher = INDEXED.matcher(entry.getKey());
                if (matcher.matches() && matcher.group(1).equals(key)) {
                    read.add(entry.getKey());
                    indexed.put(Integer.parseInt(matcher.group(2)), entry.getValue());
                }
            }
            return new ArrayList<>(indexed.values());
        }

        List<String> unread() {
            List<String> unread = new ArrayList<>();
            for (String key : values.keySet()) {
                if (!read.contains(key)) {
                    unread.add(key);
                }
            }
            return unread;
        }

        String where() {
            return file.getFileName() + ":" + line;
        }
    }

    /**
     * The FIX version a session speaks, as YAML and as Spring properties write it; null for one QuickFIX/J speaks and
     * staffix does not.
     */
    static final class Version {
        final String yaml;
        final String spring;
        final boolean fixt;

        private Version(String yaml, String spring, boolean fixt) {
            this.yaml = yaml;
            this.spring = spring;
            this.fixt = fixt;
        }

        static Version of(Session session, String beginString, String defaultApplVerId) {
            if (beginString == null) {
                throw new IllegalArgumentException(session.where() + ": BeginString is required");
            }
            switch (beginString.trim()) {
                case "FIX.4.2":
                    return new Version("FIX.4.2", "VERSION_42", false);
                case "FIX.4.3":
                    return new Version("FIX.4.3", "VERSION_43", false);
                case "FIX.4.4":
                    return new Version("FIX.4.4", "VERSION_44", false);
                case "FIXT.1.1":
                    return fixt(session, defaultApplVerId);
                case "FIX.4.0":
                case "FIX.4.1":
                    return null;
                default:
                    throw new IllegalArgumentException(session.where() + ": BeginString " + beginString
                            + " has no staffix equivalent, staffix starts at FIX.4.2");
            }
        }

        private static Version fixt(Session session, String applVerId) {
            String id = applVerId == null ? "" : applVerId.trim();
            switch (id) {
                case "7":
                case "FIX.5.0":
                    return new Version("FIX.5.0", "VERSION_50", true);
                case "8":
                case "FIX.5.0SP1":
                    return new Version("FIX.5.0-SP1", "VERSION_50_SP1", true);
                case "9":
                case "FIX.5.0SP2":
                    return new Version("FIX.5.0-SP2", "VERSION_50_SP2", true);
                case "2":
                case "3":
                case "4":
                case "5":
                case "6":
                case "FIX.4.0":
                case "FIX.4.1":
                case "FIX.4.2":
                case "FIX.4.3":
                case "FIX.4.4":
                    return null;
                default:
                    throw new IllegalArgumentException(session.where() + ": a FIXT.1.1 session needs a"
                            + " DefaultApplVerID of FIX.5.0, FIX.5.0SP1 or FIX.5.0SP2 (7, 8 or 9), not '" + id + "'");
            }
        }
    }

    static final class Initiator {
        final List<String> addresses;
        final String connectionRetry;
        final Tls tls;

        Initiator(List<String> addresses, String connectionRetry, Tls tls) {
            this.addresses = addresses;
            this.connectionRetry = connectionRetry;
            this.tls = tls;
        }
    }

    static final class Acceptor {
        final String bindAddress;
        final List<Session> sessions = new ArrayList<>();
        Tls tls;
        String name;
        String store;

        Acceptor(String bindAddress) {
            this.bindAddress = bindAddress;
        }
    }

    static final class Tls {
        String keyStore;
        String keyStorePassword;
        String keyStoreType;
        String trustStore;
        String trustStorePassword;
        String trustStoreType;
        String protocol;
        boolean needClientAuth;

        Map<String, Object> properties(boolean server) {
            Map<String, Object> properties = new LinkedHashMap<>();
            putIfSet(properties, "key-store-path", keyStore);
            putIfSet(properties, "key-store-password", keyStorePassword);
            putIfSet(properties, "key-store-type", keyStoreType);
            putIfSet(properties, "trust-store-path", trustStore);
            putIfSet(properties, "trust-store-password", trustStorePassword);
            putIfSet(properties, "trust-store-type", trustStoreType);
            putIfSet(properties, "protocol", protocol);
            if (server && needClientAuth) {
                properties.put("need-client-auth", true);
            }
            return properties;
        }

        private static void putIfSet(Map<String, Object> properties, String key, String value) {
            if (value != null) {
                properties.put(key, value);
            }
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Tls && properties(true).equals(((Tls) other).properties(true));
        }

        @Override
        public int hashCode() {
            return properties(true).hashCode();
        }
    }

    /**
     * A session document as a tree of YAML paths, in the order the settings were put.
     */
    static final class Doc {
        final Map<String, Object> root = new LinkedHashMap<>();

        @SuppressWarnings("unchecked")
        void put(String path, Object value) {
            String[] segments = path.split("\\.");
            Map<String, Object> node = root;
            for (int i = 0; i < segments.length - 1; i++) {
                node = (Map<String, Object>) node.computeIfAbsent(segments[i], k -> new LinkedHashMap<String, Object>());
            }
            node.put(segments[segments.length - 1], value);
        }
    }

    static final class Placeholder {
        static boolean in(String value) {
            return value.contains("${");
        }
    }

    static final class Yaml {

        static String session(Session session) {
            StringBuilder yaml = new StringBuilder();
            yaml.append("# From ").append(session.where()).append('\n');
            if (session.description != null) {
                yaml.append("# ").append(session.description).append('\n');
            }
            yaml.append("# yaml-language-server: $schema=fix-session-settings.v1.schema.json\n");
            yaml.append("schemaVersion: 1\n");
            yaml.append("fixSessionId:\n");
            yaml.append("  name: ").append(scalar(session.name)).append('\n');
            yaml.append("  fixVersion: ").append(scalar(session.version.yaml)).append('\n');
            if (session.version.fixt) {
                yaml.append("  fixTSession: true\n");
            }
            for (Map.Entry<String, String> part : session.id.entrySet()) {
                yaml.append("  ").append(part.getKey()).append(": ").append(scalar(part.getValue())).append('\n');
            }
            map(yaml, session.doc.root, 0);
            if (!session.applicationSettings.isEmpty()) {
                yaml.append("fixApplicationSessionSettings:\n");
                for (Map.Entry<String, String> setting : session.applicationSettings.entrySet()) {
                    yaml.append("  ").append(scalar(setting.getKey())).append(": ").append(scalar(setting.getValue()))
                            .append('\n');
                }
            }
            return yaml.toString();
        }

        @SuppressWarnings("unchecked")
        private static void map(StringBuilder yaml, Map<String, Object> map, int indent) {
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                yaml.append("  ".repeat(indent)).append(entry.getKey()).append(':');
                Object value = entry.getValue();
                if (value instanceof Map) {
                    yaml.append('\n');
                    map(yaml, (Map<String, Object>) value, indent + 1);
                } else if (value instanceof List) {
                    yaml.append('\n');
                    for (Object item : (List<Object>) value) {
                        yaml.append("  ".repeat(indent + 1)).append("- ");
                        if (item instanceof Map) {
                            StringBuilder nested = new StringBuilder();
                            map(nested, (Map<String, Object>) item, indent + 2);
                            yaml.append(nested.substring(2 * (indent + 2)));
                        } else {
                            yaml.append(scalar(item)).append('\n');
                        }
                    }
                } else {
                    yaml.append(' ').append(scalar(value)).append('\n');
                }
            }
        }

        private static String scalar(Object value) {
            if (value instanceof Boolean || value instanceof Number) {
                return value.toString();
            }
            return '"' + value.toString().replace("\\", "\\\\").replace("\"", "\\\"") + '"';
        }
    }

    static final class Spring {
        private static final Map<String, String> RENAMED = Map.of(
                "fixSessionType", "type",
                "heartBeatInterval", "heartbeat",
                "validationSettings", "validation",
                "sessionScheduleSettings", "schedule");

        static String properties(List<Session> sessions, Iterable<Acceptor> acceptors) {
            StringBuilder properties = new StringBuilder();
            Map<String, Integer> indexes = new LinkedHashMap<>();
            for (Session session : sessions) {
                int index = indexes.merge(session.store, 1, Integer::sum) - 1;
                properties.append("\n# ").append(session.name).append(", from ").append(session.where()).append('\n');
                if (session.description != null) {
                    properties.append("# ").append(session.description).append('\n');
                }
                String prefix = SPRING_STORE + session.store + ".sessions[" + index + "].";
                id(properties, prefix + "fix-session-id.", session);
                flatten(properties, prefix, session.doc.root);
                for (Map.Entry<String, String> setting : session.applicationSettings.entrySet()) {
                    line(properties, prefix + "application-session-settings[" + setting.getKey() + "]",
                            setting.getValue());
                }
                if (session.initiatorSettings != null) {
                    String initiator = "staffix.initiators." + session.name + ".";
                    id(properties, initiator + "main-target.fix-session-id.", session);
                    List<String> addresses = session.initiatorSettings.addresses;
                    for (int i = 0; i < addresses.size(); i++) {
                        line(properties, initiator + "main-target.connect-addresses[" + i + "]", addresses.get(i));
                    }
                    line(properties, initiator + "connection-retry", session.initiatorSettings.connectionRetry);
                    if (session.initiatorSettings.tls != null) {
                        flatten(properties, initiator + "ssl.", session.initiatorSettings.tls.properties(false));
                    }
                }
            }
            for (Acceptor acceptor : acceptors) {
                String prefix = "staffix.acceptors." + acceptor.name + ".";
                properties.append("\n# ").append(acceptor.name).append('\n');
                line(properties, prefix + "bind-address", acceptor.bindAddress);
                if (!DEFAULT_STORE.equals(acceptor.store)) {
                    line(properties, prefix + "target-sessions-settings-store-instance-ids[0]", acceptor.store);
                }
                if (acceptor.tls != null) {
                    flatten(properties, prefix + "ssl.", acceptor.tls.properties(true));
                }
            }
            return properties.substring(1);
        }

        private static void id(StringBuilder properties, String prefix, Session session) {
            line(properties, prefix + "name", session.name);
            if (session.version.fixt) {
                line(properties, prefix + "fix-version", "FIXT_11");
                line(properties, prefix + "default-appl-ver-id", session.version.spring);
            } else {
                line(properties, prefix + "fix-version", session.version.spring);
            }
            for (Map.Entry<String, String> part : session.id.entrySet()) {
                line(properties, prefix + kebab(part.getKey()), part.getValue());
            }
        }

        @SuppressWarnings("unchecked")
        private static void flatten(StringBuilder properties, String prefix, Map<String, Object> map) {
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                String key = prefix + kebab(RENAMED.getOrDefault(entry.getKey(), entry.getKey()));
                Object value = entry.getValue();
                if (value instanceof Map) {
                    flatten(properties, key + ".", (Map<String, Object>) value);
                } else if (value instanceof List) {
                    List<Object> items = (List<Object>) value;
                    for (int i = 0; i < items.size(); i++) {
                        if (items.get(i) instanceof Map) {
                            flatten(properties, key + "[" + i + "].", (Map<String, Object>) items.get(i));
                        } else {
                            line(properties, key + "[" + i + "]", items.get(i));
                        }
                    }
                } else {
                    line(properties, key, value);
                }
            }
        }

        private static void line(StringBuilder properties, String key, Object value) {
            properties.append(key).append('=').append(value.toString().replace("\\", "\\\\")).append('\n');
        }

        static String kebab(String camel) {
            return camel.replaceAll("([a-z0-9])([A-Z])", "$1-$2").replaceAll("([A-Z])([A-Z][a-z])", "$1-$2")
                    .toLowerCase(Locale.ROOT);
        }
    }

    /**
     * What the conversion could not do by itself, for the migration report.
     */
    static final class Report {
        final List<String[]> noEquivalent = new ArrayList<>();
        final List<String[]> code = new ArrayList<>();
        final List<String[]> behaviour = new ArrayList<>();
        final List<String[]> questions = new ArrayList<>();
        final List<String[]> applicationSettings = new ArrayList<>();
        final List<String[]> notConverted = new ArrayList<>();
        final List<String[]> dictionaries = new ArrayList<>();
        final Map<String, Map<String, List<String>>> components = new LinkedHashMap<>();

        void noEquivalent(Session session, String key, String closest) {
            noEquivalent.add(new String[]{session.name, key, session.values.get(key), closest});
        }

        void code(Session session, String key, String value, String how) {
            code.add(new String[]{session.name, key, value, how});
        }

        void behaviour(Session session, String change) {
            behaviour.add(new String[]{session.name, change});
        }

        void question(Session session, String question) {
            questions.add(new String[]{session.name, question});
        }

        void applicationSetting(Session session, String key) {
            applicationSettings.add(new String[]{session.name, key, session.values.get(key)});
        }

        void notConverted(Session session, String version) {
            notConverted.add(new String[]{session.where(), version});
        }

        void dictionary(Session session, String key, String file) {
            dictionaries.add(new String[]{session.name, key, file});
        }

        void component(String key, String value, String target, Session session) {
            components.computeIfAbsent(key + "\u0000" + target, k -> new LinkedHashMap<>())
                    .computeIfAbsent(value, v -> new ArrayList<>()).add(session.name);
        }

        String markdown(Options options, List<Session> sessions, Iterable<Acceptor> acceptors) {
            StringBuilder md = new StringBuilder("# QuickFIX/J settings conversion\n\n");
            md.append("Converted by `CfgToStaffix.java` to ").append(options.format == Format.YAML
                    ? "YAML session files" : "Spring Boot properties").append(", with ")
                    .append(options.validation == Validation.QFJ ? "QuickFIX/J's validation checks kept"
                            : "staffix's validation defaults").append(". Every value QuickFIX/J defaulted that staffix"
                            + " defaults otherwise is written, so each session behaves as it did.\n");
            table(md, "Sessions", new String[]{"session", "type", "from", "store"}, rows(sessions));
            table(md, "Sessions not converted: staffix starts at FIX 4.2", new String[]{"from", "version"},
                    notConverted);
            List<String[]> initiators = new ArrayList<>();
            for (Session session : sessions) {
                if (session.initiatorSettings != null) {
                    initiators.add(new String[]{session.name, String.join(", ", session.initiatorSettings.addresses),
                            session.initiatorSettings.connectionRetry,
                            session.initiatorSettings.tls == null ? "no" : "yes"});
                }
            }
            table(md, "Initiators, one per session", new String[]{"initiator and its session", "connect addresses",
                    "connection retry", "TLS"}, initiators);
            List<String[]> acceptorRows = new ArrayList<>();
            for (Acceptor acceptor : acceptors) {
                List<String> names = new ArrayList<>();
                for (Session session : acceptor.sessions) {
                    names.add(session.name);
                }
                acceptorRows.add(new String[]{acceptor.name, acceptor.bindAddress, acceptor.store,
                        String.join(", ", names), acceptor.tls == null ? "no" : "yes"});
            }
            table(md, "Acceptors", new String[]{"acceptor", "bind address", "sessions settings store", "sessions",
                    "TLS"}, acceptorRows);
            table(md, "Dictionaries", new String[]{"session", "key", "file"}, dictionaries);
            List<String[]> componentRows = new ArrayList<>();
            for (Map.Entry<String, Map<String, List<String>>> entry : components.entrySet()) {
                String[] keyAndTarget = entry.getKey().split("\u0000");
                for (Map.Entry<String, List<String>> value : entry.getValue().entrySet()) {
                    componentRows.add(new String[]{keyAndTarget[0], value.getKey(), keyAndTarget[1],
                            String.join(", ", value.getValue())});
                }
            }
            table(md, "Components and transport, to wire in code or properties", new String[]{"key", "value",
                    "staffix", "sessions"}, componentRows);
            table(md, "Needs code", new String[]{"session", "key", "value", "how"}, code);
            table(md, "Behaviour changes", new String[]{"session", "change"}, behaviour);
            table(md, "No equivalent", new String[]{"session", "key", "value", "closest"}, noEquivalent);
            table(md, "Application settings, written to fixApplicationSessionSettings", new String[]{"session", "key",
                    "value"}, applicationSettings);
            table(md, "Questions", new String[]{"session", "question"}, questions);
            return md.toString();
        }

        private static List<String[]> rows(List<Session> sessions) {
            List<String[]> rows = new ArrayList<>();
            for (Session session : sessions) {
                rows.add(new String[]{session.name, session.initiator ? "initiator" : "acceptor", session.where(),
                        session.store});
            }
            return rows;
        }

        private static void table(StringBuilder md, String title, String[] header, List<String[]> rows) {
            md.append("\n## ").append(title).append("\n\n");
            if (rows.isEmpty()) {
                md.append("None.\n");
                return;
            }
            md.append(row(header));
            md.append(row(Collections.nCopies(header.length, ":-").toArray(new String[0])));
            for (String[] each : rows) {
                md.append(row(each));
            }
        }

        private static String row(String[] cells) {
            StringBuilder row = new StringBuilder("|");
            for (String cell : cells) {
                row.append(' ').append(cell == null ? "" : cell.replace("|", "\\|")).append(" |");
            }
            return row.append('\n').toString();
        }
    }
}
