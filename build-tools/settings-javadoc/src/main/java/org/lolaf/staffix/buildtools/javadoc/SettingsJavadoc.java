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
package org.lolaf.staffix.buildtools.javadoc;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.javadoc.description.JavadocDescriptionElement;
import com.github.javaparser.javadoc.description.JavadocInlineTag;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The javadoc of settings classes and their fields as plain text, read from source at build time so a generated
 * schema's descriptions are the javadoc itself.
 */
public class SettingsJavadoc {

    private static final Pattern PARAGRAPH = Pattern.compile("(?i)<p\\b[^>]*>");
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern PARAMETERS = Pattern.compile("\\([^)]*\\)");

    /**
     * Keyed by the qualified class name, nested classes dotted, then {@code #field} for a field.
     */
    private final Map<String, String> descriptions = new HashMap<>();

    public void read(Path sourceFile) throws IOException {
        CompilationUnit unit = StaticJavaParser.parse(sourceFile);
        Map<String, String> constants = new HashMap<>();
        unit.findAll(VariableDeclarator.class).forEach(variable -> variable.getInitializer()
                .filter(Expression::isLiteralStringValueExpr)
                .ifPresent(value -> constants.put(variable.getNameAsString(), value.asLiteralStringValueExpr().getValue())));
        for (TypeDeclaration<?> type : unit.findAll(TypeDeclaration.class)) {
            type.getJavadoc().ifPresent(javadoc ->
                    put(type.getFullyQualifiedName().orElseThrow(), javadoc.getDescription().getElements(), constants));
        }
        unit.findAll(FieldDeclaration.class).forEach(field -> field.getJavadoc().ifPresent(javadoc ->
                field.getVariables().forEach(variable -> put(className(field) + "#" + variable.getNameAsString(),
                        javadoc.getDescription().getElements(), constants))));
    }

    /**
     * @param className qualified, nested classes dotted
     * @return the class's javadoc as plain text, or null when it has none
     */
    public String describe(String className) {
        return descriptions.get(className);
    }

    /**
     * @param className qualified, nested classes dotted
     * @return the field's javadoc as plain text, or null when it has none
     */
    public String describe(String className, String field) {
        return descriptions.get(className + "#" + field);
    }

    private void put(String key, Iterable<JavadocDescriptionElement> elements, Map<String, String> constants) {
        String text = plainText(elements, constants);
        if (!text.isEmpty()) {
            descriptions.put(key, text);
        }
    }

    private static String className(FieldDeclaration field) {
        TypeDeclaration<?> type = field.findAncestor(TypeDeclaration.class).orElseThrow();
        return type.getFullyQualifiedName().orElseThrow();
    }

    private static String plainText(Iterable<JavadocDescriptionElement> elements, Map<String, String> constants) {
        StringBuilder html = new StringBuilder();
        elements.forEach(element -> html.append(element instanceof JavadocInlineTag
                ? inlineTagText((JavadocInlineTag) element, constants)
                : element.toText()));
        String paragraphs = TAG.matcher(PARAGRAPH.matcher(html).replaceAll("\n\n")).replaceAll("")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
        return Pattern.compile("\n\n").splitAsStream(paragraphs)
                .map(paragraph -> WHITESPACE.matcher(paragraph).replaceAll(" ").trim())
                .filter(paragraph -> !paragraph.isEmpty())
                .collect(Collectors.joining("\n\n"));
    }

    /**
     * {@code {@link a.b.Type#member(Args) label}} reads as its label, else as {@code Type.member()}; {@code {@value
     * #CONSTANT}} as the constant of the same file.
     */
    private static String inlineTagText(JavadocInlineTag tag, Map<String, String> constants) {
        String content = tag.getContent().trim();
        if (tag.getType() == JavadocInlineTag.Type.VALUE) {
            return constants.getOrDefault(content.substring(content.indexOf('#') + 1), content);
        }
        if (tag.getType() != JavadocInlineTag.Type.LINK && tag.getType() != JavadocInlineTag.Type.LINKPLAIN) {
            return content;
        }
        String reference = PARAMETERS.matcher(content).replaceAll("()");
        int space = reference.indexOf(' ');
        if (space > 0) {
            return reference.substring(space + 1).trim();
        }
        int hash = reference.indexOf('#');
        String type = hash < 0 ? reference : reference.substring(0, hash);
        String simpleType = type.substring(type.lastIndexOf('.') + 1);
        if (hash < 0) {
            return simpleType;
        }
        String member = reference.substring(hash + 1);
        return simpleType.isEmpty() ? member : simpleType + "." + member;
    }
}
