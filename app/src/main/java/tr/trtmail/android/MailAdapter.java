package tr.trtmail.android;

import android.graphics.Typeface;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * RecyclerView adapter for the mail list in MainActivity.
 */
public class MailAdapter extends RecyclerView.Adapter<MailAdapter.ViewHolder> {

    public interface MailClickListener {
        void onMailClick(EwsClient.MailItem item);
        void onMailLongClick(EwsClient.MailItem item);
    }

    private final List<EwsClient.MailItem> items;
    private final MailClickListener listener;

    public MailAdapter(List<EwsClient.MailItem> items, MailClickListener listener) {
        this.items    = items;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_mail, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder h, int position) {
        EwsClient.MailItem item = items.get(position);

        h.tvFrom.setText(item.from != null && !item.from.isEmpty() ? item.from : item.fromEmail);
        h.tvSubject.setText(item.subject != null && !item.subject.isEmpty()
                ? item.subject : h.itemView.getContext().getString(R.string.list_no_subject));
        h.tvDate.setText(formatDate(item.date));
        h.ivAttach.setVisibility(item.hasAttachments ? View.VISIBLE : View.INVISIBLE);

        // Bold if unread
        int style = item.isRead ? Typeface.NORMAL : Typeface.BOLD;
        h.tvFrom.setTypeface(null, style);
        h.tvSubject.setTypeface(null, style);

        int bg = item.isRead ? android.R.color.transparent : R.color.unread_background;
        h.itemView.setBackgroundColor(ContextCompat.getColor(h.itemView.getContext(), bg));

        h.itemView.setOnClickListener(v -> listener.onMailClick(item));
        h.itemView.setOnLongClickListener(v -> { listener.onMailLongClick(item); return true; });
    }

    @Override
    public int getItemCount() { return items.size(); }

    private String formatDate(String ewsDate) {
        if (ewsDate == null || ewsDate.isEmpty()) return "";
        try {
            // EWS format: 2024-05-10T14:35:00Z
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date d = sdf.parse(ewsDate);
            if (d == null) return ewsDate;
            SimpleDateFormat out = new SimpleDateFormat("dd.MM.yy HH:mm", Locale.getDefault());
            return out.format(d);
        } catch (ParseException e) {
            return ewsDate.length() > 10 ? ewsDate.substring(0, 10) : ewsDate;
        }
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvFrom, tvSubject, tvDate;
        ImageView ivAttach;

        ViewHolder(View v) {
            super(v);
            tvFrom    = v.findViewById(R.id.tv_from);
            tvSubject = v.findViewById(R.id.tv_subject);
            tvDate    = v.findViewById(R.id.tv_date);
            ivAttach  = v.findViewById(R.id.iv_attach);
        }
    }
}
