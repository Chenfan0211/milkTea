package com.wuling.file.service;

import com.wuling.file.security.FileTypeValidator.InvalidFileException;
import com.wuling.file.service.ImageStorageService.ImageNotFoundException;
import com.wuling.file.service.ImageStorageService.InvalidFilePathException;
import com.wuling.file.service.ImageStorageService.PublicImage;
import com.wuling.file.service.ImageStorageService.StoredImage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageStorageServiceTest {

    private static final long MAX_SIZE = 5L * 1024 * 1024;
    private static final int MAX_DIMENSION = 8000;
    private static final long MAX_PIXELS = 40_000_000L;
    private static final String PUBLIC_BASE = "/api/v1/files/public";

    @TempDir
    Path storageRoot;

    private ImageStorageService service;

    @BeforeEach
    void setUp() {
        service = new ImageStorageService(storageRoot.toString(), PUBLIC_BASE, MAX_SIZE,
                MAX_DIMENSION, MAX_PIXELS);
    }

    @Test
    void storeJpegReencodesAsJpgAndReturnsPublicMetadata() throws IOException {
        byte[] original = imageBytes("jpeg", 320, 180);

        StoredImage stored = service.store("photo.jpeg", original, "image/jpeg");

        assertTrue(stored.storedName().matches("[0-9a-f]{32}\\.jpg"));
        assertEquals("image/jpeg", stored.mimeType());
        assertEquals(PUBLIC_BASE + "/" + stored.storedName(), stored.url());
        assertTrue(stored.size() > 0);
        Path saved = storageRoot.resolve(stored.storedName());
        assertTrue(Files.isRegularFile(saved));
        assertEquals(stored.size(), Files.size(saved));
        assertNotNull(ImageIO.read(saved.toFile()));
    }

    @Test
    void storePngStripsEmbeddedTextMetadata() throws IOException {
        String marker = "secret-metadata-value";
        byte[] original = pngWithTextMetadata(marker);
        assertTrue(contains(original, marker.getBytes(StandardCharsets.US_ASCII)));

        StoredImage stored = service.store("photo.png", original, "image/png");

        byte[] saved = Files.readAllBytes(storageRoot.resolve(stored.storedName()));
        assertTrue(stored.storedName().endsWith(".png"));
        assertEquals("image/png", stored.mimeType());
        assertFalse(contains(saved, marker.getBytes(StandardCharsets.US_ASCII)));
        assertArrayEquals(original, original, "原图字节仅用于确认测试样本包含元数据");
    }

    @Test
    void storeRejectsFakeImageWhoseExtensionLooksValid() {
        byte[] fake = "not an image".getBytes(StandardCharsets.UTF_8);

        assertThrows(InvalidFileException.class,
                () -> service.store("fake.jpg", fake, "image/jpeg"));
    }

    @Test
    void storeRejectsFileOverMaxSize() {
        byte[] tooLarge = new byte[(int) MAX_SIZE + 1];
        tooLarge[0] = (byte) 0xFF;
        tooLarge[1] = (byte) 0xD8;
        tooLarge[2] = (byte) 0xFF;

        assertThrows(InvalidFileException.class,
                () -> service.store("large.jpg", tooLarge, "image/jpeg"));
    }

    @Test
    void storeRejectsLongEdgeOverLimitBeforeDecodingPixels() throws IOException {
        byte[] image = imageBytes("png", MAX_DIMENSION + 1, 1);

        assertThrows(InvalidFileException.class,
                () -> service.store("wide.png", image, "image/png"));
    }

    @Test
    void storeRejectsTotalPixelsOverLimitBeforeDecodingPixels() throws IOException {
        byte[] headerOnly = pngHeader(8000, 5001);

        assertThrows(InvalidFileException.class,
                () -> service.store("huge.png", headerOnly, "image/png"));
    }

    @Test
    void storeAlwaysUsesNewUuidAndKeepsPreviousFile() throws IOException {
        byte[] image = imageBytes("png", 20, 20);

        StoredImage first = service.store("same.png", image, "image/png");
        StoredImage second = service.store("same.png", image, "image/png");

        assertNotEquals(first.storedName(), second.storedName());
        assertTrue(Files.isRegularFile(storageRoot.resolve(first.storedName())));
        assertTrue(Files.isRegularFile(storageRoot.resolve(second.storedName())));
    }

    @Test
    void loadPublicImageReturnsExactStoredBytesAndMimeType() throws IOException {
        StoredImage stored = service.store("photo.png", imageBytes("png", 16, 16), "image/png");

        PublicImage loaded = service.loadPublicImage(stored.storedName());

        assertEquals("image/png", loaded.mimeType());
        assertArrayEquals(Files.readAllBytes(storageRoot.resolve(stored.storedName())), loaded.bytes());
    }

    @Test
    void loadPublicImageRejectsTraversalAbsoluteAndBackslashPaths() throws IOException {
        StoredImage stored = service.store("safe.png", imageBytes("png", 8, 8), "image/png");

        assertThrows(InvalidFilePathException.class,
                () -> service.loadPublicImage("../" + stored.storedName()));
        assertThrows(InvalidFilePathException.class,
                () -> service.loadPublicImage("..\\" + stored.storedName()));
        assertThrows(InvalidFilePathException.class,
                () -> service.loadPublicImage("/" + stored.storedName()));
        assertThrows(InvalidFilePathException.class,
                () -> service.loadPublicImage("C:\\" + stored.storedName()));
        assertThrows(InvalidFilePathException.class,
                () -> service.loadPublicImage("nested/" + stored.storedName()));
    }

    @Test
    void loadPublicImageRejectsArbitraryExistingFile() throws IOException {
        Files.writeString(storageRoot.resolve("secret.txt"), "not public");

        assertThrows(InvalidFilePathException.class,
                () -> service.loadPublicImage("secret.txt"));
    }

    @Test
    void loadPublicImageReturnsNotFoundForMissingSafeName() {
        assertThrows(ImageNotFoundException.class,
                () -> service.loadPublicImage("0123456789abcdef0123456789abcdef.jpg"));
    }

    private static byte[] imageBytes(String format, int width, int height) throws IOException {
        int imageType = "png".equals(format) ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage image = new BufferedImage(width, height, imageType);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.BLUE);
            graphics.fillRect(0, 0, width, height);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, format, output), "测试图片编码失败: " + format);
        return output.toByteArray();
    }

    private static byte[] pngWithTextMetadata(String value) throws IOException {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("png");
        ImageWriter writer = writers.next();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ImageOutputStream imageOutput = ImageIO.createImageOutputStream(output)) {
            writer.setOutput(imageOutput);
            ImageWriteParam param = writer.getDefaultWriteParam();
            IIOMetadata metadata = writer.getDefaultImageMetadata(
                    ImageTypeSpecifier.createFromRenderedImage(image), param);
            String format = metadata.getNativeMetadataFormatName();
            IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(format);
            IIOMetadataNode text = new IIOMetadataNode("tEXt");
            IIOMetadataNode entry = new IIOMetadataNode("tEXtEntry");
            entry.setAttribute("keyword", "Comment");
            entry.setAttribute("value", value);
            text.appendChild(entry);
            root.appendChild(text);
            metadata.setFromTree(format, root);
            writer.write(null, new IIOImage(image, null, metadata), param);
        } finally {
            writer.dispose();
        }
        return output.toByteArray();
    }

    private static byte[] pngHeader(int width, int height) throws IOException {
        byte[] signature = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        byte[] type = "IHDR".getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream ihdrBytes = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(ihdrBytes)) {
            data.writeInt(width);
            data.writeInt(height);
            data.writeByte(8);
            data.writeByte(2);
            data.writeByte(0);
            data.writeByte(0);
            data.writeByte(0);
        }
        byte[] ihdr = ihdrBytes.toByteArray();

        CRC32 crc = new CRC32();
        crc.update(type);
        crc.update(ihdr);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(output)) {
            data.write(signature);
            data.writeInt(ihdr.length);
            data.write(type);
            data.write(ihdr);
            data.writeInt((int) crc.getValue());
            data.writeInt(0);
            byte[] iendType = "IEND".getBytes(StandardCharsets.US_ASCII);
            data.write(iendType);
            CRC32 iendCrc = new CRC32();
            iendCrc.update(iendType);
            data.writeInt((int) iendCrc.getValue());
        }
        return output.toByteArray();
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        if (needle.length == 0 || haystack.length < needle.length) {
            return false;
        }
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}