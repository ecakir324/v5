package com.privora.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaController;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class MainActivity extends Activity {
    private static final int PICK_MEDIA_REQUEST = 7101;
    private static final String PREFS = "privora_prefs_v5";
    private static final String KEY_PIN_HASH = "pin_hash";
    private static final String KEY_ITEMS = "items_json";

    private static final int BG = Color.rgb(13, 15, 20);
    private static final int SURFACE = Color.rgb(24, 27, 36);
    private static final int SURFACE_2 = Color.rgb(34, 38, 50);
    private static final int TEXT = Color.rgb(240, 242, 248);
    private static final int MUTED = Color.rgb(166, 173, 190);
    private static final int ACCENT = Color.rgb(124, 92, 255);

    private SharedPreferences prefs;
    private Handler mainHandler;
    private ArrayList<VaultItem> items = new ArrayList<VaultItem>();
    private File vaultDir;
    private File coverDir;
    private File tempDir;
    private boolean encryptedImport = false;
    private String activeFilter = "all";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window w = getWindow();
        w.setStatusBarColor(BG);
        w.setNavigationBarColor(BG);

        mainHandler = new Handler(Looper.getMainLooper());
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        vaultDir = new File(getFilesDir(), "vault_media");
        coverDir = new File(getFilesDir(), "covers");
        tempDir = new File(getCacheDir(), "privora_temp");
        ensureDir(vaultDir);
        ensureDir(coverDir);
        ensureDir(tempDir);
        createNoMedia(vaultDir);
        createNoMedia(coverDir);
        createNoMedia(tempDir);
        cleanTemp();

        if (!prefs.contains(KEY_PIN_HASH)) {
            showPinSetup();
        } else {
            showLogin();
        }
    }

    private void showPinSetup() {
        LinearLayout root = authRoot("Privora", "4–8 haneli PIN belirle.");
        final EditText p1 = pinInput("Yeni PIN");
        final EditText p2 = pinInput("PIN tekrar");
        Button save = filledButton("PIN Kaydet", ACCENT);
        root.addView(p1);
        root.addView(p2);
        root.addView(save);
        setContentView(root);

        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String a = p1.getText().toString().trim();
                String b = p2.getText().toString().trim();
                if (a.length() < 4 || a.length() > 8) {
                    toast("PIN 4–8 hane olmalı.");
                    return;
                }
                if (!a.equals(b)) {
                    toast("PIN tekrar eşleşmiyor.");
                    return;
                }
                prefs.edit().putString(KEY_PIN_HASH, CryptoUtils.sha256(a)).apply();
                showMain();
            }
        });
    }

    private void showLogin() {
        LinearLayout root = authRoot("Privora", "Vault'u açmak için PIN gir.");
        final EditText pin = pinInput("PIN");
        Button login = filledButton("Aç", ACCENT);
        root.addView(pin);
        root.addView(login);
        setContentView(root);

        login.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                String expected = prefs.getString(KEY_PIN_HASH, "");
                String actual = CryptoUtils.sha256(pin.getText().toString().trim());
                if (expected.equals(actual)) {
                    showMain();
                } else {
                    pin.setText("");
                    toast("PIN hatalı.");
                }
            }
        });
    }

    private LinearLayout authRoot(String titleText, String subtitleText) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(24), dp(90), dp(24), dp(24));
        root.setBackgroundColor(BG);

        TextView logo = label("●", 44, ACCENT, Gravity.CENTER, true);
        TextView title = label(titleText, 34, TEXT, Gravity.CENTER, true);
        TextView subtitle = label(subtitleText, 16, MUTED, Gravity.CENTER, false);
        subtitle.setPadding(0, dp(8), 0, dp(28));
        root.addView(logo);
        root.addView(title);
        root.addView(subtitle);
        return root;
    }

    private void showMain() {
        loadItems();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(16), dp(18), dp(16), 0);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleBlock = new LinearLayout(this);
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        TextView title = label("Privora", 28, TEXT, Gravity.LEFT, true);
        TextView subtitle = label(items.size() + " medya • hızlı gizleme", 13, MUTED, Gravity.LEFT, false);
        titleBlock.addView(title);
        titleBlock.addView(subtitle);
        header.addView(titleBlock, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        Button add = pillButton("+ Medya", ACCENT, Color.WHITE);
        header.addView(add);
        root.addView(header);

        TextView hint = label("Videoya uzun bas → kapak saniyesi seç. Orijinal galeri kopyasını manuel sil.", 13, MUTED, Gravity.LEFT, false);
        hint.setBackground(round(SURFACE, dp(16), 0));
        hint.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.setMargins(0, dp(14), 0, dp(8));
        root.addView(hint, hintLp);

        LinearLayout filters = new LinearLayout(this);
        filters.setOrientation(LinearLayout.HORIZONTAL);
        filters.addView(filterButton("Tümü", "all"));
        filters.addView(filterButton("Foto", "image"));
        filters.addView(filterButton("Video", "video"));
        root.addView(filters);

        List<VaultItem> visible = filteredItems();
        if (visible.size() == 0) {
            LinearLayout empty = new LinearLayout(this);
            empty.setOrientation(LinearLayout.VERTICAL);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(18), dp(40), dp(18), dp(40));
            TextView e1 = label("Henüz medya yok", 22, TEXT, Gravity.CENTER, true);
            TextView e2 = label("Fotoğraf veya video seçerek vault içine alabilirsin.", 15, MUTED, Gravity.CENTER, false);
            Button emptyAdd = filledButton("Medya Ekle", ACCENT);
            empty.addView(e1);
            empty.addView(e2);
            empty.addView(emptyAdd);
            root.addView(empty, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
            emptyAdd.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { showImportModeDialog(); }
            });
        } else {
            ScrollView scroll = new ScrollView(this);
            GridLayout grid = new GridLayout(this);
            grid.setColumnCount(3);
            grid.setPadding(0, dp(12), 0, dp(24));
            for (int i = 0; i < visible.size(); i++) {
                grid.addView(mediaCard(visible.get(i)));
            }
            scroll.addView(grid);
            root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        }

        setContentView(root);
        add.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showImportModeDialog(); }
        });
    }

    private Button filterButton(String text, final String filter) {
        boolean active = activeFilter.equals(filter);
        Button b = pillButton(text, active ? ACCENT : SURFACE, active ? Color.WHITE : MUTED);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(42), 1);
        lp.setMargins(dp(3), dp(4), dp(3), dp(4));
        b.setLayoutParams(lp);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                activeFilter = filter;
                showMain();
            }
        });
        return b;
    }

    private List<VaultItem> filteredItems() {
        ArrayList<VaultItem> out = new ArrayList<VaultItem>();
        for (int i = 0; i < items.size(); i++) {
            VaultItem item = items.get(i);
            if ("all".equals(activeFilter) || activeFilter.equals(item.type)) {
                out.add(item);
            }
        }
        return out;
    }

    private View mediaCard(final VaultItem item) {
        int screen = getResources().getDisplayMetrics().widthPixels;
        int cardW = (screen - dp(44)) / 3;
        int imageSize = cardW - dp(10);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(5), dp(5), dp(5), dp(7));
        card.setBackground(round(SURFACE, dp(16), 0));
        GridLayout.LayoutParams glp = new GridLayout.LayoutParams();
        glp.width = cardW;
        glp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        glp.setMargins(dp(3), dp(4), dp(3), dp(8));
        card.setLayoutParams(glp);

        FrameLayout media = new FrameLayout(this);
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Bitmap bmp = loadBitmap(new File(coverDir, item.coverName).getAbsolutePath(), imageSize);
        if (bmp != null) img.setImageBitmap(bmp);
        else img.setImageBitmap(placeholderBitmap(item.type));
        media.addView(img, new FrameLayout.LayoutParams(imageSize, imageSize));

        if ("video".equals(item.type)) {
            TextView play = label("▶", 28, Color.WHITE, Gravity.CENTER, true);
            play.setBackground(round(Color.argb(130, 0, 0, 0), dp(24), 0));
            media.addView(play, new FrameLayout.LayoutParams(dp(46), dp(46), Gravity.CENTER));
        }
        if (item.encrypted) {
            TextView lock = label("LOCK", 9, Color.WHITE, Gravity.CENTER, true);
            lock.setBackground(round(Color.argb(180, 0, 0, 0), dp(8), 0));
            media.addView(lock, new FrameLayout.LayoutParams(dp(44), dp(22), Gravity.RIGHT | Gravity.TOP));
        }

        TextView name = label(shortName(item.name), 11, MUTED, Gravity.CENTER, false);
        name.setMaxLines(2);
        card.addView(media);
        card.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openItem(item); }
        });
        card.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                showItemMenu(item);
                return true;
            }
        });
        return card;
    }

    private void showImportModeDialog() {
        String[] opts = new String[]{"Hızlı Gizle - önerilen", "Güvenli Şifrele - yavaş"};
        new AlertDialog.Builder(this)
                .setTitle("Import modu")
                .setMessage("Hızlı Gizle büyük videolarda daha seri çalışır. Şifreleme istersen sonradan dosyaya uzun basabilirsin.")
                .setItems(opts, new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface dialog, int which) {
                        encryptedImport = which == 1;
                        pickMedia();
                    }
                }).show();
    }

    private void pickMedia() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(intent, PICK_MEDIA_REQUEST);
        } catch (Exception e) {
            Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
            fallback.setType("*/*");
            fallback.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
            fallback.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(Intent.createChooser(fallback, "Medya seç"), PICK_MEDIA_REQUEST);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_MEDIA_REQUEST || resultCode != RESULT_OK || data == null) return;

        final ArrayList<Uri> uris = new ArrayList<Uri>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) uris.add(clip.getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.size() == 0) {
            toast("Dosya seçilmedi.");
            return;
        }

        final ProgressDialog progress = new ProgressDialog(this);
        progress.setTitle("Import");
        progress.setMessage("0 / " + uris.size() + " dosya alınıyor...");
        progress.setCancelable(false);
        progress.show();

        new Thread(new Runnable() {
            @Override public void run() {
                int ok = 0;
                int fail = 0;
                String error = "";
                for (int i = 0; i < uris.size(); i++) {
                    final int index = i + 1;
                    mainHandler.post(new Runnable() {
                        @Override public void run() { progress.setMessage(index + " / " + uris.size() + " dosya alınıyor..."); }
                    });
                    ImportResult r = importUri(uris.get(i), encryptedImport);
                    if (r.success) ok++;
                    else {
                        fail++;
                        error = r.error;
                    }
                }
                saveItems();
                final int fOk = ok;
                final int fFail = fail;
                final String fError = error;
                mainHandler.post(new Runnable() {
                    @Override public void run() {
                        try { progress.dismiss(); } catch (Exception ignored) {}
                        showMain();
                        String msg = fOk + " dosya eklendi";
                        if (fFail > 0) msg = msg + ", " + fFail + " hata" + (fError.length() == 0 ? "" : ": " + fError);
                        toast(msg);
                        if (fOk > 0) showPostImportNote();
                    }
                });
            }
        }).start();
    }

    private ImportResult importUri(Uri uri, boolean encrypt) {
        try {
            String mime = getContentResolver().getType(uri);
            String name = getDisplayName(uri);
            String lower = name.toLowerCase(Locale.ROOT);
            boolean video = isVideo(mime, lower);
            boolean image = isImage(mime, lower);
            if (!video && !image) return ImportResult.fail("Desteklenmeyen dosya: " + name);

            String id = UUID.randomUUID().toString().replace("-", "");
            String ext = extensionFromNameOrMime(lower, mime, video);
            String type = video ? "video" : "image";
            String coverName = id + ".jpg";
            File cover = new File(coverDir, coverName);
            String vaultName;

            if (encrypt) {
                File temp = new File(tempDir, id + ext);
                copyUriToFile(uri, temp);
                makeCover(type, temp, cover, 1);
                vaultName = id + ".vault";
                CryptoUtils.encryptFile(temp, new File(vaultDir, vaultName));
                safeDelete(temp);
            } else {
                vaultName = id + ext;
                File target = new File(vaultDir, vaultName);
                copyUriToFile(uri, target);
                makeCover(type, target, cover, 1);
            }

            VaultItem item = new VaultItem();
            item.id = id;
            item.name = name;
            item.type = type;
            item.mime = mime == null ? (video ? "video/*" : "image/*") : mime;
            item.ext = ext;
            item.vaultName = vaultName;
            item.coverName = coverName;
            item.coverSecond = video ? 1 : 0;
            item.encrypted = encrypt;
            items.add(0, item);
            return ImportResult.ok();
        } catch (Exception e) {
            return ImportResult.fail(e.getMessage() == null ? "bilinmeyen hata" : e.getMessage());
        }
    }

    private void makeCover(String type, File mediaFile, File coverFile, int second) {
        try {
            if ("video".equals(type)) saveVideoFrameAsCover(mediaFile, coverFile, second);
            else saveImageAsCover(mediaFile, coverFile);
        } catch (Exception e) {
            try { saveJpeg(placeholderBitmap(type), coverFile, 85); } catch (Exception ignored) {}
        }
    }

    private void showPostImportNote() {
        new AlertDialog.Builder(this)
                .setTitle("Import tamam")
                .setMessage("Dosya Privora içine kopyalandı. Android izinlerinden dolayı orijinal galerideki dosyayı otomatik silemeyebiliriz. Gizlemek istiyorsan orijinali galeriden manuel sil.")
                .setPositiveButton("Tamam", null)
                .show();
    }

    private void showItemMenu(final VaultItem item) {
        final ArrayList<String> opts = new ArrayList<String>();
        if ("video".equals(item.type)) opts.add("Kapak saniyesi seç");
        if (!item.encrypted) opts.add("Güvenli şifrele");
        opts.add("Sil");
        new AlertDialog.Builder(this)
                .setTitle(item.name)
                .setItems(opts.toArray(new String[0]), new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface dialog, int which) {
                        String c = opts.get(which);
                        if ("Kapak saniyesi seç".equals(c)) showCoverPicker(item);
                        else if ("Güvenli şifrele".equals(c)) confirmEncrypt(item);
                        else confirmDelete(item);
                    }
                }).show();
    }

    private void confirmEncrypt(final VaultItem item) {
        new AlertDialog.Builder(this)
                .setTitle("Dosya şifrelensin mi?")
                .setMessage("Büyük videolarda sürebilir. İşlem bitince dosya AES-GCM ile saklanır.")
                .setPositiveButton("Şifrele", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface dialog, int which) { encryptExisting(item); }
                })
                .setNegativeButton("Vazgeç", null)
                .show();
    }

    private void encryptExisting(final VaultItem item) {
        final ProgressDialog p = new ProgressDialog(this);
        p.setMessage("Şifreleniyor...");
        p.setCancelable(false);
        p.show();
        new Thread(new Runnable() {
            @Override public void run() {
                String err = "";
                try {
                    File plain = new File(vaultDir, item.vaultName);
                    if (!plain.exists()) throw new Exception("Dosya bulunamadı.");
                    File encrypted = new File(vaultDir, item.id + ".vault");
                    CryptoUtils.encryptFile(plain, encrypted);
                    safeDelete(plain);
                    item.vaultName = encrypted.getName();
                    item.encrypted = true;
                    saveItems();
                } catch (Exception e) {
                    err = e.getMessage() == null ? "bilinmeyen hata" : e.getMessage();
                }
                final String fErr = err;
                mainHandler.post(new Runnable() {
                    @Override public void run() {
                        try { p.dismiss(); } catch (Exception ignored) {}
                        if (fErr.length() == 0) {
                            toast("Şifrelendi.");
                            showMain();
                        } else {
                            toast("Şifreleme hatası: " + fErr);
                        }
                    }
                });
            }
        }).start();
    }

    private void confirmDelete(final VaultItem item) {
        new AlertDialog.Builder(this)
                .setTitle("Vault'tan silinsin mi?")
                .setMessage("Bu işlem Privora içindeki dosyayı siler.")
                .setPositiveButton("Sil", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface dialog, int which) {
                        safeDelete(new File(vaultDir, item.vaultName));
                        safeDelete(new File(coverDir, item.coverName));
                        items.remove(item);
                        saveItems();
                        showMain();
                        toast("Silindi.");
                    }
                })
                .setNegativeButton("Vazgeç", null)
                .show();
    }

    private void openItem(final VaultItem item) {
        try {
            final File readable = getReadableFile(item, "open");
            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setBackgroundColor(Color.BLACK);

            LinearLayout top = new LinearLayout(this);
            top.setGravity(Gravity.CENTER_VERTICAL);
            top.setPadding(dp(8), dp(8), dp(8), dp(8));
            top.setBackgroundColor(BG);

            ImageButton back = new ImageButton(this);
            back.setImageResource(android.R.drawable.ic_media_previous);
            back.setColorFilter(Color.WHITE);
            back.setBackgroundColor(Color.TRANSPARENT);
            top.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));

            TextView name = label(item.name + (item.encrypted ? "  LOCK" : ""), 15, TEXT, Gravity.LEFT, true);
            top.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

            if ("video".equals(item.type)) {
                Button cover = pillButton("Kapak", SURFACE_2, TEXT);
                top.addView(cover);
                cover.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) { showCoverPicker(item); }
                });
            }
            root.addView(top);

            if ("video".equals(item.type)) {
                VideoView video = new VideoView(this);
                MediaController controller = new MediaController(this);
                controller.setAnchorView(video);
                video.setMediaController(controller);
                video.setVideoPath(readable.getAbsolutePath());
                video.setOnPreparedListener(new android.media.MediaPlayer.OnPreparedListener() {
                    @Override public void onPrepared(android.media.MediaPlayer mp) {}
                });
                video.start();
                root.addView(video, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
            } else {
                ImageView img = new ImageView(this);
                img.setScaleType(ImageView.ScaleType.FIT_CENTER);
                Bitmap bmp = loadBitmap(readable.getAbsolutePath(), 1600);
                if (bmp != null) img.setImageBitmap(bmp);
                else toast("Görsel açılamadı.");
                root.addView(img, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
            }
            setContentView(root);
            back.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (item.encrypted) cleanTemp();
                    showMain();
                }
            });
        } catch (Exception e) {
            toast("Açılamadı: " + e.getMessage());
        }
    }

    private void showCoverPicker(final VaultItem item) {
        if (!"video".equals(item.type)) return;
        final File[] videoFileBox = new File[1];
        try {
            videoFileBox[0] = getReadableFile(item, "cover");
            final MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            retriever.setDataSource(videoFileBox[0].getAbsolutePath());
            String d = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            int duration = 1;
            try { duration = Math.max(1, Integer.parseInt(d == null ? "1" : d) / 1000); } catch (Exception ignored) {}

            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(18), dp(10), dp(18), dp(4));
            final ImageView preview = new ImageView(this);
            preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
            box.addView(preview, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(250)));
            final TextView secText = label("Saniye: " + item.coverSecond, 16, Color.BLACK, Gravity.CENTER, true);
            box.addView(secText);
            final SeekBar seek = new SeekBar(this);
            seek.setMax(duration);
            seek.setProgress(Math.min(item.coverSecond, duration));
            box.addView(seek);
            Button previewBtn = new Button(this);
            previewBtn.setText("Önizleme Al");
            previewBtn.setAllCaps(false);
            box.addView(previewBtn);

            final Runnable update = new Runnable() {
                @Override public void run() {
                    int sec = seek.getProgress();
                    secText.setText("Saniye: " + sec);
                    try {
                        Bitmap frame = retriever.getFrameAtTime(sec * 1000000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                        if (frame != null) preview.setImageBitmap(frame);
                    } catch (Exception ignored) {}
                }
            };
            update.run();
            previewBtn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { update.run(); }
            });
            seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) { secText.setText("Saniye: " + progress); }
                @Override public void onStartTrackingTouch(SeekBar s) {}
                @Override public void onStopTrackingTouch(SeekBar s) { update.run(); }
            });

            new AlertDialog.Builder(this)
                    .setTitle("Kapak saniyesi seç")
                    .setView(box)
                    .setPositiveButton("Kaydet", new android.content.DialogInterface.OnClickListener() {
                        @Override public void onClick(android.content.DialogInterface dialog, int which) {
                            try {
                                int sec = seek.getProgress();
                                saveVideoFrameAsCover(videoFileBox[0], new File(coverDir, item.coverName), sec);
                                item.coverSecond = sec;
                                saveItems();
                                toast("Kapak güncellendi.");
                                showMain();
                            } catch (Exception e) {
                                toast("Kapak kaydedilemedi: " + e.getMessage());
                            } finally {
                                releaseRetriever(retriever);
                                if (item.encrypted) safeDelete(videoFileBox[0]);
                            }
                        }
                    })
                    .setNegativeButton("Vazgeç", new android.content.DialogInterface.OnClickListener() {
                        @Override public void onClick(android.content.DialogInterface dialog, int which) {
                            releaseRetriever(retriever);
                            if (item.encrypted) safeDelete(videoFileBox[0]);
                        }
                    })
                    .show();
        } catch (Exception e) {
            if (item.encrypted && videoFileBox[0] != null) safeDelete(videoFileBox[0]);
            toast("Kapak seçici açılamadı: " + e.getMessage());
        }
    }

    private File getReadableFile(VaultItem item, String tag) throws Exception {
        File source = new File(vaultDir, item.vaultName);
        if (!source.exists()) throw new Exception("Vault dosyası bulunamadı.");
        if (!item.encrypted) return source;
        File out = new File(tempDir, item.id + "_" + tag + item.ext);
        CryptoUtils.decryptFile(source, out);
        return out;
    }

    private void saveVideoFrameAsCover(File file, File cover, int second) throws Exception {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(file.getAbsolutePath());
            Bitmap frame = r.getFrameAtTime(Math.max(0, second) * 1000000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) throw new Exception("Frame alınamadı.");
            saveScaledJpeg(frame, cover, 720);
        } finally {
            releaseRetriever(r);
        }
    }

    private void saveImageAsCover(File image, File cover) throws Exception {
        Bitmap bmp = loadBitmap(image.getAbsolutePath(), 900);
        if (bmp == null) throw new Exception("Görsel kapak okunamadı.");
        saveScaledJpeg(bmp, cover, 720);
    }

    private Bitmap loadBitmap(String path, int maxSize) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);
            int sample = 1;
            int max = Math.max(bounds.outWidth, bounds.outHeight);
            while (max / sample > maxSize) sample = sample * 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = Math.max(1, sample);
            return BitmapFactory.decodeFile(path, opts);
        } catch (Exception e) {
            return null;
        }
    }

    private Bitmap placeholderBitmap(String type) {
        Bitmap b = Bitmap.createBitmap(420, 420, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(SURFACE_2);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(MUTED);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTextSize(60);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        c.drawText("video".equals(type) ? "▶" : "IMG", 210, 225, p);
        return b;
    }

    private void saveScaledJpeg(Bitmap src, File out, int maxSize) throws Exception {
        int w = src.getWidth();
        int h = src.getHeight();
        float ratio = Math.min(1f, maxSize / (float) Math.max(w, h));
        Bitmap scaled = src;
        if (ratio < 1f) {
            scaled = Bitmap.createScaledBitmap(src, Math.max(1, Math.round(w * ratio)), Math.max(1, Math.round(h * ratio)), true);
        }
        saveJpeg(scaled, out, 88);
        if (scaled != src) scaled.recycle();
    }

    private void saveJpeg(Bitmap bmp, File out, int quality) throws Exception {
        File parent = out.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(out);
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, fos);
        } finally {
            if (fos != null) try { fos.close(); } catch (Exception ignored) {}
        }
    }

    private void copyUriToFile(Uri uri, File out) throws Exception {
        File parent = out.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        InputStream in = null;
        FileOutputStream fos = null;
        try {
            in = getContentResolver().openInputStream(uri);
            if (in == null) throw new Exception("Dosya okunamadı.");
            fos = new FileOutputStream(out);
            byte[] buf = new byte[512 * 1024];
            int len;
            while ((len = in.read(buf)) != -1) fos.write(buf, 0, len);
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) {}
            if (fos != null) try { fos.close(); } catch (Exception ignored) {}
        }
    }

    private String getDisplayName(Uri uri) {
        String out = null;
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) out = c.getString(idx);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        if (out == null || out.trim().length() == 0) out = "media_" + System.currentTimeMillis();
        return out;
    }

    private boolean isVideo(String mime, String lowerName) {
        return (mime != null && mime.startsWith("video/")) || lowerName.endsWith(".mp4") || lowerName.endsWith(".mov") || lowerName.endsWith(".mkv") || lowerName.endsWith(".3gp") || lowerName.endsWith(".webm");
    }

    private boolean isImage(String mime, String lowerName) {
        return (mime != null && mime.startsWith("image/")) || lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") || lowerName.endsWith(".png") || lowerName.endsWith(".webp") || lowerName.endsWith(".gif");
    }

    private String extensionFromNameOrMime(String lowerName, String mime, boolean video) {
        int dot = lowerName.lastIndexOf('.');
        if (dot >= 0 && dot < lowerName.length() - 1) {
            String ext = lowerName.substring(dot);
            if (ext.length() <= 6) return ext;
        }
        if (mime != null) {
            if (mime.contains("png")) return ".png";
            if (mime.contains("webp")) return ".webp";
            if (mime.contains("gif")) return ".gif";
            if (mime.contains("quicktime")) return ".mov";
            if (mime.contains("matroska")) return ".mkv";
            if (mime.contains("3gpp")) return ".3gp";
            if (mime.contains("webm")) return ".webm";
        }
        return video ? ".mp4" : ".jpg";
    }

    private void loadItems() {
        items.clear();
        try {
            JSONArray arr = new JSONArray(prefs.getString(KEY_ITEMS, "[]"));
            for (int i = 0; i < arr.length(); i++) items.add(VaultItem.fromJson(arr.getJSONObject(i)));
        } catch (Exception ignored) {}
    }

    private void saveItems() {
        try {
            JSONArray arr = new JSONArray();
            for (int i = 0; i < items.size(); i++) arr.put(items.get(i).toJson());
            prefs.edit().putString(KEY_ITEMS, arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    private EditText pinInput(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setHintTextColor(MUTED);
        e.setTextColor(TEXT);
        e.setTextSize(20);
        e.setGravity(Gravity.CENTER);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        e.setBackground(round(SURFACE, dp(16), dp(1), SURFACE_2));
        e.setPadding(dp(14), 0, dp(14), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58));
        lp.setMargins(0, dp(8), 0, dp(8));
        e.setLayoutParams(lp);
        return e;
    }

    private TextView label(String s, int sp, int color, int gravity, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(gravity);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setPadding(dp(2), dp(3), dp(2), dp(3));
        return t;
    }

    private Button filledButton(String s, int color) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setTextSize(16);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setBackground(round(color, dp(18), 0));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        lp.setMargins(0, dp(14), 0, 0);
        b.setLayoutParams(lp);
        return b;
    }

    private Button pillButton(String s, int bg, int fg) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(fg);
        b.setTextSize(13);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setBackground(round(bg, dp(18), 0));
        return b;
    }

    private GradientDrawable round(int color, int radius, int stroke) {
        return round(color, radius, stroke, color);
    }

    private GradientDrawable round(int color, int radius, int stroke, int strokeColor) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        if (stroke > 0) d.setStroke(stroke, strokeColor);
        return d;
    }

    private String shortName(String name) {
        if (name == null || name.length() == 0) return "media";
        return name.length() > 18 ? name.substring(0, 15) + "..." : name;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    private void ensureDir(File f) {
        if (!f.exists()) f.mkdirs();
    }

    private void createNoMedia(File dir) {
        try {
            File n = new File(dir, ".nomedia");
            if (!n.exists()) n.createNewFile();
        } catch (Exception ignored) {}
    }

    private void safeDelete(File file) {
        try {
            if (file != null && file.exists()) file.delete();
        } catch (Exception ignored) {}
    }

    private void cleanTemp() {
        try {
            File[] files = tempDir.listFiles();
            if (files != null) {
                for (int i = 0; i < files.length; i++) safeDelete(files[i]);
            }
        } catch (Exception ignored) {}
    }

    private void releaseRetriever(MediaMetadataRetriever r) {
        try { r.release(); } catch (Exception ignored) {}
    }

    @Override
    protected void onDestroy() {
        cleanTemp();
        super.onDestroy();
    }

    static class ImportResult {
        boolean success;
        String error;
        static ImportResult ok() {
            ImportResult r = new ImportResult();
            r.success = true;
            return r;
        }
        static ImportResult fail(String e) {
            ImportResult r = new ImportResult();
            r.success = false;
            r.error = e == null ? "hata" : e;
            return r;
        }
    }

    static class VaultItem {
        String id;
        String name;
        String type;
        String mime;
        String ext;
        String vaultName;
        String coverName;
        int coverSecond;
        boolean encrypted;

        JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("name", name);
            o.put("type", type);
            o.put("mime", mime);
            o.put("ext", ext);
            o.put("vaultName", vaultName);
            o.put("coverName", coverName);
            o.put("coverSecond", coverSecond);
            o.put("encrypted", encrypted);
            return o;
        }

        static VaultItem fromJson(JSONObject o) {
            VaultItem item = new VaultItem();
            item.id = o.optString("id");
            item.name = o.optString("name", "media");
            item.type = o.optString("type", "image");
            item.mime = o.optString("mime", "");
            item.ext = o.optString("ext", "video".equals(item.type) ? ".mp4" : ".jpg");
            item.vaultName = o.optString("vaultName");
            item.coverName = o.optString("coverName");
            item.coverSecond = o.optInt("coverSecond", "video".equals(item.type) ? 1 : 0);
            item.encrypted = o.optBoolean("encrypted", false);
            return item;
        }
    }
}
