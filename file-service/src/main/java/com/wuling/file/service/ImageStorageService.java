package com.wuling.file.service;

import com.wuling.file.security.ClamAvScanner;
import com.wuling.file.security.FileTypeValidator;
import com.wuling.file.security.FileTypeValidator.FileType;
import com.wuling.file.security.FileTypeValidator.InvalidFileException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import javax.imageio.IIOImage;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 图片落盘与公开读取服务。
 *
 * <p>上传内容先校验 magic bytes 与扩展名，再读取图片尺寸，最后完整解码并重新编码。
 * 重编码产物不携带原始图片元数据，保存名固定为随机 UUID，避免覆盖和路径注入。
 */
@Service
public class ImageStorageService {

    private static final Logger log = LoggerFactory.getLogger(ImageStorageService.class);
    private static final Pattern PUBLIC_NAME =
            Pattern.compile("^[0-9a-f]{32}\\.(jpg|png)$");
    private static final String JPEG_MIME = "image/jpeg";
    private static final String PNG_MIME = "image/png";
    private static final long STORAGE_TARGET_BYTES = 500 * 1024L;
    private static final String VARIANT_DIRECTORY = ".variants";

    private final Path storageRoot;
    private final String publicBaseUrl;
    private final long maxSizeBytes;
    private final int maxDimension;
    private final long maxPixels;
    private final Map<Path, Object> variantLocks = new ConcurrentHashMap<>();
    /** 病毒扫描器；null 表示未启用扫描（本地开发/测试兼容） */
    private ClamAvScanner scanner;

    public ImageStorageService(
            @Value("${app.file.storage-root}") String storageRoot,
            @Value("${app.file.public-base-url}") String publicBaseUrl,
            @Value("${app.file.max-size-bytes:5242880}") long maxSizeBytes,
            @Value("${app.file.max-dimension:16000}") int maxDimension,
            @Value("${app.file.max-pixels:40000000}") long maxPixels) {
        if (storageRoot == null || storageRoot.isBlank()) {
            throw new IllegalStateException("app.file.storage-root 不能为空");
        }
        if (publicBaseUrl == null || publicBaseUrl.isBlank()) {
            throw new IllegalStateException("app.file.public-base-url 不能为空");
        }
        this.storageRoot = Path.of(storageRoot).toAbsolutePath().normalize();
        this.publicBaseUrl = trimTrailingSlash(publicBaseUrl.trim());
        this.maxSizeBytes = maxSizeBytes;
        this.maxDimension = maxDimension;
        this.maxPixels = maxPixels;
        try {
            Files.createDirectories(this.storageRoot);
            if (!Files.isWritable(this.storageRoot)) {
                throw new IllegalStateException("文件存储目录不可写: " + this.storageRoot);
            }
        } catch (IOException e) {
            throw new IllegalStateException("无法创建文件存储目录: " + this.storageRoot, e);
        }
    }

    /** 可选注入病毒扫描器（ClamAV 未启用时 Spring 不注入，保持 null）。 */
    @Autowired(required = false)
    public void setScanner(ClamAvScanner scanner) {
        this.scanner = scanner;
    }

    /**
     * 校验、重编码并保存图片（流式入口，避免整文件读入堆内存）。
     *
     * <p>与 {@link #store(String, byte[], String)} 的差异：签名与尺寸判断阶段只读文件头，
     * 不加载全量内容；仅在确认尺寸合法后才做全量解码与重编码。
     *
     * @param originalFilename 原始文件名，仅用于扩展名校验
     * @param in               文件内容流（本方法不负责关闭）
     * @param declaredType     客户端声明的 Content-Type，仅用于告警，不作为可信依据
     */
    public StoredImage store(String originalFilename, InputStream in, String declaredType) {
        if (in == null) {
            throw new InvalidFileException("文件内容为空");
        }
        try {
            byte[] head;
            try {
                head = in.readNBytes(16);
            } catch (IOException e) {
                throw new InvalidFileException("文件读取失败");
            }
            if (head.length == 0) {
                throw new InvalidFileException("文件内容为空");
            }
            FileType type = FileTypeValidator.validate(originalFilename, head);
            if (declaredType != null && !declaredType.isBlank()
                    && !declaredType.equalsIgnoreCase(type.mimeType())) {
                log.warn("declared content-type mismatch: declared={} actual={} file={}",
                        declaredType, type.mimeType(), originalFilename);
            }
            // 已读 head 与剩余流拼接，保证解码器拿到完整文件头
            InputStream complete = new SequenceInputStream(
                    new ByteArrayInputStream(head), in);
            BufferedImage decoded = decodeWithDimensionLimits(complete);
            String suffix = JPEG_MIME.equals(type.mimeType()) ? "jpg" : "png";
            byte[] encoded = reencodeForStorage(decoded, type);
            return writeEncoded(originalFilename, encoded, type, suffix);
        } catch (InvalidFileException e) {
            throw e;
        } catch (Exception e) {
            throw new InvalidFileException("文件读取失败");
        }
    }

    /**
     * 校验、重编码并保存图片。
     *
     * @param originalFilename 原始文件名，仅用于扩展名校验
     * @param content          原始文件字节
     * @param declaredType     客户端声明的 Content-Type，仅用于告警，不作为可信依据
     */
    public StoredImage store(String originalFilename, byte[] content, String declaredType) {
        if (content == null || content.length == 0) {
            throw new InvalidFileException("文件内容为空");
        }
        if (content.length > maxSizeBytes) {
            throw new InvalidFileException(
                    "文件超过大小限制（" + (maxSizeBytes / 1024 / 1024) + "MB）");
        }

        FileType type = FileTypeValidator.validate(originalFilename, headOf(content));
        if (declaredType != null && !declaredType.isBlank()
                && !declaredType.equalsIgnoreCase(type.mimeType())) {
            log.warn("declared content-type mismatch: declared={} actual={} file={}",
                    declaredType, type.mimeType(), originalFilename);
        }

        scanIfEnabled(content);
        BufferedImage decoded = decodeWithDimensionLimits(content);
        String suffix = JPEG_MIME.equals(type.mimeType()) ? "jpg" : "png";
        byte[] encoded = reencodeForStorage(decoded, type);
        return writeEncoded(originalFilename, encoded, type, suffix);
    }

    /**
     * 读取公开图片。存储名必须是服务生成的 UUID 文件名，拒绝任何路径片段。
     */
    public PublicImage loadPublicImage(String storedName) {
        return loadOriginalImage(storedName);
    }

    /**
     * 按需生成并读取图片规格。规格文件缓存在 storageRoot/.variants 下，
     * 不修改数据库，也不改变原图。
     */
    public PublicImage loadPublicImage(String storedName, String variantValue) {
        if (variantValue == null || variantValue.isBlank()) {
            return loadOriginalImage(storedName);
        }
        ImageVariant variant = ImageVariant.parse(variantValue);
        Path original = validatePublicFile(storedName);
        String extension = storedName.endsWith(".jpg") ? ".jpg" : ".png";
        Path variantFile = variantPath(storedName, extension, variant);

        PublicImage cached = readImageIfPresent(variantFile, mimeTypeFor(extension));
        if (cached != null) {
            return cached;
        }

        Object lock = variantLocks.computeIfAbsent(variantFile, ignored -> new Object());
        try {
            synchronized (lock) {
                cached = readImageIfPresent(variantFile, mimeTypeFor(extension));
                if (cached != null) {
                    return cached;
                }
                byte[] originalBytes = readWithinRoot(original);
                BufferedImage decoded = decodeWithDimensionLimits(originalBytes);
                byte[] optimized = encodeVariant(decoded, extension, variant);
                writeVariantCache(variantFile, optimized);
                log.info("generated image variant={} source={} bytes={}",
                        variant.key(), storedName, optimized.length);
                return new PublicImage(optimized, mimeTypeFor(extension));
            }
        } finally {
            variantLocks.remove(variantFile, lock);
        }
    }

    private PublicImage loadOriginalImage(String storedName) {
        Path candidate = validatePublicFile(storedName);
        String mimeType = storedName.endsWith(".jpg") ? JPEG_MIME : PNG_MIME;
        return new PublicImage(readWithinRoot(candidate), mimeType);
    }

    private Path validatePublicFile(String storedName) {
        if (storedName == null || !PUBLIC_NAME.matcher(storedName).matches()) {
            throw new InvalidFilePathException("非法文件路径");
        }

        Path candidate = storageRoot.resolve(storedName).normalize();
        if (!candidate.getParent().equals(storageRoot)
                || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new ImageNotFoundException("图片不存在");
        }
        verifyInsideStorage(candidate);
        return candidate;
    }

    /**
     * 删除服务生成的图片文件。仅用于失败补偿；不暴露为公开接口。
     * 删除不存在的安全文件视为幂等成功。
     */
    public void deleteImage(String storedName) {
        if (storedName == null || !PUBLIC_NAME.matcher(storedName).matches()) {
            throw new InvalidFilePathException("非法文件路径");
        }
        Path candidate = storageRoot.resolve(storedName).normalize();
        if (!candidate.getParent().equals(storageRoot)) {
            throw new InvalidFilePathException("非法文件路径");
        }
        if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new InvalidFilePathException("非法文件路径");
        }
        try {
            Path realRoot = storageRoot.toRealPath();
            Path realFile = candidate.toRealPath();
            if (!realFile.startsWith(realRoot)) {
                throw new InvalidFilePathException("非法文件路径");
            }
            Files.delete(realFile);
            deleteVariantFiles(storedName);
        } catch (NoSuchFileException ignored) {
            // 并发补偿删除：目标已消失，按幂等成功处理。
        } catch (IOException e) {
            throw new IllegalStateException("图片删除失败: " + storedName, e);
        }
    }

    private Path variantPath(String storedName, String extension, ImageVariant variant) {
        int dot = storedName.lastIndexOf('.');
        String baseName = dot > 0 ? storedName.substring(0, dot) : storedName;
        return storageRoot.resolve(VARIANT_DIRECTORY)
                .resolve(baseName + "-" + variant.key() + extension)
                .normalize();
    }

    private PublicImage readImageIfPresent(Path target, String mimeType) {
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        verifyInsideStorage(target);
        try {
            return new PublicImage(Files.readAllBytes(target), mimeType);
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            throw new IllegalStateException("缓存图片读取失败: " + target.getFileName(), e);
        }
    }

    private byte[] readWithinRoot(Path target) {
        verifyInsideStorage(target);
        try {
            return Files.readAllBytes(target);
        } catch (NoSuchFileException e) {
            throw new ImageNotFoundException("图片不存在");
        } catch (IOException e) {
            throw new IllegalStateException("图片读取失败: " + target.getFileName(), e);
        }
    }

    private void verifyInsideStorage(Path target) {
        try {
            Path realRoot = storageRoot.toRealPath();
            Path realFile = target.toRealPath();
            if (!realFile.startsWith(realRoot)) {
                throw new InvalidFilePathException("非法文件路径");
            }
        } catch (NoSuchFileException e) {
            throw new ImageNotFoundException("图片不存在");
        } catch (IOException e) {
            throw new IllegalStateException("图片路径校验失败: " + target.getFileName(), e);
        }
    }

    private void writeVariantCache(Path target, byte[] bytes) {
        Path directory = target.getParent();
        Path temporary = null;
        try {
            Files.createDirectories(directory);
            temporary = Files.createTempFile(directory, target.getFileName().toString() + "-", ".tmp");
            Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("图片规格缓存写入失败: " + target.getFileName(), e);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // 临时文件清理失败不影响已完成的缓存写入。
                }
            }
        }
    }

    private void deleteVariantFiles(String storedName) {
        Path directory = storageRoot.resolve(VARIANT_DIRECTORY);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        for (ImageVariant variant : ImageVariant.values()) {
            for (String extension : new String[]{".jpg", ".png"}) {
                Path target = variantPath(storedName, extension, variant);
                try {
                    verifyInsideStorage(directory);
                    Files.deleteIfExists(target);
                } catch (ImageNotFoundException ignored) {
                    // 缓存目录已被其他请求清理。
                } catch (IOException e) {
                    throw new IllegalStateException("图片规格缓存删除失败: " + target.getFileName(), e);
                }
            }
        }
    }

    /** 编码结果落盘：UUID 命名 + 防覆盖 + 防路径越界 */
    private StoredImage writeEncoded(String originalFilename, byte[] encoded,
                                      FileType type, String suffix) {
        while (true) {
            String storedName = UUID.randomUUID().toString().replace("-", "") + "." + suffix;
            Path target = storageRoot.resolve(storedName).normalize();
            if (!target.getParent().equals(storageRoot)) {
                throw new IllegalStateException("生成的文件路径越界");
            }
            try {
                Files.write(target, encoded, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                log.info("stored image file={} size={} type={} stored={}",
                        originalFilename, encoded.length, type.mimeType(), storedName);
                return new StoredImage(publicBaseUrl + "/" + storedName,
                        storedName, type.mimeType(), encoded.length);
            } catch (FileAlreadyExistsException ignored) {
                // UUID 冲突概率极低；若发生则重新生成，绝不覆盖既有文件。
            } catch (IOException e) {
                throw new IllegalStateException("图片落盘失败: " + storedName, e);
            }
        }
    }

    /** 病毒扫描（若启用）。发现病毒或扫描失败（fail-closed）则拒绝。 */
    private void scanIfEnabled(byte[] content) {
        if (scanner == null) {
            return;
        }
        try {
            if (!scanner.isClean(new ByteArrayInputStream(content))) {
                throw new InvalidFileException("检测到病毒或恶意内容，已拒绝上传");
            }
        } catch (ClamAvScanner.ScanException e) {
            // fail-closed：扫描服务不可用同样拒绝，保留明确语义
            throw new InvalidFileException("病毒扫描服务不可用，已拒绝上传");
        }
    }

    private BufferedImage decodeWithDimensionLimits(byte[] content) {
        try (ImageInputStream input = ImageIO.createImageInputStream(
                new ByteArrayInputStream(content))) {
            if (input == null) {
                throw new InvalidFileException("图片内容无法读取");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new InvalidFileException("图片内容无法解码");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                validateDimensions(width, height);
                BufferedImage image = reader.read(0);
                if (image == null) {
                    throw new InvalidFileException("图片内容无法解码");
                }
                return image;
            } finally {
                reader.dispose();
            }
        } catch (InvalidFileException e) {
            throw e;
        } catch (Exception e) {
            throw new InvalidFileException("图片内容无法解码");
        }
    }

    /** 从流中解码图片：先读 header 判尺寸，尺寸合法后才全量解码像素。 */
    private BufferedImage decodeWithDimensionLimits(InputStream in) {
        try (ImageInputStream input = ImageIO.createImageInputStream(in)) {
            if (input == null) {
                throw new InvalidFileException("图片内容无法读取");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new InvalidFileException("图片内容无法解码");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                validateDimensions(width, height);
                BufferedImage image = reader.read(0);
                if (image == null) {
                    throw new InvalidFileException("图片内容无法解码");
                }
                return image;
            } finally {
                reader.dispose();
            }
        } catch (InvalidFileException e) {
            throw e;
        } catch (Exception e) {
            throw new InvalidFileException("图片内容无法解码");
        }
    }

    private void validateDimensions(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new InvalidFileException("图片尺寸无效");
        }
        if (width > maxDimension || height > maxDimension) {
            throw new InvalidFileException("图片最长边不能超过 " + maxDimension + " 像素");
        }
        long pixels = (long) width * height;
        if (pixels > maxPixels) {
            throw new InvalidFileException("图片总像素不能超过 " + maxPixels);
        }
    }

    private byte[] reencodeForStorage(BufferedImage image, FileType type) {
        String format = JPEG_MIME.equals(type.mimeType()) ? "jpeg" : "png";
        return compressToTarget(image, format, image.getWidth(), image.getHeight(),
                STORAGE_TARGET_BYTES, true);
    }

    private byte[] encodeVariant(BufferedImage image, String extension,
                                 ImageVariant variant) {
        String format = ".jpg".equals(extension) ? "jpeg" : "png";
        int maxWidth = variant.maxWidth();
        int maxHeight = variant.maxHeight();
        double scale = Math.min(1.0, Math.min(
                (double) maxWidth / image.getWidth(),
                (double) maxHeight / image.getHeight()));
        int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
        return compressToTarget(image, format, width, height, variant.targetBytes(), false);
    }

    /**
     * 先降质量，再按 10% 步长缩小，优先保留尽可能高的分辨率。
     */
    private byte[] compressToTarget(BufferedImage source, String format, int initialWidth,
                                    int initialHeight, long targetBytes,
                                    boolean preserveExistingDimensions) {
        double[] dimensions = preserveExistingDimensions
                ? new double[]{1.0, 0.9, 0.8, 0.7, 0.6, 0.5, 0.4, 0.3}
                : new double[]{1.0, 0.9, 0.8, 0.7, 0.6, 0.5, 0.4, 0.3};
        float[] jpegQualities = new float[]{0.86f, 0.78f, 0.7f, 0.62f, 0.54f, 0.46f};
        byte[] smallest = null;

        for (double dimension : dimensions) {
            int width = Math.max(1, (int) Math.round(initialWidth * dimension));
            int height = Math.max(1, (int) Math.round(initialHeight * dimension));
            BufferedImage resized = resize(source, width, height, "jpeg".equals(format));
            if ("jpeg".equals(format)) {
                for (float quality : jpegQualities) {
                    byte[] encoded = encodeJpeg(resized, quality);
                    smallest = smaller(smallest, encoded);
                    if (encoded.length <= targetBytes) {
                        return encoded;
                    }
                }
            } else {
                byte[] encoded = encodePng(resized);
                smallest = smaller(smallest, encoded);
                if (encoded.length <= targetBytes) {
                    return encoded;
                }
            }
        }
        return smallest == null ? new byte[0] : smallest;
    }

    private static BufferedImage resize(BufferedImage source, int width, int height,
                                        boolean opaque) {
        BufferedImage output = new BufferedImage(width, height,
                opaque ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = output.createGraphics();
        try {
            if (opaque) {
                graphics.setColor(java.awt.Color.WHITE);
                graphics.fillRect(0, 0, width, height);
            }
            graphics.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING,
                    java.awt.RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        return output;
    }

    private static byte[] encodeJpeg(BufferedImage image, float quality) {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        try (ImageOutputStream output = ImageIO.createImageOutputStream(encoded)) {
            writer.setOutput(output);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            writer.write(null, new IIOImage(image, null, null), param);
            return encoded.toByteArray();
        } catch (IOException e) {
            throw new InvalidFileException("图片重新编码失败");
        } finally {
            writer.dispose();
        }
    }

    private static byte[] encodePng(BufferedImage image) {
        try {
            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", encoded)) {
                throw new InvalidFileException("图片重新编码失败");
            }
            return encoded.toByteArray();
        } catch (IOException e) {
            throw new InvalidFileException("图片重新编码失败");
        }
    }

    private static byte[] smaller(byte[] current, byte[] candidate) {
        return current == null || candidate.length < current.length ? candidate : current;
    }

    private static String mimeTypeFor(String extension) {
        return ".jpg".equals(extension) ? JPEG_MIME : PNG_MIME;
    }

    private static byte[] headOf(byte[] content) {
        byte[] head = new byte[Math.min(16, content.length)];
        System.arraycopy(content, 0, head, 0, head.length);
        return head;
    }

    private static String trimTrailingSlash(String value) {
        String result = value;
        while (result.length() > 1 && result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    public record StoredImage(String url, String storedName, String mimeType, long size) {
    }

    public record PublicImage(byte[] bytes, String mimeType) {
    }

    private enum ImageVariant {
        THUMB("thumb", 480, 480, 80 * 1024L),
        DISPLAY("display", 1200, 1200, 250 * 1024L),
        DETAIL("detail", 1080, 16000, 350 * 1024L);

        private final String key;
        private final int maxWidth;
        private final int maxHeight;
        private final long targetBytes;

        ImageVariant(String key, int maxWidth, int maxHeight, long targetBytes) {
            this.key = key;
            this.maxWidth = maxWidth;
            this.maxHeight = maxHeight;
            this.targetBytes = targetBytes;
        }

        private String key() {
            return key;
        }

        private int maxWidth() {
            return maxWidth;
        }

        private int maxHeight() {
            return maxHeight;
        }

        private long targetBytes() {
            return targetBytes;
        }

        private static ImageVariant parse(String value) {
            String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
            for (ImageVariant variant : values()) {
                if (variant.key.equals(normalized)) {
                    return variant;
                }
            }
            throw new InvalidImageVariantException("不支持的图片规格");
        }
    }

    public static class InvalidFilePathException extends RuntimeException {
        public InvalidFilePathException(String message) {
            super(message);
        }
    }

    public static class ImageNotFoundException extends RuntimeException {
        public ImageNotFoundException(String message) {
            super(message);
        }
    }

    public static class InvalidImageVariantException extends RuntimeException {
        public InvalidImageVariantException(String message) {
            super(message);
        }
    }
}
