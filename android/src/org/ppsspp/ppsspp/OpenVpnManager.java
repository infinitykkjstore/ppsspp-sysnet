package org.ppsspp.ppsspp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.os.Vibrator;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.app.PendingIntent;
import android.net.VpnService;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
// ICS-OpenVPN classes (vendored module)
import de.blinkt.openvpn.core.ProfileManager;
import de.blinkt.openvpn.VpnProfile;
import de.blinkt.openvpn.core.VPNLaunchHelper;

public class OpenVpnManager {
    private static final String TAG = "OpenVpnManager";
    private static Activity sActivity;
    private static final int REQUEST_VPN_PERMISSION = 0x1001;
    private static String sDownloadedOvpnPath = null;

    // Initialize with Activity context
    public static void init(Activity activity) {
        sActivity = activity;
    }

    // Start the auto-connect flow: download .ovpn from url, request VPN permission,
    // and attempt to start embedded OpenVPN client.
    public static void startAutoConnect(String ovpnUrl) {
        if (sActivity == null) {
            Log.e(TAG, "startAutoConnect called before init");
            return;
        }

        new DownloadOvpnTask().execute(ovpnUrl);
    }

    // Called from Activity.onActivityResult to handle the VpnService permission result.
    public static void onActivityResult(Activity activity, int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_VPN_PERMISSION) return;
        if (resultCode == Activity.RESULT_OK) {
            // Permission granted, attempt to start VPN.
            new Handler(Looper.getMainLooper()).post(() -> startEmbeddedVpn());
        } else {
            showAlertOnUiThread("VPN permission denied", "Cannot start VPN without permission.");
        }
    }

    private static class DownloadOvpnTask extends AsyncTask<String, Void, Boolean> {
        private String err = null;

        @Override
        protected Boolean doInBackground(String... params) {
            String urlStr = params[0];
            InputStream in = null;
            FileOutputStream fos = null;
            HttpURLConnection conn = null;
            try {
                URL url = new URL(urlStr);
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.setInstanceFollowRedirects(true);
                int code = conn.getResponseCode();
                if (code != 200) {
                    err = "HTTP error code: " + code;
                    return false;
                }
                in = new BufferedInputStream(conn.getInputStream());
                File dir = sActivity.getCacheDir();
                File out = new File(dir, "ppsspp_auto.ovpn");
                fos = new FileOutputStream(out);
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) != -1) {
                    fos.write(buf, 0, r);
                }
                fos.flush();
                sDownloadedOvpnPath = out.getAbsolutePath();
                return true;
            } catch (Exception e) {
                Log.e(TAG, "Download failed", e);
                err = e.toString();
                return false;
            } finally {
                try { if (in != null) in.close(); } catch (Exception e) {}
                try { if (fos != null) fos.close(); } catch (Exception e) {}
                if (conn != null) conn.disconnect();
            }
        }

        @Override
        protected void onPostExecute(Boolean success) {
            if (!success) {
                showAlertOnUiThread("VPN download failed", "Failed to download .ovpn: " + (err == null ? "unknown" : err));
                return;
            }

            // Ask for VPN permission
            try {
                Intent intent = VpnService.prepare(sActivity);
                if (intent != null) {
                    sActivity.startActivityForResult(intent, REQUEST_VPN_PERMISSION);
                } else {
                    // Already have permission.
                    startEmbeddedVpn();
                }
            } catch (Exception e) {
                Log.e(TAG, "VpnService.prepare failed", e);
                showAlertOnUiThread("VPN prepare failed", "Unable to request VPN permission: " + e);
            }
        }
    }

    // Attempt to start an embedded OpenVPN client using the downloaded .ovpn file.
    // This is a placeholder integration: if you add an OpenVPN library (e.g. a native
    // library or a Java implementation), call its startup routine here.
    private static void startEmbeddedVpn() {
        if (sDownloadedOvpnPath == null) {
            showAlertOnUiThread("No OVPN", "No downloaded OVPN profile available.");
            return;
        }

        // Prefer the vendored ICS-OpenVPN API: import the profile, save (temporary) and start via VPNLaunchHelper.
        boolean started = false;
        try {
            Log.i(TAG, "Using vendored ICS-OpenVPN ProfileManager to import and start profile");

            // Import profile using ProfileManager API. The library provides a temporary profile import path via
            // ProfileManager.setTemporaryProfile(Context, VpnProfile) or import methods. We'll try to import from
            // an InputStream using existing helper methods; if that fails we'll parse manually.
            ProfileManager pm = ProfileManager.getInstance(sActivity);

            // The library includes utilities to import an OVPN from an InputStream via VpnProfile.importFromStream in some
            // versions, but to be robust we'll attempt to read the file and call VpnProfile.importFromStream reflectively
            // if available, otherwise fall back to creating a temporary profile file and loading it.
            VpnProfile profile = null;
            try {
                // Try static helper: VpnProfile.importFromStream(Context, InputStream)
                Method importMethod = VpnProfile.class.getMethod("importFromStream", Context.class, java.io.InputStream.class);
                java.io.InputStream fis = new java.io.FileInputStream(new File(sDownloadedOvpnPath));
                try {
                    profile = (VpnProfile) importMethod.invoke(null, sActivity, fis);
                } finally {
                    try { fis.close(); } catch (Exception e) {}
                }
            } catch (NoSuchMethodException nsme) {
                Log.i(TAG, "VpnProfile.importFromStream not present, trying ProfileManager import via reflection");
                try {
                    Method importProfile = ProfileManager.class.getMethod("importProfile", Context.class, java.io.InputStream.class);
                    java.io.InputStream fis = new java.io.FileInputStream(new File(sDownloadedOvpnPath));
                    try {
                        Object res = importProfile.invoke(pm, sActivity, fis);
                        if (res instanceof VpnProfile)
                            profile = (VpnProfile) res;
                    } finally {
                        try { fis.close(); } catch (Exception e) {}
                    }
                } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
                    Log.i(TAG, "ProfileManager.importProfile not available or failed: " + e);
                }
            } catch (IllegalAccessException | InvocationTargetException e) {
                Log.e(TAG, "Error invoking VpnProfile.importFromStream", e);
            }

            if (profile == null) {
                // As a last resort, try to create a temporary profile by writing the .ovpn as a temporary profile file
                // and asking ProfileManager to load it via setTemporaryProfile.
                try {
                    java.io.InputStream fis = new java.io.FileInputStream(new File(sDownloadedOvpnPath));
                    try {
                        // Try ProfileManager.setTemporaryProfile(Context, VpnProfile) via a known helper that some versions provide
                        // There's a helper that may accept an InputStream; attempt to find 'importProfile' that returns VpnProfile without Context
                        Method importSingle = null;
                        for (Method m : ProfileManager.class.getMethods()) {
                            if (m.getName().toLowerCase().contains("import") && m.getReturnType() == VpnProfile.class) {
                                Class<?>[] params = m.getParameterTypes();
                                if (params.length == 1 && java.io.InputStream.class.isAssignableFrom(params[0])) {
                                    importSingle = m;
                                    break;
                                }
                            }
                        }
                        if (importSingle != null) {
                            profile = (VpnProfile) importSingle.invoke(pm, fis);
                        }
                    } finally {
                        try { fis.close(); } catch (Exception e) {}
                    }
                } catch (Exception e) {
                    Log.i(TAG, "Failed last-resort import attempts: " + e);
                }
            }

            if (profile != null) {
                Log.i(TAG, "Profile imported, saving as temporary and starting via VPNLaunchHelper");
                try {
                    // Mark as temporary and save so ProfileManager knows about it
                    ProfileManager.setTemporaryProfile(sActivity, profile);
                } catch (NoSuchMethodError | Exception e) {
                    // Some versions use setTemporaryProfile(Context, VpnProfile) as static, others use instance methods.
                    try {
                        Method setTmp = ProfileManager.class.getMethod("setTemporaryProfile", Context.class, VpnProfile.class);
                        setTmp.invoke(null, sActivity, profile);
                    } catch (Exception ex) {
                        try {
                            Method setTmp2 = ProfileManager.class.getMethod("setTemporaryProfile", VpnProfile.class);
                            setTmp2.invoke(pm, profile);
                        } catch (Exception ex2) {
                            Log.i(TAG, "Could not call setTemporaryProfile: " + ex2);
                        }
                    }
                }

                // Start the VPN using the library helper
                try {
                    VPNLaunchHelper.startOpenVpn(profile, sActivity.getBaseContext(), "AutoConnect", true);
                    showAlertOnUiThread("VPN started", "VPN profile started via embedded ICS-OpenVPN.");
                    showConnectionInfoOnUiThread("VPN started via ICS-OpenVPN. Check logs for details.");
                    started = true;
                } catch (Exception e) {
                    Log.e(TAG, "VPNLaunchHelper.startOpenVpn failed", e);
                }
            } else {
                Log.i(TAG, "Failed to import profile via vendored APIs");
            }

        } catch (Throwable t) {
            Log.e(TAG, "Error while using vendored ICS-OpenVPN", t);
        }

        if (!started) {
            // Fallback to native stub
            try {
                boolean startedNative = startNativeOpenVpn(sDownloadedOvpnPath);
                if (startedNative) {
                    showAlertOnUiThread("VPN connected", "Connected using profile: " + sDownloadedOvpnPath);
                } else {
                    showAlertOnUiThread("VPN failed", "Embedded OpenVPN start failed (native returned false).\nCheck logs and ensure the OpenVPN library is bundled.");
                }
            } catch (UnsatisfiedLinkError ule) {
                Log.e(TAG, "Native OpenVPN library not present", ule);
                showAlertOnUiThread("VPN not available", "Embedded OpenVPN library not found and Java OpenVPN integration not available. Please bundle an OpenVPN implementation in the app.");
            } catch (Exception e) {
                Log.e(TAG, "startEmbeddedVpn error", e);
                showAlertOnUiThread("VPN error", "Error starting VPN: " + e);
            }
        }
    }

    private static void showConnectionInfoOnUiThread(final String msg) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                AlertDialog.Builder b = new AlertDialog.Builder(sActivity);
                b.setTitle("OpenVPN status");
                b.setMessage(msg);
                b.setPositiveButton("OK", (d, w) -> d.dismiss());
                b.create().show();
            } catch (Exception e) {
                Log.e(TAG, "Failed to show connection info dialog", e);
            }
        });
    }

    // Show an alert dialog on UI thread.
    private static void showAlertOnUiThread(final String title, final String msg) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                AlertDialog.Builder builder = new AlertDialog.Builder(sActivity);
                builder.setTitle(title);
                builder.setMessage(msg);
                builder.setPositiveButton("OK", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                    }
                });
                builder.setCancelable(true);
                builder.create().show();
            } catch (Exception e) {
                Log.e(TAG, "Failed to show dialog", e);
            }
        });
    }

    // Native stub: expected to be implemented by a native OpenVPN integration.
    // Return true if VPN started successfully and connected, false otherwise.
    private static native boolean startNativeOpenVpn(String ovpnFilePath);

    static {
        // Do not attempt to load any library here; keep optional. Developers should
        // bundle a library that implements startNativeOpenVpn and load it elsewhere
        // (or add a System.loadLibrary call here when bundling).
    }
}
