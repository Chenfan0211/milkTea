package com.wuling.common;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Spring Bean 构造器守卫。
 *
 * <p><b>为什么需要它（2026-10-16 线上事故）</b>：{@code MnsQueueStatusProvider}
 * 为了方便单测加了第二个（包私有）构造器，但没有给生产构造器标 {@code @Autowired}。
 * Spring 看到多个构造器且无一被标注时，会退回「找默认构造器」策略 ——
 * 启动期直接抛 {@code No default constructor found}，整个 server 服务起不来，
 * 只能靠回滚镜像恢复。这类错误单测（纯 mock）永远发现不了，因为只有真实启动才会触发。
 *
 * <p>本测试用源码扫描补上这一环：凡是 Spring 托管类（{@code @Component/@Service/
 * @Repository/@RestController/@Controller/@Configuration}）且源码里声明了
 * 多个构造器的，必须至少有一个构造器被 {@code @Autowired} 标注
 * （Lombok 生成的构造器不在源码里，不受影响）。
 */
class SpringBeanConstructorGuardTest {

    private static final Pattern STEREOTYPE = Pattern.compile(
            "@(Component|Service|Repository|RestController|Controller|Configuration)\\b");
    private static final Pattern CLASS_DECL = Pattern.compile(
            "(?m)^\\s*(?:public\\s+|final\\s+|abstract\\s+)*class\\s+(\\w+)");
    /**
     * 按行匹配注解本身。
     *
     * <p>不能用 {@code source.contains("@Autowired")}：注释里提到这个注解名（本测试自己的
     * 探针与说明文字就会这么写）会让检查被绕过 —— 这是实测踩到的盲区。
     */
    private static final Pattern AUTOWIRED_ANNOTATION = Pattern.compile("(?m)^\\s*@Autowired\\b");

    @Test
    void multiConstructorBeansMustAnnotateTheirProductionConstructor() throws IOException {
        Path root = repoRoot().resolve("server/src/main/java");
        List<String> offenders = new ArrayList<>();

        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                if (!STEREOTYPE.matcher(source).find()) {
                    continue;
                }
                Matcher classDecl = CLASS_DECL.matcher(source);
                while (classDecl.find()) {
                    String className = classDecl.group(1);
                    // 构造器声明形如： public ClassName(   /  ClassName(   （不与方法声明混淆：
                    // 构造器没有返回类型，紧随其后就是 '('）
                    Pattern constructor = Pattern.compile(
                            "(?m)^\\s*(?:@\\w+(\\([^)]*\\))?\\s*)*(?:public\\s+|protected\\s+)?"
                                    + Pattern.quote(className) + "\\s*\\(");
                    long count = constructor.matcher(source).results().count();
                    if (count > 1 && !AUTOWIRED_ANNOTATION.matcher(source).find()) {
                        offenders.add(file.getFileName() + " (" + className + ", 构造器 " + count + " 个)");
                    }
                }
            }
        }

        assertTrue(offenders.isEmpty(),
                "以下 Spring Bean 声明了多个构造器但没有 @Autowired，启动会失败：\n" + String.join("\n", offenders));
    }

    private static Path repoRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        for (Path path = cwd; path != null; path = path.getParent()) {
            if (Files.isDirectory(path.resolve("src/views"))) {
                return path;
            }
        }
        throw new IllegalStateException("未找到仓库根目录（应包含 src/views）");
    }
}
