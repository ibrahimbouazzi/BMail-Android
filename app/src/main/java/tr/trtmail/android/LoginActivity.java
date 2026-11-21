package tr.trtmail.android;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Login screen — mirrors LoginDialog from the desktop BMail app.
 * Collects EWS URL, domain\username, and password, then authenticates.
 */
public class LoginActivity extends AppCompatActivity {

    private EditText etEwsUrl, etOabUrl, etUsername, etPassword;
    private CheckBox cbRemember;
    private Button btnSignIn;
    private ProgressBar progress;
    private TextView tvStatus;

    private CredentialStore credStore;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        credStore = new CredentialStore(this);

        etEwsUrl   = findViewById(R.id.et_ews_url);
        etOabUrl   = findViewById(R.id.et_oab_url);
        etUsername = findViewById(R.id.et_username);
        etPassword = findViewById(R.id.et_password);
        cbRemember = findViewById(R.id.cb_remember);
        btnSignIn  = findViewById(R.id.btn_sign_in);
        progress   = findViewById(R.id.progress);
        tvStatus   = findViewById(R.id.tv_status);

        // Pre-fill from saved credentials
        CredentialStore.Settings s = credStore.load();
        etEwsUrl.setText(s.ewsUrl);
        etOabUrl.setText(s.oabUrl);
        if (!s.domain.isEmpty() || !s.username.isEmpty()) {
            etUsername.setText(s.domain.isEmpty() ? s.username : s.domain + "\\" + s.username);
        }
        etPassword.setText(s.password);
        cbRemember.setChecked(s.remember);

        btnSignIn.setOnClickListener(v -> attemptLogin());
    }

    private void attemptLogin() {
        String ewsUrl  = etEwsUrl.getText().toString().trim();
        String oabUrl  = etOabUrl.getText().toString().trim();
        String userRaw = etUsername.getText().toString().trim();
        String pass    = etPassword.getText().toString();

        if (ewsUrl.isEmpty()) {
            Toast.makeText(this, R.string.err_no_ews, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!ewsUrl.startsWith("https://")) {
            Toast.makeText(this, getString(R.string.err_https_only, ewsUrl), Toast.LENGTH_LONG).show();
            return;
        }
        if (userRaw.isEmpty() || pass.isEmpty()) {
            Toast.makeText(this, R.string.login_need_creds, Toast.LENGTH_SHORT).show();
            return;
        }

        // Parse DOMAIN\user
        String domain, username;
        int slash = userRaw.indexOf('\\');
        if (slash >= 0) {
            domain   = userRaw.substring(0, slash).trim();
            username = userRaw.substring(slash + 1).trim();
        } else {
            domain   = "";
            username = userRaw;
        }

        setLoading(true, getString(R.string.login_connecting));

        final String finalEwsUrl  = ewsUrl;
        final String finalOabUrl  = oabUrl;
        final String finalDomain  = domain;
        final String finalUser    = username;
        final String finalPass    = pass;
        final boolean remember    = cbRemember.isChecked();

        executor.execute(() -> {
            try {
                EwsClient client = new EwsClient(finalEwsUrl, finalDomain, finalUser, finalPass);
                client.testConnection();

                // Save credentials
                CredentialStore.Settings s = new CredentialStore.Settings();
                s.ewsUrl   = finalEwsUrl;
                s.oabUrl   = finalOabUrl;
                s.domain   = finalDomain;
                s.username = finalUser;
                s.password = remember ? finalPass : "";
                s.remember = remember;
                credStore.save(s);

                runOnUiThread(() -> {
                    setLoading(false, "");
                    Intent intent = new Intent(LoginActivity.this, MainActivity.class);
                    intent.putExtra("ews_url",  finalEwsUrl);
                    intent.putExtra("oab_url",  finalOabUrl);
                    intent.putExtra("domain",   finalDomain);
                    intent.putExtra("username", finalUser);
                    intent.putExtra("password", finalPass);
                    startActivity(intent);
                    finish();
                });

            } catch (EwsClient.EwsException e) {
                runOnUiThread(() -> {
                    setLoading(false, "");
                    showError(e);
                });
            }
        });
    }

    private void setLoading(boolean loading, String msg) {
        btnSignIn.setEnabled(!loading);
        progress.setVisibility(loading ? View.VISIBLE : View.GONE);
        tvStatus.setText(msg);
        tvStatus.setVisibility(loading ? View.VISIBLE : View.GONE);
    }

    private void showError(EwsClient.EwsException e) {
        String msg;
        if (e.httpCode == 401) msg = getString(R.string.err_auth);
        else if (e.httpCode == 403) msg = getString(R.string.err_forbidden);
        else msg = e.getMessage();
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
