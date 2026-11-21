package tr.trtmail.android;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Shows a single email's headers + HTML body.
 * Mirrors MailPane + detail part of MainFrame from the desktop BMail.
 */
public class MailDetailActivity extends AppCompatActivity {

    private EwsClient ewsClient;
    private EwsClient.MailDetail detail;

    private TextView tvSubject, tvFrom, tvTo, tvCc, tvDate;
    private WebView webView;
    private ProgressBar progressBar;
    private View headerCard, attachmentCard;
    private TextView tvAttachments;

    private boolean loadRemoteImages = false;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    @SuppressLint("SetJavaScriptEnabled")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mail_detail);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        Intent i = getIntent();
        ewsClient = new EwsClient(
                i.getStringExtra("ews_url"),
                i.getStringExtra("domain"),
                i.getStringExtra("username"),
                i.getStringExtra("password")
        );
        String itemId = i.getStringExtra("item_id");

        tvSubject      = findViewById(R.id.tv_subject);
        tvFrom         = findViewById(R.id.tv_from);
        tvTo           = findViewById(R.id.tv_to);
        tvCc           = findViewById(R.id.tv_cc);
        tvDate         = findViewById(R.id.tv_date);
        webView        = findViewById(R.id.web_view);
        progressBar    = findViewById(R.id.progress_bar);
        headerCard     = findViewById(R.id.header_card);
        attachmentCard = findViewById(R.id.attachment_card);
        tvAttachments  = findViewById(R.id.tv_attachments);

        // WebView setup
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(false);
        ws.setBlockNetworkImage(!loadRemoteImages);
        ws.setSupportZoom(true);
        ws.setBuiltInZoomControls(true);
        ws.setDisplayZoomControls(false);
        ws.setTextZoom(100);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                // Open links externally
                try {
                    Uri uri = Uri.parse(url);
                    String scheme = uri.getScheme();
                    if ("http".equals(scheme) || "https".equals(scheme) || "mailto".equals(scheme)) {
                        startActivity(new Intent(Intent.ACTION_VIEW, uri));
                    } else {
                        Toast.makeText(MailDetailActivity.this,
                                "Link engellendi: " + url, Toast.LENGTH_SHORT).show();
                    }
                } catch (Exception ignored) {}
                return true;
            }
        });

        loadDetail(itemId);
    }

    private void loadDetail(String itemId) {
        progressBar.setVisibility(View.VISIBLE);
        executor.execute(() -> {
            try {
                detail = ewsClient.getMailDetail(itemId);
                runOnUiThread(this::displayDetail);
            } catch (EwsClient.EwsException e) {
                runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void displayDetail() {
        progressBar.setVisibility(View.GONE);
        if (detail == null) return;

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle(detail.subject != null ? detail.subject : "(no subject)");
        }

        tvSubject.setText(detail.subject != null ? detail.subject : getString(R.string.list_no_subject));
        tvFrom.setText(detail.from != null ? detail.from + " <" + detail.fromEmail + ">" : "");
        tvTo.setText(detail.to != null ? detail.to : "");
        tvCc.setVisibility(detail.cc != null && !detail.cc.isEmpty() ? View.VISIBLE : View.GONE);
        tvCc.setText(detail.cc);
        tvDate.setText(detail.date != null ? detail.date : "");

        // Attachments
        if (!detail.attachments.isEmpty()) {
            attachmentCard.setVisibility(View.VISIBLE);
            StringBuilder sb = new StringBuilder();
            for (EwsClient.AttachmentInfo att : detail.attachments) {
                if (!att.isInline) {
                    sb.append("📎 ").append(att.name);
                    if (att.size > 0) sb.append(" (").append(formatSize(att.size)).append(")");
                    sb.append("\n");
                }
            }
            tvAttachments.setText(sb.toString().trim());
            tvAttachments.setOnClickListener(v -> showAttachmentPicker());
        } else {
            attachmentCard.setVisibility(View.GONE);
        }

        // Load HTML body
        String html = detail.bodyHtml != null ? detail.bodyHtml : "<p>(empty)</p>";
        webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
    }

    private void showAttachmentPicker() {
        if (detail == null || detail.attachments.isEmpty()) return;
        String[] names = detail.attachments.stream()
                .filter(a -> !a.isInline)
                .map(a -> a.name + (a.size > 0 ? " (" + formatSize(a.size) + ")" : ""))
                .toArray(String[]::new);
        EwsClient.AttachmentInfo[] atts = detail.attachments.stream()
                .filter(a -> !a.isInline)
                .toArray(EwsClient.AttachmentInfo[]::new);

        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.att_title)
                .setItems(names, (d, which) -> downloadAttachment(atts[which]))
                .setNegativeButton(R.string.op_cancel, null)
                .show();
    }

    private void downloadAttachment(EwsClient.AttachmentInfo att) {
        Toast.makeText(this, R.string.status_downloading, Toast.LENGTH_SHORT).show();
        executor.execute(() -> {
            try {
                byte[] data = ewsClient.getAttachment(att.id);
                // Save to cache dir and open
                File cacheDir = new File(getCacheDir(), "attachments");
                cacheDir.mkdirs();
                File file = new File(cacheDir, att.name);
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    fos.write(data);
                }
                Uri uri = FileProvider.getUriForFile(this,
                        getPackageName() + ".fileprovider", file);
                runOnUiThread(() -> {
                    Intent shareIntent = new Intent(Intent.ACTION_VIEW);
                    shareIntent.setDataAndType(uri, getMimeType(att.name));
                    shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    try {
                        startActivity(shareIntent);
                    } catch (Exception e) {
                        // If no app can open, offer share
                        Intent fallback = new Intent(Intent.ACTION_SEND);
                        fallback.setType("*/*");
                        fallback.putExtra(Intent.EXTRA_STREAM, uri);
                        fallback.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(Intent.createChooser(fallback, att.name));
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private String getMimeType(String filename) {
        String ext = filename.contains(".") ?
                filename.substring(filename.lastIndexOf('.') + 1).toLowerCase() : "";
        switch (ext) {
            case "pdf": return "application/pdf";
            case "doc": return "application/msword";
            case "docx": return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xls": return "application/vnd.ms-excel";
            case "xlsx": return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "png": return "image/png";
            case "jpg": case "jpeg": return "image/jpeg";
            case "gif": return "image/gif";
            case "zip": return "application/zip";
            default: return "*/*";
        }
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.detail_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            finish(); return true;
        } else if (id == R.id.menu_reply) {
            openReply(); return true;
        } else if (id == R.id.menu_delete) {
            confirmDelete(); return true;
        } else if (id == R.id.menu_remote_images) {
            loadRemoteImages = !loadRemoteImages;
            item.setChecked(loadRemoteImages);
            webView.getSettings().setBlockNetworkImage(!loadRemoteImages);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void openReply() {
        if (detail == null) return;
        Intent intent = new Intent(this, ComposeActivity.class);
        intent.putExtra("ews_url",  getIntent().getStringExtra("ews_url"));
        intent.putExtra("domain",   getIntent().getStringExtra("domain"));
        intent.putExtra("username", getIntent().getStringExtra("username"));
        intent.putExtra("password", getIntent().getStringExtra("password"));
        intent.putExtra("reply_to", detail.fromEmail);
        intent.putExtra("reply_subject", "RE: " + (detail.subject != null ? detail.subject : ""));
        startActivity(intent);
    }

    private void confirmDelete() {
        if (detail == null) return;
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.msg_delete_title)
                .setMessage(R.string.msg_confirm_delete)
                .setPositiveButton(R.string.op_yes, (d, w) -> {
                    executor.execute(() -> {
                        try {
                            ewsClient.deleteItem(detail.id, false);
                            runOnUiThread(() -> { finish(); });
                        } catch (EwsClient.EwsException e) {
                            runOnUiThread(() -> Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show());
                        }
                    });
                })
                .setNegativeButton(R.string.op_no, null)
                .show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
