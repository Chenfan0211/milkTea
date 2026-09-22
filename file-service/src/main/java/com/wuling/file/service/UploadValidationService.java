package com.wuling.file.service;

import com.wuling.file.security.FileTypeValidator;
import com.wuling.file.security.FileTypeValidator.FileType;
import com.wuling.file.security.FileTypeValidator.InvalidFileException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 上传校验服务。
 *
 * <p>防护流程（对齐改造方案第五章）：
 * <ol>
 *   <li>大小限制（图片默认 5MB）；</li>
 *   <li>文件头判定真实类型，并校验与扩展名一致；</li>
 *   <li>重命名为 UUID（防路径穿越、防覆盖、剥离原始文件名中的危险字符）；</li>
 *   <li>返回安全的存储名。</li>
 * </ol>
 *
 * <p><b>尚未实现（后续阶段）</b>：图片二次编码（重绘剥离 Polyglot 载荷）、
 * 对象存储上传、ClamAV 病毒扫描。<b>在这些补齐前，本服务不应对外暴露上传接口。</b>
 */
@Service
public class UploadValidationService {

    private static final Logger log = LoggerFactory.getLogger(UploadValidationService.class);

    private final long maxSizeBytes;

    public UploadValidationService(@Value("${app.file.max-size-bytes:5242880}") long maxSizeBytes) {
        this.maxSizeBytes = maxSizeBytes;
    }

    /**
     * 校验上传内容并生成安全存储信息。
     *
     * @param originalFilename 原始文件名（仅用于取扩展名）
     * @param content          文件内容
     * @param declaredType     客户端声明的 Content-Type（仅记录，不参与判定）
     */
    public ValidatedUpload validate(String originalFilename, byte[] content, String declaredType) {
        if (content == null || content.length == 0) {
            throw new InvalidFileException("文件内容为空");
        }
        if (content.length > maxSizeBytes) {
            throw new InvalidFileException(
                    "文件超过大小限制（" + (maxSizeBytes / 1024 / 1024) + "MB）");
        }

        // 取前 16 字节做类型判定
        byte[] head = new byte[Math.min(16, content.length)];
        System.arraycopy(content, 0, head, 0, head.length);

        FileType type = FileTypeValidator.validate(originalFilename, head);
        String ext = FileTypeValidator.extensionOf(originalFilename);

        // 声明类型与真实类型不一致只记 warn（客户端可能不传或被伪造，不作为拒绝依据）
        if (declaredType != null && !declaredType.isBlank()
                && !declaredType.equalsIgnoreCase(type.mimeType())) {
            log.warn("declared content-type mismatch: declared={} actual={} file={}",
                    declaredType, type.mimeType(), originalFilename);
        }

        String storedName = UUID.randomUUID().toString().replace("-", "") + "." + ext;
        log.info("upload validated file={} size={} type={} stored={}",
                originalFilename, content.length, type.mimeType(), storedName);
        return new ValidatedUpload(storedName, type.mimeType(), content.length);
    }

    /** 校验通过的上传结果 */
    public record ValidatedUpload(String storedName, String mimeType, long size) {
    }
}
