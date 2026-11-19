import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 一次性运维工具：压缩文件服务历史图片。
 *
 * 仅处理服务生成的 UUID 文件名。JPEG 优先降低编码质量，仍超目标时再逐步
 * 缩小尺寸；PNG 保留 PNG 格式和透明通道。写入使用同目录临时文件加原子替换，
 * 且只有产物更小时才覆盖原文件。
 */
public final class ImageBatchOptimizer {

    private static final Pattern PUBLIC_NAME =
            Pattern.compile("^[0-9a-f]{32}\\.(jpg|png)$");
    private static final int DEFAULT_MAX_BYTES = 500 * 1024;
    private static final double[] DIMENSION_FACTORS =
            new double[]{1.0, 0.9, 0.8, 0.7, 0.6, 0.5, 0.4, 0.3};
    private static final float[] JPEG_QUALITIES =
            new float[]{0.86f, 0.78f, 0.7f, 0.62f, 0.54f, 0.46f};

    private ImageBatchOptimizer() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("用法: ImageBatchOptimizer <图片目录> [--max-bytes=512000] [--dry-run]");
            System.exit(2);
        }
        Path directory = Path.of(args[0]).toAbsolutePath().normalize();
        int maxBytes = DEFAULT_MAX_BYTES;
        boolean dryRun = false;
        for (int index = 1; index < args.length; index++) {
            String argument = args[index];
            if ("--dry-run".equals(argument)) {
                dryRun = true;
            } else if (argument.startsWith("--max-bytes=")) {
                maxBytes = Integer.parseInt(argument.substring("--max-bytes=".length()));
            } else {
                throw new IllegalArgumentException("未知参数: " + argument);
            }
        }
        run(directory, maxBytes, dryRun);
    }

    private static void run(Path directory, int maxBytes, boolean dryRun) throws IOException {
        if (!Files.isDirectory(directory)) {
            throw new IllegalArgumentException("图片目录不存在: " + directory);
        }
        List<Path> candidates = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path path : stream) {
                if (Files.isRegularFile(path) && PUBLIC_NAME.matcher(path.getFileName().toString()).matches()) {
                    candidates.add(path);
                }
            }
        }
        candidates.sort(Comparator.comparingLong(ImageBatchOptimizer::sizeSafely).reversed());

        long beforeTotal = 0;
        long afterTotal = 0;
        int processed = 0;
        int replaced = 0;
        int skipped = 0;
        int failed = 0;
        int aboveTarget = 0;

        for (Path path : candidates) {
            long originalSize = Files.size(path);
            beforeTotal += originalSize;
            afterTotal += originalSize;
            if (originalSize <= maxBytes) {
                skipped++;
                continue;
            }
            processed++;
            try {
                byte[] original = Files.readAllBytes(path);
                BufferedImage image = decode(original);
                String format = path.getFileName().toString().endsWith(".jpg") ? "jpeg" : "png";
                byte[] optimized = compress(image, format, maxBytes);
                if (optimized.length >= originalSize) {
                    skipped++;
                    System.out.printf("SKIP %s original=%d optimized=%d%n",
                            path.getFileName(), originalSize, optimized.length);
                    continue;
                }
                validate(optimized, image.getWidth(), image.getHeight());
                long saved = originalSize - optimized.length;
                afterTotal -= saved;
                if (optimized.length > maxBytes) {
                    aboveTarget++;
                }
                System.out.printf("%s %s original=%d optimized=%d saved=%d%n",
                        dryRun ? "WOULD_COMPRESS" : "COMPRESS",
                        path.getFileName(), originalSize, optimized.length, saved);
                if (!dryRun) {
                    replaceAtomically(directory, path, optimized);
                    replaced++;
                }
            } catch (Exception error) {
                failed++;
                System.err.printf("FAIL %s error=%s%n", path.getFileName(), error.getMessage());
            }
        }

        System.out.println("SUMMARY "
                + "dryRun=" + dryRun
                + " files=" + candidates.size()
                + " processed=" + processed
                + " replaced=" + replaced
                + " skipped=" + skipped
                + " failed=" + failed
                + " aboveTarget=" + aboveTarget
                + " beforeBytes=" + beforeTotal
                + " projectedBytes=" + afterTotal
                + " savedBytes=" + (beforeTotal - afterTotal));
    }

    private static BufferedImage decode(byte[] content) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(content));
            if (image == null) {
                throw new IllegalArgumentException("图片无法解码");
            }
            return image;
        } catch (IOException error) {
            throw new IllegalArgumentException("图片无法解码", error);
        }
    }

    private static byte[] compress(BufferedImage source, String format, int targetBytes) {
        byte[] smallest = null;
        for (double factor : DIMENSION_FACTORS) {
            int width = Math.max(1, (int) Math.round(source.getWidth() * factor));
            int height = Math.max(1, (int) Math.round(source.getHeight() * factor));
            BufferedImage resized = resize(source, width, height, "jpeg".equals(format));
            if ("jpeg".equals(format)) {
                for (float quality : JPEG_QUALITIES) {
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
        if (smallest == null) {
            throw new IllegalStateException("没有可用的压缩结果");
        }
        return smallest;
    }

    private static BufferedImage resize(BufferedImage source, int width, int height,
                                        boolean opaque) {
        BufferedImage output = new BufferedImage(
                width, height, opaque ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = output.createGraphics();
        try {
            if (opaque) {
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, width, height);
            }
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
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
            ImageWriteParam parameter = writer.getDefaultWriteParam();
            parameter.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            parameter.setCompressionQuality(quality);
            writer.write(null, new IIOImage(image, null, null), parameter);
            return encoded.toByteArray();
        } catch (IOException error) {
            throw new IllegalStateException("JPEG 编码失败", error);
        } finally {
            writer.dispose();
        }
    }

    private static byte[] encodePng(BufferedImage image) {
        try {
            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", encoded)) {
                throw new IllegalStateException("PNG 编码器不可用");
            }
            return encoded.toByteArray();
        } catch (IOException error) {
            throw new IllegalStateException("PNG 编码失败", error);
        }
    }

    private static void validate(byte[] optimized, int originalWidth, int originalHeight) {
        BufferedImage decoded = decode(optimized);
        double originalRatio = (double) originalWidth / originalHeight;
        double optimizedRatio = (double) decoded.getWidth() / decoded.getHeight();
        if (Math.abs(originalRatio - optimizedRatio) > Math.max(0.01, originalRatio * 0.01)) {
            throw new IllegalStateException(
                    "压缩后宽高比异常: " + decoded.getWidth() + "x" + decoded.getHeight());
        }
    }

    private static void replaceAtomically(Path directory, Path target, byte[] content)
            throws IOException {
        Path temporary = Files.createTempFile(
                directory, target.getFileName().toString() + "-", ".tmp");
        try {
            Files.write(temporary, content);
            try {
                Files.setPosixFilePermissions(
                        temporary, PosixFilePermissions.fromString("rw-r--r--"));
            } catch (UnsupportedOperationException ignored) {
                // 非 POSIX 文件系统无需设置权限。
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static byte[] smaller(byte[] current, byte[] candidate) {
        return current == null || candidate.length < current.length ? candidate : current;
    }

    private static long sizeSafely(Path path) {
        try {
            return Files.size(path);
        } catch (IOException ignored) {
            return 0;
        }
    }
}
