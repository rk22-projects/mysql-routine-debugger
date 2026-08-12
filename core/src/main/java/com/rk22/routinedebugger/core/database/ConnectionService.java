package com.rk22.routinedebugger.core.database;

import com.rk22.routinedebugger.core.DbgException;
import com.rk22.routinedebugger.core.DebugDeployment;
import com.rk22.routinedebugger.core.DebuggerService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Core-owned gateway for connection persistence, credentials and discovery.
 * Frontends deliberately do not access JDBC, files, preferences or the registry.
 */
public final class ConnectionService {
    private static final String DPAPI_PREFIX = "dpapi:";
    private static final String AES_PREFIX = "aes:";
    private static final Preferences PREFS = Preferences.userRoot()
        .node("com/rk22/mysql-routine-debugger/connection");
    private final Preferences preferences;

    public ConnectionService() {
        this(PREFS);
    }

    ConnectionService(Preferences preferences) {
        this.preferences = preferences;
    }

    public ConnectionProfile loadProfile() {
        String protectedPassword = preferences.get("password", "");
        String password = "";
        if (!protectedPassword.isEmpty()) {
            try {
                password = unprotect(protectedPassword);
            } catch (IOException | GeneralSecurityException ignored) {
                // A moved/corrupt credential is treated as absent, never as plaintext.
            }
        }
        int port = preferences.getInt("port", 3306);
        if (port <= 0 || port > 65535) port = 3306;
        return new ConnectionProfile(preferences.get("host", "localhost"),
            port,
            preferences.get("user", ""), password,
            preferences.get("database", ""));
    }

    /** Opens the connection and persists the profile only after authentication succeeds. */
    public Connection connect(ConnectionProfile profile) throws SQLException, IOException {
        Connection connection = DatabaseEngine.MYSQL.connect(profile.host(), profile.port(),
            profile.database(), profile.user(), profile.password());
        try {
            connection.setAutoCommit(true);
            saveProfile(profile);
            return connection;
        } catch (IOException | RuntimeException ex) {
            try { connection.close(); } catch (SQLException suppressed) { ex.addSuppressed(suppressed); }
            throw ex;
        }
    }

    /** Queries the server without selecting a catalog. */
    public List<String> discoverDatabases(ConnectionProfile profile) throws SQLException {
        List<String> databases = new ArrayList<>();
        try (Connection connection = DatabaseEngine.MYSQL.connect(profile.host(), profile.port(), "",
                                                               profile.user(), profile.password());
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SHOW DATABASES")) {
            while (rows.next()) databases.add(rows.getString(1));
        }
        return List.copyOf(databases);
    }

    /** Core gateway used for dedicated connections supplied by IDE integrations. */
    public Connection connectUrl(String url, String driverClass, Properties sourceProperties,
                                 String user, String password) throws Exception {
        if (url.startsWith("jdbc:mariadb:"))
            url = "jdbc:mysql:" + url.substring("jdbc:mariadb:".length());
        if (url.startsWith("jdbc:mysql:")) Class.forName("com.mysql.cj.jdbc.Driver");
        else if (driverClass != null && !driverClass.isBlank()) Class.forName(driverClass);
        Properties properties = new Properties();
        if (sourceProperties != null) properties.putAll(sourceProperties);
        if (user != null) properties.setProperty("user", user);
        if (password != null) properties.setProperty("password", password);
        Connection connection = DriverManager.getConnection(url, properties);
        connection.setAutoCommit(true);
        return connection;
    }

    /**
     * Performs the complete debugger shutdown sequence and always closes JDBC.
     * Any restore failure is retained, with a close failure attached as suppressed.
     */
    public void shutdown(DebuggerService debugger, DebugDeployment deployment,
                         Connection connection) throws DbgException {
        DbgException failure = null;
        try {
            if (debugger != null && deployment != null) debugger.stop(deployment);
        } catch (DbgException ex) {
            failure = ex;
        } finally {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException ex) {
                    DbgException closeFailure = new DbgException(
                        "Failed to close the database connection: " + ex.getMessage(), ex);
                    if (failure == null) failure = closeFailure;
                    else failure.addSuppressed(closeFailure);
                }
            }
        }
        if (failure != null) throw failure;
    }

    private void saveProfile(ConnectionProfile profile) throws IOException {
        preferences.remove("engine");
        preferences.put("host", profile.host());
        preferences.putInt("port", profile.port());
        preferences.put("user", profile.user());
        preferences.put("database", profile.database());
        if (profile.password().isEmpty()) preferences.remove("password");
        else {
            try {
                preferences.put("password", protect(profile.password()));
            } catch (GeneralSecurityException ex) {
                throw new IOException("Could not protect the database password", ex);
            }
        }
        try {
            preferences.flush();
        } catch (BackingStoreException ex) {
            throw new IOException("Could not save the connection profile", ex);
        }
    }

    private String protect(String password) throws IOException, GeneralSecurityException {
        if (isWindows()) return DPAPI_PREFIX + runDpapi(true,
            Base64.getEncoder().encodeToString(password.getBytes(StandardCharsets.UTF_8)));
        SecretKey key = aesKey();
        byte[] nonce = new byte[12];
        new SecureRandom().nextBytes(nonce);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce));
        byte[] encrypted = cipher.doFinal(password.getBytes(StandardCharsets.UTF_8));
        byte[] result = new byte[nonce.length + encrypted.length];
        System.arraycopy(nonce, 0, result, 0, nonce.length);
        System.arraycopy(encrypted, 0, result, nonce.length, encrypted.length);
        return AES_PREFIX + Base64.getEncoder().encodeToString(result);
    }

    private String unprotect(String value) throws IOException, GeneralSecurityException {
        if (value.startsWith(DPAPI_PREFIX)) {
            String clear = runDpapi(false, value.substring(DPAPI_PREFIX.length()));
            return new String(Base64.getDecoder().decode(clear), StandardCharsets.UTF_8);
        }
        if (!value.startsWith(AES_PREFIX)) return "";
        byte[] data = Base64.getDecoder().decode(value.substring(AES_PREFIX.length()));
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, aesKey(), new GCMParameterSpec(128, data, 0, 12));
        return new String(cipher.doFinal(data, 12, data.length - 12), StandardCharsets.UTF_8);
    }

    private SecretKey aesKey() throws GeneralSecurityException {
        String stored = preferences.get("credentialKey", "");
        if (stored.isEmpty()) {
            KeyGenerator generator = KeyGenerator.getInstance("AES");
            generator.init(256);
            stored = Base64.getEncoder().encodeToString(generator.generateKey().getEncoded());
            preferences.put("credentialKey", stored);
        }
        return new SecretKeySpec(Base64.getDecoder().decode(stored), "AES");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().startsWith("windows");
    }

    private static String runDpapi(boolean encrypt, String input) throws IOException {
        String operation = encrypt ? "Protect" : "Unprotect";
        String script = "$ErrorActionPreference='Stop';Add-Type -AssemblyName System.Security;" +
            "$v=[Console]::In.ReadToEnd();$b=[Convert]::FromBase64String($v);" +
            "$r=[Security.Cryptography.ProtectedData]::" + operation +
            "($b,$null,[Security.Cryptography.DataProtectionScope]::CurrentUser);" +
            "[Console]::Out.Write([Convert]::ToBase64String($r))";
        Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                                             "-Command", script).start();
        try (var stdin = process.getOutputStream()) {
            stdin.write(input.getBytes(StandardCharsets.US_ASCII));
        }
        try {
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.US_ASCII).trim();
            String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (process.waitFor() != 0 || output.isEmpty())
                throw new IOException("Windows credential protection failed" + (error.isEmpty() ? "" : ": " + error));
            return output;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("Credential protection was interrupted", ex);
        }
    }
}
