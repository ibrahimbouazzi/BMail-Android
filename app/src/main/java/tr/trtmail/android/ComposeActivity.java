package tr.trtmail.android;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.Html;
import android.text.TextUtils;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Compose / Reply screen. Mirrors ComposeDialog from the desktop BMail.
 */
public class ComposeActivity extends AppCompatActivity {

    private EditText etTo, etCc, etBcc, etSubject, etBody;
    private Button btnSend, btnDiscard, btnAddAttachment;
    private LinearLayout attachmentContainer;
    private ProgressBar progressBar;

    private EwsClient ewsClient;
    private final List<String> attachmentNames = new ArrayList<>();
    private final List<byte[]> attachmentData  = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private ActivityResultLauncher<String[]> filePicker;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_compose);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.compose_title);
        }

        Intent i = getIntent();
        ewsClient = new EwsClient(
                i.getStringExtra("ews_url"),
                i.getStringExtra("domain"),
                i.getStringExtra("username"),
                i.getStringExtra("password")
        );

        etTo              = findViewById(R.id.et_to);
        etCc              = findViewById(R.id.et_cc);
        etBcc             = findViewById(R.id.et_bcc);
        etSubject         = findViewById(R.id.et_subject);
        etBody            = findViewById(R.id.et_body);
        btnSend           = findViewById(R.id.btn_send);
        btnDiscard        = findViewById(R.id.btn_discard);
        btnAddAttachment  = findViewById(R.id.btn_add_attachment);
        attachmentContainer = findViewById(R.id.attachment_container);
        progressBar       = findViewById(R.id.progress_bar);

        // Pre-fill for reply
        String replyTo      = i.getStringExtra("reply_to");
        String replySubject = i.getStringExtra("reply_subject");
        if (replyTo != null) etTo.setText(replyTo);
        if (replySubject != null) etSubject.setText(replySubject);

        btnSend.setOnClickListener(v -> attemptSend());
        btnDiscard.setOnClickListener(v -> finish());
        btnAddAttachment.setOnClickListener(v -> filePicker.launch(new String[]{"*/*"}));

        filePicker = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri == null) return;
                    addAttachment(uri);
                });
    }

    private void addAttachment(Uri uri) {
        // Get filename
        String name = getFileName(uri);
        try {
            byte[] data = readBytes(uri);
            long totalSize = attachmentData.stream().mapToLong(b -> b.length).sum() + data.length;
            if (totalSize > 25 * 1024 * 1024) {
                Toast.makeText(this, R.string.compose_too_large, Toast.LENGTH_LONG).show();
                return;
            }
            attachmentNames.add(name);
            attachmentData.add(data);

            // Add label to UI
            TextView tv = new TextView(this);
            tv.setText("📎 " + name + " (" + formatSize(data.length) + ")");
            tv.setPadding(8, 4, 8, 4);
            tv.setTag(attachmentNames.size() - 1);
            tv.setOnLongClickListener(v -> {
                int idx = (int) v.getTag();
                attachmentNames.remove(idx);
                attachmentData.remove(idx);
                attachmentContainer.removeView(v);
                return true;
            });
            attachmentContainer.addView(tv);
        } catch (IOException e) {
            Toast.makeText(this, "Dosya okunamadı: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void attemptSend() {
        String to      = etTo.getText().toString().trim();
        String cc      = etCc.getText().toString().trim();
        String bcc     = etBcc.getText().toString().trim();
        String subject = etSubject.getText().toString().trim();
        String body    = etBody.getText().toString();

        if (to.isEmpty() && cc.isEmpty() && bcc.isEmpty()) {
            Toast.makeText(this, R.string.compose_no_recipient, Toast.LENGTH_SHORT).show();
            return;
        }

        if (subject.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.compose_no_subject_title)
                    .setMessage(R.string.compose_no_subject)
                    .setPositiveButton(R.string.op_yes, (d, w) -> doSend(to, cc, bcc, subject, body))
                    .setNegativeButton(R.string.op_no, null)
                    .show();
            return;
        }

        doSend(to, cc, bcc, subject, body);
    }

    private void doSend(String to, String cc, String bcc, String subject, String body) {
        setLoading(true);
        // Convert plain text to simple HTML
        String html = "<html><body>" + body.replace("\n", "<br/>") + "</body></html>";
        executor.execute(() -> {
            try {
                ewsClient.sendMessage(to, cc, bcc, subject, html,
                        new ArrayList<>(attachmentNames),
                        new ArrayList<>(attachmentData));
                runOnUiThread(() -> {
                    setLoading(false);
                    Toast.makeText(this, "Gönderildi ✓", Toast.LENGTH_SHORT).show();
                    finish();
                });
            } catch (EwsClient.EwsException e) {
                runOnUiThread(() -> {
                    setLoading(false);
                    Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void setLoading(boolean loading) {
        progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        btnSend.setEnabled(!loading);
    }

    private byte[] readBytes(Uri uri) throws IOException {
        try (InputStream is = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream buf = new ByteArrayOutputStream()) {
            if (is == null) throw new IOException("Cannot open stream");
            byte[] chunk = new byte[8192];
            int read;
            while ((read = is.read(chunk)) != -1) buf.write(chunk, 0, read);
            return buf.toByteArray();
        }
    }

    private String getFileName(Uri uri) {
        String result = null;
        if ("content".equals(uri.getScheme())) {
            try (android.database.Cursor cursor = getContentResolver().query(
                    uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) result = cursor.getString(idx);
                }
            }
        }
        if (result == null) {
            result = uri.getPath();
            int cut = result != null ? result.lastIndexOf('/') : -1;
            if (cut >= 0) result = result.substring(cut + 1);
        }
        return result != null ? result : "attachment";
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) { finish(); return true; }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
