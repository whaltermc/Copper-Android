package git.artdeell.mojo.game.platform.dialog;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.view.KeyEvent;
import android.widget.SeekBar;
import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.kdt.mcgui.ExpressiveLoadingView;
import java.util.concurrent.atomic.AtomicReference;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.os.SystemClock;
import android.view.inputmethod.EditorInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import git.artdeell.mojo.R;
import git.artdeell.mojo.Tools;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Built-in file manager dialog, the Android counterpart of the tinyfd dialogs
 * on desktop: a plain filesystem browser returning real paths, with the usual
 * open/save/folder flavors. Files can be filtered by extension, multiple files
 * can be selected and save requests get a name field.
 */
public final class BuiltinFileDialog {
    private static final String TAG = "BuiltinFileDialog";
    private static final int DIALOG_IMPORT_FILES = 4;
    private static final ExecutorService FILE_OPERATIONS = Executors.newSingleThreadExecutor();
    private static final ExecutorService DIRECTORY_LISTING = Executors.newFixedThreadPool(2);

    /** Request code for shared storage access, only triggered when the user picks a shared external root. */
    private static final int REQ_SHARED_STORAGE = 0xC0DE;

    private static WeakReference<Dialog> sCurrentDialog;
    private static WeakReference<ResultCallback> sCurrentCallback;

    /** Pending shared-storage navigation, cleared once the permission request completes. */
    private static Activity pendingSharedHost;
    private static State pendingSharedState;
    private static File pendingSharedDir;

    private BuiltinFileDialog() {}

    /** Receives the picked paths, or null when the dialog was cancelled. */
    public interface ResultCallback {
        void onResult(@Nullable String[] paths);
    }

    private static boolean isSharedExternal(Activity host, File directory) {
        String abs = directory.getAbsolutePath();
        // Allow app's own external files directory without shared storage permission check
        File[] appDirectories = host.getExternalFilesDirs(null);
        if (appDirectories != null) {
            for (File appDirectory : appDirectories) {
                if (appDirectory != null) {
                    String appPath = appDirectory.getAbsolutePath();
                    if (abs.equals(appPath) || abs.startsWith(appPath + File.separator)) return false;
                }
            }
        }
        // Only consider truly shared storage roots (like /storage/emulated/0/), not app private dirs
        for (File root : sharedStorageRoots(host)) {
            String rootPath = root.getAbsolutePath();
            // Skip app-private directories that happen to be under shared storage
            if (abs.startsWith(rootPath + File.separator) && abs.contains("/Android/data/")) {
                continue;
            }
            if (abs.equals(rootPath) || abs.startsWith(rootPath + File.separator)) return true;
        }
        return false;
    }

    private static boolean sharedStoragePermissionGranted(Activity host) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return android.os.Environment.isExternalStorageManager();
        }
        return ContextCompat.checkSelfPermission(host, Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED
            && ContextCompat.checkSelfPermission(host, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static void requestSharedStorage(Activity host, State state, File directory) {
        pendingSharedHost = host;
        pendingSharedState = state;
        pendingSharedDir = directory;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent appSettings = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + host.getPackageName()));
            try {
                host.startActivity(appSettings);
            } catch (ActivityNotFoundException e) {
                try {
                    host.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                } catch (ActivityNotFoundException fallbackError) {
                    fallbackError.addSuppressed(e);
                    clearPendingSharedStorage();
                    Log.e(TAG, "Unable to open all-files access settings", fallbackError);
                    Toast.makeText(host, R.string.file_manager_storage_permission, Toast.LENGTH_LONG).show();
                }
            }
            return;
        }
        ActivityCompat.requestPermissions(host,
            new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE},
            REQ_SHARED_STORAGE);
    }

    /**
     * Show a file manager dialog of the given FileDialogBridge type. The
     * callback is invoked exactly once, from the UI thread.
     */
    public static void show(@NonNull Activity host, int type, @Nullable String title,
                            @Nullable String defaultPath, @Nullable String[] filterPatterns,
                            boolean allowMultiple, @NonNull ResultCallback callback) {
        show(host, type, title, defaultPath, filterPatterns, null, allowMultiple, callback);
    }

    public static void show(@NonNull Activity host, int type, @Nullable String title,
                            @Nullable String defaultPath, @Nullable String[] filterPatterns,
                            @Nullable String filterDescription, boolean allowMultiple,
                            @NonNull ResultCallback callback) {
        if (type == FileDialogBridge.DIALOG_SAVE_FILE) allowMultiple = false;
        List<String> patterns = parseFilterPatterns(filterPatterns);
        Dialog dialog = new Dialog(host);
        final boolean[] delivered = {false};
        dialog.setOnDismissListener(d -> {
            // Back button and outside touches read as cancellation.
            if (!delivered[0]) {
                delivered[0] = true;
                clearCurrent(dialog);
                callback.onResult(null);
            }
        });
        View content = buildContent(dialog, host, type, title, defaultPath, patterns,
                filterDescription, allowMultiple, paths -> {
                    delivered[0] = true;
                    clearCurrent(dialog);
                    dialog.dismiss();
                    callback.onResult(paths);
                });
        dialog.setContentView(content);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(R.drawable.bg_dialog_rounded);
            TypedValue animations = new TypedValue();
            if (host.getTheme().resolveAttribute(R.attr.copperDialogAnimations, animations, true)
                    && animations.resourceId != 0) {
                window.setWindowAnimations(animations.resourceId);
            }
        }
        sCurrentDialog = new WeakReference<>(dialog);
        sCurrentCallback = new WeakReference<>(callback);
        dialog.show();
        if (window != null) {
            boolean landscape = host.getResources().getConfiguration().orientation
                    == Configuration.ORIENTATION_LANDSCAPE;
            int width = Math.min(dp(host, landscape ? 1200 : 960),
                    host.getResources().getDisplayMetrics().widthPixels - dp(host, 16));
            int screenHeight = host.getResources().getDisplayMetrics().heightPixels;
            int height = landscape ? screenHeight - dp(host, 16)
                    : Math.min(dp(host, 900), Math.round(screenHeight * 0.9f));
            window.setLayout(width, height);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
    }

    /**
     * Show a color picker dialog returning "#RRGGBB", or null on cancel. It has a live preview,
     * a hex field, RGB sliders and a row of quick swatches. The result is delivered through
     * outResult[0] and the latch is counted down exactly once, from the UI thread.
     */
    public static void showColorPicker(@NonNull Activity host, @Nullable String title,
            @Nullable String defaultHex, @NonNull String[] outResult, @NonNull CountDownLatch latch) {
        final ColorPickerUi ui = new ColorPickerUi();
        final int initial = isValidHex(defaultHex)
                ? Color.parseColor(normalizeHex(defaultHex)) & 0xFFFFFF : 0xFFFFFF;

        LinearLayout box = new LinearLayout(host);
        box.setOrientation(LinearLayout.VERTICAL);

        ui.preview = new View(host);
        GradientDrawable previewShape = new GradientDrawable();
        previewShape.setCornerRadius(dp(host, 20));
        ui.preview.setBackground(previewShape);
        box.addView(ui.preview, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(host, 72)));

        TextInputLayout hexLayout = createInputLayout(host,
                host.getString(R.string.file_manager_color_hex), "",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                        | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        ui.hex = hexLayout.getEditText();
        LinearLayout.LayoutParams hexParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hexParams.topMargin = dp(host, 12);
        box.addView(hexLayout, hexParams);

        String[] channelNames = {"R", "G", "B"};
        for (int i = 0; i < 3; i++) {
            LinearLayout row = new LinearLayout(host);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView label = new TextView(host);
            label.setText(channelNames[i]);
            label.setTextSize(14f);
            label.setTextColor(MaterialColors.getColor(label, R.attr.colorOnSurfaceVariant));
            row.addView(label, new LinearLayout.LayoutParams(dp(host, 20),
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            SeekBar bar = new SeekBar(host);
            bar.setMax(255);
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (!fromUser) return;
                    ui.setColor(Color.rgb(ui.bars[0].getProgress(), ui.bars[1].getProgress(),
                            ui.bars[2].getProgress()), true);
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
            row.addView(bar, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView value = new TextView(host);
            value.setGravity(Gravity.END);
            value.setTextSize(13f);
            value.setTextColor(MaterialColors.getColor(value, R.attr.colorOnSurfaceVariant));
            row.addView(value, new LinearLayout.LayoutParams(dp(host, 34),
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            ui.bars[i] = bar;
            ui.values[i] = value;
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowParams.topMargin = dp(host, i == 0 ? 8 : 0);
            box.addView(row, rowParams);
        }

        int[] palette = {0xE53935, 0xFB8C00, 0xFDD835, 0x43A047, 0x00ACC1,
                0x1E88E5, 0x8E24AA, 0xFFFFFF, 0x9E9E9E, 0x000000};
        LinearLayout swatches = new LinearLayout(host);
        swatches.setOrientation(LinearLayout.HORIZONTAL);
        int outline = MaterialColors.getColor(host, R.attr.colorOutlineVariant, 0xFF888888);
        for (int swatchColor : palette) {
            View swatch = new View(host);
            GradientDrawable shape = new GradientDrawable();
            shape.setShape(GradientDrawable.OVAL);
            shape.setColor(0xFF000000 | swatchColor);
            shape.setStroke(dp(host, 1), outline);
            swatch.setBackground(shape);
            swatch.setOnClickListener(v -> ui.setColor(swatchColor, true));
            LinearLayout.LayoutParams swatchParams = new LinearLayout.LayoutParams(
                    dp(host, 34), dp(host, 34));
            swatchParams.setMarginEnd(dp(host, 8));
            swatches.addView(swatch, swatchParams);
        }
        HorizontalScrollView paletteScroll = new HorizontalScrollView(host);
        paletteScroll.setHorizontalScrollBarEnabled(false);
        paletteScroll.addView(swatches);
        LinearLayout.LayoutParams paletteParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        paletteParams.topMargin = dp(host, 12);
        box.addView(paletteScroll, paletteParams);

        ui.setColor(initial, true);
        ui.hex.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (ui.syncing) return;
                hexLayout.setError(null);
                String text = s.toString().trim();
                if (isValidHex(text)) {
                    ui.setColor(Color.parseColor(normalizeHex(text)), false);
                }
            }
        });

        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(host)
                .setTitle(title != null && !title.isEmpty()
                        ? title : host.getString(R.string.file_manager_color_title))
                .setView(wrapDialogContent(host, box))
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(android.R.string.cancel, (d, w) -> {
                    outResult[0] = null;
                    latch.countDown();
                })
                .setOnCancelListener(d -> {
                    outResult[0] = null;
                    latch.countDown();
                })
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String hex = ui.hex.getText().toString().trim();
                    if (!isValidHex(hex)) {
                        hexLayout.setError(host.getString(R.string.file_manager_color_invalid));
                        return;
                    }
                    outResult[0] = normalizeHex(hex);
                    latch.countDown();
                    dialog.dismiss();
                }));
        dialog.show();
    }

    /** Text field in the app's outlined style, for dialogs built in code. */
    public static TextInputLayout createInputLayout(@NonNull Activity host, @Nullable CharSequence hint,
                                                    @Nullable CharSequence text, int inputType) {
        TextInputLayout layout = new TextInputLayout(host);
        layout.setHint(hint);
        TextInputEditText edit = new TextInputEditText(layout.getContext());
        edit.setSingleLine(true);
        edit.setInputType(inputType);
        if (text != null) {
            edit.setText(text);
            edit.setSelection(edit.length());
        }
        layout.addView(edit, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return layout;
    }

    /** Gives custom dialog content the same side padding as the stock Material 3 dialogs. */
    public static View wrapDialogContent(@NonNull Activity host, @NonNull View content) {
        FrameLayout frame = new FrameLayout(host);
        frame.setPadding(dp(host, 24), dp(host, 8), dp(host, 24), 0);
        frame.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return frame;
    }

    /** Non-cancellable progress dialog with the Expressive loading indicator. */
    private static Dialog showProgress(Activity host, int titleRes) {
        LinearLayout box = new LinearLayout(host);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setOrientation(LinearLayout.HORIZONTAL);
        ExpressiveLoadingView loader = new ExpressiveLoadingView(host);
        loader.setIndeterminate(true);
        box.addView(loader, new LinearLayout.LayoutParams(dp(host, 44), dp(host, 44)));
        TextView text = new TextView(host);
        text.setText(R.string.file_manager_working);
        text.setTextSize(14f);
        text.setTextColor(MaterialColors.getColor(text, R.attr.colorOnSurfaceVariant));
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        textParams.setMarginStart(dp(host, 16));
        box.addView(text, textParams);
        Dialog progress = new MaterialAlertDialogBuilder(host)
                .setTitle(titleRes)
                .setView(wrapDialogContent(host, box))
                .setCancelable(false)
                .create();
        progress.show();
        return progress;
    }

    private static boolean isValidHex(String hex) {
        if (hex == null) return false;
        String h = hex.startsWith("#") ? hex.substring(1) : hex;
        if (h.length() != 6) return false;
        for (int i = 0; i < h.length(); i++) {
            char c = h.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                return false;
            }
        }
        return true;
    }

    private static String normalizeHex(String hex) {
        String h = hex.startsWith("#") ? hex.substring(1) : hex;
        return "#" + h.toUpperCase();
    }

    /**
     * Close the dialog that is currently open, if any, and deliver a null
     * result. Used to release game threads waiting on a dead activity.
     */
    public static void cancelCurrent() {
        clearPendingSharedStorage();
        while (true) {
            Dialog dialog;
            ResultCallback callback;
            synchronized (BuiltinFileDialog.class) {
                dialog = sCurrentDialog != null ? sCurrentDialog.get() : null;
                callback = sCurrentCallback != null ? sCurrentCallback.get() : null;
                sCurrentDialog = null;
                sCurrentCallback = null;
            }
            if (dialog != null) {
                dialog.setOnDismissListener(null);
                dialog.dismiss();
            }
            if (callback != null) callback.onResult(null);
            synchronized (BuiltinFileDialog.class) {
                if (sCurrentDialog == null) return;
            }
        }
    }

    /**
     * Host activities must forward {@link Activity#onRequestPermissionsResult} here so the
     * built-in file manager can retry shared external storage navigation after the user
     * grants or denies storage permission.
     */
    public static void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                                  @NonNull int[] grantResults) {
        if (requestCode != REQ_SHARED_STORAGE) return;
        boolean granted = grantResults.length > 0;
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                granted = false;
                break;
            }
        }
        Activity host = pendingSharedHost;
        State state = pendingSharedState;
        File directory = pendingSharedDir;
        boolean pendingUseFolder = state != null && state.pendingUseFolder;
        if (state != null) state.pendingUseFolder = false;
        clearPendingSharedStorage();
        if (granted && host != null && state != null && directory != null) {
            navigateTo(host, state, directory);
            if (pendingUseFolder) confirm(host, state);
        } else if (host != null) {
            Toast.makeText(host, R.string.file_manager_storage_permission, Toast.LENGTH_LONG).show();
        }
    }

    public static void onHostResumed(@NonNull Activity host) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || pendingSharedHost != host) return;
        State state = pendingSharedState;
        File directory = pendingSharedDir;
        boolean pendingUseFolder = state != null && state.pendingUseFolder;
        if (state != null) state.pendingUseFolder = false;
        clearPendingSharedStorage();
        if (sharedStoragePermissionGranted(host) && state != null && directory != null) {
            navigateTo(host, state, directory);
            if (pendingUseFolder) confirm(host, state);
        } else {
            Toast.makeText(host, R.string.file_manager_storage_permission, Toast.LENGTH_LONG).show();
        }
    }

    private static void clearPendingSharedStorage() {
        pendingSharedHost = null;
        pendingSharedState = null;
        pendingSharedDir = null;
    }

    private static void clearCurrent(Dialog dialog) {
        synchronized (BuiltinFileDialog.class) {
            if (sCurrentDialog != null && sCurrentDialog.get() == dialog) {
                sCurrentDialog = null;
                sCurrentCallback = null;
            }
        }
    }

    private static View buildContent(Dialog dialog, Activity host, int type, String title,
                                     String defaultPath, List<String> patterns,
                                     String filterDescription,
                                     boolean allowMultiple, ResultCallback finish) {
        LinearLayout root = new LinearLayout(host);
        root.setFocusableInTouchMode(true);
        State state = new State();
        Configuration config = host.getResources().getConfiguration();
        state.landscapeLayout = config.orientation == Configuration.ORIENTATION_LANDSCAPE;
        state.compactLayout = config.screenWidthDp < 600;
        state.shortScreen = config.screenHeightDp < 480;
        int padding = dp(host, state.compactLayout ? 12 : state.landscapeLayout ? 14 : 20);
        root.setPadding(padding, padding, padding, padding);

        state.type = type;
        state.filterPatterns = patterns;
        state.filterRegexes = compileFilters(patterns);
        state.filterEnabled = !patterns.isEmpty();
        state.allowMultiple = allowMultiple;
        state.finish = finish;
        state.dialog = dialog;
        state.restricted = type == FileDialogBridge.DIALOG_BROWSE;
        state.defaultPath = defaultPath;
        state.currentDir = initialDirectory(host, defaultPath, type);
        state.browseRoot = state.currentDir;

        LinearLayout content = (LinearLayout) LayoutInflater.from(host).inflate(
                R.layout.dialog_builtin_file_manager_content, root, false);

        // The manage dialog stays inside its folder, every other dialog can reach all locations.
        View sidebar = state.restricted ? null : buildSidebar(host, state);
        if (state.compactLayout) {
            root.setOrientation(LinearLayout.VERTICAL);
            if (sidebar != null) {
                LinearLayout.LayoutParams sidebarParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                sidebarParams.topMargin = dp(host, 10);
                content.addView(sidebar, 2, sidebarParams);
            }
            root.addView(content, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        } else {
            root.setOrientation(LinearLayout.HORIZONTAL);
            if (sidebar != null) {
                LinearLayout.LayoutParams sidebarParams = new LinearLayout.LayoutParams(
                        dp(host, state.landscapeLayout ? 168 : 188),
                        ViewGroup.LayoutParams.MATCH_PARENT);
                sidebarParams.setMarginEnd(dp(host, 12));
                root.addView(sidebar, sidebarParams);
            }
            root.addView(content, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        }

        TextView titleView = content.findViewById(R.id.file_manager_dialog_title);
        titleView.setText(title != null && !title.isEmpty()
                ? title : defaultDialogTitle(host, type, allowMultiple));
        if (state.shortScreen) titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);

        TextView hintView = content.findViewById(R.id.file_manager_dialog_hint);
        String hint = buildHint(host, state, filterDescription);
        if (!hint.isEmpty() && !state.shortScreen) {
            hintView.setText(hint);
            hintView.setVisibility(View.VISIBLE);
        }

        ListView listView = content.findViewById(R.id.file_manager_list);
        View columnsHeader = content.findViewById(R.id.file_manager_columns_header);
        columnsHeader.setVisibility(state.landscapeLayout ? View.VISIBLE : View.GONE);
        state.emptyContainer = content.findViewById(R.id.file_manager_empty_container);
        state.emptyView = content.findViewById(R.id.file_manager_empty);
        state.emptyLoader = content.findViewById(R.id.file_manager_loading);
        state.emptyView.setText(R.string.file_manager_loading);

        EntryAdapter adapter = new EntryAdapter(host, state);
        state.adapter = adapter;
        listView.setAdapter(adapter);
        listView.setEmptyView(state.emptyContainer);
        listView.setOnItemClickListener((parent, view, position, id) ->
                adapter.onRowClick(position));
        listView.setOnItemLongClickListener((parent, view, position, id) -> {
            FileInfo info = adapter.getInfo(position);
            if (info == null) return true;
            File entry = info.file;
            switch (state.type) {
                case FileDialogBridge.DIALOG_BROWSE:
                    showEntryActions(host, state, entry);
                    break;
                case FileDialogBridge.DIALOG_OPEN_FILE:
                case DIALOG_IMPORT_FILES:
                    if (!info.dir) adapter.toggleSelection(entry);
                    break;
                case FileDialogBridge.DIALOG_OPEN_FOLDER:
                    if (info.dir) {
                        state.focusedEntry = entry.equals(state.focusedEntry) ? null : entry;
                        adapter.notifyDataSetChanged();
                        updateSummary(host, state);
                    }
                    break;
                default:
                    break;
            }
            return true;
        });

        state.upButton = content.findViewById(R.id.file_manager_up);
        state.pathView = content.findViewById(R.id.file_manager_path);
        state.refreshButton = content.findViewById(R.id.file_manager_refresh);
        state.sortButton = content.findViewById(R.id.file_manager_sort);
        state.hiddenButton = content.findViewById(R.id.file_manager_hidden);
        state.newFolderButton = content.findViewById(R.id.file_manager_new_folder);
        state.filterButton = content.findViewById(R.id.file_manager_filter);
        state.selectAllButton = content.findViewById(R.id.file_manager_select_all);
        state.searchInput = content.findViewById(R.id.file_manager_search);
        state.systemButton = content.findViewById(R.id.file_manager_system);
        state.pathView.setOnClickListener(v -> promptPath(host, state));
        state.searchInput.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                android.view.inputmethod.InputMethodManager inputMethodManager =
                        (android.view.inputmethod.InputMethodManager)
                                host.getSystemService(Activity.INPUT_METHOD_SERVICE);
                if (inputMethodManager != null) {
                    inputMethodManager.hideSoftInputFromWindow(view.getWindowToken(), 0);
                }
                return true;
            }
            return false;
        });
        state.searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                state.searchQuery = s.toString().trim();
                state.focusedEntry = null;
                state.selection.clear();
                adapter.setSearchQuery(state.searchQuery);
                onSelectionChanged(host, state);
                updateEmptyMessage(state);
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        state.sortButton.setOnClickListener(v -> showSortMenu(host, state));
        state.systemButton.setOnClickListener(v -> openSystemPicker(host, state));
        state.upButton.setOnClickListener(v -> navigateUp(host, state));
        state.refreshButton.setOnClickListener(v -> refreshCurrentDirectory(host, state));
        state.newFolderButton.setOnClickListener(v -> promptNewFolder(host, state));
        state.selectAllButton.setOnClickListener(v -> toggleSelectAll(host, state));
        state.hiddenButton.setOnClickListener(v -> {
            state.showHidden = !state.showHidden;
            adapter.refilter();
            updateToolbar(host, state);
            updateEmptyMessage(state);
        });
        state.filterButton.setOnClickListener(v -> {
            state.filterEnabled = !state.filterEnabled;
            adapter.refilter();
            updateToolbar(host, state);
            updateEmptyMessage(state);
        });

        arrangeToolbar(host, state, content);

        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        content.addView(buildBottomBar(host, state, defaultPath), barParams);
        updateToolbar(host, state);

        state.searchInput.clearFocus();
        root.requestFocus();
        dialog.setOnShowListener(d -> navigateTo(host, state, state.currentDir));
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (keyCode != KeyEvent.KEYCODE_BACK || event.getAction() != KeyEvent.ACTION_UP
                    || event.isCanceled()) {
                return false;
            }
            if (!state.searchQuery.isEmpty()) {
                state.searchInput.setText("");
                return true;
            }
            if (!state.selection.isEmpty()) {
                state.selection.clear();
                state.adapter.notifyDataSetChanged();
                onSelectionChanged(host, state);
                return true;
            }
            return navigateUp(host, state);
        });
        return root;
    }

    /**
     * Phones in portrait stack the search field above the tool buttons. Short landscape screens
     * share the title line with them so the list keeps as much height as possible.
     */
    private static void arrangeToolbar(Activity host, State state, LinearLayout content) {
        LinearLayout toolsRow = content.findViewById(R.id.file_manager_tools_row);
        View searchPill = content.findViewById(R.id.file_manager_search_pill);
        View toolsScroll = content.findViewById(R.id.file_manager_tools_scroll);
        if (state.compactLayout) {
            toolsRow.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams pillParams = (LinearLayout.LayoutParams) searchPill.getLayoutParams();
            pillParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
            pillParams.weight = 0;
            searchPill.setLayoutParams(pillParams);
            LinearLayout.LayoutParams toolsParams = (LinearLayout.LayoutParams) toolsScroll.getLayoutParams();
            toolsParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
            toolsParams.setMarginStart(0);
            toolsParams.topMargin = dp(host, 8);
            toolsScroll.setLayoutParams(toolsParams);
        } else if (state.shortScreen) {
            TextView titleView = content.findViewById(R.id.file_manager_dialog_title);
            LinearLayout headerRow = new LinearLayout(host);
            headerRow.setOrientation(LinearLayout.HORIZONTAL);
            headerRow.setGravity(Gravity.CENTER_VERTICAL);
            toolsRow.removeView(searchPill);
            toolsRow.removeView(toolsScroll);
            content.removeView(toolsRow);
            content.removeView(titleView);
            headerRow.addView(titleView, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            LinearLayout.LayoutParams pillParams = new LinearLayout.LayoutParams(
                    dp(host, 200), dp(host, 44));
            pillParams.setMarginStart(dp(host, 8));
            headerRow.addView(searchPill, pillParams);
            LinearLayout.LayoutParams toolsParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            toolsParams.setMarginStart(dp(host, 8));
            headerRow.addView(toolsScroll, toolsParams);
            content.addView(headerRow, 0, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    private static String buildHint(Activity host, State state, String filterDescription) {
        StringBuilder hint = new StringBuilder();
        int hintRes;
        switch (state.type) {
            case FileDialogBridge.DIALOG_OPEN_FOLDER:
                hintRes = R.string.file_manager_folder_hint;
                break;
            case FileDialogBridge.DIALOG_BROWSE:
                hintRes = R.string.file_manager_manage_hint;
                break;
            case DIALOG_IMPORT_FILES:
                hintRes = R.string.file_manager_import_hint;
                break;
            case FileDialogBridge.DIALOG_OPEN_FILE:
                hintRes = R.string.file_manager_open_hint;
                break;
            default:
                hintRes = 0;
                break;
        }
        if (hintRes != 0) hint.append(host.getString(hintRes));
        if (state.type != FileDialogBridge.DIALOG_OPEN_FOLDER && !state.filterPatterns.isEmpty()) {
            String filter = filterDescription != null ? filterDescription.trim() : "";
            if (filter.isEmpty()) filter = android.text.TextUtils.join(", ", state.filterPatterns);
            if (hint.length() > 0) hint.append(' ');
            hint.append(host.getString(R.string.file_manager_filter_label, filter));
        }
        return hint.toString();
    }

    /** One entry of the locations list shown beside (or above) the file list. */
    private static final class Location {
        final File dir;
        final String label;
        final int icon;

        Location(File dir, String label, int icon) {
            this.dir = dir;
            this.label = label;
            this.icon = icon;
        }
    }

    private static void addLocation(List<Location> list, File dir, String label, int icon) {
        if (dir == null || !dir.isDirectory()) return;
        for (Location existing : list) {
            if (existing.dir.equals(dir)) return;
        }
        list.add(new Location(dir, label, icon));
    }

    private static boolean pathContains(File parent, File child) {
        String parentPath = parent.getAbsolutePath();
        String childPath = child.getAbsolutePath();
        return childPath.equals(parentPath)
                || childPath.startsWith(parentPath.endsWith(File.separator)
                ? parentPath : parentPath + File.separator);
    }

    private static List<Location> buildLocations(Activity host, State state) {
        List<Location> result = new ArrayList<>();
        if (state.restricted) {
            result.add(new Location(state.browseRoot, rootName(host, state.browseRoot),
                    R.drawable.ic_px_folder));
            return result;
        }
        addLocation(result, new File(Tools.DIR_GAME_HOME),
                host.getString(R.string.file_manager_root_game), R.drawable.ic_px_folder);
        addLocation(result, host.getExternalFilesDir(null),
                host.getString(R.string.file_manager_root_app), R.drawable.ic_px_folder);
        File primary = android.os.Environment.getExternalStorageDirectory();
        for (File root : sharedStorageRoots(host)) {
            addLocation(result, root, rootName(host, root),
                    root.equals(primary) ? R.drawable.ic_px_storage : R.drawable.ic_px_sd_card);
        }
        if (primary != null) {
            addLocation(result, new File(primary, "Download"),
                    host.getString(R.string.file_manager_root_downloads), R.drawable.ic_px_file_dl);
            addLocation(result, new File(primary, "Documents"),
                    host.getString(R.string.file_manager_root_documents), R.drawable.ic_px_file);
            addLocation(result, new File(primary, "Pictures"),
                    host.getString(R.string.file_manager_root_pictures), R.drawable.ic_px_image);
        }
        boolean covered = false;
        for (Location location : result) {
            if (pathContains(location.dir, state.currentDir)) {
                covered = true;
                break;
            }
        }
        if (!covered) {
            result.add(0, new Location(state.currentDir, rootName(host, state.currentDir),
                    R.drawable.ic_px_folder));
        }
        return result;
    }

    private static View buildSidebar(Activity host, State state) {
        LinearLayout sidebar = new LinearLayout(host);
        sidebar.setOrientation(LinearLayout.VERTICAL);
        if (!state.compactLayout) {
            sidebar.setBackgroundResource(R.drawable.bg_file_manager_sidebar);
            sidebar.setPadding(dp(host, 8), dp(host, 14), dp(host, 8), dp(host, 8));
            TextView heading = new TextView(host);
            heading.setText(state.restricted
                    ? R.string.file_manager_roots : R.string.file_manager_locations);
            heading.setTextSize(12f);
            heading.setTextColor(MaterialColors.getColor(heading, R.attr.colorOnSurfaceVariant));
            heading.setTypeface(null, android.graphics.Typeface.BOLD);
            heading.setPadding(dp(host, 12), 0, dp(host, 8), dp(host, 8));
            sidebar.addView(heading);
        }

        LinearLayout locations = new LinearLayout(host);
        locations.setOrientation(state.compactLayout
                ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        state.locations.clear();
        state.locationButtons.clear();
        for (Location location : buildLocations(host, state)) {
            File directory = location.dir;
            MaterialButton button = new MaterialButton(host);
            button.setText(location.label);
            button.setTextSize(13f);
            button.setAllCaps(false);
            button.setMaxLines(1);
            button.setEllipsize(android.text.TextUtils.TruncateAt.END);
            button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            button.setIconResource(location.icon);
            button.setIconSize(dp(host, 18));
            button.setIconPadding(dp(host, 10));
            button.setCornerRadius(dp(host, state.compactLayout ? 20 : 16));
            button.setMinWidth(0);
            button.setMinHeight(dp(host, state.compactLayout ? 40 : 48));
            button.setInsetTop(0);
            button.setInsetBottom(0);
            button.setPadding(dp(host, 14), 0, dp(host, 14), 0);
            button.setOnClickListener(v -> navigateTo(host, state, directory));
            LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                    state.compactLayout ? ViewGroup.LayoutParams.WRAP_CONTENT
                            : ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            if (state.compactLayout) {
                buttonParams.setMarginEnd(dp(host, 8));
            } else {
                buttonParams.bottomMargin = dp(host, 4);
            }
            locations.addView(button, buttonParams);
            state.locations.add(directory);
            state.locationButtons.add(button);
        }
        if (state.compactLayout) {
            HorizontalScrollView scroll = new HorizontalScrollView(host);
            scroll.setHorizontalScrollBarEnabled(false);
            scroll.setClipToPadding(false);
            scroll.addView(locations, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            sidebar.addView(scroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            ScrollView scroll = new ScrollView(host);
            scroll.setFillViewport(true);
            scroll.addView(locations, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            sidebar.addView(scroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        }
        return sidebar;
    }

    private static View buildBottomBar(Activity host, State state, String defaultPath) {
        LinearLayout bar = new LinearLayout(host);
        bar.setOrientation(LinearLayout.VERTICAL);
        state.bottomBar = bar;
        state.defaultPath = defaultPath;
        refreshBottomBar(host, state);
        return bar;
    }

    private static final int BUTTON_FILLED = 0;
    private static final int BUTTON_TONAL = 1;
    private static final int BUTTON_TEXT = 2;

    private static void refreshBottomBar(Activity host, State state) {
        LinearLayout bar = state.bottomBar;
        if (bar == null) return;
        bar.removeAllViews();
        bar.setPadding(0, dp(host, state.shortScreen ? 8 : 12), 0, 0);
        state.selectionButton = null;
        state.fileNameInput = null;

        if (state.type == FileDialogBridge.DIALOG_SAVE_FILE) {
            TextInputLayout nameLayout = createInputLayout(host,
                    host.getString(R.string.file_manager_file_name_hint), null,
                    InputType.TYPE_CLASS_TEXT);
            EditText nameField = nameLayout.getEditText();
            if (state.fileName == null) state.fileName = defaultSaveName(host, state.defaultPath);
            nameField.setText(state.fileName);
            nameField.setSelection(nameField.length());
            nameField.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override public void afterTextChanged(Editable s) {
                    state.fileName = s.toString();
                }
            });
            state.fileNameInput = nameField;
            LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            nameParams.bottomMargin = dp(host, 8);
            bar.addView(nameLayout, nameParams);
        }

        LinearLayout actions = new LinearLayout(host);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);

        TextView summary = new TextView(host);
        summary.setTextSize(13f);
        summary.setMaxLines(1);
        summary.setEllipsize(android.text.TextUtils.TruncateAt.END);
        summary.setTextColor(MaterialColors.getColor(summary, R.attr.colorOnSurfaceVariant));
        summary.setPadding(dp(host, 4), 0, dp(host, 8), 0);
        state.summaryView = summary;
        actions.addView(summary, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        boolean scrolls = false;
        switch (state.type) {
            case FileDialogBridge.DIALOG_BROWSE:
                scrolls = state.compactLayout;
                if (state.manageMode) {
                    if (!state.selection.isEmpty()) {
                        addIconButton(host, actions, R.drawable.ic_px_copy, R.string.file_manager_copy,
                                false, v -> chooseDestination(host, state, MODE_COPY));
                        addIconButton(host, actions, R.drawable.ic_px_move, R.string.file_manager_move,
                                false, v -> chooseDestination(host, state, MODE_MOVE));
                        if (state.selection.size() == 1) {
                            File selected = state.selection.iterator().next();
                            addIconButton(host, actions, R.drawable.ic_px_edit,
                                    R.string.file_manager_rename, false,
                                    v -> promptRename(host, state, selected));
                        }
                        addIconButton(host, actions, R.drawable.ic_px_trash, R.string.global_delete,
                                true, v -> confirmDeleteSelected(host, state));
                    }
                    addButton(host, actions, BUTTON_TONAL, R.string.file_manager_import_here,
                            R.drawable.ic_px_file_add, v -> importFiles(host, state));
                    addButton(host, actions, BUTTON_FILLED, R.string.file_manager_done, 0, v -> {
                        state.manageMode = false;
                        state.selection.clear();
                        state.adapter.notifyDataSetChanged();
                        refreshBottomBar(host, state);
                        updateToolbar(host, state);
                    });
                } else {
                    addButton(host, actions, BUTTON_TEXT, R.string.file_manager_manage, 0, v -> {
                        state.manageMode = true;
                        state.selection.clear();
                        state.adapter.notifyDataSetChanged();
                        refreshBottomBar(host, state);
                        updateToolbar(host, state);
                    });
                    addButton(host, actions, BUTTON_TONAL, R.string.file_manager_import_here,
                            R.drawable.ic_px_file_add, v -> importFiles(host, state));
                    addButton(host, actions, BUTTON_FILLED, R.string.file_manager_done, 0,
                            v -> state.finish.onResult(null));
                }
                break;
            case FileDialogBridge.DIALOG_SAVE_FILE:
                addButton(host, actions, BUTTON_TEXT, R.string.global_cancel, 0,
                        v -> state.finish.onResult(null));
                addButton(host, actions, BUTTON_FILLED, R.string.file_manager_save, 0,
                        v -> confirm(host, state));
                break;
            case FileDialogBridge.DIALOG_OPEN_FOLDER:
                addButton(host, actions, BUTTON_TEXT, R.string.global_cancel, 0,
                        v -> state.finish.onResult(null));
                addButton(host, actions, BUTTON_FILLED, R.string.file_manager_use_folder, 0,
                        v -> confirm(host, state));
                break;
            default:
                addButton(host, actions, BUTTON_TEXT, R.string.global_cancel, 0,
                        v -> state.finish.onResult(null));
                state.selectionButton = addButton(host, actions, BUTTON_FILLED,
                        state.type == DIALOG_IMPORT_FILES
                                ? R.string.file_manager_import_selected : R.string.file_manager_open,
                        0, v -> confirm(host, state));
                break;
        }

        if (scrolls) {
            HorizontalScrollView scroll = new HorizontalScrollView(host);
            scroll.setHorizontalScrollBarEnabled(false);
            scroll.setFillViewport(true);
            scroll.setClipToPadding(false);
            scroll.addView(actions, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            bar.addView(scroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            bar.addView(actions, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        updateSelectionButton(host, state);
        updateSummary(host, state);
    }

    private static MaterialButton addButton(Activity host, LinearLayout row, int style, int label,
                                            int icon, View.OnClickListener listener) {
        int layout = style == BUTTON_FILLED ? R.layout.view_file_manager_button_filled
                : style == BUTTON_TONAL ? R.layout.view_file_manager_button_tonal
                : R.layout.view_file_manager_button_text;
        MaterialButton button = (MaterialButton) LayoutInflater.from(host).inflate(layout, row, false);
        button.setText(label);
        if (icon != 0) button.setIconResource(icon);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMarginStart(dp(host, 6));
        row.addView(button, params);
        return button;
    }

    private static void addIconButton(Activity host, LinearLayout row, int icon, int description,
                                      boolean destructive, View.OnClickListener listener) {
        MaterialButton button = (MaterialButton) LayoutInflater.from(host).inflate(
                R.layout.view_file_manager_button_icon, row, false);
        button.setIconResource(icon);
        button.setContentDescription(host.getString(description));
        if (destructive) {
            button.setIconTint(ColorStateList.valueOf(
                    MaterialColors.getColor(button, R.attr.colorError)));
        }
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                dp(host, 44), dp(host, 44));
        params.setMarginStart(dp(host, 6));
        row.addView(button, params);
    }

    /** Keeps the summary line and tool buttons in sync with the state of the dialog. */
    private static void onSelectionChanged(Activity host, State state) {
        updateSelectionButton(host, state);
        updateSummary(host, state);
        if (state.type == FileDialogBridge.DIALOG_BROWSE && state.manageMode) {
            refreshBottomBar(host, state);
        }
        updateToolbar(host, state);
    }

    private static void updateSummary(Activity host, State state) {
        if (state.summaryView == null) return;
        String text = "";
        switch (state.type) {
            case FileDialogBridge.DIALOG_OPEN_FILE:
            case DIALOG_IMPORT_FILES:
                if (!state.selection.isEmpty()) {
                    text = host.getString(R.string.file_manager_selected_count, state.selection.size());
                }
                break;
            case FileDialogBridge.DIALOG_BROWSE:
                if (state.manageMode) {
                    text = host.getString(R.string.file_manager_selected_count, state.selection.size());
                }
                break;
            case FileDialogBridge.DIALOG_OPEN_FOLDER: {
                File target = state.focusedEntry != null && state.focusedEntry.isDirectory()
                        ? state.focusedEntry : state.currentDir;
                String name = target.getName().isEmpty() ? target.getPath() : target.getName();
                text = host.getString(R.string.file_manager_folder_target, name);
                break;
            }
            default:
                break;
        }
        state.summaryView.setText(text);
    }

    private static void updateToolbar(Activity host, State state) {
        boolean multiSelect = state.allowMultiple
                && (state.type == FileDialogBridge.DIALOG_OPEN_FILE || state.type == DIALOG_IMPORT_FILES);
        boolean manage = state.type == FileDialogBridge.DIALOG_BROWSE && state.manageMode;
        state.selectAllButton.setVisibility(multiSelect || manage ? View.VISIBLE : View.GONE);
        boolean canCreate = state.type == FileDialogBridge.DIALOG_SAVE_FILE
                || state.type == FileDialogBridge.DIALOG_OPEN_FOLDER
                || state.type == FileDialogBridge.DIALOG_BROWSE;
        state.newFolderButton.setVisibility(canCreate ? View.VISIBLE : View.GONE);
        boolean hasFilter = state.type != FileDialogBridge.DIALOG_OPEN_FOLDER
                && !state.filterPatterns.isEmpty();
        state.filterButton.setVisibility(hasFilter ? View.VISIBLE : View.GONE);
        state.systemButton.setVisibility(state.type == FileDialogBridge.DIALOG_OPEN_FILE
                || state.type == DIALOG_IMPORT_FILES
                || state.type == FileDialogBridge.DIALOG_OPEN_FOLDER ? View.VISIBLE : View.GONE);
        state.filterButton.setCheckable(true);
        state.filterButton.setChecked(state.filterEnabled);
        state.filterButton.setContentDescription(host.getString(state.filterEnabled
                ? R.string.file_manager_show_all_files : R.string.file_manager_apply_filter));
        state.hiddenButton.setIconResource(state.showHidden
                ? R.drawable.ic_px_eye : R.drawable.ic_px_eye_off);
        state.hiddenButton.setContentDescription(host.getString(state.showHidden
                ? R.string.file_manager_hide_hidden : R.string.file_manager_show_hidden));
        updateSortButton(state);
    }

    private static void updateSortButton(State state) {
        state.sortButton.setText(sortLabel(state.sortMode));
        state.sortButton.setIconResource(state.sortDescending
                ? R.drawable.ic_px_sort_desc : R.drawable.ic_px_sort_asc);
    }

    private static void toggleSelectAll(Activity host, State state) {
        List<File> candidates = new ArrayList<>();
        for (FileInfo info : state.adapter.entries) {
            if (state.type == FileDialogBridge.DIALOG_BROWSE || !info.dir) candidates.add(info.file);
        }
        if (candidates.isEmpty()) return;
        if (state.selection.containsAll(candidates)) {
            state.selection.removeAll(candidates);
        } else {
            state.selection.addAll(candidates);
        }
        state.focusedEntry = null;
        state.adapter.notifyDataSetChanged();
        onSelectionChanged(host, state);
    }

    private static void confirm(Activity host, State state) {
        if (state.type == FileDialogBridge.DIALOG_BROWSE) return;
        if (state.type == FileDialogBridge.DIALOG_OPEN_FOLDER) {
            File selectedDirectory = state.focusedEntry != null
                    && state.focusedEntry.isDirectory() ? state.focusedEntry : state.currentDir;
            if (isSharedExternal(host, selectedDirectory)
                    && !sharedStoragePermissionGranted(host)) {
                state.pendingUseFolder = true;
                requestSharedStorage(host, state, selectedDirectory);
                return;
            }
            if (!selectedDirectory.canRead() || !selectedDirectory.canWrite()) {
                Toast.makeText(host, R.string.file_manager_folder_access_failed,
                        Toast.LENGTH_LONG).show();
                return;
            }
            state.finish.onResult(new String[]{selectedDirectory.getAbsolutePath()});
            return;
        }
        if (state.type == FileDialogBridge.DIALOG_SAVE_FILE) {
            if (isSharedExternal(host, state.currentDir)
                    && !sharedStoragePermissionGranted(host)) {
                state.pendingUseFolder = true;
                requestSharedStorage(host, state, state.currentDir);
                return;
            }
            if (!state.currentDir.canRead() || !state.currentDir.canWrite()) {
                Toast.makeText(host, R.string.file_manager_folder_access_failed,
                        Toast.LENGTH_LONG).show();
                return;
            }
            String name = state.fileName != null ? state.fileName.trim() : "";
            if (!isValidEntryName(name)) {
                Toast.makeText(host, R.string.file_manager_name_invalid, Toast.LENGTH_SHORT).show();
                return;
            }
            String extension = firstSaveExtension(state.filterPatterns);
            if (!name.contains(".") && extension != null) {
                name = name + "." + extension;
            }
            File target = new File(state.currentDir, name);
            if (target.exists()) {
                new MaterialAlertDialogBuilder(host)
                        .setTitle(R.string.file_manager_overwrite_title)
                        .setMessage(host.getString(R.string.file_manager_overwrite_message, name))
                        .setPositiveButton(R.string.global_yes, (d, w) ->
                                state.finish.onResult(new String[]{target.getAbsolutePath()}))
                        .setNegativeButton(R.string.global_no, null)
                        .show();
                return;
            }
            state.finish.onResult(new String[]{target.getAbsolutePath()});
            return;
        }
        List<File> selected = new ArrayList<>(state.selection);
        if (selected.size() != 1 && !state.allowMultiple) {
            Toast.makeText(host, R.string.file_manager_nothing_selected, Toast.LENGTH_SHORT).show();
            return;
        }
        String[] paths = new String[selected.size()];
        for (int i = 0; i < selected.size(); i++) {
            File entry = selected.get(i);
            if (!entry.isFile() || !entry.canRead()) {
                Toast.makeText(host, R.string.file_manager_unreadable, Toast.LENGTH_LONG).show();
                return;
            }
            paths[i] = entry.getAbsolutePath();
        }
        state.finish.onResult(paths);
    }

    private interface NameCallback {
        void onName(String name);
    }

    /** Asks for a file or folder name; rejects empty, invalid and already used names. */
    private static void promptName(Activity host, State state, int titleRes, int positiveRes,
                                   @Nullable String initial, NameCallback callback) {
        TextInputLayout input = createInputLayout(host, host.getString(titleRes == R.string.file_manager_new_folder
                ? R.string.file_manager_folder_name_hint : R.string.file_manager_file_name_hint),
                initial, InputType.TYPE_CLASS_TEXT);
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(host)
                .setTitle(titleRes)
                .setView(wrapDialogContent(host, input))
                .setPositiveButton(positiveRes, null)
                .setNegativeButton(R.string.global_cancel, null)
                .create();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
        dialog.setOnShowListener(ignored -> {
            input.getEditText().requestFocus();
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String name = input.getEditText().getText().toString().trim();
                if (name.isEmpty()) {
                    input.setError(host.getString(R.string.global_error_field_empty));
                    return;
                }
                if (!isValidEntryName(name)) {
                    input.setError(host.getString(R.string.file_manager_name_invalid));
                    return;
                }
                if (name.equals(initial)) {
                    dialog.dismiss();
                    return;
                }
                if (new File(state.currentDir, name).exists()) {
                    input.setError(host.getString(R.string.file_manager_name_exists));
                    return;
                }
                dialog.dismiss();
                callback.onName(name);
            });
        });
        dialog.show();
    }

    /** Prompts for a new folder name and creates it inside the current directory. */
    private static void promptNewFolder(Activity host, State state) {
        promptName(host, state, R.string.file_manager_new_folder, R.string.file_manager_create,
                null, name -> createFolder(host, state, name));
    }

    private static void createFolder(Activity host, State state, String name) {
        if (!isValidEntryName(name)) {
            Toast.makeText(host, R.string.file_manager_name_invalid, Toast.LENGTH_SHORT).show();
            return;
        }
        File target = new File(state.currentDir, name);
        if (target.exists()) {
            Toast.makeText(host, R.string.file_manager_operation_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        if (!target.mkdirs()) {
            Toast.makeText(host, R.string.file_manager_operation_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        navigateTo(host, state, state.currentDir);
    }

    /** Shows file operations for a file or folder entry. */
    private static void showEntryActions(Activity host, State state, File entry) {
        boolean selected = state.selection.contains(entry);
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        if (entry.isDirectory()) {
            labels.add(host.getString(R.string.file_manager_open_folder));
            actions.add(() -> navigateTo(host, state, entry));
        } else {
            labels.add(host.getString(R.string.file_manager_open_item));
            actions.add(() -> Tools.openPath(host, entry, false));
        }
        labels.add(host.getString(selected ? R.string.file_manager_deselect
                : R.string.file_manager_select));
        actions.add(() -> {
            state.manageMode = true;
            if (!state.selection.remove(entry)) state.selection.add(entry);
            state.adapter.notifyDataSetChanged();
            onSelectionChanged(host, state);
        });
        labels.add(host.getString(R.string.file_manager_copy));
        actions.add(() -> {
            selectOnly(state, entry);
            chooseDestination(host, state, MODE_COPY);
        });
        labels.add(host.getString(R.string.file_manager_move));
        actions.add(() -> {
            selectOnly(state, entry);
            chooseDestination(host, state, MODE_MOVE);
        });
        labels.add(host.getString(R.string.file_manager_rename));
        actions.add(() -> promptRename(host, state, entry));
        labels.add(host.getString(R.string.global_delete));
        actions.add(() -> confirmDelete(host, state, entry));
        new MaterialAlertDialogBuilder(host)
                .setTitle(entry.getName())
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .setNegativeButton(R.string.global_cancel, null)
                .show();
    }

    /** Makes the entry the whole selection unless it is already part of a bigger one. */
    private static void selectOnly(State state, File entry) {
        state.manageMode = true;
        if (!state.selection.contains(entry)) {
            state.selection.clear();
            state.selection.add(entry);
        }
        state.adapter.notifyDataSetChanged();
    }

    /** Prompts for a new name and renames the entry. */
    private static void promptRename(Activity host, State state, File entry) {
        promptName(host, state, R.string.file_manager_rename, R.string.file_manager_rename,
                entry.getName(), name -> renameEntry(host, state, entry, name));
    }

    private static void renameEntry(Activity host, State state, File entry, String name) {
        if (!isValidEntryName(name)) {
            Toast.makeText(host, R.string.file_manager_name_invalid, Toast.LENGTH_SHORT).show();
            return;
        }
        File target = new File(entry.getParentFile(), name);
        if (target.exists() || !entry.renameTo(target)) {
            Toast.makeText(host, R.string.file_manager_operation_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        state.selection.remove(entry);
        refreshCurrentDirectory(host, state);
        onSelectionChanged(host, state);
    }

    private static boolean isValidEntryName(String name) {
        return name != null && !name.isEmpty() && !name.equals(".") && !name.equals("..")
                && name.indexOf('/') < 0 && name.indexOf('\\') < 0;
    }

    /** Confirms deletion then removes the entry (recursively for folders). */
    private static void confirmDelete(Activity host, State state, File entry) {
        new MaterialAlertDialogBuilder(host)
                .setTitle(R.string.global_delete)
                .setMessage(host.getString(R.string.file_manager_delete_confirm, entry.getName()))
                .setPositiveButton(R.string.global_yes, (d, w) ->
                        deleteEntries(host, state, Collections.singletonList(entry)))
                .setNegativeButton(R.string.global_no, null)
                .show();
    }

    /** Deletes the entries off the UI thread, behind a progress dialog. */
    private static void deleteEntries(Activity host, State state, List<File> entries) {
        Dialog progress = showProgress(host, R.string.global_delete);
        FILE_OPERATIONS.execute(() -> {
            List<File> failed = new ArrayList<>();
            for (File entry : entries) {
                if (!deleteRecursively(entry)) failed.add(entry);
            }
            host.runOnUiThread(() -> {
                progress.dismiss();
                if (!failed.isEmpty()) {
                    Toast.makeText(host, host.getString(R.string.file_manager_operation_failures,
                            failed.size(), failed.get(0).getName()), Toast.LENGTH_LONG).show();
                }
                if (!host.isFinishing() && !host.isDestroyed()
                        && state.dialog != null && state.dialog.isShowing()) {
                    state.selection.removeAll(entries);
                    refreshCurrentDirectory(host, state);
                    onSelectionChanged(host, state);
                }
            });
        });
    }

    private static boolean deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!deleteRecursively(child)) return false;
                }
            }
        }
        return file.delete();
    }

    private static final int MODE_IMPORT = 0;
    private static final int MODE_MOVE = 1;
    private static final int MODE_COPY = 2;

    private static void importFiles(Activity host, State parent) {
        File destination = parent.currentDir;
        File downloads = new File(android.os.Environment.getExternalStorageDirectory(), "Download");
        String start = downloads.isDirectory() ? downloads.getAbsolutePath()
                : parent.browseRoot.getAbsolutePath();
        show(host, DIALOG_IMPORT_FILES,
                host.getString(R.string.file_manager_import_title),
                start, null, true, paths -> {
                    restoreParentDialog(parent);
                    if (paths == null || paths.length == 0) return;
                    List<File> sources = new ArrayList<>();
                    for (String path : paths) {
                        if (path != null) sources.add(new File(path));
                    }
                    if (sources.isEmpty()) return;
                    confirmConflicts(host, parent, sources, destination, MODE_IMPORT);
                });
    }

    /** Asks for a destination folder, then moves or copies the selection there. */
    private static void chooseDestination(Activity host, State parent, int mode) {
        List<File> sources = new ArrayList<>(parent.selection);
        if (sources.isEmpty()) return;
        show(host, FileDialogBridge.DIALOG_OPEN_FOLDER,
                host.getString(mode == MODE_COPY ? R.string.file_manager_select_copy_destination
                        : R.string.file_manager_select_destination),
                parent.currentDir.getAbsolutePath(), null, false, paths -> {
                    restoreParentDialog(parent);
                    if (paths == null || paths.length == 0) return;
                    confirmConflicts(host, parent, sources, new File(paths[0]), mode);
                });
    }

    private static void restoreParentDialog(State parent) {
        if (parent.dialog == null || !parent.dialog.isShowing()) return;
        synchronized (BuiltinFileDialog.class) {
            sCurrentDialog = new WeakReference<>(parent.dialog);
            sCurrentCallback = new WeakReference<>(parent.finish);
        }
    }

    private static int transferTitle(int mode) {
        return mode == MODE_MOVE ? R.string.file_manager_move_title
                : mode == MODE_COPY ? R.string.file_manager_copy_title
                : R.string.file_manager_import_title;
    }

    private static File uniqueCopyTarget(File directory, String name) {
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        File target;
        int index = 1;
        do {
            target = new File(directory, base + " (copy" + (index > 1 ? " " + index : "")
                    + ")" + extension);
            index++;
        } while (target.exists());
        return target;
    }

    private static void confirmConflicts(Activity host, State state, List<File> sources,
                                         File destination, int mode) {
        File conflict = null;
        for (File source : sources) {
            File target = new File(destination, source.getName());
            if (target.exists() && !(mode == MODE_COPY && target.equals(source))) {
                conflict = target;
                break;
            }
        }
        if (conflict == null) {
            transferFiles(host, state, sources, destination, mode, false);
            return;
        }
        new MaterialAlertDialogBuilder(host)
                .setTitle(transferTitle(mode))
                .setMessage(host.getString(R.string.file_manager_conflict_message,
                        conflict.getName()))
                .setPositiveButton(R.string.global_yes, (dialog, which) ->
                        transferFiles(host, state, sources, destination, mode, true))
                .setNegativeButton(R.string.global_no, null)
                .show();
    }

    private static void transferFiles(Activity host, State state, List<File> sources,
                                      File destination, int mode, boolean replace) {
        boolean move = mode == MODE_MOVE;
        Dialog progress = showProgress(host, transferTitle(mode));
        FILE_OPERATIONS.execute(() -> {
            List<File> failed = new ArrayList<>();
            for (File source : sources) {
                File target = new File(destination, source.getName());
                if (mode == MODE_COPY && target.equals(source)) {
                    target = uniqueCopyTarget(destination, source.getName());
                }
                if (source.equals(target) || isInside(source, destination)) {
                    failed.add(source);
                    continue;
                }
                boolean cleanupTarget = !target.exists();
                try {
                    if (target.exists()) {
                        if (!replace || !deleteRecursively(target)) {
                            throw new IOException("Unable to replace destination");
                        }
                        cleanupTarget = true;
                    }
                    if (move && source.renameTo(target)) continue;
                    copyRecursively(source, target);
                    if (move && !deleteRecursively(source)) {
                        deleteRecursively(target);
                        throw new IOException("Unable to remove source after copy");
                    }
                } catch (IOException | SecurityException e) {
                    if (cleanupTarget) deleteRecursively(target);
                    Log.e(TAG, "File transfer failed: " + source, e);
                    failed.add(source);
                }
            }
            host.runOnUiThread(() -> {
                progress.dismiss();
                if (!failed.isEmpty()) {
                    Toast.makeText(host, host.getString(
                            R.string.file_manager_operation_failures,
                            failed.size(), failed.get(0).getName()),
                            Toast.LENGTH_LONG).show();
                }
                if (!host.isFinishing() && !host.isDestroyed()
                        && state.dialog != null && state.dialog.isShowing()) {
                    state.selection.clear();
                    refreshCurrentDirectory(host, state);
                    onSelectionChanged(host, state);
                }
            });
        });
    }

    // region Android system file picker

    /**
     * Opens the stock Android document picker as a fallback. Picked documents are copied into the
     * cache because the callers need real paths; folders only work when the tree maps to a path.
     */
    private static void openSystemPicker(Activity host, State state) {
        if (!(host instanceof ComponentActivity)) {
            Toast.makeText(host, R.string.file_manager_system_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        boolean folder = state.type == FileDialogBridge.DIALOG_OPEN_FOLDER;
        Intent intent;
        if (folder) {
            intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        } else {
            intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, state.allowMultiple);
        }
        AtomicReference<ActivityResultLauncher<Intent>> launcher = new AtomicReference<>();
        launcher.set(((ComponentActivity) host).getActivityResultRegistry().register(
                "builtin_file_dialog_system_" + SystemClock.elapsedRealtime(),
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    launcher.get().unregister();
                    Intent data = result.getData();
                    if (result.getResultCode() != Activity.RESULT_OK || data == null) return;
                    if (state.dialog == null || !state.dialog.isShowing()) return;
                    if (folder) {
                        useSystemFolder(host, state, data.getData());
                    } else {
                        copySystemFiles(host, state, data);
                    }
                }));
        try {
            launcher.get().launch(intent);
        } catch (ActivityNotFoundException e) {
            launcher.get().unregister();
            Log.e(TAG, "No document picker available", e);
            Toast.makeText(host, R.string.file_manager_system_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private static void useSystemFolder(Activity host, State state, @Nullable Uri tree) {
        File directory = tree != null ? treeToFile(tree) : null;
        if (directory == null || !directory.isDirectory() || !directory.canRead()) {
            Toast.makeText(host, R.string.file_manager_system_folder_unsupported,
                    Toast.LENGTH_LONG).show();
            return;
        }
        state.finish.onResult(new String[]{directory.getAbsolutePath()});
    }

    @Nullable
    private static File treeToFile(Uri tree) {
        try {
            String id = DocumentsContract.getTreeDocumentId(tree);
            int colon = id.indexOf(':');
            if (colon < 0) return null;
            String volume = id.substring(0, colon);
            String relative = id.substring(colon + 1);
            File base = "primary".equalsIgnoreCase(volume)
                    ? android.os.Environment.getExternalStorageDirectory()
                    : new File("/storage/" + volume);
            return relative.isEmpty() ? base : new File(base, relative);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static void copySystemFiles(Activity host, State state, Intent data) {
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                Uri uri = data.getClipData().getItemAt(i).getUri();
                if (uri != null) uris.add(uri);
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) return;
        Dialog progress = showProgress(host, R.string.file_manager_import_title);
        FILE_OPERATIONS.execute(() -> {
            List<String> paths = new ArrayList<>();
            File directory = new File(host.getCacheDir(), "file_dialog/" + System.nanoTime());
            for (Uri uri : uris) {
                try {
                    if (!directory.isDirectory() && !directory.mkdirs()) {
                        throw new IOException("Unable to create " + directory);
                    }
                    File target = new File(directory, systemDisplayName(host, uri));
                    try (InputStream input = host.getContentResolver().openInputStream(uri);
                         FileOutputStream output = new FileOutputStream(target)) {
                        if (input == null) throw new IOException("Unable to open " + uri);
                        byte[] buffer = new byte[32 * 1024];
                        int count;
                        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                    }
                    paths.add(target.getAbsolutePath());
                } catch (IOException | SecurityException e) {
                    Log.e(TAG, "Unable to copy picked document " + uri, e);
                }
            }
            host.runOnUiThread(() -> {
                progress.dismiss();
                if (paths.isEmpty()) {
                    Toast.makeText(host, R.string.file_manager_system_failed,
                            Toast.LENGTH_LONG).show();
                } else if (state.dialog != null && state.dialog.isShowing()) {
                    state.finish.onResult(paths.toArray(new String[0]));
                }
            });
        });
    }

    private static String systemDisplayName(Activity host, Uri uri) {
        String name = null;
        try (Cursor cursor = host.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to read the picked document's name", e);
        }
        if (name == null || !isValidEntryName(name.trim())) name = "picked_file";
        return name.trim();
    }

    // endregion

    private static boolean isInside(File source, File destination) {
        try {
            String sourcePath = source.getCanonicalPath();
            String destinationPath = destination.getCanonicalPath();
            return destinationPath.startsWith(sourcePath + File.separator);
        } catch (IOException | SecurityException e) {
            Log.e(TAG, "Unable to resolve transfer paths", e);
            return true;
        }
    }

    private static void copyRecursively(File source, File destination) throws IOException {
        if (source.isDirectory()) {
            if (!destination.mkdirs() && !destination.isDirectory()) {
                throw new IOException("Unable to create destination directory");
            }
            File[] children = source.listFiles();
            if (children == null) throw new IOException("Unable to list source directory");
            for (File child : children) {
                copyRecursively(child, new File(destination, child.getName()));
            }
            return;
        }
        File parent = destination.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) {
            throw new IOException("Unable to create destination parent");
        }
        try (InputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
        }
    }

    private static void confirmDeleteSelected(Activity host, State state) {
        List<File> selected = new ArrayList<>(state.selection);
        if (selected.isEmpty()) return;
        new MaterialAlertDialogBuilder(host)
                .setTitle(R.string.global_delete)
                .setMessage(host.getString(R.string.file_manager_delete_selected_confirm,
                        selected.size()))
                .setPositiveButton(R.string.global_yes, (dialog, which) ->
                        deleteEntries(host, state, selected))
                .setNegativeButton(R.string.global_no, null)
                .show();
    }

    private static boolean navigateUp(Activity host, State state) {
        File parent = state.currentDir.getParentFile();
        if (parent == null || !parent.isDirectory() || !isWithinBrowseRoot(state, parent)
                || !isAccessibleDirectory(host, parent)) {
            return false;
        }
        navigateTo(host, state, parent);
        return true;
    }

    private static void navigateTo(Activity host, State state, File directory) {
        if (directory == null || !directory.isDirectory()) return;
        if (!isWithinBrowseRoot(state, directory) || !isAccessibleDirectory(host, directory)) {
            Log.w(TAG, "Blocked navigation outside the allowed locations: "
                    + directory.getAbsolutePath());
            return;
        }
        state.currentDir = directory;
        state.directoryEntryCount = -1;
        state.focusedEntry = null;
        if (!state.searchQuery.isEmpty()) {
            state.searchInput.setText("");
        }
        state.selection.clear();
        state.pathView.setText(directory.getAbsolutePath());
        state.pathView.setContentDescription(directory.getAbsolutePath()
                + ". " + host.getString(R.string.file_manager_path_title));
        File parent = directory.getParentFile();
        state.upButton.setEnabled(parent != null && isWithinBrowseRoot(state, parent)
                && isAccessibleDirectory(host, parent));
        updateLocationSelection(state, directory);
        state.emptyView.setText(R.string.file_manager_loading);
        refreshBottomBar(host, state);
        updateToolbar(host, state);
        refreshCurrentDirectory(host, state);
    }

    private static void refreshCurrentDirectory(Activity host, State state) {
        File directory = state.currentDir;
        String path = directory.getAbsolutePath();
        long requestId = ++state.listingRequestId;
        state.emptyView.setText(R.string.file_manager_loading);
        state.emptyLoader.setVisibility(View.VISIBLE);
        state.adapter.setEntries(Collections.emptyList());
        if (isSharedExternal(host, directory) && !sharedStoragePermissionGranted(host)) {
            state.emptyLoader.setVisibility(View.GONE);
            state.emptyView.setText(R.string.file_manager_storage_permission);
            requestSharedStorage(host, state, directory);
            return;
        }
        Log.i(TAG, "Starting directory scan: " + path);
        DIRECTORY_LISTING.execute(() -> {
            File[] listed = null;
            List<FileInfo> infos = null;
            RuntimeException failure = null;
            try {
                listed = directory.listFiles();
                if (listed != null) {
                    // Stat every entry here once, so sorting and drawing never touch the disk.
                    infos = new ArrayList<>(listed.length);
                    for (File file : listed) infos.add(new FileInfo(file));
                }
            } catch (RuntimeException e) {
                failure = e;
            }
            Log.i(TAG, "Directory scan finished: " + path
                    + ", entries=" + (listed != null ? listed.length : "unavailable"));
            File[] result = listed;
            List<FileInfo> resultInfos = infos;
            RuntimeException listingFailure = failure;
            host.runOnUiThread(() -> {
                if (requestId != state.listingRequestId || !directory.equals(state.currentDir)
                    || host.isFinishing() || host.isDestroyed()) {
                    return;
                }
                state.emptyLoader.setVisibility(View.GONE);
                if (listingFailure != null) {
                    Log.w(TAG, "Directory scan failed for " + path, listingFailure);
                    state.emptyView.setText(R.string.file_manager_unreadable);
                    return;
                }
                if (result == null && isSharedExternal(host, directory)) {
                    if (Build.VERSION.SDK_INT >= 30
                            && !android.os.Environment.isExternalStorageManager()) {
                        state.emptyView.setText(R.string.file_manager_storage_permission);
                        requestSharedStorage(host, state, directory);
                    } else {
                        Log.w(TAG, "Shared storage listing failed for " + path
                                + " (exists=" + directory.exists()
                                + ", readable=" + directory.canRead() + ")");
                        state.emptyView.setText(R.string.file_manager_unreadable);
                    }
                    return;
                }
                if (result == null || resultInfos == null) {
                    Log.w(TAG, "listFiles returned null for " + path
                            + " (exists=" + directory.exists()
                            + ", readable=" + directory.canRead()
                            + ", writable=" + directory.canWrite() + ")");
                    state.emptyView.setText(R.string.file_manager_unreadable);
                    return;
                }
                state.adapter.setEntries(resultInfos);
                updateEmptyMessage(state);
                Log.i(TAG, "Listed " + result.length + " entries at " + path
                        + "; visible entries=" + state.adapter.getCount());
            });
        });
    }

    private static File[] rootCandidates(Activity host) {
        List<File> roots = new ArrayList<>();
        File gameHome = new File(git.artdeell.mojo.Tools.DIR_GAME_HOME);
        addRoot(roots, gameHome);
        addRoot(roots, host.getExternalFilesDir(null));
        for (File root : sharedStorageRoots(host)) addRoot(roots, root);
        return roots.toArray(new File[0]);
    }

    private static boolean isAccessibleDirectory(Activity host, File directory) {
        for (File root : rootCandidates(host)) {
            try {
                String path = directory.getCanonicalPath();
                String rootPath = root.getCanonicalPath();
                if (path.equals(rootPath) || path.startsWith(rootPath + File.separator)) {
                    return true;
                }
            } catch (IOException e) {
                Log.w(TAG, "Unable to resolve file manager location", e);
            }
        }
        return false;
    }

    /** Only the manage dialog is confined to its starting folder. */
    private static boolean isWithinBrowseRoot(State state, File directory) {
        if (!state.restricted) return true;
        try {
            String rootPath = state.browseRoot.getCanonicalPath();
            String directoryPath = directory.getCanonicalPath();
            return directoryPath.equals(rootPath)
                    || directoryPath.startsWith(rootPath + File.separator);
        } catch (IOException e) {
            Log.w(TAG, "Unable to check dialog navigation boundary", e);
            return false;
        }
    }

    private static List<File> sharedStorageRoots(Activity host) {
        LinkedHashSet<File> roots = new LinkedHashSet<>();
        File primary = android.os.Environment.getExternalStorageDirectory();
        if (primary != null && primary.isDirectory()) roots.add(primary);
        File[] appDirectories = host.getExternalFilesDirs(null);
        if (appDirectories != null) {
            for (File appDirectory : appDirectories) {
                File androidDirectory = appDirectory;
                while (androidDirectory != null && !"Android".equals(androidDirectory.getName())) {
                    androidDirectory = androidDirectory.getParentFile();
                }
                File root = androidDirectory != null ? androidDirectory.getParentFile() : null;
                if (root != null && root.isDirectory()) roots.add(root);
            }
        }
        return new ArrayList<>(roots);
    }

    private static void addRoot(List<File> roots, File candidate) {
        if (candidate != null && candidate.isDirectory() && !roots.contains(candidate)) {
            roots.add(candidate);
        }
    }

    private static String rootName(Activity host, File root) {
        if (root.getAbsolutePath().equals(git.artdeell.mojo.Tools.DIR_GAME_HOME)) {
            return host.getString(R.string.file_manager_root_game);
        }
        if (root.equals(host.getExternalFilesDir(null))) {
            return host.getString(R.string.file_manager_root_app);
        }
        File primary = android.os.Environment.getExternalStorageDirectory();
        if (root.equals(primary)) return host.getString(R.string.file_manager_root_shared);
        return root.getName();
    }

    private static void updateLocationSelection(State state, File directory) {
        File selected = null;
        int selectedPathLength = -1;
        String directoryPath = directory.getAbsolutePath();
        for (File location : state.locations) {
            String locationPath = location.getAbsolutePath();
            boolean containsDirectory = directoryPath.equals(locationPath)
                    || directoryPath.startsWith(locationPath.endsWith(File.separator)
                    ? locationPath : locationPath + File.separator);
            if (containsDirectory && locationPath.length() > selectedPathLength) {
                selected = location;
                selectedPathLength = locationPath.length();
            }
        }
        for (int i = 0; i < state.locationButtons.size(); i++) {
            MaterialButton button = state.locationButtons.get(i);
            boolean active = state.locations.get(i).equals(selected);
            int background = active
                    ? MaterialColors.getColor(button, R.attr.colorSecondaryContainer)
                    : state.compactLayout
                    ? MaterialColors.getColor(button, R.attr.colorSurfaceContainerHighest)
                    : android.graphics.Color.TRANSPARENT;
            int foreground = MaterialColors.getColor(button, active
                    ? R.attr.colorOnSecondaryContainer : R.attr.colorOnSurfaceVariant);
            button.setBackgroundTintList(ColorStateList.valueOf(background));
            button.setTextColor(foreground);
            button.setIconTint(ColorStateList.valueOf(foreground));
        }
    }

    private static void updateSelectionButton(Activity host, State state) {
        if (state.selectionButton == null) return;
        int count = state.selection.size();
        if (count == 0) {
            int label = state.type == FileDialogBridge.DIALOG_OPEN_FILE
                    ? R.string.file_manager_open
                    : state.type == DIALOG_IMPORT_FILES
                    ? R.string.file_manager_import_selected : R.string.file_manager_select;
            state.selectionButton.setText(label);
        } else if (state.type == DIALOG_IMPORT_FILES) {
            state.selectionButton.setText(host.getString(
                    R.string.file_manager_import_count, count));
        } else {
            state.selectionButton.setText(host.getString(R.string.file_manager_open_count, count));
        }
        state.selectionButton.setEnabled(count > 0);
    }

    private static void updateEmptyMessage(State state) {
        if (state.adapter.getCount() > 0) {
            state.emptyView.setText(R.string.file_manager_no_results);
        } else if (state.searchQuery.length() > 0
                || (state.type != FileDialogBridge.DIALOG_OPEN_FOLDER
                && state.filterEnabled)) {
            state.emptyView.setText(R.string.file_manager_no_results);
        } else if (state.directoryEntryCount == 0) {
            state.emptyView.setText(R.string.file_manager_empty);
        } else if (state.type == FileDialogBridge.DIALOG_OPEN_FOLDER) {
            state.emptyView.setText(R.string.file_manager_no_folders);
        } else {
            state.emptyView.setText(R.string.file_manager_no_results);
        }
    }

    private static void showSortMenu(Activity host, State state) {
        PopupMenu menu = new PopupMenu(host, state.sortButton);
        int[] labels = {R.string.file_manager_sort_name, R.string.file_manager_sort_date,
                R.string.file_manager_sort_size};
        for (int i = 0; i < labels.length; i++) {
            MenuItem item = menu.getMenu().add(1, i, i, labels[i]);
            item.setCheckable(true);
            item.setChecked(state.sortMode == i);
        }
        menu.getMenu().setGroupCheckable(1, true, true);
        MenuItem reverse = menu.getMenu().add(2, 100, 100, R.string.file_manager_reverse_order);
        reverse.setCheckable(true);
        reverse.setChecked(state.sortDescending);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 100) {
                state.sortDescending = !state.sortDescending;
            } else {
                state.sortMode = item.getItemId();
                // Newest and largest first feel right for date and size, A to Z for names.
                state.sortDescending = state.sortMode != 0;
            }
            updateSortButton(state);
            state.adapter.refilter();
            return true;
        });
        menu.show();
    }

    private static int sortLabel(int mode) {
        switch (mode) {
            case 1:
                return R.string.file_manager_sort_date;
            case 2:
                return R.string.file_manager_sort_size;
            default:
                return R.string.file_manager_sort_name;
        }
    }

    private static void promptPath(Activity host, State state) {
        TextInputLayout input = createInputLayout(host, host.getString(R.string.file_manager_path_hint),
                state.currentDir.getAbsolutePath(), InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(host)
                .setTitle(R.string.file_manager_path_title)
                .setView(wrapDialogContent(host, input))
                .setPositiveButton(android.R.string.ok, null)
                .setNegativeButton(R.string.global_cancel, null)
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                File target = new File(input.getEditText().getText().toString().trim());
                if (!target.isAbsolute() || !target.isDirectory()
                        || !isWithinBrowseRoot(state, target)
                        || !isAccessibleDirectory(host, target)) {
                    input.setError(host.getString(R.string.file_manager_path_invalid));
                    return;
                }
                dialog.dismiss();
                navigateTo(host, state, target);
            });
        });
        dialog.show();
    }

    private static File initialDirectory(Activity host, String defaultPath, int type) {
        if (defaultPath != null && !defaultPath.isEmpty()) {
            File candidate = new File(defaultPath);
            if (!candidate.isDirectory()) {
                File parent = candidate.getParentFile();
                if (parent != null && parent.isDirectory()
                        && isAccessibleDirectory(host, parent)) return parent;
            }
            if (candidate.isDirectory() && isAccessibleDirectory(host, candidate)) {
                return candidate;
            }
        }
        for (File root : rootCandidates(host)) {
            if (root.isDirectory()) return root;
        }
        return host.getFilesDir();
    }

    private static String defaultSaveName(Activity host, String defaultPath) {
        if (defaultPath == null || defaultPath.isEmpty()) return "";
        File candidate = new File(defaultPath);
        if (candidate.isDirectory()) return "";
        return candidate.getName();
    }

    // Converts TinyFD filters such as "*.png", "*.tar.gz" and "png,jpg" to
    // case-insensitive filename patterns. An empty result means all files.
    private static List<String> parseFilterPatterns(String[] filterPatterns) {
        List<String> patterns = new ArrayList<>();
        if (filterPatterns != null) {
            for (String pattern : filterPatterns) {
                if (pattern == null) continue;
                for (String filter : pattern.split("[;,]")) {
                    filter = filter.trim().toLowerCase(Locale.ROOT);
                    if (filter.isEmpty()) continue;
                    if (filter.equals("*") || filter.equals("*.*")) return new ArrayList<>();
                    if (filter.indexOf('*') < 0 && filter.indexOf('?') < 0) {
                        while (filter.startsWith(".")) filter = filter.substring(1);
                        if (filter.isEmpty()) continue;
                        filter = "*." + filter;
                    }
                    if (!patterns.contains(filter)) patterns.add(filter);
                }
            }
        }
        return patterns;
    }

    @Nullable
    private static String firstSaveExtension(List<String> patterns) {
        if (patterns.isEmpty()) return null;
        String pattern = patterns.get(0);
        if (!pattern.startsWith("*.") || pattern.indexOf('*', 2) >= 0
                || pattern.indexOf('?') >= 0) return null;
        return pattern.substring(2);
    }

    private static List<Pattern> compileFilters(List<String> patterns) {
        List<Pattern> compiled = new ArrayList<>();
        for (String pattern : patterns) {
            StringBuilder regex = new StringBuilder();
            for (int i = 0; i < pattern.length(); i++) {
                char character = pattern.charAt(i);
                if (character == '*') {
                    regex.append(".*");
                } else if (character == '?') {
                    regex.append('.');
                } else {
                    regex.append(Pattern.quote(String.valueOf(character)));
                }
            }
            compiled.add(Pattern.compile(regex.toString()));
        }
        return compiled;
    }

    private static boolean matchesFileFilter(String lowerName, List<Pattern> regexes) {
        if (regexes.isEmpty()) return true;
        for (Pattern regex : regexes) {
            if (regex.matcher(lowerName).matches()) return true;
        }
        return false;
    }

    private static String defaultDialogTitle(Activity host, int type, boolean allowMultiple) {
        switch (type) {
            case FileDialogBridge.DIALOG_SAVE_FILE:
                return host.getString(R.string.file_manager_title_save);
            case FileDialogBridge.DIALOG_OPEN_FOLDER:
                return host.getString(R.string.file_manager_title_folder);
            case FileDialogBridge.DIALOG_BROWSE:
                return host.getString(R.string.file_manager_title_browse);
            case DIALOG_IMPORT_FILES:
                return host.getString(R.string.file_manager_import_title);
            default:
                return host.getString(allowMultiple
                        ? R.string.file_manager_title_open_multiple
                        : R.string.file_manager_title_open);
        }
    }

    private static int dp(Context host, float value) {
        return Math.round(host.getResources().getDisplayMetrics().density * value);
    }

    private static final class ColorPickerUi {
        int rgb;
        boolean syncing;
        View preview;
        EditText hex;
        final SeekBar[] bars = new SeekBar[3];
        final TextView[] values = new TextView[3];

        void setColor(int newRgb, boolean updateHex) {
            rgb = newRgb & 0xFFFFFF;
            syncing = true;
            ((GradientDrawable) preview.getBackground()).setColor(0xFF000000 | rgb);
            int[] channels = {Color.red(rgb), Color.green(rgb), Color.blue(rgb)};
            for (int i = 0; i < 3; i++) {
                bars[i].setProgress(channels[i]);
                values[i].setText(String.valueOf(channels[i]));
            }
            if (updateHex) {
                hex.setText(String.format(Locale.ROOT, "#%06X", rgb));
                hex.setSelection(hex.length());
            }
            syncing = false;
        }
    }

    /** Everything the list needs about an entry, read once on the listing thread. */
    private static final class FileInfo {
        final File file;
        final boolean dir;
        final boolean hidden;
        final long size;
        final long modified;
        final String lowerName;

        FileInfo(File file) {
            this.file = file;
            this.dir = file.isDirectory();
            String name = file.getName();
            this.hidden = name.startsWith(".");
            this.size = dir ? 0 : file.length();
            this.modified = file.lastModified();
            this.lowerName = name.toLowerCase(Locale.ROOT);
        }
    }

    private static class State {
        int type;
        boolean allowMultiple;
        boolean compactLayout;
        boolean landscapeLayout;
        boolean shortScreen;
        boolean restricted;
        boolean showHidden = true;
        boolean filterEnabled;
        boolean sortDescending;
        List<String> filterPatterns;
        List<Pattern> filterRegexes;
        EntryAdapter adapter;
        TextView pathView;
        TextView summaryView;
        MaterialButton upButton;
        MaterialButton refreshButton;
        MaterialButton hiddenButton;
        MaterialButton newFolderButton;
        MaterialButton filterButton;
        MaterialButton selectAllButton;
        MaterialButton systemButton;
        View emptyContainer;
        View emptyLoader;
        TextView emptyView;
        MaterialButton selectionButton;
        LinearLayout bottomBar;
        final List<File> locations = new ArrayList<>();
        final List<MaterialButton> locationButtons = new ArrayList<>();
        ResultCallback finish;
        Dialog dialog;
        File currentDir = new File("/");
        File browseRoot = new File("/");
        final LinkedHashSet<File> selection = new LinkedHashSet<>();
        boolean manageMode;
        boolean pendingUseFolder;
        long listingRequestId;
        int sortMode;
        int directoryEntryCount = -1;
        String defaultPath;
        String searchQuery = "";
        EditText fileNameInput;
        EditText searchInput;
        MaterialButton sortButton;
        String fileName;
        File focusedEntry;
    }

    private static class EntryAdapter extends BaseAdapter {
        private final Activity host;
        private final State state;
        private final DateFormat dateFormat = DateFormat.getDateTimeInstance(
                DateFormat.SHORT, DateFormat.SHORT);
        private List<FileInfo> allEntries = new ArrayList<>();
        private List<FileInfo> entries = new ArrayList<>();

        EntryAdapter(Activity host, State state) {
            this.host = host;
            this.state = state;
        }

        void setEntries(List<FileInfo> listed) {
            allEntries = new ArrayList<>(listed);
            state.directoryEntryCount = listed.size();
            refilter();
        }

        void setSearchQuery(String query) {
            state.searchQuery = query;
            refilter();
        }

        /** Re-applies search, hidden, filter and sort settings to the listed entries. */
        void refilter() {
            entries = new ArrayList<>(allEntries.size());
            String query = state.searchQuery.toLowerCase(Locale.ROOT);
            for (FileInfo info : allEntries) {
                if (!state.showHidden && info.hidden) continue;
                if (!info.dir) {
                    if (state.type == FileDialogBridge.DIALOG_OPEN_FOLDER) continue;
                    if (state.filterEnabled && !matchesFileFilter(info.lowerName, state.filterRegexes)) {
                        continue;
                    }
                }
                if (query.isEmpty() || info.lowerName.contains(query)) entries.add(info);
            }
            Collections.sort(entries, (a, b) -> {
                if (a.dir != b.dir) return a.dir ? -1 : 1;
                int comparison = 0;
                if (state.sortMode == 1) {
                    comparison = Long.compare(a.modified, b.modified);
                } else if (state.sortMode == 2 && !a.dir) {
                    comparison = Long.compare(a.size, b.size);
                }
                if (comparison == 0) comparison = a.lowerName.compareTo(b.lowerName);
                return state.sortDescending ? -comparison : comparison;
            });
            notifyDataSetChanged();
        }

        @Nullable FileInfo getInfo(int position) {
            return position >= 0 && position < entries.size() ? entries.get(position) : null;
        }

        void onRowClick(int position) {
            FileInfo info = getInfo(position);
            if (info == null) return;
            File entry = info.file;
            if (info.dir) {
                navigateTo(host, state, entry);
                return;
            }
            switch (state.type) {
                case FileDialogBridge.DIALOG_SAVE_FILE:
                    state.fileName = entry.getName();
                    if (state.fileNameInput != null) {
                        state.fileNameInput.setText(state.fileName);
                        state.fileNameInput.setSelection(state.fileName.length());
                    }
                    break;
                case FileDialogBridge.DIALOG_BROWSE:
                    if (state.manageMode) {
                        toggleSelection(entry);
                    } else {
                        Tools.openPath(host, entry, false);
                    }
                    break;
                case FileDialogBridge.DIALOG_OPEN_FILE:
                case DIALOG_IMPORT_FILES:
                    toggleSelection(entry);
                    break;
                default:
                    break;
            }
        }

        void toggleSelection(File entry) {
            if (!state.allowMultiple && (state.type == FileDialogBridge.DIALOG_OPEN_FILE
                    || state.type == DIALOG_IMPORT_FILES)) {
                boolean wasSelected = state.selection.contains(entry);
                state.selection.clear();
                if (!wasSelected) state.selection.add(entry);
            } else if (!state.selection.remove(entry)) {
                state.selection.add(entry);
            }
            state.focusedEntry = null;
            notifyDataSetChanged();
            onSelectionChanged(host, state);
        }

        @Override public int getCount() {
            return entries.size();
        }

        @Override public File getItem(int position) {
            return entries.get(position).file;
        }

        @Override public long getItemId(int position) {
            return position;
        }

        private static int iconFor(FileInfo info) {
            if (info.dir) return R.drawable.ic_px_folder;
            String name = info.lowerName;
            if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")
                    || name.endsWith(".webp") || name.endsWith(".gif")) {
                return R.drawable.ic_px_image;
            }
            if (name.endsWith(".jar")) return R.drawable.ic_px_java;
            return R.drawable.ic_px_file;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView != null ? convertView : LayoutInflater.from(host).inflate(
                    R.layout.item_file_manager_entry, parent, false);
            View body = row.findViewById(R.id.file_manager_row);
            TextView nameView = row.findViewById(R.id.file_manager_name);
            TextView detailView = row.findViewById(R.id.file_manager_detail);
            TextView sizeView = row.findViewById(R.id.file_manager_size);
            TextView modifiedView = row.findViewById(R.id.file_manager_modified);
            ImageView iconView = row.findViewById(R.id.file_manager_icon);
            MaterialCheckBox checkView = row.findViewById(R.id.file_manager_check);

            FileInfo info = entries.get(position);
            File entry = info.file;
            boolean selected = state.selection.contains(entry);
            boolean highlighted = selected || entry.equals(state.focusedEntry);
            String modified = dateFormat.format(new Date(info.modified));
            String size = info.dir ? "" : readableSize(info.size);

            nameView.setText(entry.getName());
            sizeView.setText(size);
            modifiedView.setText(modified);
            detailView.setText(info.dir ? modified
                    : host.getString(R.string.file_manager_entry_detail, size, modified));
            sizeView.setVisibility(state.landscapeLayout ? View.VISIBLE : View.GONE);
            modifiedView.setVisibility(state.landscapeLayout ? View.VISIBLE : View.GONE);
            detailView.setVisibility(state.landscapeLayout ? View.GONE : View.VISIBLE);

            int primaryText = MaterialColors.getColor(row, highlighted
                    ? R.attr.colorOnSecondaryContainer : R.attr.colorOnSurface);
            int secondaryText = MaterialColors.getColor(row, highlighted
                    ? R.attr.colorOnSecondaryContainer : R.attr.colorOnSurfaceVariant);
            nameView.setTextColor(primaryText);
            detailView.setTextColor(secondaryText);
            sizeView.setTextColor(secondaryText);
            modifiedView.setTextColor(secondaryText);

            iconView.setImageResource(iconFor(info));
            iconView.setBackgroundTintList(ColorStateList.valueOf(MaterialColors.getColor(row,
                    info.dir ? R.attr.colorPrimaryContainer : R.attr.colorSurfaceContainerHighest)));
            iconView.setColorFilter(MaterialColors.getColor(row,
                    info.dir ? R.attr.colorOnPrimaryContainer : R.attr.colorOnSurfaceVariant));
            body.setBackgroundResource(highlighted
                    ? R.drawable.bg_file_manager_row_selected : R.drawable.bg_file_manager_row);

            boolean manage = state.type == FileDialogBridge.DIALOG_BROWSE && state.manageMode;
            boolean pickFiles = (state.type == FileDialogBridge.DIALOG_OPEN_FILE
                    || state.type == DIALOG_IMPORT_FILES) && !info.dir;
            if (manage || pickFiles) {
                checkView.setVisibility(View.VISIBLE);
                checkView.setChecked(selected);
                checkView.setClickable(true);
                checkView.setOnClickListener(v -> toggleSelection(entry));
            } else {
                checkView.setOnClickListener(null);
                checkView.setClickable(false);
                checkView.setVisibility(View.GONE);
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                row.setOnContextClickListener(v -> {
                    if (state.type != FileDialogBridge.DIALOG_BROWSE) return false;
                    showEntryActions(host, state, entry);
                    return true;
                });
            }
            return row;
        }
    }

    private static String readableSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format(java.util.Locale.ROOT,
                "%.1f KB", size / 1024.0);
        if (size < 1024L * 1024 * 1024) return String.format(java.util.Locale.ROOT,
                "%.1f MB", size / (1024.0 * 1024));
        return String.format(java.util.Locale.ROOT,
                "%.1f GB", size / (1024.0 * 1024 * 1024));
    }

}
