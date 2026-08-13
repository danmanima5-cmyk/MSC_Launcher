import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Минимальный клиент Discord Rich Presence (Discord IPC), написанный без
 * внешних библиотек — только на стандартном JDK.
 * <p>
 * Работает по тому же протоколу, что и официальный discord-rpc: подключается
 * к локальному IPC-сокету Discord-клиента (именованный канал на Windows,
 * Unix-сокет на Linux/macOS), делает handshake и затем отправляет команды
 * {@code SET_ACTIVITY} с текущим статусом лаунчера.
 * <p>
 * Класс полностью самодостаточен: вся работа с сокетом идёт в собственном
 * фоновом потоке-демоне, а публичные методы ({@link #start()}, {@link #stop()},
 * {@link #updatePresence}, {@link #clearPresence()}) можно безопасно вызывать
 * из потока обработки событий Swing (EDT) — они лишь кладут запрос в очередь
 * и не блокируют интерфейс. Если Discord не запущен или недоступен, класс
 * тихо переходит в режим переподключения и не мешает работе лаунчера.
 */
final class DiscordRpcService {

    /** ID приложения Discord, зарегистрированного для этого лаунчера. */
    private static final String CLIENT_ID = "1519716068099358931";

    private static final long RECONNECT_DELAY_MS = 15_000L;
    private static final long IDLE_WAIT_MS = 15_000L;

    // Opcodes IPC-протокола Discord.
    private static final int OP_HANDSHAKE = 0;
    private static final int OP_FRAME = 1;
    private static final int OP_CLOSE = 2;

    /** Маркер "снять статус" — отличается от null, который означает "обновлений нет". */
    private static final Object CLEAR = new Object();

    private final Object lock = new Object();
    private Object pendingUpdate; // null | CLEAR | Map<String,Object> (тело activity)

    private volatile boolean running;
    private Thread worker;

    private final long startedAtEpochSeconds = System.currentTimeMillis() / 1000L;

    /** Запускает фоновый поток подключения к Discord. Повторные вызовы игнорируются. */
    synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        worker = new Thread(this::runLoop, "discord-rpc");
        worker.setDaemon(true);
        worker.start();
    }

    /** Останавливает клиент и закрывает соединение. Безопасно вызывать даже если не запущен. */
    synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        synchronized (lock) {
            lock.notifyAll();
        }
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
    }

    /**
     * Обновляет Discord Rich Presence.
     *
     * @param details      верхняя строка статуса (например, "В библиотеке версий")
     * @param state        нижняя строка статуса (например, "Играет: 1.20.1 Fabric")
     * @param showElapsed  показывать ли таймер "прошло времени" от момента запуска лаунчера
     */
    void updatePresence(String details, String state, boolean showElapsed) {
        updatePresence(details, state, showElapsed, null, null);
    }

    /**
     * Обновляет Discord Rich Presence с указанием картинки статуса.
     *
     * @param largeImageKey  ключ изображения из раздела Rich Presence Art Assets
     *                       Discord-приложения (может быть {@code null} — тогда без картинки)
     * @param largeImageText подсказка при наведении на большую картинку
     */
    void updatePresence(String details, String state, boolean showElapsed,
                         String largeImageKey, String largeImageText) {
        Map<String, Object> activity = new LinkedHashMap<>();
        if (details != null && !details.isBlank()) {
            activity.put("details", details);
        }
        if (state != null && !state.isBlank()) {
            activity.put("state", state);
        }
        if (showElapsed) {
            Map<String, Object> timestamps = new LinkedHashMap<>();
            timestamps.put("start", startedAtEpochSeconds);
            activity.put("timestamps", timestamps);
        }
        if (largeImageKey != null && !largeImageKey.isBlank()) {
            Map<String, Object> assets = new LinkedHashMap<>();
            assets.put("large_image", largeImageKey);
            if (largeImageText != null && !largeImageText.isBlank()) {
                assets.put("large_text", largeImageText);
            }
            activity.put("assets", assets);
        }
        queueUpdate(activity);
    }

    /** Полностью снимает статус активности (Discord перестаёт показывать "играет/использует ..."). */
    void clearPresence() {
        queueUpdate(CLEAR);
    }

    private void queueUpdate(Object value) {
        synchronized (lock) {
            pendingUpdate = value;
            lock.notifyAll();
        }
    }

    // ── Основной цикл подключения/переподключения ────────────────────────────

    private void runLoop() {
        while (running) {
            Transport transport = null;
            try {
                transport = connectAny();
                if (transport == null) {
                    sleepQuiet(RECONNECT_DELAY_MS);
                    continue;
                }
                handshake(transport);
                // Сразу выставляем то, что уже успели попросить обновить до подключения.
                sessionLoop(transport);
            } catch (IOException | RuntimeException ex) {
                // Discord закрыл канал / не ответил / соединение оборвалось — переподключимся позже.
            } finally {
                closeQuietly(transport);
            }
            if (running) {
                sleepQuiet(RECONNECT_DELAY_MS);
            }
        }
    }

    /** Держит соединение открытым и отправляет обновления статуса по мере их появления. */
    private void sessionLoop(Transport transport) throws IOException {
        while (running) {
            Object update;
            synchronized (lock) {
                while (running && pendingUpdate == null) {
                    try {
                        lock.wait(IDLE_WAIT_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                if (!running) {
                    return;
                }
                update = pendingUpdate;
                pendingUpdate = null;
            }
            sendActivity(transport, update == CLEAR ? null : castActivity(update));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castActivity(Object value) {
        return (Map<String, Object>) value;
    }

    private void sendActivity(Transport transport, Map<String, Object> activityOrNull) throws IOException {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("pid", ProcessHandle.current().pid());
        args.put("activity", activityOrNull);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("cmd", "SET_ACTIVITY");
        payload.put("args", args);
        payload.put("nonce", UUID.randomUUID().toString());

        writeFrame(transport, OP_FRAME, Json.stringify(payload));
    }

    private void handshake(Transport transport) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("v", 1);
        payload.put("client_id", CLIENT_ID);
        writeFrame(transport, OP_HANDSHAKE, Json.stringify(payload));
        // Читаем ответ (обычно DISPATCH/READY) — нужно только чтобы убедиться, что
        // канал жив и слить его буфер; содержимое нам не требуется.
        readFrame(transport);
    }

    // ── Кадры протокола: 4 байта opcode (LE) + 4 байта длины (LE) + payload ──

    private void writeFrame(Transport transport, int opcode, String jsonPayload) throws IOException {
        byte[] payload = jsonPayload.getBytes(StandardCharsets.UTF_8);
        ByteBuffer header = ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        header.putInt(opcode);
        header.putInt(payload.length);
        transport.write(header.array());
        transport.write(payload);
    }

    /** Читает один кадр целиком (используется только сразу после handshake). */
    private void readFrame(Transport transport) throws IOException {
        byte[] header = readFully(transport, 8);
        if (header == null) {
            return;
        }
        ByteBuffer buf = ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        buf.getInt(); // opcode — не используется
        int length = buf.getInt();
        if (length > 0) {
            readFully(transport, length);
        }
    }

    private byte[] readFully(Transport transport, int length) throws IOException {
        byte[] buf = new byte[length];
        int total = 0;
        while (total < length) {
            int read = transport.read(buf, total, length - total);
            if (read < 0) {
                return null; // канал закрыт
            }
            total += read;
        }
        return buf;
    }

    private static void sleepQuiet(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }

    // ── Транспорт: именованный канал Windows или Unix-сокет на Linux/macOS ──

    /** Абстракция над байтовым каналом до Discord-клиента. */
    private interface Transport extends Closeable {
        void write(byte[] data) throws IOException;
        int read(byte[] buffer, int offset, int length) throws IOException;
    }

    /** Пытается подключиться к одному из локальных IPC-каналов Discord (0..9). */
    private Transport connectAny() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        for (int i = 0; i < 10; i++) {
            Transport t = windows ? tryWindowsPipe(i) : tryUnixSocket(i);
            if (t != null) {
                return t;
            }
        }
        return null;
    }

    private Transport tryWindowsPipe(int index) {
        String path = "\\\\.\\pipe\\discord-ipc-" + index;
        try {
            RandomAccessFile file = new RandomAccessFile(path, "rw");
            return new Transport() {
                @Override public void write(byte[] data) throws IOException {
                    file.write(data);
                }
                @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                    return file.read(buffer, offset, length);
                }
                @Override public void close() throws IOException {
                    file.close();
                }
            };
        } catch (IOException ex) {
            return null; // Discord не запущен либо этот индекс канала занят/не существует
        }
    }

    private Transport tryUnixSocket(int index) {
        for (Path base : unixSocketDirectories()) {
            Path socketPath = base.resolve("discord-ipc-" + index);
            if (!Files.exists(socketPath)) {
                continue;
            }
            try {
                // Unix-domain channels were added after Java 8. Reflection keeps
                // this optional transport usable on newer JREs without making
                // the launcher class fail to load on Java 8.
                Class<?> protocolFamilyClass = Class.forName("java.net.ProtocolFamily");
                Class<?> standardProtocolFamilyClass = Class.forName("java.net.StandardProtocolFamily");
                @SuppressWarnings({"rawtypes", "unchecked"})
                Object unix = Enum.valueOf((Class<? extends Enum>) standardProtocolFamilyClass, "UNIX");
                SocketChannel channel = (SocketChannel) SocketChannel.class
                        .getMethod("open", protocolFamilyClass)
                        .invoke(null, unix);
                Class<?> addressClass = Class.forName("java.net.UnixDomainSocketAddress");
                SocketAddress address = (SocketAddress) addressClass
                        .getMethod("of", Path.class)
                        .invoke(null, socketPath);
                channel.connect(address);
                return new Transport() {
                    @Override public void write(byte[] data) throws IOException {
                        ByteBuffer buf = ByteBuffer.wrap(data);
                        while (buf.hasRemaining()) {
                            channel.write(buf);
                        }
                    }
                    @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                        ByteBuffer buf = ByteBuffer.wrap(buffer, offset, length);
                        return channel.read(buf);
                    }
                    @Override public void close() throws IOException {
                        channel.close();
                    }
                };
            } catch (Exception ex) {
                // пробуем следующую директорию/индекс
            }
        }
        return null;
    }

    /** Стандартные и flatpak/snap-варианты расположения discord-ipc-N на Linux/macOS. */
    private static List<Path> unixSocketDirectories() {
        List<Path> dirs = new ArrayList<>();
        String[] envVars = {"XDG_RUNTIME_DIR", "TMPDIR", "TMP", "TEMP"};
        for (String var : envVars) {
            String value = System.getenv(var);
            if (value != null && !value.isBlank()) {
                Path base = Path.of(value);
                dirs.add(base);
                dirs.add(base.resolve("app/com.discordapp.Discord")); // flatpak
                dirs.add(base.resolve("snap.discord"));               // snap
            }
        }
        dirs.add(Path.of("/tmp"));
        return dirs;
    }
}
