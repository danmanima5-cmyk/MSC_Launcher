import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

final class AccountStore {
    private static final Path LEGACY_ACCOUNT_FILE = LauncherSettings.settingsDirectory().resolve("account.properties");
    private static final Path ACCOUNTS_FILE = LauncherSettings.settingsDirectory().resolve("accounts.properties");

    private AccountStore() {
    }

    static Optional<Account> load() {
        return loadSelected();
    }

    static Optional<Account> loadSelected() {
        AccountData data = loadAccounts();
        if (data.accounts().isEmpty()) {
            return Optional.empty();
        }
        return data.accounts().stream()
                .filter(account -> account.key().equals(data.selectedKey()))
                .findFirst()
                .or(() -> Optional.of(data.accounts().get(0)));
    }

    static List<Account> loadAll() {
        return new ArrayList<>(loadAccounts().accounts());
    }

    static void save(Account account) {
        AccountData data = loadAccounts();
        List<Account> accounts = new ArrayList<>(data.accounts());
        int existing = indexOf(accounts, account);
        if (existing >= 0) {
            accounts.set(existing, account);
        } else {
            accounts.add(account);
        }
        writeAccounts(accounts, account.key());
    }

    static void select(Account account) {
        AccountData data = loadAccounts();
        List<Account> accounts = new ArrayList<>(data.accounts());
        if (indexOf(accounts, account) < 0) {
            accounts.add(account);
        }
        writeAccounts(accounts, account.key());
    }

    static void delete(Account account) {
        AccountData data = loadAccounts();
        List<Account> accounts = new ArrayList<>(data.accounts());
        accounts.removeIf(saved -> saved.equals(account));
        String selectedKey = accounts.isEmpty() ? "" : accounts.get(0).key();
        writeAccounts(accounts, selectedKey);
    }

    private static AccountData loadAccounts() {
        Optional<AccountData> saved = readAccountsFile();
        if (saved.isPresent()) {
            return saved.get();
        }
        Optional<Account> legacy = readLegacyAccountFile();
        return legacy.map(account -> new AccountData(List.of(account), account.key()))
                .orElseGet(() -> new AccountData(List.of(), ""));
    }

    private static Optional<AccountData> readAccountsFile() {
        if (!Files.isRegularFile(ACCOUNTS_FILE)) {
            return Optional.empty();
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(ACCOUNTS_FILE)) {
            properties.load(in);
            int count = parseInt(properties.getProperty("count"), 0);
            List<Account> accounts = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                readAccount(properties, "account." + i + ".").ifPresent(accounts::add);
            }
            if (accounts.isEmpty()) {
                return Optional.empty();
            }
            String selectedKey = properties.getProperty("selected", accounts.get(0).key());
            return Optional.of(new AccountData(accounts, normalizeSelectedKey(accounts, selectedKey)));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private static Optional<Account> readLegacyAccountFile() {
        if (!Files.isRegularFile(LEGACY_ACCOUNT_FILE)) {
            return Optional.empty();
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(LEGACY_ACCOUNT_FILE)) {
            properties.load(in);
            return readAccount(properties, "");
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private static Optional<Account> readAccount(Properties properties, String prefix) {
        try {
            Account.Type type = Account.Type.valueOf(properties.getProperty(prefix + "type", "OFFLINE"));
            String username = properties.getProperty(prefix + "username", "Player");
            if (type == Account.Type.OFFLINE) {
                return Optional.of(Account.offline(username));
            }
            Instant expiresAt = Instant.ofEpochSecond(parseLong(properties.getProperty(prefix + "expiresAt"), 0L));
            return Optional.of(new Account(
                    type,
                    username,
                    properties.getProperty(prefix + "uuid", ""),
                    properties.getProperty(prefix + "accessToken", ""),
                    properties.getProperty(prefix + "refreshToken", ""),
                    expiresAt,
                    properties.getProperty(prefix + "xuid", "")
            ));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private static void writeAccounts(List<Account> accounts, String selectedKey) {
        Properties properties = new Properties();
        properties.setProperty("format", "2");
        properties.setProperty("count", Integer.toString(accounts.size()));
        properties.setProperty("selected", normalizeSelectedKey(accounts, selectedKey));
        for (int i = 0; i < accounts.size(); i++) {
            writeAccount(properties, "account." + i + ".", accounts.get(i));
        }
        try {
            Files.createDirectories(LauncherSettings.settingsDirectory());
            try (OutputStream out = Files.newOutputStream(ACCOUNTS_FILE)) {
                properties.store(out, "MSC Launcher accounts. Tokens are stored locally in plain text.");
            }
        } catch (IOException ex) {
            throw new LauncherException("Не удалось сохранить аккаунты: " + ex.getMessage(), ex);
        }
    }

    private static void writeAccount(Properties properties, String prefix, Account account) {
        properties.setProperty(prefix + "type", account.type().name());
        properties.setProperty(prefix + "username", account.username());
        properties.setProperty(prefix + "uuid", account.uuid());
        properties.setProperty(prefix + "accessToken", account.accessToken());
        properties.setProperty(prefix + "refreshToken", account.refreshToken());
        properties.setProperty(prefix + "expiresAt", Long.toString(account.expiresAt().getEpochSecond()));
        properties.setProperty(prefix + "xuid", account.xuid());
    }

    private static int indexOf(List<Account> accounts, Account target) {
        for (int i = 0; i < accounts.size(); i++) {
            if (accounts.get(i).equals(target)) {
                return i;
            }
        }
        return -1;
    }

    private static String normalizeSelectedKey(List<Account> accounts, String selectedKey) {
        if (accounts.isEmpty()) {
            return "";
        }
        for (Account account : accounts) {
            if (account.key().equals(selectedKey)) {
                return selectedKey;
            }
        }
        return accounts.get(0).key();
    }

    private static int parseInt(String text, int fallback) {
        try {
            return Integer.parseInt(text);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static long parseLong(String text, long fallback) {
        try {
            return Long.parseLong(text);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private record AccountData(List<Account> accounts, String selectedKey) {
    }
}
