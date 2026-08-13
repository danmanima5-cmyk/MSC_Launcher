import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

final class Account {
    enum Type {
        OFFLINE,
        MICROSOFT,
        ELY_BY
    }

    private final Type type;
    private final String username;
    private final String uuid;
    private final String accessToken;
    private final String refreshToken;
    private final Instant expiresAt;
    private final String xuid;

    Account(Type type, String username, String uuid, String accessToken, String refreshToken, Instant expiresAt, String xuid) {
        this.type = type;
        this.username = username;
        this.uuid = uuid;
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.expiresAt = expiresAt;
        this.xuid = xuid;
    }

    static Account offline(String username) {
        String cleanName = username == null || username.isBlank() ? "Player" : username.trim();
        return new Account(Type.OFFLINE, cleanName, offlineUuid(cleanName), "0", "", Instant.EPOCH, "");
    }

    static String offlineUuid(String username) {
        String cleanName = username == null || username.isBlank() ? "Player" : username.trim();
        UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + cleanName).getBytes(StandardCharsets.UTF_8));
        return uuid.toString().replace("-", "");
    }

    Type type() {
        return type;
    }

    String username() {
        return username;
    }

    String uuid() {
        return uuid;
    }

    String accessToken() {
        return accessToken;
    }

    String refreshToken() {
        return refreshToken;
    }

    Instant expiresAt() {
        return expiresAt;
    }

    String xuid() {
        return xuid;
    }

    String key() {
        String id = uuid == null || uuid.isBlank() ? username : uuid;
        return type.name() + ":" + id.toLowerCase(Locale.ROOT);
    }

    String typeLabel() {
        return switch (type) {
            case MICROSOFT -> "Microsoft";
            case ELY_BY -> "Ely.by";
            case OFFLINE -> "offline/local";
        };
    }

    @Override
    public String toString() {
        return username + " (" + typeLabel() + ")";
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Account account && key().equals(account.key());
    }

    @Override
    public int hashCode() {
        return key().hashCode();
    }
}
