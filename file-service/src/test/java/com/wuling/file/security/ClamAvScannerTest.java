package com.wuling.file.security;

import com.wuling.file.security.ClamAvScanner.ScanException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ClamAV 客户端测试：验证 INSTREAM 协议交互与 fail-closed 策略 */
class ClamAvScannerTest {

    private static final String CLEAN_RESPONSE = "stream: OK\0";
    private static final String VIRUS_RESPONSE = "stream: Eicar-Test-Signature FOUND\0";
    private static final String ERROR_RESPONSE = "stream: ERROR\0";

    @Test
    void isCleanReturnsTrueWhenClamdSaysOk() throws Exception {
        AtomicReference<String> receivedCommand = new AtomicReference<>();
        try (FakeClamd clamd = new FakeClamd(CLEAN_RESPONSE, receivedCommand)) {
            ClamAvScanner scanner = new ClamAvScanner("127.0.0.1", clamd.port(), 2000, 4096);
            assertTrue(scanner.isClean(new ByteArrayInputStream("fake-image-bytes".getBytes())));
            assertTrue(receivedCommand.get().startsWith("nINSTREAM"),
                    "应发送 INSTREAM 命令，实际: " + receivedCommand.get());
        }
    }

    @Test
    void isCleanReturnsFalseWhenVirusFound() throws Exception {
        try (FakeClamd clamd = new FakeClamd(VIRUS_RESPONSE, new AtomicReference<>())) {
            ClamAvScanner scanner = new ClamAvScanner("127.0.0.1", clamd.port(), 2000, 4096);
            assertFalse(scanner.isClean(new ByteArrayInputStream("infected".getBytes())));
        }
    }

    @Test
    void isCleanThrowsOnClamdError() throws Exception {
        try (FakeClamd clamd = new FakeClamd(ERROR_RESPONSE, new AtomicReference<>())) {
            ClamAvScanner scanner = new ClamAvScanner("127.0.0.1", clamd.port(), 2000, 4096);
            assertThrows(ScanException.class,
                    () -> scanner.isClean(new ByteArrayInputStream("x".getBytes())));
        }
    }

    @Test
    void isCleanThrowsOnConnectionFailure() {
        // 连接一个未监听的端口，模拟 clamd 不可用（fail-closed）
        ClamAvScanner scanner = new ClamAvScanner("127.0.0.1", 1, 500, 4096);
        assertThrows(ScanException.class,
                () -> scanner.isClean(new ByteArrayInputStream("x".getBytes())));
    }

    /** 极简假 clamd：接受一个连接，读入请求，返回预设响应。 */
    private static final class FakeClamd implements AutoCloseable {
        private final ServerSocket server;
        private final String response;
        private final AtomicReference<String> receivedCommand;
        private final Thread thread;

        FakeClamd(String response, AtomicReference<String> receivedCommand) throws IOException {
            this.response = response;
            this.receivedCommand = receivedCommand;
            this.server = new ServerSocket(0);
            this.thread = new Thread(() -> {
                try (Socket socket = server.accept();
                     InputStream in = socket.getInputStream();
                     OutputStream out = socket.getOutputStream()) {
                    // 读命令行（nINSTREAM\n）
                    StringBuilder sb = new StringBuilder();
                    int b;
                    while ((b = in.read()) != -1) {
                        sb.append((char) b);
                        if (sb.toString().endsWith("\n")) {
                            break;
                        }
                    }
                    receivedCommand.set(sb.toString());
                    // 读 chunk 数据直到结束标记（长度 0），此处简单消费
                    // 客户端会发 4 字节长度 + 数据，最后 4 字节 0
                    // 简化：只读固定几个字节不阻塞即可，真实响应不依赖 body
                    out.write(response.getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                } catch (IOException ignored) {
                }
            });
            thread.setDaemon(true);
            thread.start();
        }

        int port() {
            return server.getLocalPort();
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }
}
