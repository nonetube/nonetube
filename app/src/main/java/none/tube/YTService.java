package none.tube;

import android.app.*;
import android.content.*;
import android.os.*;
import android.net.*;
import android.webkit.*;
import android.content.pm.ServiceInfo;
import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.List;

public class YTService extends Service {

    static boolean isAdRequest(Uri u) {
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase();
        String path = u.getPath() == null ? "" : u.getPath().toLowerCase();
        String query = u.getQuery() == null ? "" : u.getQuery().toLowerCase();

        if (host.endsWith("doubleclick.net")
                || host.endsWith("googlesyndication.com")
                || host.endsWith("googleadservices.com")
                || host.endsWith("moatads.com")
                || host.equals("imasdk.googleapis.com")
                || host.startsWith("pagead")
                || host.startsWith("adservice.")) return true;

        if (path.contains("/pagead/")
                || path.contains("/api/stats/ads")
                || path.contains("/get_midroll")
                || path.contains("/ad_data_204")
                || path.contains("/pcs/activeview")
                || path.contains("/ads/")
                || path.contains("/ptracking")) return true;

        if (host.endsWith("googlevideo.com")
                && (query.contains("adformat=") || query.contains("ctier=a&"))) return true;

        return query.contains("adformat=") && host.contains("youtube");
    }

    static WebResourceResponse emptyResponse() {
        return new WebResourceResponse("text/plain", "UTF-8", 204, "No Content",
                new HashMap<String, String>(), new ByteArrayInputStream(new byte[0]));
    }

    static final String AD_CSS =
        ".ytp-ad-module,.video-ads,.ytp-ad-overlay-container,.ytp-ad-message-container," +
        "ytm-promoted-sparkles-web-renderer,ytm-companion-ad-renderer,ytm-promoted-video-renderer," +
        "ytm-display-ad-renderer,ytm-ad-slot-renderer,ad-slot-renderer,ytm-in-feed-ad-layout-renderer," +
        "ytm-banner-promo-renderer,ytm-statement-banner-renderer,.ad-unit{display:none!important}";

    static void injectEarly(WebView v) {
        if (v == null) return;
        StringBuilder js = new StringBuilder();
        js.append("(function(){if(window.__adx)return;window.__adx=1;");

        if (ZeroActivity.VideoAdsSkip) {
            js.append("var K=['adPlacements','playerAds','adSlots','adBreakHeartbeatParams','adBreakParams'];");
            js.append("function strip(o){try{if(o&&typeof o==='object'){");
            js.append("K.forEach(function(k){if(k in o)o[k]=Array.isArray(o[k])?[]:undefined;});");
            js.append("if(o.playerResponse)strip(o.playerResponse);}}catch(e){}return o;}");
            js.append("var jp=JSON.parse;JSON.parse=function(){return strip(jp.apply(this,arguments));};");
            js.append("if(window.Response){var oj=Response.prototype.json;");
            js.append("Response.prototype.json=function(){return oj.call(this).then(strip);};}");
            js.append("var _v;try{Object.defineProperty(window,'ytInitialPlayerResponse',");
            js.append("{configurable:true,get:function(){return _v;},set:function(x){_v=strip(x);}});}catch(e){}");
        }

        if (ZeroActivity.bannerBlock) {
            js.append("var st=document.createElement('style');st.textContent='").append(AD_CSS).append("';");
            js.append("(document.head||document.documentElement).appendChild(st);");
        }

        js.append("})();");
        v.evaluateJavascript(js.toString(), null);
    }

    static void injectLogic(WebView v) {
        if (v == null) return;
        v.evaluateJavascript(
            "clearInterval(window.ytTimer); window.ytTimer = setInterval(() => {" +
            "  const video = document.querySelector('video');" +
            "  const player = document.querySelector('#movie_player');" +
            "  if (!video || !player) return;" +
            "  const isAd = player.classList.contains('ad-showing') || player.classList.contains('ad-interrupting');" +
            "  if (!isAd && isFinite(video.duration) && video.duration > 0) {" +
            "    if (" + ZeroActivity.rep + "==true){" +
            "      if (video.duration - video.currentTime <= 1) {" +
            "        video.currentTime = 0;" +
            "        video.play();" +
            "      }}" +
            "  }" +
            "  if (isAd && " + ZeroActivity.VideoAdsSkip + "==true) {" +
            "    video.muted = true;" +
            "    video.playbackRate = 16;" +
            "    if (isFinite(video.duration) && video.duration > 0) {" +
            "      video.currentTime = video.duration + 1;" +
            "    }" +
            "    document.querySelectorAll('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button')" +
            "      .forEach(b => b.click());" +
            "    document.querySelectorAll('.video-ads, .ytp-ad-module').forEach(e => e.remove());" +
            "    if (!window.__adReloaded && player.loadVideoById) {" +
            "      const id = new URLSearchParams(location.search).get('v');" +
            "      if (id) { window.__adReloaded = true; player.loadVideoById(id, window.__lastT || 0); }" +
            "    }" +
            "    video.play();" +
            "  } else {" +
            "    if (!isAd) { window.__adReloaded = false; window.__lastT = video.currentTime; }" +
            "    if (video.playbackRate === 16) video.playbackRate = 1;" +
            "    if (video.muted && window.__adMuted) { video.muted = false; window.__adMuted = false; }" +
            "  }" +
            "  if (isAd) window.__adMuted = true;" +
            "  if (" + ZeroActivity.bannerBlock + "==true){" +
            "    const selectors = ['.ytp-ad-overlay-container', '.ytp-ad-message-container', 'ytm-companion-ad-renderer'," +
            "      'ytm-promoted-sparkles-web-renderer', '.ad-unit', 'ytm-promoted-video-renderer'," +
            "      'ytm-display-ad-renderer', 'ytm-ad-slot-renderer', 'ad-slot-renderer'," +
            "      'ytm-in-feed-ad-layout-renderer', 'ytm-banner-promo-renderer', 'ytm-statement-banner-renderer'];" +
            "    document.querySelectorAll(selectors.join(',')).forEach(el => el.remove());" +
            "  }" +
            "}, 100);", null);
    }

    @Override
    public void onCreate() {
        super.onCreate();
    }

    static void setupWebStatic(WebView v) {
        WebSettings s = v.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        v.setWebViewClient(new WebViewClient() {

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                if (url == null) return super.shouldInterceptRequest(view, request);

                if ((ZeroActivity.VideoAdsSkip || ZeroActivity.bannerBlock) && isAdRequest(url)) {
                    return emptyResponse();
                }

                String host = url.getHost();
                if (host != null) {
                    boolean isEssential = (ZeroActivity.BlockUnknownLinksAndProtocols == false)
                            || host.matches("(^|.*\\.)(youtube|youtube-nocookie|google|googlevideo|gstatic|ytimg|ggpht)\\.[a-z.]+$");
                    if (!isEssential) {
                        return new WebResourceResponse("text/plain", "UTF-8", null);
                    }
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (ZeroActivity.BlockUnknownLinksAndProtocols == false || url.startsWith("http")) {
                    return false;
                }
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                injectEarly(view);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                injectEarly(view);
                injectLogic(view);
            }
        });
        v.loadUrl("https://m.youtube.com");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startEnforcedService();
        return START_STICKY;
    }

    private void startEnforcedService() {
        Context context = this;
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        String pkg = context.getPackageName();

        List<NotificationChannel> channels = nm.getNotificationChannels();
        String activeId = null;
        boolean needNew = false;

        for (NotificationChannel ch : channels) {
            if (ch.getImportance() == NotificationManager.IMPORTANCE_NONE) {
                nm.deleteNotificationChannel(ch.getId());
                needNew = true;
            } else if (activeId == null) {
                activeId = ch.getId();
            }
        }

        if (needNew || activeId == null) {
            activeId = "none.tube" + Long.toHexString(new java.security.SecureRandom().nextLong());
            NotificationChannel nch = new NotificationChannel(activeId, "Media Play", NotificationManager.IMPORTANCE_LOW);
            nch.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
            nm.createNotificationChannel(nch);
        }

        Notification notif = new Notification.Builder(context, activeId)
                .setContentTitle("Media")
                .setContentText("Play")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setVisibility(Notification.VISIBILITY_SECRET)
                .setOngoing(true)
                .build();

        if (android.os.Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, notif);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
