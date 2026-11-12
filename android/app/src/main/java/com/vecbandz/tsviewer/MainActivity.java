package com.vecbandz.tsviewer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Shows tsviewer session logs (JSONL) on the phone.
 *
 * Logs come in through the "Open logs" button, a share from Termux
 * (termux-open --send file.jsonl) or a file manager. Each one is copied into
 * the app's private storage as its own session, then the bundled
 * TSViewer.html page is filled with every session, the same way
 * build_viewer.py does it in Termux. Nothing is sent anywhere: the app has no
 * internet permission.
 */
public class MainActivity extends Activity {

    private static final int REQ_OPEN = 1;
    private static final long MAX_BYTES = 20L * 1024 * 1024;

    private WebView web;
    private File logsDir;
    private String template;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        logsDir = new File(getFilesDir(), "logs");
        //noinspection ResultOfMethodCallIgnored
        logsDir.mkdirs();
        seedExamples();

        goEdgeToEdge();
        FrameLayout root = new FrameLayout(this);
        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int l, t, r, b;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                l = i.left; t = i.top; r = i.right; b = i.bottom;
            } else {
                l = insets.getSystemWindowInsetLeft(); t = insets.getSystemWindowInsetTop();
                r = insets.getSystemWindowInsetRight(); b = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(l, t, r, b);
            return insets;
        });
        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return true; // the page is self-contained; never navigate away
            }
        });
        web.addJavascriptInterface(new Bridge(), "TSV");

        // Offline app: drop the Google Fonts link so the page uses system fonts.
        template = readAsset("TSViewer.html")
                .replaceAll("<link[^>]*fonts\\.googleapis[^>]*>", "");

        render(handleIncoming(getIntent()));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String selected = handleIncoming(intent);
        if (selected != null) render(selected);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_OPEN || resultCode != RESULT_OK || data == null) return;
        List<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) uris.add(clip.getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        String selected = importAll(uris);
        if (selected != null) render(selected);
    }

    // ---- page ----

    private void render(String select) {
        String page = template.replace("__DATA__", buildData()) + injected(select);
        web.loadDataWithBaseURL(null, page, "text/html", "utf-8", null);
    }

    /** Same shape as build_viewer.py: {"session": [event, ...], ...}, sorted by name. */
    private String buildData() {
        File[] files = logsDir.listFiles((d, n) -> n.endsWith(".jsonl"));
        if (files == null) files = new File[0];
        Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (File f : files) {
            String name = f.getName().substring(0, f.getName().length() - ".jsonl".length());
            JSONArray events = new JSONArray();
            for (String line : readFile(f).split("\n")) {
                if (line.trim().isEmpty()) continue;
                try {
                    events.put(new JSONObject(line));
                } catch (JSONException ignored) {
                    // skip a damaged line rather than losing the whole session
                }
            }
            if (!first) sb.append(',');
            first = false;
            sb.append(JSONObject.quote(name)).append(':').append(events.toString());
        }
        return sb.append('}').toString().replace("</", "<\\/");
    }

    /** Adds the app's buttons to the page's controls and selects a session. */
    private String injected(String select) {
        String sel = select == null ? "null" : JSONObject.quote(select);
        return "<style>"
                + ".tsv-actions{display:flex;gap:8px}"
                + ".tsv-actions button{height:36px;padding:0 12px;border:1px solid var(--bd);border-radius:8px;background:var(--panel);cursor:pointer}"
                + ".tsv-actions button:disabled{opacity:.4}"
                + "@media (max-width:640px){.tsv-actions button{flex:1;height:48px;font-size:16px;border-radius:10px}}"
                + "</style>"
                + "<script>(function(){"
                + "var c=document.querySelector('.controls');if(!c)return;"
                + "var a=document.createElement('div');a.className='tsv-actions';"
                + "a.innerHTML='<button type=\"button\" id=\"tsvOpen\">Open logs</button>"
                + "<button type=\"button\" id=\"tsvRemove\">Remove session</button>';"
                + "c.appendChild(a);"
                + "document.getElementById('tsvOpen').onclick=function(){TSV.openLogs()};"
                + "var r=document.getElementById('tsvRemove');r.disabled=!names.length;"
                + "r.onclick=function(){if(cur)TSV.removeSession(cur)};"
                + "var s=" + sel + ";if(s&&D[s]){sess.value=s;load(s)}"
                + "})();</script>";
    }

    // ---- page -> app ----

    private class Bridge {
        @JavascriptInterface
        public void openLogs() {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try {
                    startActivityForResult(i, REQ_OPEN);
                } catch (ActivityNotFoundException e) {
                    toast("No file picker found on this device");
                }
            });
        }

        @JavascriptInterface
        public void removeSession(String name) {
            runOnUiThread(() -> {
                File f = sessionFile(name);
                if (f == null || !f.exists()) return;
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Remove session?")
                        .setMessage("“" + name + "” will be removed from TSViewer. "
                                + "The original log file isn't changed.")
                        .setPositiveButton("Remove", (d, w) -> {
                            //noinspection ResultOfMethodCallIgnored
                            f.delete();
                            render(null);
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            });
        }
    }

    // ---- importing logs ----

    @SuppressWarnings("deprecation")
    private String handleIncoming(Intent intent) {
        if (intent == null || intent.getAction() == null) return null;
        List<Uri> uris = new ArrayList<>();
        switch (intent.getAction()) {
            case Intent.ACTION_VIEW:
                if (intent.getData() != null) uris.add(intent.getData());
                break;
            case Intent.ACTION_SEND: {
                Parcelable p = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                if (p instanceof Uri) uris.add((Uri) p);
                break;
            }
            case Intent.ACTION_SEND_MULTIPLE: {
                ArrayList<Parcelable> list = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                if (list != null) for (Parcelable p : list) if (p instanceof Uri) uris.add((Uri) p);
                break;
            }
            default:
                return null;
        }
        // Handle a share only once, even if the activity is recreated.
        intent.setAction(null);
        return uris.isEmpty() ? null : importAll(uris);
    }

    /** Copies each log into the app; returns the last imported session name. */
    private String importAll(List<Uri> uris) {
        int ok = 0, bad = 0;
        String last = null;
        for (Uri uri : uris) {
            try {
                String text = readUri(uri);
                if (text == null || !looksLikeLog(text)) { bad++; continue; }
                String name = sessionName(displayName(uri));
                File out = sessionFile(name);
                if (out == null) { bad++; continue; }
                try (OutputStream os = new FileOutputStream(out)) {
                    os.write(text.getBytes(StandardCharsets.UTF_8));
                }
                last = name;
                ok++;
            } catch (IOException | SecurityException e) {
                bad++;
            }
        }
        if (ok > 0 && bad == 0) toast(ok == 1 ? "Opened " + last : "Opened " + ok + " sessions");
        else if (ok > 0) toast("Opened " + ok + ", skipped " + bad + " that weren't tsviewer logs");
        else if (bad > 0) toast("That file isn't a tsviewer log (.jsonl)");
        return last;
    }

    /** A tsviewer log has at least one JSON line with a "type" field. */
    private static boolean looksLikeLog(String text) {
        for (String line : text.split("\n")) {
            String t = line.trim();
            if (!t.startsWith("{")) continue;
            try {
                if (new JSONObject(t).has("type")) return true;
            } catch (JSONException ignored) {
                // keep looking
            }
        }
        return false;
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) return c.getString(0);
        } catch (RuntimeException ignored) {
            // fall through to the path
        }
        return uri.getLastPathSegment();
    }

    private static String sessionName(String fileName) {
        String n = fileName == null ? "" : fileName;
        int slash = n.lastIndexOf('/');
        if (slash >= 0) n = n.substring(slash + 1);
        for (String ext : new String[]{".jsonl", ".json", ".txt", ".log"}) {
            if (n.toLowerCase().endsWith(ext)) { n = n.substring(0, n.length() - ext.length()); break; }
        }
        n = n.replaceAll("[^A-Za-z0-9._ -]", "_").trim();
        if (n.startsWith(".")) n = "_" + n;
        return n.isEmpty() ? "session" : n;
    }

    /** The stored file for a session name, or null if the name would escape the logs folder. */
    private File sessionFile(String name) {
        if (name == null || name.isEmpty() || name.contains("/") || name.contains("\\") || name.startsWith(".")) {
            return null;
        }
        return new File(logsDir, name + ".jsonl");
    }

    /** Puts the sample sessions in place on first launch so there's something to look at. */
    private void seedExamples() {
        SharedPreferences prefs = getSharedPreferences("tsviewer", MODE_PRIVATE);
        if (prefs.getBoolean("seeded", false)) return;
        try {
            String[] names = getAssets().list("examples");
            if (names != null) {
                for (String n : names) {
                    File out = new File(logsDir, n);
                    if (out.exists()) continue;
                    try (InputStream in = getAssets().open("examples/" + n);
                         OutputStream os = new FileOutputStream(out)) {
                        copy(in, os);
                    }
                }
            }
        } catch (IOException ignored) {
            // samples are optional
        }
        prefs.edit().putBoolean("seeded", true).apply();
    }

    // ---- helpers ----

    @SuppressWarnings("deprecation")
    private void goEdgeToEdge() {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(getWindow().getDecorView().getSystemUiVisibility()
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    private String readAsset(String path) {
        try (InputStream in = getAssets().open(path)) {
            return readStream(in);
        } catch (IOException e) {
            throw new IllegalStateException("Missing asset " + path, e);
        }
    }

    private static String readFile(File f) {
        try (InputStream in = new java.io.FileInputStream(f)) {
            return readStream(in);
        } catch (IOException e) {
            return "";
        }
    }

    private String readUri(Uri uri) throws IOException {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            return in == null ? null : readStream(in);
        }
    }

    private static String readStream(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > MAX_BYTES) throw new IOException("File is larger than 20 MB");
            out.write(buf, 0, n);
        }
        return out.toString("UTF-8");
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
