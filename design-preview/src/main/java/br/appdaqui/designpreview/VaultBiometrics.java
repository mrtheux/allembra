package br.appdaqui.designpreview;

import android.app.Activity;
import android.content.SharedPreferences;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.Build;
import android.os.CancellationSignal;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.function.Consumer;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class VaultBiometrics {
    private static final String ALIAS = "allembra.biometric.v1";
    private final Activity activity;
    private final SharedPreferences prefs;
    private CancellationSignal active;
    VaultBiometrics(Activity activity) {
        this.activity = activity;
        prefs = activity.getSharedPreferences("allembra.biometric", Activity.MODE_PRIVATE);
    }
    boolean enabled() { return prefs.contains("wrapped") && prefs.contains("iv"); }

    void clear() {
        if (active != null) active.cancel();
        prefs.edit().clear().apply();
        try {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            if (store.containsAlias(ALIAS)) store.deleteEntry(ALIAS);
        } catch (Exception ignored) {}
    }

    private SecretKey readKey(boolean generate) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.getKey(ALIAS, null) instanceof SecretKey) return (SecretKey) store.getKey(ALIAS, null);
        if (!generate) throw new IllegalStateException("Biometria removida.");
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(ALIAS,
            KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true).setInvalidatedByBiometricEnrollment(true);
        if (Build.VERSION.SDK_INT >= 30) builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG);
        else builder.setUserAuthenticationValidityDurationSeconds(-1);
        generator.init(builder.build());
        return generator.generateKey();
    }

    void authenticate(String phrase, Consumer<String> onResult, Consumer<String> onError) {
        if (active != null) return;
        boolean saving = phrase != null;
        if (Build.VERSION.SDK_INT >= 30) {
            BiometricManager manager = activity.getSystemService(BiometricManager.class);
            if (manager == null || manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) != BiometricManager.BIOMETRIC_SUCCESS) {
                onError.accept("Configure uma impressão digital compatível nas configurações do Android.");
                return;
            }
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            if (saving) cipher.init(Cipher.ENCRYPT_MODE, readKey(true));
            else cipher.init(Cipher.DECRYPT_MODE, readKey(false),
                new GCMParameterSpec(128, Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP)));
            BiometricPrompt.Builder prompt = new BiometricPrompt.Builder(activity).setTitle("Allembra")
                .setSubtitle(saving ? "Ativar desbloqueio por biometria" : "Desbloquear o cofre")
                .setNegativeButton("Usar frase", activity.getMainExecutor(), (dialog, which) -> {
                    active = null;
                    onError.accept("Continue com sua frase de recuperação.");
                });
            if (Build.VERSION.SDK_INT >= 30) prompt.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG);
            active = new CancellationSignal();
            prompt.build().authenticate(new BiometricPrompt.CryptoObject(cipher), active, activity.getMainExecutor(),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                        active = null;
                        try {
                            Cipher authenticated = result.getCryptoObject().getCipher();
                            if (saving) {
                                byte[] credential = authenticated.doFinal(phrase.getBytes(StandardCharsets.UTF_8));
                                prefs.edit().putString("wrapped", Base64.encodeToString(credential, Base64.NO_WRAP))
                                    .putString("iv", Base64.encodeToString(authenticated.getIV(), Base64.NO_WRAP)).apply();
                                onResult.accept("");
                            } else {
                                byte[] credential = Base64.decode(prefs.getString("wrapped", ""), Base64.NO_WRAP);
                                onResult.accept(new String(authenticated.doFinal(credential), StandardCharsets.UTF_8));
                            }
                        } catch (Exception failure) { onError.accept("Não foi possível acessar a biometria. Use a frase de recuperação."); }
                    }
                    @Override public void onAuthenticationError(int code, CharSequence message) {
                        active = null;
                        onError.accept(message.toString());
                    }
                });
        } catch (Exception failure) {
            active = null;
            onError.accept("Biometria indisponível. Use a frase de recuperação para entrar.");
        }
    }
}
