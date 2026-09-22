package com.wuling.file.security;

import java.util.Map;
import java.util.Set;

/**
 * 上传文件类型校验（Magic Bytes 白名单）。
 *
 * <p>设计依据：{@code docs/微服务改造方案.md} 第五章。
 *
 * <p><b>核心原则：扩展名与 Content-Type 都是客户端可伪造的，一律不可信。</b>
 * 唯一可靠的是文件头（Magic Bytes）。常见攻击：
 * <ul>
 *   <li>把可执行文件 / 脚本改名为 {@code .jpg} 上传（扩展名伪造）；</li>
 *   <li>构造 Polyglot 文件，同时是合法图片又是合法脚本；</li>
 *   <li>伪装 Content-Type 绕过网关或前置校验。</li>
 * </ul>
 *
 * <p>因此校验顺序是：先读文件头判定真实类型，再要求扩展名与真实类型一致，
 * 最后交由图片重编码环节剥离嵌入载荷。
 */
public final class FileTypeValidator {

    /** 允许的图片类型（扩展名 → 类型定义） */
    private static final Map<String, FileType> ALLOWED = Map.of(
            "jpg", new FileType("image/jpeg", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}),
            "jpeg", new FileType("image/jpeg", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}),
            "png", new FileType("image/png", new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}),
            "gif", new FileType("image/gif", new byte[]{'G', 'I', 'F', '8'})
    );

    /** 禁止的扩展名（即便文件头合法也拒绝，避免"借壳"脚本） */
    private static final Set<String> FORBIDDEN_EXT = Set.of(
            "exe", "dll", "so", "sh", "bat", "cmd", "ps1", "js", "jar",
            "php", "jsp", "asp", "py", "rb", "pl", "html", "htm", "svg"
    );

    private FileTypeValidator() {
    }

    /**
     * 校验上传文件。
     *
     * @param filename 原始文件名（仅用于取扩展名，不参与路径拼接）
     * @param head     文件头字节（建议至少前 16 字节）
     * @return 判定出的真实类型
     * @throws InvalidFileException 校验不通过
     */
    public static FileType validate(String filename, byte[] head) {
        String ext = extensionOf(filename);
        if (ext == null) {
            throw new InvalidFileException("文件缺少扩展名");
        }
        if (FORBIDDEN_EXT.contains(ext)) {
            throw new InvalidFileException("不允许上传该类型文件: " + ext);
        }
        FileType declared = ALLOWED.get(ext);
        if (declared == null) {
            throw new InvalidFileException("不支持的文件类型: " + ext);
        }
        if (head == null || head.length == 0) {
            throw new InvalidFileException("文件内容为空");
        }
        // 关键：以文件头判定真实类型，并要求与扩展名一致
        FileType actual = detectByMagicBytes(head);
        if (actual == null) {
            throw new InvalidFileException("文件内容不是受支持的图片格式");
        }
        if (!actual.mimeType().equals(declared.mimeType())) {
            throw new InvalidFileException(
                    "文件扩展名与实际内容不符（扩展名 " + ext + "，实际 " + actual.mimeType() + "）");
        }
        return actual;
    }

    /**
     * 按文件头识别真实类型。
     *
     * @return 识别出的类型；无法识别返回 null
     */
    public static FileType detectByMagicBytes(byte[] head) {
        if (head == null) {
            return null;
        }
        for (FileType type : ALLOWED.values()) {
            if (startsWith(head, type.magicBytes())) {
                return type;
            }
        }
        return null;
    }

    /** 提取小写扩展名；无扩展名返回 null */
    public static String extensionOf(String filename) {
        if (filename == null || filename.isBlank()) {
            return null;
        }
        // 防止路径穿越：只取最后一段
        String name = filename.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return null;
        }
        return name.substring(dot + 1).toLowerCase();
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    /** 受支持的图片类型 */
    public record FileType(String mimeType, byte[] magicBytes) {
    }

    /** 校验失败 */
    public static class InvalidFileException extends RuntimeException {
        public InvalidFileException(String message) {
            super(message);
        }
    }
}
