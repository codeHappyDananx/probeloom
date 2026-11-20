package dev.probeloom.service;

import dev.probeloom.config.PlatformStorage;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class CryptoService {
    private static final int GCM_TAG_LENGTH = 128;
    private static final int IV_LENGTH = 12;
    private final PlatformStorage storage;
    private SecretKey key;

    public CryptoService(PlatformStorage storage) {
        this.storage = storage;
    }

    @PostConstruct
    public void initialize() throws Exception {
        Path keyFile = storage.getDataDirectory().resolve("probeloom.key");
        if (Files.exists(keyFile)) {
            key = new SecretKeySpec(Base64.getDecoder().decode(new String(Files.readAllBytes(keyFile), StandardCharsets.US_ASCII)), "AES");
            return;
        }
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(256);
        key = generator.generateKey();
        Files.write(keyFile, Base64.getEncoder().encode(key.getEncoded()));
    }

    public String encrypt(String plainText) {
        if (plainText == null || plainText.trim().isEmpty()) {
            return null;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            byte[] value = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, value, 0, iv.length);
            System.arraycopy(encrypted, 0, value, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(value);
        } catch (Exception e) {
            throw new IllegalStateException("加密数据源口令失败", e);
        }
    }

    public String decrypt(String ciphertext) {
        if (ciphertext == null || ciphertext.trim().isEmpty()) {
            return null;
        }
        try {
            byte[] value = Base64.getDecoder().decode(ciphertext);
            byte[] iv = new byte[IV_LENGTH];
            byte[] encrypted = new byte[value.length - IV_LENGTH];
            System.arraycopy(value, 0, iv, 0, IV_LENGTH);
            System.arraycopy(value, IV_LENGTH, encrypted, 0, encrypted.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("解密数据源口令失败", e);
        }
    }
}
