package com.wuling.marketing;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mapper mapping self-check to prevent overloaded methods being silently ignored by MyBatis.
 *
 * MyBatis statement id = fully-qualified-interface.method-name (no parameter types).
 * Overloaded methods therefore collide; MyBatis-Plus logs
 * "mapper[...] is ignored, because it exists, maybe from xml file" and silently drops one.
 * Mockito-based unit tests bypass real mapping and hide this defect.
 *
 * This test parses every Mapper interface with a real Configuration and asserts that
 * (1) no interface declares overloaded methods, and
 * (2) every SQL-annotated method registers a mapped statement.
 *
 * The test class deliberately lives in com.wuling.marketing (not the mapper sub-package)
 * so classpath scanning resolves mapper packages to main classes, not test classes.
 */
class MapperStatementSelfCheckTest {

    private static final String[] MAPPER_PACKAGES = {
            "com.wuling.marketing.mapper",
            "com.wuling.user.mapper"
    };

    @Test
    @DisplayName("Mapper interfaces must not declare overloaded methods")
    void mapperInterfacesMustNotDeclareOverloadedMethods() throws Exception {
        List<Class<?>> mappers = scanMappers(MAPPER_PACKAGES);
        assertTrue(mappers.size() > 0, "no mapper interfaces scanned");

        List<String> overloads = new ArrayList<>();
        for (Class<?> mapperClass : mappers) {
            java.util.Map<String, Integer> nameCount = new java.util.LinkedHashMap<>();
            for (Method method : mapperClass.getDeclaredMethods()) {
                nameCount.merge(method.getName(), 1, Integer::sum);
            }
            for (java.util.Map.Entry<String, Integer> e : nameCount.entrySet()) {
                if (e.getValue() > 1) {
                    overloads.add(mapperClass.getName() + "." + e.getKey());
                }
            }
        }
        assertTrue(overloads.isEmpty(),
                "overloaded Mapper methods (MyBatis will silently drop one):\n" + String.join("\n", overloads));
    }

    @Test
    @DisplayName("every SQL-annotated Mapper method must register a mapped statement")
    void everyAnnotatedMapperMethodHasMappedStatement() throws Exception {
        Configuration configuration = new Configuration();
        configuration.setLogImpl(org.apache.ibatis.logging.nologging.NoLoggingImpl.class);

        List<Class<?>> mappers = scanMappers(MAPPER_PACKAGES);
        assertTrue(mappers.size() > 0, "no mapper interfaces scanned");

        for (Class<?> mapperClass : mappers) {
            configuration.addMapper(mapperClass);
        }

        java.util.Collection<String> statementNames = configuration.getMappedStatementNames();

        for (Class<?> mapperClass : mappers) {
            String namespace = mapperClass.getName();
            for (Method method : declaredSqlMethods(mapperClass)) {
                String statementId = namespace + "." + method.getName();
                assertTrue(statementNames.contains(statementId),
                        "mapper method missing statement (possible overload collision): " + statementId);
            }
        }
    }

    private static List<Method> declaredSqlMethods(Class<?> mapperClass) {
        List<Method> result = new ArrayList<>();
        for (Method method : mapperClass.getDeclaredMethods()) {
            if (method.isDefault() || Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            if (method.isAnnotationPresent(Select.class)
                    || method.isAnnotationPresent(Update.class)
                    || method.isAnnotationPresent(Insert.class)
                    || method.isAnnotationPresent(Delete.class)) {
                result.add(method);
            }
        }
        return result;
    }

    private static List<Class<?>> scanMappers(String[] packages) throws Exception {
        List<Class<?>> result = new ArrayList<>();
        for (String pkg : packages) {
            String path = pkg.replace('.', '/');
            java.net.URL resource = Thread.currentThread().getContextClassLoader().getResource(path);
            if (resource == null) {
                throw new IllegalStateException("package resource not found: " + pkg);
            }
            java.io.File dir = new java.io.File(resource.toURI());
            java.io.File[] files = dir.listFiles((d, name) -> name.endsWith(".class"));
            if (files == null) {
                continue;
            }
            for (java.io.File file : files) {
                String className = pkg + "." + file.getName().replace(".class", "");
                Class<?> clazz = Class.forName(className);
                if (clazz.isInterface()) {
                    result.add(clazz);
                }
            }
        }
        return result;
    }
}
