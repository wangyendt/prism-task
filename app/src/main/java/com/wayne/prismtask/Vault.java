package com.wayne.prismtask;

import android.content.*;
import android.security.keystore.*;
import android.util.Base64;
import java.security.KeyStore;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;

/** Token ciphertext in app-private preferences; non-exportable key in Android Keystore. */
final class Vault {
  private static final String ALIAS = "prism.service.token.v1";
  private final SharedPreferences prefs;

  Vault(Context context) {
    prefs = context.getSharedPreferences("vault", Context.MODE_PRIVATE);
  }

  private SecretKey key() throws Exception {
    KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
    ks.load(null);
    if (!ks.containsAlias(ALIAS)) {
      KeyGenerator generator =
          KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
      generator.init(
          new KeyGenParameterSpec.Builder(
                  ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
              .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
              .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
              .build());
      generator.generateKey();
    }
    return (SecretKey) ks.getKey(ALIAS, null);
  }

  void write(String token) throws Exception {
    if (token.isEmpty()) {
      if (!prefs.edit().clear().commit()) throw new Exception();
      return;
    }
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(Cipher.ENCRYPT_MODE, key());
    String iv = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP);
    String data =
        Base64.encodeToString(
            cipher.doFinal(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            Base64.NO_WRAP);
    if (!prefs.edit().putString("iv", iv).putString("data", data).commit()) throw new Exception();
  }

  String read() throws Exception {
    String data = prefs.getString("data", null);
    if (data == null) return "";
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(
        Cipher.DECRYPT_MODE,
        key(),
        new GCMParameterSpec(128, Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP)));
    return new String(
        cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)),
        java.nio.charset.StandardCharsets.UTF_8);
  }
}
