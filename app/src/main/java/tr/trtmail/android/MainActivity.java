package tr.trtmail.android;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Main mail list screen. Mirrors MainFrame from the desktop BMail.
 * Shows folder sidebar + mail list; tapping a mail opens MailDetailActivity.
 */
public class MainActivity extends AppCompatActivity implements MailAdapter.MailClickListener {

    private static final int PAGE_SIZE = 50;

    private EwsClient ewsClient;
    private String currentFolder = "inbox";
    private int offset = 0;
    private boolean hasMore = true;
    private String currentQuery = null;

    private Spinner spnFolders;
    private RecyclerView rvMails;
    private SwipeRefreshLayout swipeRefresh;
    private ProgressBar progressBar;
    private TextView tvStatus;
    private EditText etSearch;
    private FloatingActionButton fabCompose;

    private MailAdapter adapter;
    private final List<EwsClient.MailItem> mailItems = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        // Build EWS client from login extras
        Intent i = getIntent();
        ewsClient = new EwsClient(
                i.getStringExtra("ews_url"),
                i.getStringExtra("domain"),
                i.getStringExtra("username"),
                i.getStringExtra("password")
        );

        spnFolders  = findViewById(R.id.spn_folders);
        rvMails     = findViewById(R.id.rv_mails);
        swipeRefresh= findViewById(R.id.swipe_refresh);
        progressBar = findViewById(R.id.progress_bar);
        tvStatus    = findViewById(R.id.tv_status);
        etSearch    = findViewById(R.id.et_search);
        fabCompose  = findViewById(R.id.fab_compose);

        // Folder spinner
        String[] folders = {
                getString(R.string.folder_inbox),
                getString(R.string.folder_drafts),
                getString(R.string.folder_sent),
                getString(R.string.folder_deleted),
                getString(R.string.folder_junk)
        };
        String[] folderIds = {"inbox","drafts","sentitems","deleteditems","junkemail"};
        ArrayAdapter<String> folderAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, folders);
        folderAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spnFolders.setAdapter(folderAdapter);
        spnFolders.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                currentFolder = folderIds[pos];
                resetAndLoad();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        // RecyclerView
        adapter = new MailAdapter(mailItems, this);
        rvMails.setLayoutManager(new LinearLayoutManager(this));
        rvMails.addItemDecoration(new DividerItemDecoration(this, DividerItemDecoration.VERTICAL));
        rvMails.setAdapter(adapter);

        // Load more on scroll end
        rvMails.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView rv, int dx, int dy) {
                LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
                if (lm != null && hasMore
                        && lm.findLastVisibleItemPosition() >= mailItems.size() - 3) {
                    loadMore();
                }
            }
        });

        // Pull-to-refresh
        swipeRefresh.setOnRefreshListener(this::resetAndLoad);

        // Search
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void afterTextChanged(Editable s) {
                String q = s.toString().trim();
                currentQuery = q.isEmpty() ? null : q;
                resetAndLoad();
            }
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after){}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count){}
        });

        fabCompose.setOnClickListener(v -> openCompose(null));

        // Initial load
        loadFolder(0);
    }

    private void resetAndLoad() {
        mailItems.clear();
        adapter.notifyDataSetChanged();
        offset = 0;
        hasMore = true;
        loadFolder(0);
    }

    private void loadFolder(int off) {
        if (!hasMore && off > 0) return;
        setLoading(true);
        executor.execute(() -> {
            try {
                List<EwsClient.MailItem> items = ewsClient.listFolder(
                        currentFolder, PAGE_SIZE, off, currentQuery);
                runOnUiThread(() -> {
                    setLoading(false);
                    swipeRefresh.setRefreshing(false);
                    if (off == 0) {
                        mailItems.clear();
                    }
                    if (items.isEmpty()) {
                        hasMore = false;
                    } else {
                        mailItems.addAll(items);
                        offset = off + items.size();
                        hasMore = items.size() == PAGE_SIZE;
                    }
                    adapter.notifyDataSetChanged();
                    tvStatus.setText(getString(R.string.status_loading).equals("") ?
                            mailItems.size() + " mesaj" : mailItems.size() + " mesaj");
                });
            } catch (EwsClient.EwsException e) {
                runOnUiThread(() -> {
                    setLoading(false);
                    swipeRefresh.setRefreshing(false);
                    Toast.makeText(MainActivity.this, e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void loadMore() {
        loadFolder(offset);
    }

    @Override
    public void onMailClick(EwsClient.MailItem item) {
        Intent intent = new Intent(this, MailDetailActivity.class);
        intent.putExtra("item_id", item.id);
        intent.putExtra("ews_url",  getIntent().getStringExtra("ews_url"));
        intent.putExtra("domain",   getIntent().getStringExtra("domain"));
        intent.putExtra("username", getIntent().getStringExtra("username"));
        intent.putExtra("password", getIntent().getStringExtra("password"));
        startActivity(intent);
    }

    @Override
    public void onMailLongClick(EwsClient.MailItem item) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.msg_delete_title)
                .setMessage(R.string.msg_confirm_delete)
                .setPositiveButton(R.string.op_yes, (d, w) -> deleteItem(item, false))
                .setNegativeButton(R.string.op_no, null)
                .show();
    }

    private void deleteItem(EwsClient.MailItem item, boolean hard) {
        setLoading(true);
        executor.execute(() -> {
            try {
                ewsClient.deleteItem(item.id, hard);
                runOnUiThread(() -> {
                    setLoading(false);
                    mailItems.remove(item);
                    adapter.notifyDataSetChanged();
                    Toast.makeText(this, "Silindi", Toast.LENGTH_SHORT).show();
                });
            } catch (EwsClient.EwsException e) {
                runOnUiThread(() -> {
                    setLoading(false);
                    Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void openCompose(EwsClient.MailItem replyTo) {
        Intent intent = new Intent(this, ComposeActivity.class);
        intent.putExtra("ews_url",  getIntent().getStringExtra("ews_url"));
        intent.putExtra("domain",   getIntent().getStringExtra("domain"));
        intent.putExtra("username", getIntent().getStringExtra("username"));
        intent.putExtra("password", getIntent().getStringExtra("password"));
        if (replyTo != null) {
            intent.putExtra("reply_to_id", replyTo.id);
        }
        startActivity(intent);
    }

    private void setLoading(boolean loading) {
        progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.menu_refresh) {
            resetAndLoad();
            return true;
        } else if (id == R.id.menu_about) {
            showAbout();
            return true;
        } else if (id == R.id.menu_logout) {
            logout();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void showAbout() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.about_title)
                .setMessage(getString(R.string.about_version, "3.6") + "\n" +
                        getString(R.string.about_text, "AI"))
                .setPositiveButton(R.string.op_ok, null)
                .show();
    }

    private void logout() {
        new CredentialStore(this).clearPassword();
        Intent intent = new Intent(this, LoginActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
}
