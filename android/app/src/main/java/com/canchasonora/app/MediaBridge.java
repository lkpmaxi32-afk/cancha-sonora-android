package com.canchasonora.app;

import android.content.Context;
import android.webkit.JavascriptInterface;

/** Puente que el reproductor web usa como window.AndroidMedia */
public class MediaBridge {
    private final Context ctx;

    public MediaBridge(Context context) {
        this.ctx = context.getApplicationContext();
    }

    @JavascriptInterface
    public void update(String title, String artist, boolean playing, double durMs, double posMs) {
        MediaService.update(ctx, title, artist, playing, (long) durMs, (long) posMs);
    }

    @JavascriptInterface
    public void stop() {
        MediaService.stop(ctx);
    }
}
