/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0 and the Server Side Public License, v 1; you may not use this file except
 * in compliance with, at your election, the Elastic License 2.0 or the Server
 * Side Public License, v 1.
 */

package org.elasticsearch.plugin.scanner;

import org.elasticsearch.plugin.api.Extensible;
import org.elasticsearch.plugin.api.NamedComponent;
import org.elasticsearch.xcontent.XContentBuilder;
import org.elasticsearch.xcontent.XContentFactory;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class NamedComponentScanner {
    // main method to be used by gradle build plugin
    public static void main(String[] args) throws IOException {
        List<ClassReader> classReaders = ClassReaders.ofClassPath();

        NamedComponentScanner scanner = new NamedComponentScanner();
        Map<String, Map<String, String>> namedComponentsMap = scanner.scanForNamedClasses(classReaders);
        Path outputFile = Path.of(args[0]);
        scanner.writeToFile(namedComponentsMap, outputFile);
    }

    // scope for testing
    void writeToFile(Map<String, Map<String, String>> namedComponentsMap, Path outputFile) throws IOException {
        // String json = OBJECT_MAPPER.writeValueAsString(namedComponentsMap);
        Files.createDirectories(outputFile.getParent());

        try (OutputStream outputStream = Files.newOutputStream(outputFile)) {
            try (XContentBuilder namedComponents = XContentFactory.jsonBuilder(outputStream)) {
                namedComponents.startObject();
                for (Map.Entry<String, Map<String, String>> extensibleToComponents : namedComponentsMap.entrySet()) {
                    namedComponents.startObject(extensibleToComponents.getKey());// extensible class name
                    for (Map.Entry<String, String> components : extensibleToComponents.getValue().entrySet()) {
                        namedComponents.field(components.getKey(), components.getValue());// component name : component class
                    }
                    namedComponents.endObject();
                }
                namedComponents.endObject();
            }
        }

    }

    // returns a Map<String, Map<String,String> - extensible interface -> map{ namedName -> className }
    public Map<String, Map<String, String>> scanForNamedClasses(List<ClassReader> classReaderStream) {
        ClassScanner extensibleClassScanner = new ClassScanner(Type.getDescriptor(Extensible.class), (classname, map) -> {
            map.put(classname, classname);
            return null;
        });
        extensibleClassScanner.visit(classReaderStream);

        ClassScanner namedComponentsScanner = new ClassScanner(
            Type.getDescriptor(NamedComponent.class),
            (classname, map) -> new AnnotationVisitor(Opcodes.ASM9) {
                @Override
                public void visit(String key, Object value) {
                    assert key.equals("value");
                    assert value instanceof String;
                    map.put(value.toString(), classname);
                }
            }
        );

        namedComponentsScanner.visit(classReaderStream);

        Map<String, Map<String, String>> componentInfo = new HashMap<>();
        for (var e : namedComponentsScanner.getFoundClasses().entrySet()) {
            String name = e.getKey();
            String classnameWithSlashes = e.getValue();
            String extensibleClassnameWithSlashes = extensibleClassScanner.getFoundClasses().get(classnameWithSlashes);
            if (extensibleClassnameWithSlashes == null) {
                throw new RuntimeException(
                    "Named component " + name + "(" + pathToClassName(classnameWithSlashes) + ") does not extend from an extensible class"
                );
            }
            var named = componentInfo.computeIfAbsent(pathToClassName(extensibleClassnameWithSlashes), k -> new HashMap<>());
            named.put(name, pathToClassName(classnameWithSlashes));
        }
        return componentInfo;
    }

    private String pathToClassName(String classWithSlashes) {
        return classWithSlashes.replace('/', '.');
    }

}
