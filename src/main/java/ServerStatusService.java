import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Minimal client for the Minecraft "Server List Ping" (SLP) protocol.
 * <p>
 * Opens a short-lived TCP connection to a Java Edition server, performs the
 * modern (1.7+) status handshake and parses the JSON status response. This is
 * the same mechanism the vanilla multiplayer server list uses to show
 * online/offline state, the player count and the MOTD &mdash; no login or
 * join is required.
 */
final class ServerStatusService {

    /** Protocol version sent during the handshake. Servers ignore it for status requests. */
    private static final int HANDSHAKE_PROTOCOL_VERSION = 769; // 1.21.4

    record ServerStatus(boolean online, int onlinePlayers, int maxPlayers, String motd, String error) {
        static ServerStatus offline(String error) {
            return new ServerStatus(false, 0, 0, "", error);
        }
    }

    /**
     * Pings the given server and returns its current status. Never throws:
     * any connection or protocol error results in an "offline" status.
     */
    ServerStatus ping(String host, int port, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(timeoutMs);
            OutputStream out = socket.getOutputStream();
            DataInputStream in = new DataInputStream(socket.getInputStream());

            writePacket(out, handshakePayload(host, port));
            writePacket(out, new byte[]{0x00}); // status request, empty body

            int length = readVarInt(in);
            byte[] data = new byte[length];
            in.readFully(data);
            DataInputStream packet = new DataInputStream(new ByteArrayInputStream(data));
            readVarInt(packet); // packet id, expected 0x00 — not needed further
            String json = readString(packet);

            return parseStatus(json);
        } catch (IOException | RuntimeException ex) {
            return ServerStatus.offline(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
        }
    }

    private byte[] handshakePayload(String host, int port) throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        writeVarInt(payload, 0x00); // packet id: handshake
        writeVarInt(payload, HANDSHAKE_PROTOCOL_VERSION);
        writeString(payload, host);
        payload.write((port >>> 8) & 0xFF);
        payload.write(port & 0xFF);
        writeVarInt(payload, 1); // next state: 1 = status
        return payload.toByteArray();
    }

    private ServerStatus parseStatus(String json) {
        try {
            Map<String, Object> root = Json.object(Json.parse(json));
            Map<String, Object> players = Json.object(root, "players");
            int online = (int) Json.longValue(players, "online", 0);
            int max = (int) Json.longValue(players, "max", 0);
            String motd = extractMotd(root.get("description"));
            return new ServerStatus(true, online, max, motd, null);
        } catch (RuntimeException ex) {
            return ServerStatus.offline("bad status response");
        }
    }

    private String extractMotd(Object description) {
        if (description instanceof String text) {
            return text;
        }
        if (description instanceof Map<?, ?> map) {
            Object text = map.get("text");
            return text == null ? "" : String.valueOf(text);
        }
        return "";
    }

    // ── Minecraft protocol primitives (VarInt-prefixed strings, packet framing) ─────

    private void writePacket(OutputStream out, byte[] payload) throws IOException {
        ByteArrayOutputStream framed = new ByteArrayOutputStream();
        writeVarInt(framed, payload.length);
        framed.write(payload);
        out.write(framed.toByteArray());
        out.flush();
    }

    private void writeVarInt(OutputStream out, int value) throws IOException {
        while (true) {
            int chunk = value & 0x7F;
            value >>>= 7;
            if (value != 0) {
                out.write(chunk | 0x80);
            } else {
                out.write(chunk);
                return;
            }
        }
    }

    private void writeString(OutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }

    private int readVarInt(InputStream in) throws IOException {
        int value = 0;
        int position = 0;
        while (true) {
            int b = in.read();
            if (b < 0) {
                throw new IOException("Соединение закрыто во время чтения VarInt.");
            }
            value |= (b & 0x7F) << position;
            if ((b & 0x80) == 0) {
                return value;
            }
            position += 7;
            if (position >= 32) {
                throw new IOException("VarInt слишком длинный.");
            }
        }
    }

    private String readString(DataInputStream in) throws IOException {
        int length = readVarInt(in);
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
