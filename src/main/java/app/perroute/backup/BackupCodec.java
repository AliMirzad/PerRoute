package app.perroute.backup;

import app.perroute.util.Json;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The outer layer of a backup file: a small JSON envelope around the payload.
 *
 * <p>Encrypted backups use AES-256-GCM with a key derived from the password by PBKDF2-HMAC-SHA256
 * ({@link #ITERATIONS} iterations, random 16-byte salt, random 12-byte nonce). Every header field is bound to the
 * ciphertext as associated data, so neither the payload nor the header can be changed without the
 * authentication check failing. Unencrypted backups carry a SHA-256 digest that detects damage, but not a
 * deliberate change.
 */
public final class BackupCodec {
    public static final String FORMAT = "perroute-backup";
    public static final int ENVELOPE_VERSION = 1;
    public static final int ITERATIONS = 600_000;
    public static final int MIN_PASSWORD_LENGTH = 8;
    /** Backups larger than this are refused before anything is parsed. */
    public static final int MAX_FILE_BYTES = 32 * 1024 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();

    private BackupCodec() {}

    /** What can be read from a backup without its password. */
    public record Header(int envelopeVersion, long createdAt, String application, int schema, boolean encrypted) {}

    public static final class BackupException extends Exception {
        public enum Reason { NOT_A_BACKUP, NEWER_FORMAT, PASSWORD_REQUIRED, WRONG_PASSWORD_OR_TAMPERED, DAMAGED }
        private final Reason reason;
        public BackupException(Reason reason, String message) { super(message); this.reason = reason; }
        public Reason reason() { return reason; }
    }

    public static byte[] encode(byte[] payload, int schema, String application, long createdAt, char[] password) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("format", FORMAT);
        envelope.put("version", ENVELOPE_VERSION);
        envelope.put("createdAt", createdAt);
        envelope.put("application", application);
        envelope.put("schema", schema);
        if (password == null || password.length == 0) {
            envelope.put("encrypted", false);
            envelope.put("sha256", sha256(payload));
            envelope.put("payload", Base64.getEncoder().encodeToString(payload));
        } else {
            if (password.length < MIN_PASSWORD_LENGTH) throw new IllegalArgumentException("Use a password of at least " + MIN_PASSWORD_LENGTH + " characters.");
            byte[] salt = random(16);
            byte[] nonce = random(12);
            envelope.put("encrypted", true);
            envelope.put("kdf", Map.of("name", "PBKDF2-HMAC-SHA256", "iterations", ITERATIONS, "salt", b64(salt)));
            envelope.put("cipher", Map.of("name", "AES-256-GCM", "nonce", b64(nonce)));
            byte[] key = derive(password, salt, ITERATIONS);
            try {
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
                cipher.updateAAD(associatedData(envelope));
                envelope.put("payload", b64(cipher.doFinal(payload)));
            } catch (GeneralSecurityException error) {
                throw new IllegalStateException("Could not encrypt the backup", error);
            } finally {
                Arrays.fill(key, (byte) 0);
            }
        }
        return (Json.stringify(envelope) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
    }

    public static Header header(byte[] file) throws BackupException {
        Map<String, Object> envelope = envelope(file);
        return new Header(integer(envelope, "version"), number(envelope, "createdAt"), text(envelope, "application"),
                integer(envelope, "schema"), Boolean.TRUE.equals(envelope.get("encrypted")));
    }

    /** Returns the payload after checking its integrity, decrypting it when the backup is encrypted. */
    @SuppressWarnings("unchecked")
    public static byte[] decode(byte[] file, char[] password) throws BackupException {
        Map<String, Object> envelope = envelope(file);
        byte[] payload;
        try { payload = Base64.getDecoder().decode(text(envelope, "payload")); }
        catch (IllegalArgumentException invalid) { throw new BackupException(BackupException.Reason.DAMAGED, "The backup payload is damaged."); }
        if (!Boolean.TRUE.equals(envelope.get("encrypted"))) {
            if (!MessageDigest.isEqual(sha256(payload).getBytes(StandardCharsets.US_ASCII), text(envelope, "sha256").getBytes(StandardCharsets.US_ASCII))) {
                throw new BackupException(BackupException.Reason.DAMAGED, "The backup is damaged: its checksum does not match.");
            }
            return payload;
        }
        if (password == null || password.length == 0) {
            throw new BackupException(BackupException.Reason.PASSWORD_REQUIRED, "This backup is encrypted. Enter its password.");
        }
        if (!(envelope.get("kdf") instanceof Map<?, ?> kdf) || !(envelope.get("cipher") instanceof Map<?, ?> cipherInfo)) {
            throw new BackupException(BackupException.Reason.DAMAGED, "The backup's encryption settings are missing.");
        }
        if (!"PBKDF2-HMAC-SHA256".equals(kdf.get("name")) || !"AES-256-GCM".equals(cipherInfo.get("name"))) {
            throw new BackupException(BackupException.Reason.NEWER_FORMAT, "The backup uses an encryption method this version does not know.");
        }
        int iterations = ((Number) kdf.get("iterations")).intValue();
        if (iterations < 100_000 || iterations > 10_000_000) {
            throw new BackupException(BackupException.Reason.DAMAGED, "The backup's key derivation settings are invalid.");
        }
        byte[] key = null;
        try {
            byte[] salt = Base64.getDecoder().decode(String.valueOf(kdf.get("salt")));
            byte[] nonce = Base64.getDecoder().decode(String.valueOf(cipherInfo.get("nonce")));
            if (salt.length < 16 || nonce.length != 12) throw new BackupException(BackupException.Reason.DAMAGED, "The backup's encryption settings are invalid.");
            key = derive(password, salt, iterations);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            Map<String, Object> header = new LinkedHashMap<>(envelope);
            header.remove("payload");
            cipher.updateAAD(associatedData(header));
            return cipher.doFinal(payload);
        } catch (AEADBadTagException wrong) {
            throw new BackupException(BackupException.Reason.WRONG_PASSWORD_OR_TAMPERED,
                    "The password is wrong, or the backup was changed after it was created.");
        } catch (GeneralSecurityException | IllegalArgumentException error) {
            throw new BackupException(BackupException.Reason.DAMAGED, "The backup could not be decrypted.");
        } finally {
            if (key != null) Arrays.fill(key, (byte) 0);
        }
    }

    private static Map<String, Object> envelope(byte[] file) throws BackupException {
        if (file == null || file.length == 0 || file.length > MAX_FILE_BYTES) {
            throw new BackupException(BackupException.Reason.NOT_A_BACKUP, "This is not a PerRoute backup.");
        }
        Map<String, Object> envelope;
        try { envelope = Json.object(new String(file, StandardCharsets.UTF_8)); }
        catch (RuntimeException invalid) { throw new BackupException(BackupException.Reason.NOT_A_BACKUP, "This is not a PerRoute backup."); }
        if (!FORMAT.equals(envelope.get("format"))) throw new BackupException(BackupException.Reason.NOT_A_BACKUP, "This is not a PerRoute backup.");
        if (integer(envelope, "version") > ENVELOPE_VERSION) {
            throw new BackupException(BackupException.Reason.NEWER_FORMAT, "This backup was made by a newer PerRoute. Update PerRoute to restore it.");
        }
        return envelope;
    }

    /** Every header field in a fixed order; the payload itself is excluded. */
    private static byte[] associatedData(Map<String, Object> header) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (String key : new String[]{"format", "version", "createdAt", "application", "schema", "encrypted", "kdf", "cipher"}) {
            Object value = header.get(key);
            if (value instanceof Map<?, ?> map) value = new java.util.TreeMap<>(map);
            copy.put(key, value);
        }
        return Json.stringify(copy).getBytes(StandardCharsets.UTF_8);
    }

    static byte[] derive(char[] password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("PBKDF2 is unavailable", error);
        } finally {
            spec.clearPassword();
        }
    }

    static String sha256(byte[] data) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); }
        catch (GeneralSecurityException error) { throw new IllegalStateException(error); }
    }

    private static byte[] random(int length) { byte[] value = new byte[length]; RANDOM.nextBytes(value); return value; }
    private static String b64(byte[] value) { return Base64.getEncoder().encodeToString(value); }
    private static String text(Map<String, Object> map, String key) { Object value = map.get(key); return value == null ? "" : String.valueOf(value); }
    private static int integer(Map<String, Object> map, String key) { return map.get(key) instanceof Number number ? number.intValue() : 0; }
    private static long number(Map<String, Object> map, String key) { return map.get(key) instanceof Number number ? number.longValue() : 0L; }
}
