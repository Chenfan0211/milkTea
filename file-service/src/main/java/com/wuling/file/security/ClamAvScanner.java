package com.wuling.file.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * ClamAV 病毒扫描客户端（clamd INSTREAM 协议）。
 *
 * <p>通过 TCP 直连 clamd（默认 3310 端口），使用流式 INSTREAM 命令，
 * 无需在磁盘落临时文件、也无需把整个文件读入内存。
 *
 * <p>协议（见 ClamAV 官方文档 clamd man page）：
 * <pre>
 *   C: nINSTREAM\n
 *   C: <4 字节大端 chunk 长度><chunk 数据> ...（循环，直到发送完）
 *   C: <4 字节 0>（结束标记）
 *   S: stream: OK\0                       —— 无病毒
 *   S: stream: &lt;病毒名&gt; FOUND\0      —— 发现病毒
 *   S: stream: ERROR\0                   —— 扫描出错
 * </pre>
 *
 * <p><b>失败策略（fail-closed）</b>：连接失败、超时、响应异常一律抛
 * {@link ScanException}，由调用方决定拒绝上传，绝不静默放行。
 */
public class ClamAvScanner {

    private static final Logger log = LoggerFactory.getLogger(ClamAvScanner.class);

    /** clamd 主机（默认本机） */
    private final String host;
    /** clamd 端口（默认 3310） */
    private final int port;
    /** 连接/读写超时（毫秒） */
    private final int timeoutMillis;
    /** 流式分块大小（默认 4KB，避免单块过大占用内存） */
    private final int chunkSize;

    public ClamAvScanner(String host, int port, int timeoutMillis, int chunkSize) {
        this.host = host;
        this.port = port;
        this.timeoutMillis = timeoutMillis;
        this.chunkSize = chunkSize > 0 ? chunkSize : 4096;
    }

    /**
     * 扫描文件内容流（本方法不负责关闭输入流）。
     *
     * @param in 文件内容流
     * @return true 表示未发现病毒；false 表示发现病毒
     * @throws ScanException clamd 不可用或扫描过程出错（fail-closed）
     */
    public boolean isClean(InputStream in) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMillis);
            socket.setSoTimeout(timeoutMillis);
            try (OutputStream rawOut = socket.getOutputStream();
                 InputStream rawIn = socket.getInputStream()) {
                DataOutputStream out = new DataOutputStream(rawOut);
                // 发送 INSTREAM 命令
                out.write("nINSTREAM\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();

                // 流式发送文件内容
                byte[] buffer = new byte[chunkSize];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.writeInt(read);          // 4 字节大端长度
                    out.write(buffer, 0, read);  // chunk 数据
                }
                out.writeInt(0);                 // 结束标记
                out.flush();

                // 读取响应（以 NUL 结尾）
                String response = readNullTerminated(rawIn);
                return interpret(response);
            }
        } catch (IOException e) {
            log.warn("clamd 扫描失败（fail-closed 拒绝）: host={} port={} err={}",
                    host, port, e.getMessage());
            throw new ScanException("病毒扫描服务不可用，已拒绝上传", e);
        }
    }

    /** 解析 clamd 响应 */
    private boolean interpret(String response) {
        if (response == null || response.isBlank()) {
            throw new ScanException("病毒扫描服务返回空响应");
        }
        String r = response.trim();
        if (r.endsWith("OK")) {
            return true;
        }
        if (r.contains("FOUND")) {
            String virus = r.replace("stream:", "").replace("FOUND", "").trim();
            log.warn("clamd 发现病毒: {}", virus);
            return false;
        }
        // ERROR 或其它异常响应
        log.warn("clamd 异常响应: {}", r);
        throw new ScanException("病毒扫描服务异常响应: " + r);
    }

    /** 读取以 NUL(0x00) 结尾的响应字符串 */
    private static String readNullTerminated(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == 0) {
                break;
            }
            buffer.write(b);
        }
        return buffer.toString(StandardCharsets.US_ASCII);
    }

    /** 扫描失败（fail-closed 用） */
    public static class ScanException extends RuntimeException {
        public ScanException(String message) {
            super(message);
        }

        public ScanException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
