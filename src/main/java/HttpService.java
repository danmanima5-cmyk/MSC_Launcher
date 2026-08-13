import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

final class HttpService {
    // Cloudflare/edge/origin errors that are almost always transient (site is momentarily
    // overloaded or the origin didn't answer in time — e.g. optifine.net regularly returns
    // 522 for a few seconds under load). Worth a few retries before giving up.
    private static final java.util.Set<Integer> RETRYABLE_STATUS = java.util.Set.of(
            408, 429, 500, 502, 503, 504, 520, 521, 522, 523, 524, 525, 526, 527, 530);
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_BASE_DELAY_MS = 800;
    private static final long DOWNLOAD_STALL_TIMEOUT_SECONDS = 45;
    private static final java.util.concurrent.ScheduledExecutorService DOWNLOAD_WATCHDOG =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "msc-download-watchdog");
                thread.setDaemon(true);
                return thread;
            });

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .version(HttpClient.Version.HTTP_2)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    String getString(String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", "MSC-Launcher/1.0")
                .GET()
                .build();
        IOException lastIoError = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (RETRYABLE_STATUS.contains(response.statusCode()) && attempt < MAX_RETRIES) {
                    sleepBeforeRetry(attempt);
                    continue;
                }
                ensureSuccess(url, response.statusCode(), response.body());
                return response.body();
            } catch (IOException ex) {
                lastIoError = ex;
                if (attempt < MAX_RETRIES) {
                    sleepBeforeRetry(attempt);
                    continue;
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new LauncherException("Операция прервана.", ex);
            }
        }
        throw new LauncherException("Ошибка сети при GET " + url + ": " + (lastIoError == null ? "неизвестная ошибка" : lastIoError.getMessage()), lastIoError);
    }

    private void sleepBeforeRetry(int attempt) {
        try {
            Thread.sleep(RETRY_BASE_DELAY_MS * (1L << attempt));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new LauncherException("Операция прервана.", ie);
        }
    }

    Map<String, Object> getJsonObject(String url) {
        return Json.object(Json.parse(getString(url)));
    }

    Object getJson(String url) {
        return Json.parse(getString(url));
    }

    String postForm(String url, Map<String, String> form) {
        String body = form.entrySet().stream()
                .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(Collectors.joining("&"));
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", "MSC-Launcher/1.0")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            ensureSuccess(url, response.statusCode(), response.body());
            return response.body();
        } catch (IOException ex) {
            throw new LauncherException("Ошибка сети при POST " + url + ": " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new LauncherException("Операция прервана.", ex);
        }
    }

    String postJson(String url, Object body) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", "MSC-Launcher/1.0")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(body), StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            ensureSuccess(url, response.statusCode(), response.body());
            return response.body();
        } catch (IOException ex) {
            throw new LauncherException("Ошибка сети при POST " + url + ": " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new LauncherException("Операция прервана.", ex);
        }
    }

    String putAuthorizedJson(String url, Object body, String bearerToken) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", "MSC-Launcher/1.0")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json");
        if (bearerToken != null && !bearerToken.isBlank()) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        HttpRequest request = builder
                .PUT(HttpRequest.BodyPublishers.ofString(Json.stringify(body), StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            ensureSuccess(url, response.statusCode(), response.body());
            return response.body();
        } catch (IOException ex) {
            throw new LauncherException("Ошибка сети при PUT " + url + ": " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new LauncherException("Операция прервана.", ex);
        }
    }

    void deleteAuthorized(String url, String bearerToken) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", "MSC-Launcher/1.0");
        if (bearerToken != null && !bearerToken.isBlank()) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        HttpRequest request = builder.DELETE().build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            // 204 No Content is success for DELETE
            if (response.statusCode() != 204 && response.statusCode() != 200) {
                ensureSuccess(url, response.statusCode(), response.body());
            }
        } catch (IOException ex) {
            throw new LauncherException("Ошибка сети при DELETE " + url + ": " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new LauncherException("Операция прервана.", ex);
        }
    }

    String postMultipart(String url, java.nio.file.Path file, java.util.Map<String, String> fields, String bearerToken) {
        String boundary = "----MSCLauncherBoundary" + System.currentTimeMillis();
        String nl = "\r\n";
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            // fields
            for (java.util.Map.Entry<String, String> e : fields.entrySet()) {
                out.write(("--" + boundary + nl).getBytes(StandardCharsets.UTF_8));
                out.write(("Content-Disposition: form-data; name=\"" + e.getKey() + "\"" + nl + nl).getBytes(StandardCharsets.UTF_8));
                out.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                out.write(nl.getBytes(StandardCharsets.UTF_8));
            }
            // file
            out.write(("--" + boundary + nl).getBytes(StandardCharsets.UTF_8));
            String filename = file.getFileName().toString();
            out.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"" + nl).getBytes(StandardCharsets.UTF_8));
            out.write(("Content-Type: application/octet-stream" + nl + nl).getBytes(StandardCharsets.UTF_8));
            out.write(java.nio.file.Files.readAllBytes(file));
            out.write(nl.getBytes(StandardCharsets.UTF_8));
            out.write(("--" + boundary + "--" + nl).getBytes(StandardCharsets.UTF_8));

            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMinutes(2))
                    .header("User-Agent", "MSC-Launcher/1.0")
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray()));
            if (bearerToken != null && !bearerToken.isBlank()) {
                rb.header("Authorization", "Bearer " + bearerToken);
            }
            HttpRequest request = rb.build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            ensureSuccess(url, response.statusCode(), response.body());
            return response.body();
        } catch (IOException ex) {
            throw new LauncherException("Ошибка сети при multipart POST " + url + ": " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new LauncherException("Операция прервана.", ex);
        }
    }

    byte[] getBytes(String url) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", "MSC-Launcher/1.0")
                .GET()
                .build();
        try {
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            ensureSuccess(url, response.statusCode(), "");
            return response.body();
        } catch (IOException ex) {
            throw new LauncherException("Ошибка сети при GET байт " + url + ": " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new LauncherException("Операция прервана.", ex);
        }
    }

    byte[] getAuthorizedBytes(String url, String bearerToken) {
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", "MSC-Launcher/1.0")
                .GET();
        if (bearerToken != null && !bearerToken.isBlank()) {
            rb.header("Authorization", "Bearer " + bearerToken);
        }
        HttpRequest request = rb.build();
        try {
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            ensureSuccess(url, response.statusCode(), "");
            return response.body();
        } catch (IOException ex) {
            throw new LauncherException("Ошибка сети при GET байт " + url + ": " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new LauncherException("Операция прервана.", ex);
        }
    }

    Map<String, Object> getAuthorizedJsonObject(String url, String bearerToken) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", "MSC-Launcher/1.0")
                .header("Authorization", "Bearer " + bearerToken)
                .GET()
                .build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            ensureSuccess(url, response.statusCode(), response.body());
            return Json.object(Json.parse(response.body()));
        } catch (IOException ex) {
            throw new LauncherException("Ошибка сети при GET " + url + ": " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new LauncherException("Операция прервана.", ex);
        }
    }

    String getStringWithApiKey(String url, String apiKey) {
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", "MSC-Launcher/1.0");
        if (apiKey != null && !apiKey.isBlank()) {
            rb.header("x-api-key", apiKey);
        }
        HttpRequest request = rb.GET().build();
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            ensureSuccess(url, response.statusCode(), response.body());
            return response.body();
        } catch (IOException ex) {
            throw new LauncherException("Ошибка сети при GET " + url + ": " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new LauncherException("Операция прервана.", ex);
        }
    }

    Map<String, Object> getJsonObjectWithApiKey(String url, String apiKey) {
        return Json.object(Json.parse(getStringWithApiKey(url, apiKey)));
    }

    Object getJsonWithApiKey(String url, String apiKey) {
        return Json.parse(getStringWithApiKey(url, apiKey));
    }

    byte[] getBytesWithApiKey(String url, String apiKey) {
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", "MSC-Launcher/1.0");
        if (apiKey != null && !apiKey.isBlank()) {
            rb.header("x-api-key", apiKey);
        }
        HttpRequest request = rb.GET().build();
        try {
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            ensureSuccess(url, response.statusCode(), "");
            return response.body();
        } catch (IOException ex) {
            throw new LauncherException("Ошибка сети при GET байт " + url + ": " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new LauncherException("Операция прервана.", ex);
        }
    }

    /**
     * Opens a streaming GET connection, retrying a few times with backoff if the origin
     * returns a transient error (Cloudflare 522/524, 502/503/504, 429...) or the connection
     * itself fails. Returns the last response obtained even if it still has a bad status —
     * the caller's existing ensureSuccess() check will surface a proper error in that case.
     */
    private HttpResponse<InputStream> connectWithRetry(String url, HttpRequest request, ProgressSink progress) throws IOException, InterruptedException {
        HttpResponse<InputStream> response = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (IOException ex) {
                if (attempt == MAX_RETRIES) {
                    throw ex;
                }
                progress.log("Сетевая ошибка при подключении к " + url + ", повтор...");
                sleepBeforeRetry(attempt);
                continue;
            }
            if (RETRYABLE_STATUS.contains(response.statusCode()) && attempt < MAX_RETRIES) {
                progress.log("Сервер временно недоступен (HTTP " + response.statusCode() + "), повтор через несколько секунд: " + url);
                try {
                    response.body().close();
                } catch (IOException ignored) {
                    // best-effort cleanup of the discarded response body
                }
                sleepBeforeRetry(attempt);
                continue;
            }
            return response;
        }
        return response;
    }

    void download(String url, Path target, String sha1, long expectedSize, ProgressSink progress) {
        if (Files.isRegularFile(target) && fileLooksValid(target, sha1, expectedSize)) {
            progress.log("Уже есть: " + target.getFileName());
            return;
        }
        progress.status("Скачивание " + target.getFileName());
        String downloadName = target.getFileName().toString();
        progress.downloadStarted(downloadName);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(10))
                .header("User-Agent", "MSC-Launcher/1.0")
                .GET()
                .build();
        Path temp = null;
        try {
            Files.createDirectories(target.getParent());
            temp = Files.createTempFile(target.getParent(), ".msc-download-", ".part");
            boolean valid = false;
            for (int validationAttempt = 0; validationAttempt < 2; validationAttempt++) {
                IOException transferError = null;
                for (int transferAttempt = 0; transferAttempt <= MAX_RETRIES; transferAttempt++) {
                    try {
                        HttpResponse<InputStream> response = connectWithRetry(url, request, progress);
                        long total = response.headers().firstValueAsLong("Content-Length").orElse(expectedSize);
                        try (InputStream in = response.body();
                             OutputStream out = Files.newOutputStream(temp,
                                     StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                            ensureSuccess(url, response.statusCode(), "");
                            copyDownload(in, out, total, progress, downloadName);
                        }
                        transferError = null;
                        break;
                    } catch (IOException ex) {
                        transferError = ex;
                        if (transferAttempt < MAX_RETRIES) {
                            progress.log("Загрузка прервалась или зависла, повтор "
                                    + (transferAttempt + 1) + "/" + MAX_RETRIES + ": "
                                    + target.getFileName());
                            sleepBeforeRetry(transferAttempt);
                        }
                    }
                }
                if (transferError != null) {
                    throw transferError;
                }
                if (fileLooksValid(temp, sha1, expectedSize)) {
                    valid = true;
                    break;
                }
                progress.log("Проверка скачанного файла не пройдена, пробую повторно скачать: "
                        + target.getFileName());
            }
            if (!valid) {
                throw new LauncherException("Файл скачан, но не прошёл проверку: " + target);
            }
            replaceDownloadedFile(temp, target);
            temp = null;
            progress.log("Скачано: " + target.getFileName());
        } catch (IOException ex) {
            throw new LauncherException("Не удалось скачать " + url + ": " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new LauncherException("Операция прервана.", ex);
        } finally {
            progress.downloadFinished(downloadName);
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // The original target is untouched; stale staging cleanup is best effort.
                }
            }
        }
    }

    private void copyDownload(InputStream in, OutputStream out, long total, ProgressSink progress,
                              String downloadName)
            throws IOException {
        java.util.concurrent.atomic.AtomicLong lastDataAt =
                new java.util.concurrent.atomic.AtomicLong(System.nanoTime());
        java.util.concurrent.atomic.AtomicBoolean finished =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicBoolean stalled =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.ScheduledFuture<?> watchdog = DOWNLOAD_WATCHDOG.scheduleAtFixedRate(() -> {
            if (!finished.get()
                    && System.nanoTime() - lastDataAt.get()
                    >= java.util.concurrent.TimeUnit.SECONDS.toNanos(DOWNLOAD_STALL_TIMEOUT_SECONDS)) {
                stalled.set(true);
                try {
                    in.close();
                } catch (IOException ignored) {
                    // Closing is only used to unblock a stalled network read.
                }
            }
        }, 5, 5, java.util.concurrent.TimeUnit.SECONDS);
        try {
            byte[] buffer = new byte[512 * 1024];
            long done = 0;
            long lastProgressAt = 0;
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                lastDataAt.set(System.nanoTime());
                out.write(buffer, 0, read);
                done += read;
                long now = System.nanoTime();
                if (done == total || now - lastProgressAt > 120_000_000L) {
                    progress.progress(done, total);
                    progress.downloadProgress(downloadName, done, total);
                    lastProgressAt = now;
                }
            }
            if (stalled.get()) {
                throw new IOException("Сервер не передавал данные более "
                        + DOWNLOAD_STALL_TIMEOUT_SECONDS + " секунд.");
            }
        } finally {
            finished.set(true);
            watchdog.cancel(false);
        }
    }

    /** Commits a fully verified download without exposing a partial target file. */
    private void replaceDownloadedFile(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static Map<String, Object> map(Object... pairs) {
        LinkedHashMap<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return map;
    }

    private boolean fileLooksValid(Path file, String sha1, long expectedSize) {
        try {
            if (expectedSize > 0 && Files.size(file) != expectedSize) {
                return false;
            }
            return sha1 == null || sha1.isBlank() || sha1.equalsIgnoreCase(Hashing.sha1(file));
        } catch (IOException ex) {
            return false;
        }
    }

    private void ensureSuccess(String url, int status, String body) {
        if (status < 200 || status >= 300) {
            String suffix = body == null || body.isBlank() ? "" : " Ответ: " + trim(body, 500);
            throw new LauncherException("HTTP " + status + " для " + url + "." + suffix, status);
        }
    }

    private String trim(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
