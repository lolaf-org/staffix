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
package org.lolaf.staffix.generator;

import org.lolaf.staffix.api.serde.SerDe;
import java.nio.charset.StandardCharsets;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Value;
import org.apache.maven.plugin.logging.Log;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.RuntimeConstants;
import org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader;
import org.lolaf.staffix.api.codec.FixMessageEncoderFactory;
import org.lolaf.staffix.api.fields.FieldLocation;
import org.lolaf.staffix.api.fields.FieldType;
import org.lolaf.staffix.api.fields.FieldsRegistry;
import org.lolaf.staffix.api.fields.FixtFieldsRegistry;
import org.lolaf.staffix.api.msg.*;
import org.lolaf.staffix.api.version.FixRegularVersion;
import org.lolaf.staffix.api.version.FixVersion;
import org.lolaf.staffix.api.version.FixtVersion;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Generates the encoders, field classes, message types and SPI registries for one dictionary.
 */
public class CodeGenerator {

    public static void process(String packageName, File dictionary, File sourcesOutputDirectory, File resourcesOutputDirectory,
                               String dictionaryId, Log log, boolean addFIXEngineAndAppInfoFields) throws IOException, SAXException, ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document document = builder.parse(dictionary);
        Element fix = document.getDocumentElement();

        VelocityEngine ve = new VelocityEngine();
        ve.setProperty(RuntimeConstants.RESOURCE_LOADERS, "classpath");
        ve.setProperty("resource.loader.classpath.class", ClasspathResourceLoader.class.getName());

        String messagesTypePackage = packageName + ".msg";
        String fieldsPackage = packageName + ".fields";

        String dictType = fix.getAttribute("type");
        boolean fixtDict = "FIXT".equalsIgnoreCase(dictType);

        String major = fix.getAttribute("major");
        String servicePack = fix.getAttribute("servicepack");
        if (servicePack.isEmpty()) {
            servicePack = null;
        }
        if (major.equalsIgnoreCase("latest")) {
            major = "5";
            servicePack = "latest";
        }

        FixVersion fixVersion = fixtDict
                ? FixtVersion.fromVersion(Integer.parseInt(major), Integer.parseInt(fix.getAttribute("minor")))
                : FixRegularVersion.fromVersion(Integer.parseInt(major), Integer.parseInt(fix.getAttribute("minor")), servicePack);

        dictionaryId = dictionaryId + "-" + fixVersion.toString();

        log.info("Generating code for fix version " + fixVersion + " and dictionary id " + dictionaryId);
        Set<Field> usedField = new HashSet<>();
        if (addFIXEngineAndAppInfoFields) {
            addStringField(usedField, 1600, "FIXEngineName");
            addStringField(usedField, 1601, "FIXEngineVersion");
            addStringField(usedField, 1602, "FIXEngineVendor");
            addStringField(usedField, 1603, "ApplicationSystemName");
            addStringField(usedField, 1604, "ApplicationSystemVersion");
            addStringField(usedField, 1605, "ApplicationSystemVendor");
        }
        List<Field> generatedFields = generateFields(fix, log);
        generateMessagesTypes(fixtDict, fix, sourcesOutputDirectory, resourcesOutputDirectory,
                log, messagesTypePackage, ve, fixVersion, dictionaryId);
        generateMessagesFieldsOrderRegistry(fixtDict, sourcesOutputDirectory, resourcesOutputDirectory,
                log, messagesTypePackage, ve, fixVersion, dictionaryId);
        List<EncoderInfo> encoderInfos = generateMessagesEncoders(fix, fixVersion, dictionaryId, sourcesOutputDirectory, resourcesOutputDirectory,
                log, packageName + ".encoders", ve, messagesTypePackage, fieldsPackage, generatedFields, usedField);
        if (!fixtDict) {
            generateEncoderRegistryAndFactory(sourcesOutputDirectory, resourcesOutputDirectory, log,
                    packageName + ".encoders", ve, dictionaryId, (FixRegularVersion) fixVersion, encoderInfos);
        }
        generateUsedFields(fixtDict, generatedFields, sourcesOutputDirectory, resourcesOutputDirectory,
                log, fieldsPackage, ve, fixVersion, dictionaryId, usedField);
    }

    private static void addStringField(Set<Field> usedField, int code, String name) {
        usedField.add(new Field(code, name, MappedFieldType.STRING, null, null, false, getFieldChecksum(code), FieldLocation.HEADER, false,
                Collections.emptyList(), false));
    }

    private static boolean isDeprecated(Element element) {
        return Boolean.parseBoolean(element.getAttribute("deprecated"));
    }

    private static List<EncoderInfo> generateMessagesEncoders(Element fix, FixVersion fixVersion, String dictionaryId, File sourcesOutputDirectory, File resourcesOutputDirectory,
                                                              Log log, String packageName, VelocityEngine ve, String messagesTypePackage,
                                                              String fieldsPackage, List<Field> generatedFields, Set<Field> usedFields) {
        log.info("Processing encoders");
        List<EncoderInfo> encoderInfos = new ArrayList<>();
        File packageDir = generatePackagesDir(sourcesOutputDirectory, packageName);
        NodeList messages = ((Element) fix.getElementsByTagName("messages").item(0)).getElementsByTagName("message");
        // Two passes. A repeating group belongs to a component far more often than to one message - Parties and the
        // swap components are reached by dozens of messages apiece - and its encoder is built from the group's field
        // list alone. So every message is read first, every group it holds is offered to the registry below, and only
        // once the whole dictionary is in are the group encoders named and written: one class per distinct group,
        // shared by every message that carries it.
        List<MessageModel> messageModels = new ArrayList<>();
        GroupEncoders groupEncoders = new GroupEncoders();
        for (int i = 0; i < messages.getLength(); i++) {
            Element message = (Element) messages.item(i);
            // current data structure does not allow to put create group methods in correct group inner classes
            List<Field> fieldsList = new ArrayList<>();
            List<Group> groupsList = new ArrayList<>();

            // a deprecated message deprecates its encoder class, not each setter of it, so it is not handed down here
            processMessageOrComponent(fix, generatedFields, message, fieldsList, groupsList, usedFields, false);

            List<FieldValidationInfo> fieldValidationInfo = fieldsList.stream()
                    .map(f -> new FieldValidationInfo(f.getNumber(), -1, f.isRequired(), f.getType().equals(MappedFieldType.NUMINGROUP))).collect(Collectors.toList());

            groupsList.forEach(group -> {
                Field f = group.getField();
                group.getGroupFields().forEach(gf ->
                        fieldValidationInfo.add(new FieldValidationInfo(gf.getNumber(), f.getNumber(), gf.isRequired(), true)));
            });
            try (FileOutputStream fos = new FileOutputStream(new File(resourcesOutputDirectory, fixVersion.getId() + "." + dictionaryId + "." + message.getAttribute("name") + ".fieldsInfo"))) {
                fos.write(fieldValidationInfo.stream().map(f -> f.getFieldCode() + "," + f.getParentFieldCode() + "," + bts(f.isGroupField()) + "," + bts(f.isRequired()))
                        .collect(Collectors.joining("\n")).getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }

            if (message.getAttribute("msgcat").equals("admin")) {
                // do not generate encoders for admin messages
                continue;
            }

            String className = message.getAttribute("name") + "Encoder";
            String messageName = message.getAttribute("name");
            encoderInfos.add(new EncoderInfo(className, messageName, isDeprecated(message)));
            groupsList.forEach(g -> groupEncoders.collect(g, message.getAttribute("name")));
            messageModels.add(new MessageModel(className, messageName, isDeprecated(message), fieldsList, groupsList));
        }

        groupEncoders.assignNames();
        log.info("Processing encoders: " + messageModels.size() + " messages, " + groupEncoders.totalReferences()
                + " group references sharing " + groupEncoders.distinct().size() + " group encoders");

        for (MessageModel model : messageModels) {
            VelocityContext context = new VelocityContext();
            context.put("deprecated", model.isDeprecated());
            // only where there is something to suppress, so that a dictionary carrying no deprecation at all still
            // generates exactly the code it generated before any of this existed
            context.put("referencesDeprecated", referencesDeprecated(model.getFields(), model.getGroups()));
            context.put("packageName", packageName);
            context.put("groupPackageName", packageName + ".group");
            context.put("messagesTypePackage", messagesTypePackage);
            context.put("fieldsPackage", fieldsPackage);
            context.put("messageName", model.getMessageName());
            context.put("className", model.getClassName());
            context.put("fields", model.getFields().stream().filter(f -> !f.getType().equals(MappedFieldType.NUMINGROUP)).collect(Collectors.toList()));
            context.put("groups", model.getGroups().stream()
                    .map(g -> new GroupRef(g.getField(), groupEncoders.nameOf(g)))
                    .collect(Collectors.toList()));
            processTemplate(packageDir, model.getClassName(), getTemplate(ve, "fixMessageEncoder.vm"), context);
        }

        String groupPackageName = packageName + ".group";
        File groupPackageDir = generatePackagesDir(sourcesOutputDirectory, groupPackageName);
        for (Map.Entry<String, Group> entry : groupEncoders.distinct().entrySet()) {
            Group group = entry.getValue();
            VelocityContext context = new VelocityContext();
            context.put("packageName", groupPackageName);
            context.put("fieldsPackage", fieldsPackage);
            context.put("className", entry.getKey());
            context.put("deprecated", group.getField().isDeprecated());
            context.put("referencesDeprecated", group.getField().isDeprecated()
                    || group.getGroupFields().stream().anyMatch(Field::isDeprecated));
            context.put("groupFieldName", group.getField().getName());
            context.put("groupFields", group.getGroupFields());
            processTemplate(groupPackageDir, entry.getKey(), getTemplate(ve, "fixGroupEncoder.vm"), context);
        }
        return encoderInfos;
    }

    private static boolean referencesDeprecated(List<Field> fieldsList, List<Group> groupsList) {
        return fieldsList.stream().anyMatch(Field::isDeprecated)
                || groupsList.stream().anyMatch(g -> g.getField().isDeprecated()
                || g.getGroupFields().stream().anyMatch(Field::isDeprecated));
    }

    private static void generateEncoderRegistryAndFactory(File sourcesOutputDirectory, File resourcesOutputDirectory,
                                                          Log log, String packageName, VelocityEngine ve,
                                                          String dictionaryId, FixRegularVersion fixVersion, List<EncoderInfo> encoderInfos) {
        log.info("Processing encoder registry and factory");
        File packageDir = generatePackagesDir(sourcesOutputDirectory, packageName);

        // Generate FixMessageEncoderRegistry
        VelocityContext registryContext = new VelocityContext();
        registryContext.put("packageName", packageName);
        registryContext.put("encoders", encoderInfos);
        registryContext.put("referencesDeprecated",
                encoderInfos.stream().anyMatch(EncoderInfo::isDeprecated));
        processTemplate(packageDir, "FixMessageEncoderRegistry", getTemplate(ve, "fixMessageEncoderRegistry.vm"), registryContext);

        // Generate FixMessageEncoderFactoryImpl
        VelocityContext factoryContext = new VelocityContext();
        factoryContext.put("packageName", packageName);
        factoryContext.put("dictionaryId", dictionaryId);
        factoryContext.put("fixVersionId", fixVersion.getId());
        processTemplate(packageDir, "FixMessageEncoderFactoryImpl", getTemplate(ve, "fixMessageEncoderFactory.vm"), factoryContext);

        // Register SPI
        writeSPIFile(resourcesOutputDirectory, FixMessageEncoderFactory.class, packageName + ".FixMessageEncoderFactoryImpl");
    }

    private static String bts(boolean b) {
        return b ? "y" : "n";
    }

    private static void processMessageOrComponent(Element fixRootElement, List<Field> generatedFields, Element messageOrComponent, List<Field> fieldsList,
                                                  List<Group> groupsList, Set<Field> usedFields, boolean inherited) {
        processMessageOrComponent(fixRootElement, generatedFields, messageOrComponent, fieldsList, groupsList, usedFields, inherited, null);
    }

    // enclosingComponent is the name of the nearest <component> the recursion came through, or null at message level.
    // It is carried only to name a group encoder when two different groups share a counter field name - see
    // GroupEncoders.
    private static void processMessageOrComponent(Element fixRootElement, List<Field> generatedFields, Element messageOrComponent, List<Field> fieldsList,
                                                  List<Group> groupsList, Set<Field> usedFields, boolean inherited, String enclosingComponent) {
        NodeList children = messageOrComponent.getChildNodes();
        for (int j = 0; j < children.getLength(); j++) {
            Node child = children.item(j);
            if (child instanceof Element) {
                Element el = (Element) child;
                if (el.getTagName().equals("field")) {
                    fieldsList.add(findField(generatedFields, el, usedFields, inherited));
                } else if (el.getTagName().equals("group")) {
                    processMessageGroup(true, generatedFields, el, fixRootElement, fieldsList, groupsList, usedFields, inherited, enclosingComponent);
                } else if (el.getTagName().equals("component")) {
                    Element innerComponent = findComponent(el.getAttribute("name"), fixRootElement);
                    processMessageOrComponent(fixRootElement, generatedFields, innerComponent, fieldsList, groupsList, usedFields,
                            isDeprecated(el) || isDeprecated(innerComponent) || inherited, el.getAttribute("name"));
                } else {
                    throw new IllegalStateException("Element " + el.getTagName() + " is not managed");
                }
            }
        }
    }

    private static void processMessageGroup(boolean addToMainFieldsList, List<Field> generatedFields, Element groupElement, Element fixRootElement,
                                            List<Field> fieldsList, List<Group> groupsList, Set<Field> usedFields, boolean inherited,
                                            String enclosingComponent) {
        Field groupField = findField(generatedFields, groupElement, usedFields, inherited);
        if (addToMainFieldsList) {
            fieldsList.add(groupField);
        }
        if (!groupField.getType().equals(MappedFieldType.NUMINGROUP)) {
            throw new IllegalStateException("Group field " + groupField.getNumber() + ":" + groupField.getName()
                    + " has wrong type " + groupField.getType().name() + " should be " + MappedFieldType.NUMINGROUP.name() + ", this may happen in some fix dictionaries with wrong data, update it");
        }
        Group group = new Group(groupField, new ArrayList<>(), enclosingComponent);
        // a deprecated group becomes a deprecated group encoder class, which already covers every setter inside it,
        // so the members are only told about a deprecation the group itself does not carry
        boolean forMembers = !groupField.isDeprecated() && inherited;
        processMessageGroupChildren(generatedFields, fixRootElement, fieldsList, groupsList, groupElement.getChildNodes(), group, usedFields, forMembers,
                enclosingComponent);
        groupsList.add(group);
    }

    private static void processMessageGroupChildren(List<Field> generatedFields, Element fixRootElement, List<Field> fieldsList, List<Group> groupsList,
                                                    NodeList children, Group group, Set<Field> usedFields, boolean inherited,
                                                    String enclosingComponent) {
        for (int j = 0; j < children.getLength(); j++) {
            Node child = children.item(j);
            if (child instanceof Element) {
                Element el = (Element) child;
                if (el.getTagName().equals("field")) {
                    group.groupFields.add(findField(generatedFields, el, usedFields, inherited));
                } else if (el.getTagName().equals("group")) {
                    group.groupFields.add(findField(generatedFields, el, usedFields, inherited));
                    processMessageGroup(false, generatedFields, el, fixRootElement, fieldsList, groupsList, usedFields, inherited, enclosingComponent);
                } else if (el.getTagName().equals("component")) {
                    Element innerComponent = findComponent(el.getAttribute("name"), fixRootElement);
                    processMessageGroupChildren(generatedFields, fixRootElement, fieldsList, groupsList, innerComponent.getChildNodes(), group, usedFields,
                            isDeprecated(el) || isDeprecated(innerComponent) || inherited, el.getAttribute("name"));
                } else {
                    throw new IllegalStateException("Element " + el.getTagName() + " is not managed");
                }
            }
        }
    }

    private static Field findField(List<Field> generatedFields, Element el, Set<Field> usedFields, boolean inherited) {
        String fieldName = el.getAttribute("name");
        String required = el.getAttribute("required");
        Field match = generatedFields.stream().filter(f -> f.name.equals(fieldName)).findFirst()
                .orElseThrow(() -> new IllegalStateException("Unable to find field " + fieldName + " in declared fields list"));
        usedFields.add(match);
        return new Field(match.getNumber(), match.getName(), match.getType(), match.getEnumType(),
                match.getValuesEnumClass(), match.isValuesEnumClassSet(), match.getChecksum(), match.getLocation(), required.equalsIgnoreCase("y"),
                match.getFieldValues(), isDeprecated(el) || match.isDeprecated() || inherited);
    }

    private static void generateMessagesTypes(boolean fixtDict, Element fix, File sourcesOutputDirectory, File resourcesOutputDirectory,
                                              Log log, String packageName, VelocityEngine ve, FixVersion fixVersion, String dictionaryId) {
        log.info("Processing messages types");
        File packageDir = generatePackagesDir(sourcesOutputDirectory, packageName);
        List<EnumValue> messagesType = new ArrayList<>();
        NodeList messages = ((Element) fix.getElementsByTagName("messages").item(0)).getElementsByTagName("message");
        for (int i = 0; i < messages.getLength(); i++) {
            Element message = (Element) messages.item(i);
            boolean admin = message.getAttribute("msgcat").equals("admin");
            // Resolve the storability rule at generation time so the hot-path isStorable() is a constant per type:
            // all business messages are storable; among admin messages only Reject(3) is storable.
            boolean storable = !admin
                    || message.getAttribute("msgtype").equals(CoreMessageType.REJECT)
                    || message.getAttribute("msgtype").equals(CoreMessageType.XML_NON_FIX);
            String enumValue = message.getAttribute("name")
                    + "(\"" + message.getAttribute("msgtype") + "\")"
                    + " { @Override public boolean isAdmin() { return " + admin + "; }"
                    + " @Override public boolean isStorable() { return " + storable + "; } }";
            messagesType.add(new EnumValue(addEnumEndChar(i, messages, enumValue), isDeprecated(message)));
        }
        VelocityContext context = new VelocityContext();
        context.put("packageName", packageName);
        context.put("messagesType", messagesType);
        context.put("fixVersionId", fixVersion.getId());
        context.put("dictionaryId", dictionaryId);
        processTemplate(packageDir, "MessageTypes", getTemplate(ve, "messageTypes.vm"), context);
        processTemplate(packageDir, "MessageTypeRegistryImpl", getTemplate(ve, fixtDict ? "fixtMessageTypesRegistry.vm" : "messageTypesRegistry.vm"), context);

        writeSPIFile(resourcesOutputDirectory, fixtDict ? FixtMessageTypeRegistry.class : MessageTypeRegistry.class, packageName + ".MessageTypeRegistryImpl");
    }

    private static void generateMessagesFieldsOrderRegistry(boolean fixtDict, File sourcesOutputDirectory, File resourcesOutputDirectory,
                                                            Log log, String packageName, VelocityEngine ve, FixVersion fixVersion, String dictionaryId) {
        log.info("Processing messages fields order registry");
        File packageDir = generatePackagesDir(sourcesOutputDirectory, packageName);
        VelocityContext context = new VelocityContext();
        context.put("packageName", packageName);
        context.put("fixVersionId", fixVersion.getId());
        context.put("dictionaryId", dictionaryId);
        processTemplate(packageDir, "AbstractMessageFieldsRegistry", getTemplate(ve, "abstractMessageFieldsRegistry.vm"), context);
        processTemplate(packageDir, "MessageFieldsRegistryImpl", getTemplate(ve, fixtDict ? "fixtMessageFieldsRegistry.vm" : "messageFieldsRegistry.vm"), context);

        writeSPIFile(resourcesOutputDirectory, fixtDict ? FixtMessageFieldsRegistry.class : MessageFieldsRegistry.class, packageName + ".MessageFieldsRegistryImpl");
    }

    private static void generateUsedFields(boolean fixtDict, List<Field> generatedFields, File sourcesOutputDirectory, File resourcesOutputDirectory,
                                           Log log, String packageName, VelocityEngine ve, FixVersion fixVersion, String dictionaryId, Set<Field> usedFields) {
        log.info("Processing used fields");
        File packageDir = generatePackagesDir(sourcesOutputDirectory, packageName);
        // adding header and trailer fields
        generatedFields.stream().filter(field -> !field.location.equals(FieldLocation.BODY)).forEach(usedFields::add);

        Set<Field> sortedFields = new TreeSet<>(Comparator.comparingInt(Field::getNumber));
        sortedFields.addAll(usedFields);

        int index = 0;
        for (Field f : sortedFields) {
            VelocityContext context = new VelocityContext();
            context.put("packageName", packageName);
            context.put("field", f);
            context.put("fieldIndex", index++);
            context.put("fieldValues", f.getFieldValues());
            processTemplate(packageDir, f.getName(), getTemplate(ve, "field.vm"), context);
        }
        VelocityContext context = new VelocityContext();
        context.put("packageName", packageName);
        context.put("fixVersionId", fixVersion.getId());
        context.put("dictionaryId", dictionaryId);

        processTemplate(packageDir, "FieldsRegistryImpl", getTemplate(ve, fixtDict ? "fixtFieldsRegistry.vm" : "fieldsRegistry.vm"), context);
        writeSPIFile(resourcesOutputDirectory, fixtDict ? FixtFieldsRegistry.class : FieldsRegistry.class, packageName + ".FieldsRegistryImpl");

        String fileName = fixtDict ? fixVersion.getId() + ".fields" : fixVersion.getId() + "." + dictionaryId + ".fields";
        try (FileOutputStream fos = new FileOutputStream(new File(resourcesOutputDirectory, fileName))) {
            fos.write(sortedFields.stream().map(f -> f.getNumber() + "=" + packageName + "." + f.getName()).collect(Collectors.joining("\n")).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<Field> generateFields(Element fix, Log log) {
        log.info("Processing fields main list");
        Set<String> headerFields = getFieldsLocation(fix, "header");
        Set<String> trailerFields = getFieldsLocation(fix, "trailer");
        List<Field> generatedFields = new ArrayList<>();
        Element n = (Element) fix.getElementsByTagName("fields").item(0);
        NodeList fields = n.getElementsByTagName("field");
        for (int i = 0; i < fields.getLength(); i++) {
            Element field = (Element) fields.item(i);
            NodeList values = field.getElementsByTagName("value");
            int fieldNumber = Integer.parseInt(field.getAttribute("number"));
            String fieldName = field.getAttribute("name");
            FieldType fieldType = FieldType.valueOf(field.getAttribute("type"));
            if (fieldNumber == 383 && fieldName.equals("MaxMessageSize")) {
                // Field MaxMessageSize(383) is of type LENGTH in the FIX dictionaries, but this is actually not a real LENGTH field
                // and can corrupt parsing of messages as no DATA field will ever follow this field, setting it to INT
                fieldType = FieldType.INT;
            }

            MappedFieldType type = MappedFieldType.of(fieldType);
            String enumType = getEnumType(type, values, field);
            List<EnumValue> fieldValues = new ArrayList<>();
            String valuesEnumClassName = null;
            for (int j = 0; j < values.getLength(); j++) {
                Element value = (Element) values.item(j);
                String enumValue = value.getAttribute("enum");
                String enumName = value.getAttribute("description");
                if (enumType.equals("IntValuesEnum")) {
                    enumName += "(" + enumValue + ")";
                } else if (enumType.equals("StringValuesEnum")) {
                    enumName += "(\"" + enumValue + "\")";
                } else if (enumType.equals("CharValuesEnum")) {
                    enumName += "('" + enumValue + "')";
                }
                fieldValues.add(new EnumValue(addEnumEndChar(j, values, enumName), isDeprecated(value)));
            }
            if (!fieldValues.isEmpty()) {
                valuesEnumClassName = field.getAttribute("name") + "Values";
            }

            FieldLocation location = FieldLocation.BODY;
            if (headerFields.contains(fieldName)) {
                location = FieldLocation.HEADER;
            }
            if (trailerFields.contains(fieldName)) {
                location = FieldLocation.TRAILER;
            }
            Field f = new Field(fieldNumber, fieldName,
                    type, enumType, valuesEnumClassName, valuesEnumClassName != null, getFieldChecksum(fieldNumber),
                    location, false, fieldValues, isDeprecated(field));
            generatedFields.add(f);
        }

        return generatedFields;
    }

    private static int getFieldChecksum(int fieldNumber) {
        int checksum = 0;
        // SerDe.CHARSET, not the UTF-8 the generated sources are written in: this has to equal the
        // checksum FixFieldImpl computes from the same "tag=" at runtime.
        byte[] numberSerialized = (fieldNumber + "=").getBytes(SerDe.CHARSET);
        for (byte b : numberSerialized) {
            checksum += b;
        }
        return checksum;
    }

    private static String getEnumType(MappedFieldType type, NodeList values, Element field) {
        String enumType = null;
        if (type.equals(MappedFieldType.INT) || type.equals(MappedFieldType.NUMINGROUP)) {
            enumType = "IntValuesEnum";
        } else if (type.equals(MappedFieldType.STRING) || type.equals(MappedFieldType.MULTIPLEVALUESTRING) || type.equals(MappedFieldType.MULTIPLESTRINGVALUE)) {
            enumType = "StringValuesEnum";
        } else if (type.equals(MappedFieldType.CHAR) || type.equals(MappedFieldType.BOOLEAN) || type.equals(MappedFieldType.MULTIPLECHARVALUE)) {
            enumType = "CharValuesEnum";
        } else if (values.getLength() > 0) {
            throw new IllegalStateException("Type " + type + " not handled for field " + field.getAttribute("name"));
        }
        return enumType;
    }

    private static void writeSPIFile(File outputDir, Class<?> spiClass, String spiImpl) {
        File metaInf = new File(outputDir, "META-INF");
        if (!metaInf.exists() && !metaInf.mkdirs()) {
            throw new IllegalStateException(metaInf + " could not be created");
        }
        File servicesDir = new File(metaInf, "services");
        if (!servicesDir.exists() && !servicesDir.mkdirs()) {
            throw new IllegalStateException(servicesDir + " could not be created");
        }
        File spi = new File(servicesDir, spiClass.getName());
        try {
            FileOutputStream fos = new FileOutputStream(spi);
            fos.write(spiImpl.getBytes(StandardCharsets.UTF_8));
            fos.flush();
            fos.close();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String addEnumEndChar(int i, NodeList values, String enumValue) {
        if (i + 1 < values.getLength()) {
            return enumValue + ",";
        }
        return enumValue + ";";
    }

    private static Template getTemplate(VelocityEngine ve, String name) {
        Template template;
        try {
            template = ve.getTemplate(name);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
        return template;
    }

    private static File generatePackagesDir(File outputDir, String packageName) {
        AtomicReference<File> packageDir = new AtomicReference<>(outputDir);
        Arrays.stream(packageName.split("\\.")).forEach(p -> {
            File packageFilePart = new File(packageDir.get(), p);
            if (!packageFilePart.exists() && !packageFilePart.mkdirs()) {
                throw new IllegalStateException(packageFilePart + " could not be created");
            }
            packageDir.set(packageFilePart);
        });
        return packageDir.get();
    }

    private static void processTemplate(File packageDir, String className, Template template, VelocityContext context) {
        try {
            FileWriter fw = new FileWriter(new File(packageDir, className + ".java"));
            template.merge(context, fw);
            fw.flush();
            fw.close();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static Element findComponent(String componentName, Element rootElement) {
        Element componentsRootElement = ((Element) rootElement.getElementsByTagName("components").item(0));
        NodeList components = componentsRootElement.getElementsByTagName("component");
        for (int i = 0; i < components.getLength(); i++) {
            Element comp = (Element) components.item(i);
            // getElementsByTagName will return all elements matching the tag name component,
            // we want only those direct childrens of componentsRootElement
            if (comp.getParentNode().equals(componentsRootElement)
                    && comp.getAttribute("name").equals(componentName)) {
                return comp;
            }
        }
        throw new IllegalStateException("Unable to find component " + componentName);
    }

    private static Set<String> getFieldsLocation(Element fix, String elementName) {
        Set<String> fieldNames = new HashSet<>();
        NodeList fields = (fix.getElementsByTagName(elementName).item(0)).getChildNodes();
        for (int i = 0; i < fields.getLength(); i++) {
            if (fields.item(i) instanceof Element) {
                Element fieldsOrGroup = (Element) fields.item(i);
                fieldNames.add(fieldsOrGroup.getAttribute("name"));
                if (fieldsOrGroup.getTagName().equals("group")) {
                    NodeList groupFields = fieldsOrGroup.getChildNodes();
                    for (int j = 0; j < groupFields.getLength(); j++) {
                        if (groupFields.item(j) instanceof Element) {
                            Element groupField = (Element) groupFields.item(j);
                            fieldNames.add(groupField.getAttribute("name"));
                        }
                    }
                }
            }
        }
        return fieldNames;
    }

    /**
     * One message, read but not yet written. Exists because group encoders cannot be named until every message has
     * been read - see {@link GroupEncoders}.
     */
    @Value
    private static class MessageModel {
        String className;
        String messageName;
        boolean deprecated;
        List<Field> fields;
        List<Group> groups;
    }

    /**
     * A group as one message refers to it: the counter field, so the accessor can write its NumInGroup, and the name
     * of the shared encoder class that group resolved to.
     */
    @Value
    public static class GroupRef {
        Field field;
        String encoderClassName;
    }

    /**
     * Every distinct repeating group in a dictionary, and the class name each one is generated under.
     * <p>
     * A group's encoder is built from its field list and nothing else, so groups with the same
     * {@link Group#signature()} share a class. That is the whole point of this class: FIX Latest referred to a
     * repeating group 20,985 times across its messages for 515 distinct field lists, and generating a class per
     * reference - which is what nesting each group inside its message encoder amounted to - made 80% of the
     * package's jar duplication.
     * <p>
     * <b>Naming.</b> The counter field plus {@code Encoder}, {@code NoPartyIDsEncoder}, which is unique for 463 of
     * the 478 counter fields in FIX Latest. The other 15 stand for more than one field list - {@code NoLegs} for nine
     * of them - and there <em>every</em> variant is qualified, the first one met included: leaving one unqualified
     * would make {@code NoLegsEncoder} mean whichever group the dictionary happened to list first, and move it
     * silently the next time the dictionary is regenerated. The qualifier is the component that defines the group
     * where there is one, and otherwise the first message to declare it; an ordinal is the last resort and has never
     * been reached, not even across the 173 messages of the full FIX Latest standard.
     * <p>
     * <b>The component branch almost never fires</b>, and that is not a defect: a group defined once inside a shared
     * component has one field list, which is exactly the case that is never ambiguous. Ambiguity comes from groups a
     * message declares inline, where the message is the only name available.
     * <p>
     * <b>These names are stable for a given dictionary, not across dictionary edits.</b> Widening
     * {@code fix-latest-messages.txt} can introduce a collision that qualifies a name which was bare, or change which
     * message declares a shape first. Both rename a public class. That is a reason to keep the message list
     * deliberate rather than a reason to name groups any other way.
     */
    private static class GroupEncoders {

        private final Map<String, Group> bySignature = new LinkedHashMap<>();
        private final Map<String, String> firstMessageBySignature = new LinkedHashMap<>();
        private final Map<String, String> nameBySignature = new LinkedHashMap<>();
        private final Map<String, Group> distinct = new LinkedHashMap<>();
        private int references;

        void collect(Group group, String messageName) {
            references++;
            bySignature.putIfAbsent(group.signature(), group);
            firstMessageBySignature.putIfAbsent(group.signature(), messageName);
        }

        int totalReferences() {
            return references;
        }

        void assignNames() {
            Map<String, Integer> signaturesPerCounterField = new HashMap<>();
            bySignature.values().forEach(g ->
                    signaturesPerCounterField.merge(g.getField().getName(), 1, Integer::sum));
            Set<String> taken = new HashSet<>();
            for (Map.Entry<String, Group> entry : bySignature.entrySet()) {
                Group group = entry.getValue();
                String counterField = group.getField().getName();
                String name;
                if (signaturesPerCounterField.get(counterField) == 1) {
                    name = counterField + "Encoder";
                } else {
                    // Every variant is qualified, including the first one met: leaving one of them unqualified would
                    // make NoLegsEncoder mean whichever group the dictionary happened to list first, and move it
                    // silently when the dictionary is regenerated.
                    String qualifier = group.getDefiningComponent() != null
                            ? group.getDefiningComponent()
                            : firstMessageBySignature.get(entry.getKey());
                    name = qualifier + counterField + "Encoder";
                    for (int ordinal = 2; taken.contains(name); ordinal++) {
                        name = qualifier + counterField + ordinal + "Encoder";
                    }
                }
                if (!taken.add(name)) {
                    throw new IllegalStateException("Duplicate group encoder class name " + name);
                }
                nameBySignature.put(entry.getKey(), name);
                distinct.put(name, group);
            }
        }

        String nameOf(Group group) {
            String name = nameBySignature.get(group.signature());
            if (name == null) {
                throw new IllegalStateException("Group " + group.getField().getName() + " was never collected");
            }
            return name;
        }

        Map<String, Group> distinct() {
            return distinct;
        }
    }

    @Getter
    @AllArgsConstructor
    private static class FieldValidationInfo {
        int fieldCode;
        int parentFieldCode;
        boolean required;
        boolean groupField;
    }

    @Value
    public static class Field {
        int number;
        String name;
        MappedFieldType type;
        String enumType;
        String valuesEnumClass;
        boolean valuesEnumClassSet;
        int checksum;
        FieldLocation location;
        boolean required;
        List<EnumValue> fieldValues;
        boolean deprecated;
    }

    @Value
    public static class EnumValue {
        String text;
        boolean deprecated;
    }

    @Value
    public static class Group {
        Field field;
        List<Field> groupFields;
        /**
         * The nearest {@code <component>} this group was reached through, or null when the message declares it
         * inline. Not part of the group's identity - two messages reaching the same component get the same group -
         * it only names the encoder when {@link GroupEncoders} has a counter-field-name collision to break.
         */
        String definingComponent;

        /**
         * Everything the generated encoder is built from, and nothing else. Two groups with the same signature
         * produce byte-identical classes, so they get one class between them.
         * <p>
         * Deliberately narrower than {@code equals}: {@code required} and a field's location take part in
         * validation and in the fieldsInfo resource, not in an encoder's setters, so two groups differing only
         * there must still share.
         */
        public String signature() {
            StringBuilder sb = new StringBuilder(field.getName()).append(field.isDeprecated() ? "!" : "");
            for (Field f : groupFields) {
                sb.append('|').append(f.getName()).append(':').append(f.getType().name())
                        .append(':').append(f.getValuesEnumClass())
                        .append(':').append(f.getEnumType())
                        .append(f.isDeprecated() ? "!" : "");
            }
            return sb.toString();
        }
    }

    @Value
    public static class EncoderInfo {
        String className;
        String messageName;
        boolean deprecated;
    }
}