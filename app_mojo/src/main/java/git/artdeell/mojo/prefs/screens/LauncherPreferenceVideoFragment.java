package git.artdeell.mojo.prefs.screens;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import androidx.preference.ListPreference;
import androidx.preference.MultiSelectListPreference;
import androidx.preference.Preference;
import androidx.preference.SwitchPreferenceCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import git.artdeell.mojo.R;

import git.artdeell.mojo.mobileglues.MainActivity;
import git.artdeell.mojo.game.renderer.RendererCache;
import git.artdeell.mojo.game.renderer.def.Renderers;
import git.artdeell.mojo.game.renderer.extra.GLESProvider;
import git.artdeell.mojo.prefs.CustomSeekBarPreference;
import git.artdeell.mojo.prefs.LauncherPreferences;
import git.artdeell.mojo.utils.GpuUtils;
import git.artdeell.mojo.utils.PanVkDriverManager;
import git.artdeell.mojo.utils.plugins.NativePlugin;
import git.artdeell.mojo.utils.plugins.PluginManager;

import java.io.File;
import java.util.List;

/**
 * Fragment for any settings video related
 */
public class LauncherPreferenceVideoFragment extends LauncherPreferenceFragment {
    private Boolean hasAngle = null;
    @Override
    public void onCreatePreferences(Bundle b, String str) {
        addPreferencesFromResource(R.xml.pref_video);
        int resolution = (int) (LauncherPreferences.PREF_SCALE_FACTOR * 100);

        CustomSeekBarPreference resolutionSeekbar = requirePreference("resolutionRatio",
                CustomSeekBarPreference.class);
        resolutionSeekbar.setSuffix(" %");

        // #724 bug fix
        if (resolution < 25) {
            resolutionSeekbar.setValue(100);
        } else {
            resolutionSeekbar.setValue(resolution);
        }

        // Sustained performance is only available since Nougat
        SwitchPreferenceCompat sustainedPerfSwitch = requirePreference("sustainedPerformance",
                SwitchPreferenceCompat.class);
        sustainedPerfSwitch.setVisible(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N);
        sustainedPerfSwitch.setChecked(LauncherPreferences.PREF_SUSTAINED_PERFORMANCE);

        requirePreference("alternate_surface", SwitchPreferenceCompat.class).setChecked(LauncherPreferences.PREF_USE_ALTERNATE_SURFACE);
        requirePreference("force_vsync", SwitchPreferenceCompat.class).setChecked(LauncherPreferences.PREF_FORCE_VSYNC);

        ListPreference driverPreference = requirePreference("vulkanDriver", ListPreference.class);
        PackageManager packageManager = driverPreference.getContext().getPackageManager();
        GpuUtils.GLInfo glInfo = GpuUtils.getGlInfo();
        boolean supportsCustomDriver = GpuUtils.checkVulkanSupport(packageManager) && (glInfo.isAdreno() || glInfo.isArm());
        driverPreference.setVisible(supportsCustomDriver);
        if (glInfo.isAdreno()) {
            driverPreference.setEntries(new CharSequence[]{
                    getString(R.string.preference_vulkan_driver_system),
                    getString(R.string.preference_vulkan_driver_custom_adreno)});
        } else {
            driverPreference.setEntries(new CharSequence[]{
                    getString(R.string.preference_vulkan_driver_system),
                    getString(R.string.preference_vulkan_driver_custom_mali)});
        }
        driverPreference.setEntryValues(new CharSequence[]{
                LauncherPreferences.PREF_VULKAN_DRIVER_SYSTEM,
                LauncherPreferences.PREF_VULKAN_DRIVER_CUSTOM});

        setupPanvkDriverManager(glInfo, supportsCustomDriver);

        // Show the bundled ANGLE switch only if the bundled ANGLE libraries are actually present
        if(hasAngle == null) {
            hasAngle = new GLESProvider.ExternalAngleProvider(requireContext()).supported();
        }
        SwitchPreferenceCompat angleSwitch = requirePreference("use_angle", SwitchPreferenceCompat.class);
        angleSwitch.setVisible(hasAngle);
        angleSwitch.setChecked(LauncherPreferences.PREF_USE_ANGLE);

        // System ANGLE is only shipped by the OS on Android 15+ (API 35)
        SwitchPreferenceCompat systemAngleSwitch = requirePreference("use_system_angle", SwitchPreferenceCompat.class);
        systemAngleSwitch.setVisible(Build.VERSION.SDK_INT >= 35);
        systemAngleSwitch.setChecked(LauncherPreferences.PREF_USE_SYSTEM_ANGLE);
        // The two ANGLE sources are mutually exclusive
        angleSwitch.setOnPreferenceChangeListener((preference, newValue) -> {
            if(Boolean.TRUE.equals(newValue)) systemAngleSwitch.setChecked(false);
            return true;
        });
        systemAngleSwitch.setOnPreferenceChangeListener((preference, newValue) -> {
            if(Boolean.TRUE.equals(newValue)) angleSwitch.setChecked(false);
            return true;
        });

        ListPreference rendererListPreference = requirePreference("renderer",
                ListPreference.class);
        RendererCache list = RendererCache.getCompatibleRenderers(getContext());
        rendererListPreference.setEntries(list.rendererDisplayNames);
        rendererListPreference.setEntryValues(list.rendererIds.toArray(new String[0]));

        requirePreference("renderer_settings").setOnPreferenceClickListener(preference -> {
            startActivity(new Intent(getContext(), MainActivity.class));
            return true;
        });

        setupNativePluginPreference();

        computeVisibility();
    }

    private void setupPanvkDriverManager(GpuUtils.GLInfo glInfo, boolean supportsCustomDriver) {
        Preference managerPreference = requirePreference("panvkDriverManager");
        // The driver manager only applies to Mali GPUs that can use the custom driver
        managerPreference.setVisible(supportsCustomDriver && glInfo.isArm());
        managerPreference.setOnPreferenceClickListener(preference -> {
            showPanvkDriverManagerDialog();
            return true;
        });
    }

    private void showPanvkDriverManagerDialog() {
        PanVkDriverManager manager = new PanVkDriverManager(requireContext());
        GpuUtils.GLInfo glInfo = GpuUtils.getGlInfo();
        int arch = manager.detectMaliArchVersion();
        if(arch == 0) {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.preference_panvk_driver_manager_title)
                    .setMessage(R.string.panvk_driver_manager_status_unsupported)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }

        File driverFile = manager.getArchDriverFile(arch);
        boolean downloaded = driverFile.exists();

        String message = getString(R.string.panvk_driver_manager_gpu, glInfo.renderer) + "\n" +
                getString(R.string.panvk_driver_manager_arch, "v" + arch) + "\n" +
                (downloaded
                        ? getString(R.string.panvk_driver_manager_status_downloaded)
                        : getString(R.string.panvk_driver_manager_status_bundled));

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.preference_panvk_driver_manager_title)
                .setMessage(message)
                .setPositiveButton(downloaded
                        ? R.string.panvk_driver_manager_update
                        : R.string.panvk_driver_manager_download, (dialog, which) -> {
                    if(downloaded) {
                        // Force a fresh download over the old copy
                        if(!driverFile.delete()) {
                            Toast.makeText(getContext(),
                                    getString(R.string.panvk_driver_manager_download_failed,
                                            "cannot remove old driver"),
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                    }
                    manager.getDriverForDevice(new PanVkDriverManager.DriverReadyListener() {
                        @Override public void onDriverReady(File driverFile) {
                            Toast.makeText(getContext(), R.string.panvk_driver_manager_downloaded,
                                    Toast.LENGTH_LONG).show();
                        }
                        @Override public void onDriverError(String error) {
                            Toast.makeText(getContext(),
                                    getString(R.string.panvk_driver_manager_download_failed, error),
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                    Toast.makeText(getContext(), R.string.panvk_driver_manager_downloading,
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.panvk_driver_manager_close, null)
                .show();
    }

    private void setupNativePluginPreference() {
        MultiSelectListPreference pluginsPreference = requirePreference(
                PluginManager.PREF_ENABLED_NATIVE_PLUGINS, MultiSelectListPreference.class);
        PluginManager.refresh(requireContext());
        List<NativePlugin> plugins = PluginManager.getNativePlugins(requireContext());
        if (plugins.isEmpty()) {
            pluginsPreference.setVisible(false);
            return;
        }
        CharSequence[] entries = new CharSequence[plugins.size()];
        CharSequence[] entryValues = new CharSequence[plugins.size()];
        for (int i = 0; i < plugins.size(); i++) {
            NativePlugin plugin = plugins.get(i);
            entries[i] = plugin.displayName;
            entryValues[i] = plugin.packageName;
        }
        pluginsPreference.setEntries(entries);
        pluginsPreference.setEntryValues(entryValues);
        // Every installed plugin is enabled by default
        if (!pluginsPreference.getSharedPreferences().contains(PluginManager.PREF_ENABLED_NATIVE_PLUGINS)) {
            pluginsPreference.setValues(PluginManager.getAllNativePluginPackageNames(requireContext()));
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        Activity activity = getActivity();
        if(activity != null) {
            requirePreference("ignoreNotch").setVisible(LauncherPreferences.hasNotch(activity));
        }
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences p, String s) {
        super.onSharedPreferenceChanged(p, s);
        computeVisibility();
    }

    private void computeVisibility(){
        requirePreference("force_vsync", SwitchPreferenceCompat.class)
                .setVisible(LauncherPreferences.PREF_USE_ALTERNATE_SURFACE);

        boolean isMobileGluesRenderer = Renderers.MOBILEGLUES_RENDERER.equals(LauncherPreferences.PREF_RENDERER);
        requirePreference("renderer_settings").setVisible(isMobileGluesRenderer);
    }
}
