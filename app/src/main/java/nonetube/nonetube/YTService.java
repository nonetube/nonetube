package nonetube.nonetube;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.*;
import android.util.DisplayMetrics;
import android.view.*;
import android.webkit.*;
import android.widget.FrameLayout;
import java.io.ByteArrayInputStream;
import java.util.HashMap;
import java.util.List;
import org.json.JSONObject;

public class YTService extends Service {

    public static final int MSG_ATTACH = 1;
    public static final int MSG_DETACH = 3;
    public static final int MSG_CMD    = 4;
    public static final int MSG_TOUCH  = 5;
    public static final int MSG_TEXT   = 6;
    public static final int MSG_ENTER  = 7;
    public static final int MSG_SEL    = 8;
    public static final int MSG_HANDLE = 9;
    
    public static final int MSG_FS         = 11;
    public static final int MSG_URL        = 12;
    public static final int MSG_INPUT_SHOW = 13;
    public static final int MSG_INPUT_HIDE = 14;
    public static final int MSG_STATE      = 15;

    static WebView web;
    static YTService inst;

    private VirtualDisplay vd;
    private Presentation pres;
    private ImageReader dummy;
    private HandlerThread dummyThread;
    private int vdW, vdH, vdDpi;

    private FrameLayout rootFrame;
    private Messenger client;
    private IBinder.DeathRecipient clientDeath;
    private View fsView;
    private WebChromeClient.CustomViewCallback fsCallback;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    static class Bridge {
        @JavascriptInterface
        public void focus(final String v, final int s, final int e, final String hint,
                          final boolean multi, final boolean pw) {
            if (inst != null) inst.uiHandler.post(() -> inst.sendInput(v, s, e, hint, multi, pw));
        }

        @JavascriptInterface
        public void state(final String t, final int s, final int e,
                          final int l, final int tp, final int r, final int b,
                          final int sx, final int sy, final int sh,
                          final int ex, final int ey, final int eh) {
            if (inst != null) inst.uiHandler.post(() -> inst.sendState(t, s, e, l, tp, r, b, sx, sy, sh, ex, ey, eh));
        }

        @JavascriptInterface
        public void blur() {
            if (inst != null) inst.uiHandler.post(() -> {
                if (inst.client == null) return;
                try { inst.client.send(Message.obtain(null, MSG_INPUT_HIDE)); }
                catch (RemoteException ignored) {}
            });
        }
    }

    private void sendInput(String v, int s, int e, String hint, boolean multi, boolean pw) {
        if (web != null) {
            web.requestFocus();
            web.onWindowFocusChanged(true);
        }
        if (client == null) return;
        Message m = Message.obtain(null, MSG_INPUT_SHOW);
        Bundle d = m.getData();
        d.putString("text", v);
        d.putInt("sel", s);
        d.putInt("selEnd", e);
        d.putString("hint", hint);
        d.putBoolean("multi", multi);
        d.putBoolean("pw", pw);
        try { client.send(m); } catch (RemoteException ignored) {}
    }

    private void sendState(String t, int s, int e, int l, int tp, int r, int b,
                           int sx, int sy, int sh, int ex, int ey, int eh) {
        if (client == null) return;
        Message m = Message.obtain(null, MSG_STATE);
        Bundle d = m.getData();
        d.putString("text", t);
        d.putInt("s", s);
        d.putInt("e", e);
        d.putInt("l", l);
        d.putInt("t", tp);
        d.putInt("r", r);
        d.putInt("b", b);
        d.putInt("sx", sx);
        d.putInt("sy", sy);
        d.putInt("sh", sh);
        d.putInt("ex", ex);
        d.putInt("ey", ey);
        d.putInt("eh", eh);
        try { client.send(m); } catch (RemoteException ignored) {}
    }

    private final Messenger messenger = new Messenger(new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message m) {
            switch (m.what) {
                case MSG_ATTACH: {
                    Bundle d = m.getData();
                    Surface surf = d.getParcelable("surf");
                    attach(surf, d.getInt("w"), d.getInt("h"), d.getInt("dpi"), m.replyTo);
                    break;
                }
                case MSG_DETACH:
                    detach();
                    break;
                case MSG_TOUCH: {
                    Bundle d = m.getData();
                    d.setClassLoader(MotionEvent.class.getClassLoader());
                    MotionEvent ev = d.getParcelable("ev");
                    if (ev != null && rootFrame != null) {
                        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN && web != null) {
                            web.requestFocus();
                        }
                        rootFrame.dispatchTouchEvent(ev);
                        ev.recycle();
                    }
                    break;
                }
                case MSG_TEXT: {
                    if (web == null) break;
                    String t = m.getData().getString("t", "");
                    int sel = m.getData().getInt("sel", t.length());
                    web.requestFocus();
                    web.evaluateJavascript(
                        "(function(v,s){var e=window.__ntEl||document.activeElement;if(!e)return;" +
                        "if(document.activeElement!==e){try{e.focus({preventScroll:true});}catch(x){}}" +
                        "if(e.isContentEditable){e.textContent=v;}else{" +
                        "var p=e.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;" +
                        "Object.getOwnPropertyDescriptor(p,'value').set.call(e,v);}" +
                        "e.dispatchEvent(new Event('input',{bubbles:true}));" +
                        "if(window.__ntSetSel)window.__ntSetSel(e,s,s);" +
                        "})(" + JSONObject.quote(t) + "," + sel + ")", null);
                    break;
                }
                case MSG_SEL: {
                    if (web == null) break;
                    web.requestFocus();
                    web.evaluateJavascript(
                        "(function(s,n){var e=window.__ntEl||document.activeElement;if(!e)return;" +
                        "if(document.activeElement!==e){try{e.focus({preventScroll:true});}catch(x){}}" +
                        "if(window.__ntSetSel)window.__ntSetSel(e,s,n);})(" + m.arg1 + "," + m.arg2 + ")", null);
                    break;
                }
                case MSG_HANDLE: {
                    if (web == null) break;
                    int x = m.getData().getInt("x");
                    int y = m.getData().getInt("y");
                    web.evaluateJavascript("window.__ntDrag&&window.__ntDrag(" + m.arg1 + "," + x + "," + y + ")", null);
                    break;
                }
                case MSG_ENTER:
                    if (web == null) break;
                    web.evaluateJavascript(
                        "(function(){var e=window.__ntEl||document.activeElement;if(!e)return;" +
                        "['keydown','keypress','keyup'].forEach(function(t){e.dispatchEvent(new KeyboardEvent(t," +
                        "{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true}));});" +
                        "if(e.form&&e.form.requestSubmit){try{e.form.requestSubmit();}catch(x){}}})()", null);
                    break;
                case MSG_CMD:
                    if (web == null) break;
                    if (m.arg1 == 3 && web.canGoBack()) web.goBack();
                    else if (m.arg1 == 4) web.reload();
                    else if (m.arg1 == 2 && m.replyTo != null && web.getUrl() != null) {
                        Message r = Message.obtain(null, MSG_URL);
                        r.getData().putString("url", web.getUrl());
                        try { m.replyTo.send(r); } catch (RemoteException ignored) {}
                    }
                    break;
            }
        }
    });

    private void attach(Surface surf, int w, int h, int dpi, Messenger reply) {
        if (vd == null || surf == null || !surf.isValid() || w <= 0 || h <= 0) return;

        unlinkClient();
        client = reply;
        if (reply != null) {
            try {
                clientDeath = () -> uiHandler.post(this::detach);
                reply.getBinder().linkToDeath(clientDeath, 0);
            } catch (RemoteException e) {
                clientDeath = null;
            }
        }

        if (w != vdW || h != vdH || dpi != vdDpi) {
            vd.resize(w, h, dpi);
            vdW = w; vdH = h; vdDpi = dpi;
        }
        vd.setSurface(surf);
        notifyFs();
    }

    private void detach() {
        unlinkClient();
        client = null;
        if (vd != null && dummy != null) vd.setSurface(dummy.getSurface());
    }

    private void unlinkClient() {
        if (client != null && clientDeath != null) {
            try { client.getBinder().unlinkToDeath(clientDeath, 0); } catch (Exception ignored) {}
        }
        clientDeath = null;
    }

    private void notifyFs() {
        if (client == null) return;
        try {
            client.send(Message.obtain(null, MSG_FS, fsView != null ? 1 : 0, 0));
        } catch (RemoteException ignored) {}
    }

    private final WebChromeClient chrome = new WebChromeClient() {
        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            if (fsView != null) {
                callback.onCustomViewHidden();
                return;
            }
            fsView = view;
            fsCallback = callback;
            if (view.getParent() instanceof ViewGroup) {
                ((ViewGroup) view.getParent()).removeView(view);
            }
            view.setBackgroundColor(android.graphics.Color.BLACK);
            rootFrame.addView(view, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            notifyFs();
        }

        @Override
        public void onHideCustomView() {
            if (fsView == null) return;
            rootFrame.removeView(fsView);
            fsView = null;
            fsCallback = null;
            notifyFs();
        }

        @Override
        public android.graphics.Bitmap getDefaultVideoPoster() {
            return android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888);
        }
    };

    static class DummyActionMode extends ActionMode {
        private CharSequence title, subtitle;
        private View custom;

        @Override public void setTitle(CharSequence t) { title = t; }
        @Override public void setTitle(int resId) { }
        @Override public void setSubtitle(CharSequence t) { subtitle = t; }
        @Override public void setSubtitle(int resId) { }
        @Override public void setCustomView(View view) { custom = view; }
        @Override public void invalidate() { }
        @Override public void finish() { }
        @Override public Menu getMenu() { return null; }
        @Override public CharSequence getTitle() { return title; }
        @Override public CharSequence getSubtitle() { return subtitle; }
        @Override public View getCustomView() { return custom; }
        @Override public MenuInflater getMenuInflater() { return null; }
    }

    static class MyWebView extends WebView {
        MyWebView(Context context) {
            super(context);
        }

        @Override
        public ActionMode startActionMode(ActionMode.Callback callback) {
            return new DummyActionMode();
        }

        @Override
        public ActionMode startActionMode(ActionMode.Callback callback, int type) {
            return new DummyActionMode();
        }

        @Override
        public void onWindowFocusChanged(boolean hasWindowFocus) {
            super.onWindowFocusChanged(true);
        }
    }

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

        js.append("try{Object.defineProperty(document,'visibilityState',{get:function(){return 'visible';},configurable:true});");
        js.append("Object.defineProperty(document,'hidden',{get:function(){return false;},configurable:true});}catch(e){}");
        js.append("try{document.hasFocus=function(){return true;};}catch(e){}");
        js.append("document.addEventListener('visibilitychange',function(e){e.stopImmediatePropagation();},true);");
        js.append("document.addEventListener('webkitvisibilitychange',function(e){e.stopImmediatePropagation();},true);");
        js.append("['blur','pagehide','freeze'].forEach(function(t){window.addEventListener(t,function(e){e.stopImmediatePropagation();},true);});");

        js.append("document.addEventListener('canplay',function(e){var v=e.target;");
        js.append("if(v&&v.tagName==='VIDEO'&&location.pathname.indexOf('/shorts')===0&&v.paused){");
        js.append("var r=v.getBoundingClientRect();if(r.bottom>0&&r.top<innerHeight){v.play().catch(function(){});}}},true);");

        js.append("window.__ntMk=function(e,fill){var cs=getComputedStyle(e),r=e.getBoundingClientRect(),m=document.createElement('div'),st=m.style;");
        js.append("['fontFamily','fontSize','fontWeight','fontStyle','letterSpacing','wordSpacing','textTransform','textIndent',");
        js.append("'paddingTop','paddingRight','paddingBottom','paddingLeft','borderTopWidth','borderRightWidth','borderBottomWidth','borderLeftWidth',");
        js.append("'borderTopStyle','borderRightStyle','borderBottomStyle','borderLeftStyle','lineHeight','textAlign','tabSize','direction'].forEach(function(k){st[k]=cs[k];});");
        js.append("st.position='fixed';st.left=r.left+'px';st.top=r.top+'px';st.width=r.width+'px';st.height=r.height+'px';");
        js.append("st.boxSizing='border-box';st.overflow='hidden';st.visibility='hidden';st.pointerEvents='none';");
        js.append("var ta=e.tagName==='TEXTAREA';st.whiteSpace=ta?'pre-wrap':'pre';st.wordWrap='break-word';");
        js.append("if(!ta){st.lineHeight=Math.max(1,e.clientHeight-parseFloat(cs.paddingTop)-parseFloat(cs.paddingBottom))+'px';}");
        js.append("fill(m);document.body.appendChild(m);m.scrollLeft=e.scrollLeft;m.scrollTop=e.scrollTop;return m;};");

        js.append("window.__ntCaret=function(e,p){var d=window.devicePixelRatio||1,x=0,y=0,h=0;");
        js.append("if(e.isContentEditable){var f=e.firstChild,rc=null;");
        js.append("if(f&&f.nodeType===3){var g=document.createRange();g.setStart(f,Math.min(p,f.textContent.length));g.collapse(true);");
        js.append("rc=g.getClientRects()[0]||g.getBoundingClientRect();}");
        js.append("if(!rc||(!rc.height&&!rc.width))rc=e.getBoundingClientRect();x=rc.left;y=rc.top;h=rc.height;}");
        js.append("else{var sp;var m=window.__ntMk(e,function(m){m.appendChild(document.createTextNode((e.value||'').substring(0,p)));");
        js.append("sp=document.createElement('span');sp.textContent=String.fromCharCode(8203);m.appendChild(sp);});");
        js.append("var q=sp.getBoundingClientRect();x=q.left;y=q.top;h=q.height;m.remove();}");
        js.append("return [Math.round(x*d),Math.round(y*d),Math.round(h*d)];};");

        js.append("window.__ntHit=function(e,x,y){");
        js.append("if(e.isContentEditable){var r=document.caretRangeFromPoint(x,y);");
        js.append("if(r&&e.contains(r.startContainer)&&r.startContainer.nodeType===3)return r.startOffset;return (e.textContent||'').length;}");
        js.append("var v=(e.value||'').substring(0,3000),sps=[];");
        js.append("var m=window.__ntMk(e,function(m){for(var i=0;i<v.length;i++){var s=document.createElement('span');s.textContent=v.charAt(i);sps.push(s);m.appendChild(s);}});");
        js.append("var best=v.length,bs=1e9,last=null;");
        js.append("for(var i=0;i<sps.length;i++){var q=sps[i].getBoundingClientRect();var cy=q.top+q.height/2;");
        js.append("var sc=Math.abs(y-cy)*4+Math.abs(x-q.left);if(sc<bs){bs=sc;best=i;}last=q;}");
        js.append("if(last){var cy2=last.top+last.height/2;var sc2=Math.abs(y-cy2)*4+Math.abs(x-last.right);if(sc2<bs)best=v.length;}");
        js.append("m.remove();return best;};");

        js.append("window.__ntRead=function(e){var tx,s,n;");
        js.append("if(e.isContentEditable){tx=e.textContent||'';s=n=tx.length;var sl=getSelection();");
        js.append("if(sl.rangeCount){var r=sl.getRangeAt(0);");
        js.append("if(r.startContainer===r.endContainer&&e.contains(r.startContainer)&&r.startContainer.nodeType===3){s=r.startOffset;n=r.endOffset;}}}");
        js.append("else{tx=e.value||'';s=(typeof e.selectionStart==='number')?e.selectionStart:tx.length;");
        js.append("n=(typeof e.selectionEnd==='number')?e.selectionEnd:s;}");
        js.append("var b=e.getBoundingClientRect(),d=window.devicePixelRatio||1,c1=[0,0,0],c2=c1;");
        js.append("try{c1=window.__ntCaret(e,s);c2=(n===s)?c1:window.__ntCaret(e,n);}catch(x){}");
        js.append("return [tx,s,n,Math.round(b.left*d),Math.round(b.top*d),Math.round(b.right*d),Math.round(b.bottom*d),c1[0],c1[1],c1[2],c2[0],c2[1],c2[2]];};");

        js.append("window.__ntPush=function(e){var a=window.__ntRead(e);window.__ntLast=JSON.stringify(a);");
        js.append("if(window.NTBridge)NTBridge.state(a[0],a[1],a[2],a[3],a[4],a[5],a[6],a[7],a[8],a[9],a[10],a[11],a[12]);};");

        js.append("window.__ntSetSel=function(e,s,n){");
        js.append("if(e.isContentEditable){var f=e.firstChild;if(f&&f.nodeType===3){var r=document.createRange(),sl=getSelection();");
        js.append("var l=f.textContent.length;r.setStart(f,Math.min(s,l));r.setEnd(f,Math.min(n,l));sl.removeAllRanges();sl.addRange(r);}}");
        js.append("else{try{e.setSelectionRange(s,n);}catch(x){}}");
        js.append("window.__ntPush(e);};");

        js.append("window.__ntDrag=function(w,x,y){var e=window.__ntEl;if(!e)return;");
        js.append("if(document.activeElement!==e){try{e.focus({preventScroll:true});}catch(q){}}");
        js.append("var d=window.devicePixelRatio||1,p=window.__ntHit(e,x/d,y/d),a=window.__ntRead(e),s=a[1],n=a[2];");
        js.append("if(w===0)s=Math.min(p,n);else n=Math.max(p,s);");
        js.append("window.__ntSetSel(e,s,n);};");

        js.append("var ed=function(e){return e&&((e.tagName==='INPUT'&&!/^(checkbox|radio|button|submit|range|file|color|image|reset)$/i.test(e.type))||e.tagName==='TEXTAREA'||e.isContentEditable);};");
        js.append("document.addEventListener('focusin',function(e){var t=e.target;if(ed(t)&&window.NTBridge){");
        js.append("window.__ntEl=t;var k=window.__ntRead(t);window.__ntLast='';");
        js.append("NTBridge.focus(k[0],k[1],k[2],t.placeholder||t.getAttribute('aria-label')||'',t.tagName==='TEXTAREA',t.type==='password');}},true);");
        js.append("document.addEventListener('focusout',function(){setTimeout(function(){");
        js.append("if(!ed(document.activeElement)&&window.NTBridge)NTBridge.blur();},200);},true);");

        js.append("setInterval(function(){var e=window.__ntEl;");
        js.append("if(!e||document.activeElement!==e||!window.NTBridge)return;");
        js.append("var a=window.__ntRead(e);var k=JSON.stringify(a);");
        js.append("if(k!==window.__ntLast){window.__ntLast=k;");
        js.append("NTBridge.state(a[0],a[1],a[2],a[3],a[4],a[5],a[6],a[7],a[8],a[9],a[10],a[11],a[12]);}},120);");

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
            "clearInterval(window.ytTimer); window.ytTimer = setInterval(function(){" +
            "  var video=null,best=0;" +
            "  document.querySelectorAll('video').forEach(function(x){" +
            "    var r=x.getBoundingClientRect();" +
            "    var w=Math.max(0,Math.min(r.right,innerWidth)-Math.max(r.left,0));" +
            "    var h=Math.max(0,Math.min(r.bottom,innerHeight)-Math.max(r.top,0));" +
            "    if(w*h>best){best=w*h;video=x;}" +
            "  });" +
            "  if(!video) return;" +
            "  var player=document.querySelector('#movie_player');" +
            "  var shorts=location.pathname.indexOf('/shorts')===0;" +
            "  var isAd=!!player&&(player.classList.contains('ad-showing')||player.classList.contains('ad-interrupting'));" +
            "  if(!isAd&&!shorts&&" + ZeroActivity.rep + "==true&&isFinite(video.duration)&&video.duration>0" +
            "     &&video.duration-video.currentTime<=1){video.currentTime=0;video.play();}" +
            "  if(isAd&&" + ZeroActivity.VideoAdsSkip + "==true){" +
            "    video.muted=true; video.playbackRate=16;" +
            "    if(isFinite(video.duration)&&video.duration>0){video.currentTime=video.duration+1;}" +
            "    document.querySelectorAll('.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-skip-ad-button').forEach(function(b){b.click();});" +
            "    document.querySelectorAll('.video-ads,.ytp-ad-module').forEach(function(e){e.remove();});" +
            "    if(!window.__adReloaded&&player&&player.loadVideoById){" +
            "      var id=new URLSearchParams(location.search).get('v');" +
            "      if(id){window.__adReloaded=true;player.loadVideoById(id,window.__lastT||0);}" +
            "    }" +
            "    video.play();" +
            "  } else {" +
            "    if(!isAd&&!shorts){window.__adReloaded=false;window.__lastT=video.currentTime;}" +
            "    if(video.playbackRate===16) video.playbackRate=1;" +
            "    if(video.muted&&window.__adMuted){video.muted=false;window.__adMuted=false;}" +
            "  }" +
            "  if(isAd) window.__adMuted=true;" +
            "  if(" + ZeroActivity.bannerBlock + "==true){" +
            "    var sel=['.ytp-ad-overlay-container','.ytp-ad-message-container','ytm-companion-ad-renderer'," +
            "      'ytm-promoted-sparkles-web-renderer','.ad-unit','ytm-promoted-video-renderer'," +
            "      'ytm-display-ad-renderer','ytm-ad-slot-renderer','ad-slot-renderer'," +
            "      'ytm-in-feed-ad-layout-renderer','ytm-banner-promo-renderer','ytm-statement-banner-renderer'];" +
            "    document.querySelectorAll(sel.join(',')).forEach(function(el){el.remove();});" +
            "  }" +
            "}, 100);", null);
    }

    static void setupWebStatic(WebView v) {
        WebSettings s = v.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        v.addJavascriptInterface(new Bridge(), "NTBridge");
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
    public void onCreate() {
        super.onCreate();
        inst = this;

        DisplayMetrics dm = getResources().getDisplayMetrics();
        vdW = dm.widthPixels;
        vdH = dm.heightPixels;
        vdDpi = dm.densityDpi;

        dummyThread = new HandlerThread("nt-dummy");
        dummyThread.start();
        dummy = ImageReader.newInstance(vdW, vdH, PixelFormat.RGBA_8888, 2);
        dummy.setOnImageAvailableListener(r -> {
            try {
                Image img = r.acquireLatestImage();
                if (img != null) img.close();
            } catch (Exception ignored) {}
        }, new Handler(dummyThread.getLooper()));

        DisplayManager dmgr = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        vd = dmgr.createVirtualDisplay("nonetube-web", vdW, vdH, vdDpi, dummy.getSurface(),
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
                        | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);

        pres = new Presentation(this, vd.getDisplay());
        Context pc = pres.getContext();

        rootFrame = new FrameLayout(pc);
        web = new MyWebView(pc);
        web.setWebChromeClient(chrome);
        setupWebStatic(web);
        rootFrame.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        pres.setContentView(rootFrame);
        Window w = pres.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(null);
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            w.getDecorView().setPadding(0, 0, 0, 0);
        }
        pres.show();

        web.onResume();
        web.resumeTimers();
    }

    @Override
    public void onDestroy() {
        unlinkClient();
        try { if (pres != null) pres.dismiss(); } catch (Exception ignored) {}
        if (web != null) {
            try { web.destroy(); } catch (Exception ignored) {}
            web = null;
        }
        if (vd != null) { vd.release(); vd = null; }
        if (dummy != null) { dummy.close(); dummy = null; }
        if (dummyThread != null) { dummyThread.quitSafely(); dummyThread = null; }
        inst = null;
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startEnforcedService();
        return START_STICKY;
    }

    private void startEnforcedService() {
        Context context = this;
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

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
        return messenger.getBinder();
    }
}
