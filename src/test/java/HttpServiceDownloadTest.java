import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class HttpServiceDownloadTest {
    @TempDir
    Path directory;

    @Test
    void failedValidationLeavesExistingTargetUntouchedAndCleansStagingFile() throws Exception {
        byte[] original = "existing mod".getBytes(StandardCharsets.UTF_8);
        byte[] invalid = "invalid download".getBytes(StandardCharsets.UTF_8);
        byte[] expected = "expected replacement".getBytes(StandardCharsets.UTF_8);
        Path target = directory.resolve("library.jar");
        Files.write(target, original);

        HttpServer server = serverReturning(invalid);
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/file";
            assertThrows(LauncherException.class, () -> new HttpService().download(
                    url, target, sha1(expected), expected.length, ProgressSink.NONE));
        } finally {
            server.stop(0);
        }

        assertArrayEquals(original, Files.readAllBytes(target));
        try (var files = Files.list(directory)) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void verifiedDownloadReplacesExistingTargetOnlyAfterValidation() throws Exception {
        byte[] replacement = "verified mod".getBytes(StandardCharsets.UTF_8);
        Path target = directory.resolve("library.jar");
        Files.writeString(target, "old mod", StandardCharsets.UTF_8);

        HttpServer server = serverReturning(replacement);
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/file";
            new HttpService().download(url, target, sha1(replacement),
                    replacement.length, ProgressSink.NONE);
        } finally {
            server.stop(0);
        }

        assertArrayEquals(replacement, Files.readAllBytes(target));
        assertTrue(Files.isRegularFile(target));
    }

    private HttpServer serverReturning(byte[] body) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/file", exchange -> {
            exchange.sendResponseHeaders(200, body.length);
            try (var response = exchange.getResponseBody()) {
                response.write(body);
            }
        });
        server.start();
        return server;
    }

    private String sha1(byte[] input) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-1").digest(input);
        StringBuilder value = new StringBuilder(digest.length * 2);
        for (byte item : digest) {
            value.append(String.format("%02x", item & 0xff));
        }
        return value.toString();
    }
}
