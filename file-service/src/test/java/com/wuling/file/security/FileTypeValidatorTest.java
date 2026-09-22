package com.wuling.file.security;

import com.wuling.file.security.FileTypeValidator.FileType;
import com.wuling.file.security.FileTypeValidator.InvalidFileException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 文件类型校验测试：覆盖改造方案第五章列出的攻击手法 */
class FileTypeValidatorTest {

    // 各格式的真实文件头
    private static byte[] jpegHead() {
        return new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0};
    }

    private static byte[] pngHead() {
        return new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0};
    }

    private static byte[] gifHead() {
        return new byte[]{'G', 'I', 'F', '8', '9', 'a', 0, 0};
    }

    /**
     * 伪装的脚本内容：以 HTML 注释开头，模拟"内容是文本而非图片"。
     * 说明：此处刻意不使用真实脚本标签，避免测试样本被安全软件误判；
     * 对校验逻辑而言，只要头部不是图片 magic bytes 即等价。
     */
    private static byte[] nonImageContent() {
        return "<!-- text payload, not an image -->".getBytes();
    }

    // ---------- 正常路径 ----------

    @Test
    void shouldAcceptRealJpeg() {
        FileType type = FileTypeValidator.validate("photo.jpg", jpegHead());
        assertEquals("image/jpeg", type.mimeType());
    }

    @Test
    void shouldAcceptRealPngAndGif() {
        assertEquals("image/png", FileTypeValidator.validate("a.png", pngHead()).mimeType());
        assertEquals("image/gif", FileTypeValidator.validate("a.gif", gifHead()).mimeType());
    }

    @Test
    void shouldAcceptUppercaseExtension() {
        assertEquals("image/jpeg", FileTypeValidator.validate("PHOTO.JPG", jpegHead()).mimeType());
    }

    // ---------- 攻击场景 ----------

    @Test
    void shouldRejectNonImageContentRenamedAsImage() {
        // 经典手法：非图片内容改名为 .jpg 上传
        assertThrows(InvalidFileException.class,
                () -> FileTypeValidator.validate("shell.jpg", nonImageContent()));
    }

    @Test
    void shouldRejectForbiddenExtensionEvenWithValidHeader() {
        // 即便文件头是合法图片，禁用扩展名也一律拒绝
        assertThrows(InvalidFileException.class,
                () -> FileTypeValidator.validate("evil.php", jpegHead()));
        assertThrows(InvalidFileException.class,
                () -> FileTypeValidator.validate("evil.jsp", pngHead()));
        assertThrows(InvalidFileException.class,
                () -> FileTypeValidator.validate("evil.svg", pngHead()));
        assertThrows(InvalidFileException.class,
                () -> FileTypeValidator.validate("evil.exe", jpegHead()));
        assertThrows(InvalidFileException.class,
                () -> FileTypeValidator.validate("evil.sh", jpegHead()));
    }

    @Test
    void shouldRejectExtensionMismatch() {
        // 扩展名说 png，内容其实是 jpeg —— 说明被改过，拒绝
        assertThrows(InvalidFileException.class,
                () -> FileTypeValidator.validate("fake.png", jpegHead()));
    }

    @Test
    void shouldRejectUnknownType() {
        assertThrows(InvalidFileException.class,
                () -> FileTypeValidator.validate("a.txt", "hello world".getBytes()));
        assertThrows(InvalidFileException.class,
                () -> FileTypeValidator.validate("a.bin", new byte[]{0x00, 0x01, 0x02, 0x03}));
    }

    @Test
    void shouldRejectMissingOrEmptyExtension() {
        assertThrows(InvalidFileException.class,
                () -> FileTypeValidator.validate("noext", jpegHead()));
        assertThrows(InvalidFileException.class,
                () -> FileTypeValidator.validate("trailing.", jpegHead()));
        assertThrows(InvalidFileException.class,
                () -> FileTypeValidator.validate(null, jpegHead()));
        assertThrows(InvalidFileException.class,
                () -> FileTypeValidator.validate("", jpegHead()));
    }

    @Test
    void shouldRejectEmptyContent() {
        assertThrows(InvalidFileException.class,
                () -> FileTypeValidator.validate("a.jpg", new byte[0]));
        assertThrows(InvalidFileException.class,
                () -> FileTypeValidator.validate("a.jpg", null));
    }

    @Test
    void shouldRejectTooShortHeader() {
        // 只有 2 字节，不足以匹配任何 magic bytes
        assertThrows(InvalidFileException.class,
                () -> FileTypeValidator.validate("a.jpg", new byte[]{(byte) 0xFF, (byte) 0xD8}));
    }

    @Test
    void shouldRejectPolyglotWhoseHeadIsNotImage() {
        // Polyglot：垃圾内容在前、图片头在后 —— 头部判定为"非图片"，拒绝
        byte[] junk = nonImageContent();
        byte[] combined = new byte[junk.length + 8];
        System.arraycopy(junk, 0, combined, 0, junk.length);
        System.arraycopy(jpegHead(), 0, combined, junk.length, 8);
        assertThrows(InvalidFileException.class,
                () -> FileTypeValidator.validate("polyglot.jpg", combined));
    }

    // ---------- 路径穿越 ----------

    @Test
    void shouldRejectFilenameWithoutValidExtension() {
        assertThrows(InvalidFileException.class,
                () -> FileTypeValidator.validate("../../etc/passwd", jpegHead()));
    }

    @Test
    void shouldExtractExtensionFromPathLikeName() {
        // 带路径的合法图片应正常取到扩展名；路径本身不参与拼接
        assertEquals("image/jpeg",
                FileTypeValidator.validate("../../uploads/a.jpg", jpegHead()).mimeType());
        assertEquals("image/jpeg",
                FileTypeValidator.validate("C:\\temp\\a.jpg", jpegHead()).mimeType());
    }

    @Test
    void extensionOfShouldHandleEdgeCases() {
        assertNull(FileTypeValidator.extensionOf(null));
        assertNull(FileTypeValidator.extensionOf(""));
        assertNull(FileTypeValidator.extensionOf("noext"));
        assertEquals("jpg", FileTypeValidator.extensionOf("a.JPG"));
        assertEquals("jpg", FileTypeValidator.extensionOf("/path/to/a.jpg"));
    }

    @Test
    void detectByMagicBytesShouldReturnNullForUnknown() {
        assertNull(FileTypeValidator.detectByMagicBytes(new byte[]{0x00, 0x01}));
        assertNull(FileTypeValidator.detectByMagicBytes(null));
    }
}
