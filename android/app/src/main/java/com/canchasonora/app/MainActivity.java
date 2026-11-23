package com.canchasonora.app;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.webkit.WebView;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        WebView webView = getBridge().getWebView();
        webView.getSettings().setMediaPlaybackRequiresUserGesture(false);
        // La página llama a window.AndroidMedia.update(...) para mostrar la notificación
        webView.addJavascriptInterface(new MediaBridge(this), "AndroidMedia");

        // Botones de la notificación -> reproductor web
        MediaService.setListener(action -> runOnUiThread(() -> {
            try {
                getBridge().getWebView().evaluateJavascript(
                    "window.__onNativeMedia && window.__onNativeMedia('" + action + "')", null);
            } catch (Exception ignored) { }
        }));

        // Android 13+: permiso para mostrar la notificación
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    /** Evita que el WebView se pause al minimizar o apagar la pantalla: la música sigue. */
    private void keepWebViewAlive() {
        try {
            WebView w = getBridge().getWebView();
            if (w != null) {
                w.onResume();
                w.resumeTimers();
            }
        } catch (Exception ignored) { }
    }

    @Override
    public void onPause() {
        super.onPause();
        keepWebViewAlive();
    }

    @Override
    public void onStop() {
        super.onStop();
        keepWebViewAlive();
    }

    @Override
    public void onDestroy() {
        MediaService.setListener(null);
        MediaService.stop(this);
        super.onDestroy();
    }
}
