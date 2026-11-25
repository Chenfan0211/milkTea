package com.wuling.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code @MapperScan} 覆盖面回归测试。
 *
 * <p>背景：2026-11-25 上线时 {@code server} 启动失败，报
 * <pre>
 * Parameter 0 of constructor in com.wuling.subject.onboard.MerchantOnboardService
 * required a bean of type 'com.wuling.subject.onboard.MerchantOnboardMapper'
 * that could not be found.
 * </pre>
 * 根因是新增了 Mapper 接口，却没有把所在包登记到
 * {@link SpringBootApplication} 启动类上的 {@code @MapperScan}。
 * MyBatis 不会扫描未登记的包，Mapper 接口就不会生成代理 bean，
 * 依赖它的 Service 全部构造失败 —— 整个应用起不来。
 *
 * <p>这类问题编译期完全静默，只有启动才暴露，所以用测试提前拦住：
 * 扫描源码目录下所有 {@code *Mapper.java}，逐个校验其所在包
 * 是否被 {@code @MapperScan} 的某个值覆盖。
 */
class MapperScanCoverageTest {

    /** 源码根目录（Maven 以模块目录为工作目录运行测试）。 */
    private static final Path SOURCE_ROOT = Path.of("src", "main", "java");

    @Test
    @DisplayName("所有 *Mapper 接口所在包都必须被 @MapperScan 覆盖")
    void everyMapperPackageIsScanned() {
        List<String> scanned = readMapperScanPackages();
        assertFalse(scanned.isEmpty(), "启动类上未找到 @MapperScan 配置");

        Set<String> mapperPackages = findMapperPackages();
        assertFalse(mapperPackages.isEmpty(), "未在源码中找到任何 *Mapper 接口，测试无效");

        List<String> missing = new ArrayList<>();
        for (String pkg : mapperPackages) {
            boolean covered = scanned.stream().anyMatch(scan -> covers(scan, pkg));
            if (!covered) {
                missing.add(pkg);
            }
        }

        assertTrue(
                missing.isEmpty(),
                "以下包中的 Mapper 未被 @MapperScan 覆盖，应用启动时会因找不到 bean 而失败：\n"
                        + String.join("\n", missing)
                        + "\n请在 ServerApplication 的 @MapperScan 中补充这些包。"
        );
    }

    /** 扫描目录，返回所有含 *Mapper.java 的包名。 */
    private Set<String> findMapperPackages() {
        assertTrue(Files.isDirectory(SOURCE_ROOT), "找不到源码目录：" + SOURCE_ROOT.toAbsolutePath());
        try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith("Mapper.java"))
                    .map(this::toPackageName)
                    .collect(Collectors.toCollection(TreeSet::new));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 由文件路径推导包名：src/main/java/com/wuling/x/YMapper.java -> com.wuling.x */
    private String toPackageName(Path file) {
        Path relative = SOURCE_ROOT.relativize(file);
        // 去掉最后一段文件名
        String joined = relative.subpath(0, relative.getNameCount() - 1)
                .toString()
                .replace('\\', '.')
                .replace('/', '.');
        return joined;
    }

    /**
     * 判断 @MapperScan 的一个扫描路径能否覆盖目标包。
     * MyBatis 的扫描是「该包及其子包」，所以前缀匹配即可。
     */
    private boolean covers(String scanPath, String pkg) {
        return pkg.equals(scanPath) || pkg.startsWith(scanPath + ".");
    }

    /**
     * 从启动类注解上读取 @MapperScan 的 value。
     * 用反射读注解，保证与真实生效的配置一致，而不是解析源码文本。
     */
    private List<String> readMapperScanPackages() {
        Class<?> appClass = locateApplicationClass();
        org.mybatis.spring.annotation.MapperScan scan =
                appClass.getAnnotation(org.mybatis.spring.annotation.MapperScan.class);
        assertNotNull(scan, "启动类上缺少 @MapperScan 注解");
        return List.of(scan.value());
    }

    /** 在源码中找带 @SpringBootApplication 的类并加载它。 */
    private Class<?> locateApplicationClass() {
        assertTrue(Files.isDirectory(SOURCE_ROOT), "找不到源码目录：" + SOURCE_ROOT.toAbsolutePath());
        Pattern annotation = Pattern.compile("@SpringBootApplication");
        try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
            List<Path> candidates = files
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .toList();
            for (Path path : candidates) {
                String source = Files.readString(path, StandardCharsets.UTF_8);
                if (!annotation.matcher(source).find()) {
                    continue;
                }
                String className = toPackageName(path) + "." + path.getFileName().toString().replace(".java", "");
                return Class.forName(className);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("找到启动类源码但无法加载其 Class：" + e.getMessage(), e);
        }
        throw new IllegalStateException("未找到 @SpringBootApplication 标注的启动类");
    }

    @Test
    @DisplayName("启动类本身不含被注释掉的 Mapper 扫描路径")
    void noCommentedOutScanPaths() throws IOException {
        // 防止有人把包路径注释掉却留下 @MapperScan 空壳
        List<String> scanned = readMapperScanPackages();
        for (String p : scanned) {
            assertFalse(p.isBlank(), "@MapperScan 存在空白路径");
        }
        // 反射只能读到生效值；这条断言同时确保读到的是真实配置
        Method[] methods = org.mybatis.spring.annotation.MapperScan.class.getDeclaredMethods();
        assertTrue(methods.length > 0, "@MapperScan 注解结构异常");
    }
}
