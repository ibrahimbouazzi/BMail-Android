package tr.trtmail.android;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

/**
 * Stores EWS credentials securely using EncryptedSharedPreferences.
 * Mirrors CredentialStore from the original BMail desktop app.
 */
public class CredentialStore {

    private static final String PREFS_NAME = "bmail_credentials";
    private static final String KEY_EWS_URL   = "ews_url";
    private static final String KEY_OAB_URL   = "oab_url";
    private static final String KEY_DOMAIN    = "domain";
    private static final String KEY_USERNAME  = "username";
    private static final String KEY_PASSWORD  = "password";
    private static final String KEY_REMEMBER  = "remember";
    private static final String KEY_LANG      = "language";

    public static class Settings {
        public String ewsUrl   = "";
        public String oabUrl   = "";
        public String domain   = "";
        public String username = "";
        public String password = "";
        public boolean remember = false;
        public String language = "tr";
    }

    private final SharedPreferences prefs;

    public CredentialStore(Context context) {
        SharedPreferences p;
        try {
            MasterKey masterKey = new MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            p = EncryptedSharedPreferences.create(
                    context,
                    PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            );
        } catch (Exception e) {
            // Fallback to plain prefs (not ideal, but keeps app functional)
            p = context.getSharedPreferences(PREFS_NAME + "_plain", Context.MODE_PRIVATE);
        }
        this.prefs = p;
    }

    public Settings load() {
        Settings s = new Settings();
        s.ewsUrl   = prefs.getString(KEY_EWS_URL,  "");
        s.oabUrl   = prefs.getString(KEY_OAB_URL,  "");
        s.domain   = prefs.getString(KEY_DOMAIN,   "");
        s.username = prefs.getString(KEY_USERNAME, "");
        s.remember = prefs.getBoolean(KEY_REMEMBER, false);
        s.language = prefs.getString(KEY_LANG, "tr");
        if (s.remember) {
            s.password = prefs.getString(KEY_PASSWORD, "");
        }
        return s;
    }

    public void save(Settings s) {
        SharedPreferences.Editor ed = prefs.edit();
        ed.putString(KEY_EWS_URL,  s.ewsUrl);
        ed.putString(KEY_OAB_URL,  s.oabUrl);
        ed.putString(KEY_DOMAIN,   s.domain);
        ed.putString(KEY_USERNAME, s.username);
        ed.putBoolean(KEY_REMEMBER, s.remember);
        ed.putString(KEY_LANG,     s.language);
        if (s.remember) {
            ed.putString(KEY_PASSWORD, s.password);
        } else {
            ed.remove(KEY_PASSWORD);
        }
        ed.apply();
    }

    public void clearPassword() {
        prefs.edit().remove(KEY_PASSWORD).apply();
    }

    public void clearAll() {
        prefs.edit().clear().apply();
    }
}
