package org.ppsspp.ppsspp;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.net.VpnService;
import android.util.Log;

public class EmbeddedVpnService extends VpnService {
    private static final String TAG = "EmbeddedVpnService";
    private ParcelFileDescriptor mInterface = null;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "EmbeddedVpnService started");
        // Actual VPN start should be handled by native code or an internal VPN
        // implementation. This placeholder simply returns START_STICKY so the
        // service isn't killed immediately.
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "EmbeddedVpnService destroyed");
        try {
            if (mInterface != null) mInterface.close();
        } catch (Exception e) {}
        mInterface = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return super.onBind(intent);
    }
}
