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

        // First try Java ICS-OpenVPN integration (via reflection to be resilient
        // to minor API differences). If not available, fallback to native stub.
        boolean handled = false;
        try {
            Class<?> profileManagerClass = Class.forName("de.blinkt.openvpn.core.ProfileManager");
            // getInstance(Context)
            Method getInstance = profileManagerClass.getMethod("getInstance", Context.class);
            Object pm = getInstance.invoke(null, sActivity);

            // Try several import method names that appear in various forks.
            String[] candidateMethods = new String[] {"importProfile", "importConfig", "importFromStream", "importProfileFromStream", "importProfileFromInputStream"};
            Method importer = null;
            for (String name : candidateMethods) {
                for (Method m : profileManagerClass.getMethods()) {
                    if (m.getName().equals(name)) {
                        Class<?>[] params = m.getParameterTypes();
                        if (params.length == 1 && InputStream.class.isAssignableFrom(params[0])) {
                            importer = m;
                            break;
                        }
                        if (params.length == 2 && Context.class.isAssignableFrom(params[0]) && InputStream.class.isAssignableFrom(params[1])) {
                            importer = m;
                            break;
                        }
                    }
                }
                if (importer != null) break;
            }

            if (importer != null) {
                InputStream fis = new FileInputStream(new File(sDownloadedOvpnPath));
                Object profileObj = null;
                try {
                    if (importer.getParameterTypes().length == 1) {
                        profileObj = importer.invoke(pm, fis);
                    } else {
                        profileObj = importer.invoke(pm, sActivity, fis);
                    }
                } finally {
                    try { fis.close(); } catch (Exception e) {}
                }

                if (profileObj != null) {
                    // Save profile if ProfileManager supports saveProfile
                    try {
                        Method saveProfile = profileManagerClass.getMethod("saveProfile", profileObj.getClass());
                        saveProfile.invoke(pm, profileObj);
                    } catch (NoSuchMethodException nsme) {
                        // ignore
                    }

                    // Try to start the OpenVPN service
                    try {
                        Class<?> openVpnServiceClass = Class.forName("de.blinkt.openvpn.core.OpenVPNService");
                        Intent intent = new Intent(sActivity, openVpnServiceClass);
                        // Attempt to start using known action or extras if available.
                        // Some versions accept extras like "profileName" or expect Profile to be set as a static.
                        sActivity.startService(intent);
                        showAlertOnUiThread("VPN started", "VPN service started via ICS-OpenVPN integration.");
                        handled = true;
                    } catch (ClassNotFoundException cnfe) {
                        // Can't find the service class. Try starting via ProfileManager start method.
                        try {
                            Method startProfile = profileManagerClass.getMethod("startProfile", profileObj.getClass());
                            startProfile.invoke(pm, profileObj);
                            showAlertOnUiThread("VPN started", "VPN profile started via ProfileManager.");
                            handled = true;
                        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
                            // ignore and fallback
                        }
                    }
                }
            }
        } catch (ClassNotFoundException cnf) {
            // ICS-OpenVPN not present; will fallback to native.
        } catch (Exception e) {
            Log.e(TAG, "Java OpenVPN integration failed", e);
        }

        if (!handled) {
            // Fallback to native stub
            try {
                boolean started = startNativeOpenVpn(sDownloadedOvpnPath);
                if (started) {
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
