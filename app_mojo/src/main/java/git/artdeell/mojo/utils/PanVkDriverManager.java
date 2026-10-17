package git.artdeell.mojo.utils;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

/**
 * Manages PanVK Kbase Vulkan driver binaries for different Mali GPU architectures.
 * Automatically downloads the correct driver from GitHub releases based on the
 * device's Mali GPU architecture version (v9-v13) and caches it locally.
 */
public class PanVkDriverManager {
    private static final String TAG = "PanVkDriverManager";

    private static final String GITHUB_OWNER = "zenithblue-oss";
    private static final String GITHUB_REPO = "panvk-kbase-android";

    private static final Map<Integer, String> ARCH_TO_RELEASE_TAG = new HashMap<>();
    static {
        ARCH_TO_RELEASE_TAG.put(9,  "panvk-kbase-v0.1.0-rc3-lowmem");
        ARCH_TO_RELEASE_TAG.put(10, "panvk-kbase-v0.1.0-rc3-lowmem");
        ARCH_TO_RELEASE_TAG.put(11, "panvk-kbase-v0.1.0-rc3-lowmem");
        ARCH_TO_RELEASE_TAG.put(12, "panvk-kbase-v0.1.0-rc3-lowmem");
        ARCH_TO_RELEASE_TAG.put(13, "panvk-kbase-v0.1.0-rc3-lowmem");
    }

    private static final Map<Integer, String> ARCH_TO_DRIVER_ASSET = new HashMap<>();
    static {
        ARCH_TO_DRIVER_ASSET.put(9,  "libvulkan_panfrost-android-aarch64.so");
        ARCH_TO_DRIVER_ASSET.put(10, "libvulkan_panfrost-android-aarch64.so");
        ARCH_TO_DRIVER_ASSET.put(11, "libvulkan_panfrost-android-aarch64.so");
        ARCH_TO_DRIVER_ASSET.put(12, "libvulkan_panfrost-android-aarch64.so");
        ARCH_TO_DRIVER_ASSET.put(13, "libvulkan_panfrost-android-aarch64.so");
    }

    private final Context context;
    private final File driversDir;
    private volatile File cachedDriverFile;
    private volatile Integer cachedArchVersion;

    public interface DriverReadyListener {
        void onDriverReady(File driverFile);
        void onDriverError(String error);
    }

    public PanVkDriverManager(Context context) {
        this.context = context.getApplicationContext();
        this.driversDir = new File(context.getFilesDir(), "panvk_drivers");
        if(!driversDir.exists()) driversDir.mkdirs();
    }

    /**
     * Get the Mali GPU architecture version for the current device.
     * @return architecture version (9-13) or 0 if not a Mali GPU
     */
    public int detectMaliArchVersion() {
        GpuUtils.GLInfo glInfo = GpuUtils.getGlInfo();
        return glInfo.getMaliArchVersion();
    }

    /**
     * Get the path to the PanVK driver for the current device's GPU architecture.
     * If the driver is already cached locally, returns it immediately.
     * Otherwise downloads it asynchronously and calls the listener.
     *
     * @param listener callback when driver is ready or on error
     */
    public void getDriverForDevice(DriverReadyListener listener) {
        int arch = detectMaliArchVersion();
        if(arch == 0) {
            listener.onDriverError("Not a Mali GPU or unknown architecture");
            return;
        }

        if(cachedDriverFile != null && cachedDriverFile.exists()
                && cachedArchVersion != null && cachedArchVersion == arch) {
            listener.onDriverReady(cachedDriverFile);
            return;
        }

        String releaseTag = ARCH_TO_RELEASE_TAG.get(arch);
        String assetName = ARCH_TO_DRIVER_ASSET.get(arch);
        if(releaseTag == null || assetName == null) {
            listener.onDriverError("No PanVK driver available for Mali architecture v" + arch);
            return;
        }

        File targetFile = new File(driversDir, "libvulkan_panfrost_v" + arch + ".so");
        if(targetFile.exists()) {
            cachedDriverFile = targetFile;
            cachedArchVersion = arch;
            listener.onDriverReady(targetFile);
            return;
        }

        downloadDriver(arch, releaseTag, assetName, targetFile, listener);
    }

    private void downloadDriver(int arch, String releaseTag, String assetName, File targetFile, DriverReadyListener listener) {
        String url = String.format(
            "https://github.com/%s/%s/releases/download/%s/%s",
            GITHUB_OWNER, GITHUB_REPO, releaseTag, assetName
        );

        new Thread(() -> {
            try {
                Log.i(TAG, "Downloading PanVK driver v" + arch + " from " + url);
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("GET");
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(30000);
                conn.setReadTimeout(60000);
                conn.connect();

                int responseCode = conn.getResponseCode();
                if(responseCode != HttpURLConnection.HTTP_OK) {
                    String err = "HTTP " + responseCode + " downloading PanVK driver";
                    Log.e(TAG, err);
                    new Handler(Looper.getMainLooper()).post(() -> listener.onDriverError(err));
                    return;
                }

                try(InputStream in = conn.getInputStream();
                    FileOutputStream out = new FileOutputStream(targetFile)) {
                    byte[] buf = new byte[8192];
                    int len;
                    while((len = in.read(buf)) != -1) {
                        out.write(buf, 0, len);
                    }
                }

                conn.disconnect();

                Log.i(TAG, "PanVK driver v" + arch + " downloaded to " + targetFile.getAbsolutePath());
                cachedDriverFile = targetFile;
                cachedArchVersion = arch;
                new Handler(Looper.getMainLooper()).post(() -> listener.onDriverReady(targetFile));
            }catch (IOException e) {
                Log.e(TAG, "Failed to download PanVK driver", e);
                new Handler(Looper.getMainLooper()).post(() -> listener.onDriverError(e.getMessage()));
            }
        }).start();
    }

    public File getCachedDriver() {
        return cachedDriverFile;
    }

    public void clearCache() {
        cachedDriverFile = null;
        cachedArchVersion = null;
        if(driversDir.exists()) {
            File[] files = driversDir.listFiles();
            if(files != null) {
                for(File f : files) f.delete();
            }
        }
    }
}
