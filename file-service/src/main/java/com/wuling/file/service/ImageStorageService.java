package com.wuling.file.service;

import com.wuling.file.security.FileTypeValidator;
import com.wuling.file.security.FileTypeValidator.FileType;
import com.wuling.file.security.FileTypeValidator.InvalidFileException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;
import java.util.Locale;
import java.util.UUID;
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

    private final Path storageRoot;
    private final String publicBaseUrl;
    private final long maxSizeBytes;
    private final int maxDimension;
    private final long maxPixels;

    public ImageStorageService(
            @Value("${app.file.storage-root}") String storageRoot,
            @Value("${app.file.public-base-url}") String publicBaseUrl,
            @Value("${app.file.max-size-bytes:5242880}") long maxSizeBytes,
            @Value("${app.file.max-dimension:8000}") int maxDimension,
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

        BufferedImage decoded = decodeWithDimensionLimits(content);
        String suffix = JPEG_MIME.equals(type.mimeType()) ? "jpg" : "png";
        byte[] encoded = reencode(decoded, type);

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

    /**
     * 读取公开图片。存储名必须是服务生成的 UUID 文件名，拒绝任何路径片段。
     */
    public PublicImage loadPublicImage(String storedName) {
        if (storedName == null || !PUBLIC_NAME.matcher(storedName).matches()) {
            throw new InvalidFilePathException("非法文件路径");
        }

        Path candidate = storageRoot.resolve(storedName).normalize();
        if (!candidate.getParent().equals(storageRoot)
                || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
            throw new ImageNotFoundException("图片不存在");
        }

        try {
            Path realRoot = storageRoot.toRealPath();
            Path realFile = candidate.toRealPath();
            if (!realFile.startsWith(realRoot)) {
                throw new InvalidFilePathException("非法文件路径");
            }
            String mimeType = storedName.endsWith(".jpg") ? JPEG_MIME : PNG_MIME;
            return new PublicImage(Files.readAllBytes(realFile), mimeType);
        } catch (NoSuchFileException e) {
            throw new ImageNotFoundException("图片不存在");
        } catch (IOException e) {
            throw new IllegalStateException("图片读取失败: " + storedName, e);
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

    private byte[] reencode(BufferedImage image, FileType type) {
        String format = JPEG_MIME.equals(type.mimeType()) ? "jpeg" : "png";
        BufferedImage output = image;
        if ("jpeg".equals(format) && image.getType() != BufferedImage.TYPE_INT_RGB
                && image.getType() != BufferedImage.TYPE_3BYTE_BGR) {
            output = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = output.createGraphics();
            try {
                graphics.drawImage(image, 0, 0, null);
            } finally {
                graphics.dispose();
            }
        }

        try {
            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            if (!ImageIO.write(output, format, encoded)) {
                throw new InvalidFileException("图片重新编码失败");
            }
            return encoded.toByteArray();
        } catch (IOException e) {
            throw new InvalidFileException("图片重新编码失败");
        }
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
}