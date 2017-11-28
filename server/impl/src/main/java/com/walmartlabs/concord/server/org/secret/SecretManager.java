package com.walmartlabs.concord.server.org.secret;

import com.google.common.base.Throwables;
import com.google.common.io.ByteStreams;
import com.walmartlabs.concord.common.secret.BinaryDataSecret;
import com.walmartlabs.concord.common.secret.KeyPair;
import com.walmartlabs.concord.common.secret.SecretStoreType;
import com.walmartlabs.concord.common.secret.UsernamePassword;
import com.walmartlabs.concord.sdk.Secret;
import com.walmartlabs.concord.server.api.org.ResourceAccessLevel;
import com.walmartlabs.concord.server.api.org.secret.SecretEntry;
import com.walmartlabs.concord.server.api.org.secret.SecretOwner;
import com.walmartlabs.concord.server.api.org.secret.SecretType;
import com.walmartlabs.concord.server.api.org.secret.SecretVisibility;
import com.walmartlabs.concord.server.cfg.SecretStoreConfiguration;
import com.walmartlabs.concord.server.org.secret.SecretDao.SecretDataEntry;
import com.walmartlabs.concord.server.security.UserPrincipal;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.shiro.authz.UnauthorizedException;
import org.sonatype.siesta.ValidationErrorsException;

import javax.inject.Inject;
import javax.inject.Named;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import static com.walmartlabs.concord.server.jooq.tables.Secrets.SECRETS;

@Named
public class SecretManager {

    private static final int SECRET_PASSWORD_LENGTH = 12;
    private static final String SECRET_PASSWORD_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789~`!@#$%^&*()-_=+[{]}|,<.>/?\\";

    private final SecretDao secretDao;
    private final SecretStoreConfiguration secretCfg;

    @Inject
    public SecretManager(SecretDao secretDao,
                         SecretStoreConfiguration secretCfg) {

        this.secretDao = secretDao;
        this.secretCfg = secretCfg;
    }

    public SecretEntry assertAccess(UUID orgId, String secretName, ResourceAccessLevel level, boolean orgMembersOnly) {
        SecretEntry e = secretDao.getByName(orgId, secretName);
        if (e == null) {
            throw new ValidationErrorsException("Secret not found: " + secretName);
        }

        UserPrincipal p = UserPrincipal.getCurrent();
        if (p.isAdmin()) {
            // an admin can access any secret
            return e;
        }

        SecretOwner owner = e.getOwner();
        if (owner != null && owner.getId().equals(p.getId())) {
            // the owner can do anything with his secrets
            return e;
        }

        if (orgMembersOnly || e.getVisibility() != SecretVisibility.PUBLIC) {
            if (!secretDao.hasAccessLevel(e.getId(), p.getId(), ResourceAccessLevel.atLeast(level))) {
                throw new UnauthorizedException("The current user doesn't have " +
                        "the necessary access level (" + level + ") to the secret: " + e.getName());
            }
        }

        return e;
    }

    public DecryptedKeyPair createKeyPair(UUID orgId, String name, String storePassword,
                                          SecretVisibility visibility) throws IOException {

        KeyPair k = KeyPairUtils.create();
        UUID id = store(name, orgId, k, storePassword, visibility);
        return new DecryptedKeyPair(id, k.getPublicKey());
    }

    public DecryptedKeyPair createKeyPair(UUID orgId, String name, String storePassword,
                                          InputStream publicKey, InputStream privateKey, SecretVisibility visibility) throws IOException {

        KeyPair k = KeyPairUtils.create(publicKey, privateKey);
        validate(k);

        UUID id = store(name, orgId, k, storePassword, visibility);
        return new DecryptedKeyPair(id, k.getPublicKey());
    }

    public DecryptedUsernamePassword createUsernamePassword(UUID orgId, String name, String storePassword,
                                                            String username, char[] password, SecretVisibility visibility) {

        UsernamePassword p = new UsernamePassword(username, password);
        UUID id = store(name, orgId, p, storePassword, visibility);
        return new DecryptedUsernamePassword(id);
    }

    public DecryptedBinaryData createBinaryData(UUID orgId, String name, String storePassword,
                                                InputStream data, SecretVisibility visibility) throws IOException {

        BinaryDataSecret d = new BinaryDataSecret(ByteStreams.toByteArray(data));
        UUID id = store(name, orgId, d, storePassword, visibility);
        return new DecryptedBinaryData(id);
    }

    public DecryptedKeyPair getKeyPair(UUID orgId, String name) {
        DecryptedSecret e = getSecret(orgId, name, null);
        if (e == null) {
            return null;
        }

        Secret s = e.getSecret();
        if (!(s instanceof KeyPair)) {
            throw new IllegalArgumentException("Invalid secret type: " + name + ", expected a key pair, got: " + e.getClass());
        }

        KeyPair k = (KeyPair) s;
        return new DecryptedKeyPair(e.getId(), k.getPublicKey());
    }

    public UUID getId(UUID orgId, String name) {
        return secretDao.getId(orgId, name);
    }

    public boolean exists(UUID orgId, String name) {
        return secretDao.getByName(orgId, name) != null;
    }

    public List<SecretEntry> list(UUID orgId) {
        return secretDao.list(orgId, SECRETS.SECRET_NAME, true);
    }

    public void delete(UUID secretId) {
        secretDao.delete(secretId);
    }

    public String generatePassword() {
        return RandomStringUtils.random(SECRET_PASSWORD_LENGTH, SECRET_PASSWORD_CHARS);
    }

    public DecryptedSecret getSecret(UUID orgId, String name, String password) {
        SecretDataEntry e = secretDao.getByName(orgId, name);
        if (e == null) {
            return null;
        }

        SecretStoreType providedStoreType = getStoreType(password);
        assertStoreType(name, providedStoreType, e.getStoreType());

        byte[] pwd = getPwd(password);
        byte[] salt = secretCfg.getSecretStoreSalt();

        Secret s = decrypt(e.getType(), e.getData(), pwd, salt);
        return new DecryptedSecret(e.getId(), s);
    }

    public SecretDataEntry getRaw(UUID orgId, String name, String password) {
        SecretDataEntry s = secretDao.getByName(orgId, name);
        if (s == null) {
            return null;
        }

        byte[] pwd = getPwd(password);
        byte[] salt = secretCfg.getSecretStoreSalt();

        try {
            byte[] ab = SecretUtils.decrypt(s.getData(), pwd, salt);
            return new SecretDataEntry(s, ab);
        } catch (GeneralSecurityException e) {
            throw new SecurityException("Error decrypting a secret: " + name);
        }
    }

    public KeyPair getKeyPair(UUID orgId, String name, String password) {
        DecryptedSecret e = getSecret(orgId, name, password);
        if (e == null) {
            return null;
        }

        Secret s = e.getSecret();
        if (!(s instanceof KeyPair)) {
            throw new IllegalArgumentException("Invalid secret type: " + name + ", expected a key pair, got: " + e.getClass());
        }

        return (KeyPair) s;
    }

    public UUID store(String name, UUID orgId, Secret s, String password, SecretVisibility visibility) {
        byte[] data;

        SecretType type;
        if (s instanceof KeyPair) {
            type = SecretType.KEY_PAIR;
            data = KeyPair.serialize((KeyPair) s);
        } else if (s instanceof UsernamePassword) {
            type = SecretType.USERNAME_PASSWORD;
            data = UsernamePassword.serialize((UsernamePassword) s);
        } else if (s instanceof BinaryDataSecret) {
            type = SecretType.DATA;
            data = ((BinaryDataSecret) s).getData();
        } else {
            throw new IllegalArgumentException("Unknown secret type: " + s.getClass());
        }

        SecretStoreType storeType = getStoreType(password);

        byte[] pwd = getPwd(password);
        byte[] salt = secretCfg.getSecretStoreSalt();

        byte[] ab;
        try {
            ab = SecretUtils.encrypt(data, pwd, salt);
        } catch (GeneralSecurityException e) {
            throw Throwables.propagate(e);
        }

        return secretDao.insert(orgId, name, getOwnerId(), type, storeType, visibility, ab);
    }

    public byte[] encryptData(String projectName, byte[] data) {
        byte[] pwd = projectName.getBytes();
        try {
            return SecretUtils.encrypt(data, pwd, secretCfg.getProjectSecretsSalt());
        } catch (GeneralSecurityException e) {
            throw Throwables.propagate(e);
        }
    }

    public byte[] decryptData(String projectName, byte[] data) {
        byte[] pwd = projectName.getBytes();
        try {
            return SecretUtils.decrypt(data, pwd, secretCfg.getProjectSecretsSalt());
        } catch (GeneralSecurityException e) {
            throw Throwables.propagate(e);
        }
    }

    private static void assertStoreType(String name, SecretStoreType provided, SecretStoreType actual) {
        if (provided == actual) {
            return;
        }

        switch (actual) {
            case SERVER_KEY:
                throw new SecurityException("Not a password-protected secret: " + name);
            case PASSWORD:
                throw new SecurityException("The secret requires a password to decrypt: " + name);
            default:
                throw new IllegalArgumentException("Unsupported secret store type: " + actual);
        }
    }

    private static Secret decrypt(SecretType type, byte[] input, byte[] password, byte[] salt) {
        Function<byte[], ? extends Secret> deserializer;
        switch (type) {
            case KEY_PAIR:
                deserializer = KeyPair::deserialize;
                break;
            case USERNAME_PASSWORD:
                deserializer = UsernamePassword::deserialize;
                break;
            case DATA:
                deserializer = BinaryDataSecret::new;
                break;
            default:
                throw new IllegalArgumentException("Unknown secret type: " + type);
        }

        return SecretUtils.decrypt(deserializer, input, password, salt);
    }

    private byte[] getPwd(String pwd) {
        if (pwd == null) {
            return secretCfg.getServerPwd();
        }
        return pwd.getBytes(StandardCharsets.UTF_8);
    }

    private static void validate(KeyPair k) {
        byte[] pub = k.getPublicKey();
        byte[] priv = k.getPrivateKey();

        // with a 1024 bit key, the minimum size of a public RSA key file is 226 bytes
        if (pub == null || pub.length < 226) {
            throw new IllegalArgumentException("Invalid public key file size");
        }

        // 887 bytes is the minimum file size of a 1024 bit RSA private key
        if (priv == null || priv.length < 800) {
            throw new IllegalArgumentException("Invalid private key file size");
        }

        try {
            KeyPairUtils.validateKeyPair(pub, priv);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid key pair data");
        }
    }

    private static SecretStoreType getStoreType(String pwd) {
        if (pwd == null) {
            return SecretStoreType.SERVER_KEY;
        }
        return SecretStoreType.PASSWORD;
    }

    private static UUID getOwnerId() {
        UserPrincipal p = UserPrincipal.getCurrent();
        return p.getId();
    }

    public static class DecryptedSecret {

        private final UUID id;
        private final Secret secret;

        public DecryptedSecret(UUID id, Secret secret) {
            this.id = id;
            this.secret = secret;
        }

        public UUID getId() {
            return id;
        }

        public Secret getSecret() {
            return secret;
        }
    }

    public static class DecryptedKeyPair {

        private final UUID id;
        private final byte[] data;

        public DecryptedKeyPair(UUID id, byte[] data) {
            this.id = id;
            this.data = data;
        }

        public UUID getId() {
            return id;
        }

        public byte[] getData() {
            return data;
        }
    }

    public static class DecryptedUsernamePassword {

        private final UUID id;

        public DecryptedUsernamePassword(UUID id) {
            this.id = id;
        }

        public UUID getId() {
            return id;
        }
    }

    public static class DecryptedBinaryData {

        private final UUID id;

        public DecryptedBinaryData(UUID id) {
            this.id = id;
        }

        public UUID getId() {
            return id;
        }
    }
}
