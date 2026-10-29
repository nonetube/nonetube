package nonetube.nonetube;

import android.net.Uri;
import android.provider.Settings;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.ActionMode;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.RelativeLayout;
import android.widget.TextView;

public class MainActivity extends Activity {

    private static class HandleView extends View {
        private final boolean left;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        HandleView(Context c, boolean left) {
            super(c);
            this.left = left;
            p.setColor(0xFF1A73E8);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            float r = Math.min(w, h) / 2f;
            c.drawCircle(w / 2f, h / 2f, r, p);
            if (left) c.drawRect(w / 2f, 0, w, h / 2f, p);
            else c.drawRect(0, 0, w / 2f, h / 2f, p);
        }
    }

    private SurfaceView sv;
    private RelativeLayout topPanel;
    private EditText input;
    private boolean inputActive = false;
    private boolean suppressWatcher = false;
    private String lastSentText = "";
    private long lastLocalEdit = 0;
    private long lastApplyTime = 0;
    private Messenger service;
    private boolean bound = false;
    private boolean surfaceReady = false;
    private boolean attached = false;
    private int surfW, surfH;
    private int prevOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
    private boolean inFullscreen = false;
    private float density = 1f;

    private int csx, csy, csh, cex, cey, ceh;

    private ActionMode selMode;
    private boolean menuIsCaret = false;
    private int caretMenuPos = -1;
    private String dismissedKey = null;
    private final Rect selRect = new Rect();

    private HandleView hStart, hEnd;
    private int handleSize;
    private boolean dragging = false;
    private final Runnable endDrag = () -> {
        dragging = false;
        try { if (selMode != null) selMode.hide(0); } catch (Exception ignored) {}
        updateHandles();
        syncMenu();
    };

    private float downX, downY;
    private long downTime;
    private final Runnable lpCheck = () -> {
        if (!inputActive || input == null) return;
        if (input.getSelectionStart() != input.getSelectionEnd()) return;
        if (!pointInField(downX, downY, 24)) return;
        showCaretMenu();
    };

    private final Handler ui = new Handler(Looper.getMainLooper());

    private final Messenger me = new Messenger(new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message m) {
            switch (m.what) {
                case YTService.MSG_FS:
                    setFullscreen(m.arg1 == 1);
                    break;
                case YTService.MSG_URL: {
                    String url = m.getData().getString("url");
                    ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cb != null && url != null) cb.setPrimaryClip(ClipData.newPlainText("Link", url));
                    break;
                }
                case YTService.MSG_INPUT_SHOW: {
                    Bundle d = m.getData();
                    String text = d.getString("text", "");
                    showInput(text, d.getInt("sel", text.length()), d.getInt("selEnd", text.length()),
                            d.getBoolean("multi"), d.getBoolean("pw"));
                    break;
                }
                case YTService.MSG_STATE: {
                    Bundle d = m.getData();
                    selRect.set(d.getInt("l"), d.getInt("t"), d.getInt("r"), d.getInt("b"));
                    csx = d.getInt("sx"); csy = d.getInt("sy"); csh = d.getInt("sh");
                    cex = d.getInt("ex"); cey = d.getInt("ey"); ceh = d.getInt("eh");
                    applyState(d.getString("text", ""), d.getInt("s"), d.getInt("e"));
                    updateHandles();
                    break;
                }
                case YTService.MSG_INPUT_HIDE:
                    hideInput();
                    break;
            }
        }
    });

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName n, IBinder b) {
            service = new Messenger(b);
            attached = false;
            sendAttach();
        }

        @Override
        public void onServiceDisconnected(ComponentName n) {
            service = null;
            attached = false;
            if (bound) {
                try { unbindService(conn); } catch (Exception ignored) {}
                bound = false;
                ui.postDelayed(() -> bindToService(), 500);
            }
        }

        @Override
        public void onBindingDied(ComponentName name) {
            onServiceDisconnected(name);
        }
    };

    private void bindToService() {
        if (bound) return;
        Intent svc = new Intent(this, YTService.class);
        try {
            startForegroundService(svc);
            bound = bindService(svc, conn, Context.BIND_AUTO_CREATE);
        } catch (Exception ignored) {}
    }

    private void send(int what, int a1, int a2, boolean reply) {
        if (service == null) return;
        try {
            Message m = Message.obtain(null, what, a1, a2);
            if (reply) m.replyTo = me;
            service.send(m);
        } catch (RemoteException ignored) {}
    }

    private void sendText(String t, int sel) {
        if (service == null) return;
        lastSentText = t;
        lastLocalEdit = SystemClock.uptimeMillis();
        try {
            Message m = Message.obtain(null, YTService.MSG_TEXT);
            m.getData().putString("t", t);
            m.getData().putInt("sel", sel);
            service.send(m);
        } catch (RemoteException ignored) {}
    }

    private void sendHandle(int which, int x, int y) {
        if (service == null) return;
        try {
            Message m = Message.obtain(null, YTService.MSG_HANDLE, which, 0);
            m.getData().putInt("x", x);
            m.getData().putInt("y", y);
            service.send(m);
        } catch (RemoteException ignored) {}
    }

    private void sendAttach() {
        if (service == null || sv == null || !surfaceReady || surfW <= 0 || surfH <= 0) return;
        Surface s = sv.getHolder().getSurface();
        if (s == null || !s.isValid()) return;
        try {
            Message m = Message.obtain(null, YTService.MSG_ATTACH);
            m.replyTo = me;
            Bundle d = m.getData();
            d.putParcelable("surf", s);
            d.putInt("w", surfW);
            d.putInt("h", surfH);
            d.putInt("dpi", getResources().getDisplayMetrics().densityDpi);
            service.send(m);
            attached = true;
        } catch (RemoteException ignored) {}
    }

    private void forwardTouch(MotionEvent e) {
        if (service == null || !attached) return;
        MotionEvent copy = MotionEvent.obtain(e);
        try {
            Message m = Message.obtain(null, YTService.MSG_TOUCH);
            m.getData().putParcelable("ev", copy);
            service.send(m);
        } catch (RemoteException ignored) {
        } finally {
            copy.recycle();
        }
    }

    private boolean pointInField(float x, float y, int pad) {
        return selRect.width() > 0 && selRect.height() > 0
                && x >= selRect.left - pad && x <= selRect.right + pad
                && y >= selRect.top - pad && y <= selRect.bottom + pad;
    }

    private void trackLongPress(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                downTime = SystemClock.uptimeMillis();
                ui.removeCallbacks(lpCheck);
                ui.postDelayed(lpCheck, ViewConfiguration.getLongPressTimeout() + 300);
                break;
            case MotionEvent.ACTION_MOVE:
                if (Math.hypot(e.getX() - downX, e.getY() - downY)
                        > ViewConfiguration.get(this).getScaledTouchSlop()) {
                    ui.removeCallbacks(lpCheck);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (SystemClock.uptimeMillis() - downTime < ViewConfiguration.getLongPressTimeout()) {
                    ui.removeCallbacks(lpCheck);
                }
                break;
        }
    }

    private boolean clipHasText() {
        ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        return cb != null && cb.hasPrimaryClip() && cb.getPrimaryClip() != null
                && cb.getPrimaryClip().getItemCount() > 0;
    }

    private final ActionMode.Callback2 selCallback = new ActionMode.Callback2() {
        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            boolean hasSel = input != null && input.getSelectionStart() != input.getSelectionEnd();
            if (menuIsCaret && !hasSel) {
                if (clipHasText()) menu.add(0, 3, 0, android.R.string.paste);
                if (input != null && input.length() > 0) menu.add(0, 4, 1, android.R.string.selectAll);
                return menu.size() > 0;
            }
            menu.add(0, 1, 0, android.R.string.cut);
            menu.add(0, 2, 1, android.R.string.copy);
            menu.add(0, 3, 2, android.R.string.paste);
            menu.add(0, 4, 3, android.R.string.selectAll);
            return true;
        }

        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            return false;
        }

        @Override
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            if (input == null) return true;
            int a = Math.min(input.getSelectionStart(), input.getSelectionEnd());
            int b = Math.max(input.getSelectionStart(), input.getSelectionEnd());
            ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            switch (item.getItemId()) {
                case 1:
                    if (a != b && cb != null) {
                        cb.setPrimaryClip(ClipData.newPlainText("text", input.getText().subSequence(a, b)));
                        input.getText().delete(a, b);
                    }
                    mode.finish();
                    break;
                case 2:
                    if (a != b && cb != null) {
                        cb.setPrimaryClip(ClipData.newPlainText("text", input.getText().subSequence(a, b)));
                    }
                    mode.finish();
                    break;
                case 3:
                    if (clipHasText()) {
                        CharSequence t = cb.getPrimaryClip().getItemAt(0).coerceToText(MainActivity.this);
                        if (t != null) input.getText().replace(a, b, t);
                    }
                    mode.finish();
                    break;
                case 4:
                    lastApplyTime = 0;
                    mode.finish();
                    ui.post(() -> { if (input != null && inputActive) input.selectAll(); });
                    break;
            }
            return true;
        }

        @Override
        public void onDestroyActionMode(ActionMode mode) {
            if (selMode == mode) {
                selMode = null;
                if (input != null && input.getSelectionStart() != input.getSelectionEnd()) {
                    dismissedKey = input.getSelectionStart() + ":" + input.getSelectionEnd();
                }
            }
        }

        @Override
        public void onGetContentRect(ActionMode mode, View view, Rect outRect) {
            int a = input != null ? input.getSelectionStart() : 0;
            int b = input != null ? input.getSelectionEnd() : 0;
            if (a == b && csh > 0) {
                outRect.set(csx, csy, csx + 2, csy + csh);
            } else if (a != b && csh > 0 && ceh > 0) {
                boolean oneLine = Math.abs(csy - cey) < csh / 2;
                if (oneLine) {
                    outRect.set(Math.min(csx, cex), csy, Math.max(csx, cex) + 1, csy + csh);
                } else {
                    outRect.set(selRect.left, Math.min(csy, cey), selRect.right, Math.max(csy + csh, cey + ceh));
                }
            } else if (selRect.width() > 0 && selRect.height() > 0) {
                outRect.set(selRect);
            } else {
                outRect.set(0, 0, Math.max(1, view.getWidth()), (int) (48 * density));
            }
        }
    };

    private void showCaretMenu() {
        if (sv == null || input == null || !inputActive) return;
        int a = input.getSelectionStart();
        if (a != input.getSelectionEnd()) return;
        finishMenu();
        menuIsCaret = true;
        caretMenuPos = a;
        dismissedKey = null;
        selMode = sv.startActionMode(selCallback, ActionMode.TYPE_FLOATING);
        if (selMode == null) menuIsCaret = false;
    }

    private void syncMenu() {
        if (sv == null || input == null || !inputActive) {
            finishMenu();
            return;
        }
        int a = input.getSelectionStart();
        int b = input.getSelectionEnd();
        if (a == b) {
            if (selMode != null && menuIsCaret && a == caretMenuPos) {
                selMode.invalidateContentRect();
                return;
            }
            dismissedKey = null;
            finishMenu();
            return;
        }
        if (selMode != null && menuIsCaret) finishMenu();
        String key = Math.min(a, b) + ":" + Math.max(a, b);
        if (key.equals(dismissedKey)) return;
        if (dragging) return;
        if (selMode == null) {
            menuIsCaret = false;
            selMode = sv.startActionMode(selCallback, ActionMode.TYPE_FLOATING);
        } else {
            selMode.invalidateContentRect();
        }
    }

    private void finishMenu() {
        ActionMode m = selMode;
        selMode = null;
        if (m != null) m.finish();
    }

    private void updateHandles() {
        if (hStart == null || hEnd == null || dragging) return;
        boolean show = inputActive && !inFullscreen && input != null
                && input.getSelectionStart() != input.getSelectionEnd()
                && selRect.width() > 0 && csh > 0 && ceh > 0;
        if (!show) {
            hStart.setVisibility(View.GONE);
            hEnd.setVisibility(View.GONE);
            return;
        }
        boolean s1 = pointInField(csx, csy + csh / 2f, 4);
        boolean s2 = pointInField(cex, cey + ceh / 2f, 4);
        hStart.setTranslationX(csx - handleSize);
        hStart.setTranslationY(csy + csh);
        hEnd.setTranslationX(cex);
        hEnd.setTranslationY(cey + ceh);
        hStart.setVisibility(s1 ? View.VISIBLE : View.GONE);
        hEnd.setVisibility(s2 ? View.VISIBLE : View.GONE);
    }

    private void setupHandle(final HandleView hv, final int which) {
        hv.setOnTouchListener(new View.OnTouchListener() {
            float dx, dy;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                float px = v.getTranslationX() + e.getX();
                float py = v.getTranslationY() + e.getY();
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN: {
                        ui.removeCallbacks(endDrag);
                        dragging = true;
                        float cx = which == 0 ? csx : cex;
                        float cy = which == 0 ? csy + csh / 2f : cey + ceh / 2f;
                        dx = px - cx;
                        dy = py - cy;
                        try { if (selMode != null) selMode.hide(3000); } catch (Exception ignored) {}
                        return true;
                    }
                    case MotionEvent.ACTION_MOVE: {
                        float tx = px - dx;
                        float ty = py - dy;
                        float ch = which == 0 ? csh : ceh;
                        v.setTranslationX(which == 0 ? tx - handleSize : tx);
                        v.setTranslationY(ty + ch / 2f);
                        sendHandle(which, (int) tx, (int) ty);
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        ui.postDelayed(endDrag, 250);
                        return true;
                }
                return true;
            }
        });
    }

    private void showInput(String text, int s, int e, boolean multi, boolean pw) {
        if (input == null) return;
        int type = InputType.TYPE_CLASS_TEXT;
        if (multi) type |= InputType.TYPE_TEXT_FLAG_MULTI_LINE;
        if (pw) type |= InputType.TYPE_TEXT_VARIATION_PASSWORD;
        input.setInputType(type);
        input.setImeOptions(multi ? EditorInfo.IME_ACTION_NONE : EditorInfo.IME_ACTION_SEARCH);

        suppressWatcher = true;
        input.setText(text);
        int len = text.length();
        int a = Math.max(0, Math.min(s, len));
        int b = Math.max(a, Math.min(e, len));
        input.setSelection(a, b);
        suppressWatcher = false;
        lastSentText = text;
        lastApplyTime = SystemClock.uptimeMillis();
        dismissedKey = null;

        inputActive = true;
        input.requestFocus();
        input.post(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
        });
        syncMenu();
        updateHandles();
    }

    private void applyState(String text, int s, int e) {
        if (input == null || !inputActive) return;
        String cur = input.getText().toString();
        suppressWatcher = true;
        try {
            if (!cur.equals(text)) {
                if (SystemClock.uptimeMillis() - lastLocalEdit < 300) return;
                input.setText(text);
            }
            int len = input.length();
            int a = Math.max(0, Math.min(s, len));
            int b = Math.max(a, Math.min(e, len));
            input.setSelection(a, b);
            lastSentText = text;
            lastApplyTime = SystemClock.uptimeMillis();
        } finally {
            suppressWatcher = false;
        }
        syncMenu();
    }

    private void hideInput() {
        ui.removeCallbacks(lpCheck);
        ui.removeCallbacks(endDrag);
        dragging = false;
        finishMenu();
        dismissedKey = null;
        boolean was = inputActive;
        inputActive = false;
        updateHandles();
        if (input == null || !was) return;
        input.clearFocus();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
    }

    private void setFullscreen(boolean on) {
        if (on == inFullscreen) return;
        inFullscreen = on;
        if (on) {
            prevOrientation = getRequestedOrientation();
            topPanel.setVisibility(View.GONE);
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        } else {
            topPanel.setVisibility(View.VISIBLE);
            setRequestedOrientation(prevOrientation);
        }
        updateHandles();
    }

    private void buildUi() {
        density = getResources().getDisplayMetrics().density;
        handleSize = (int) (26 * density);

        int safeMarginPx = (int) (44 * density);
        int cornerRadius = 0;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            android.view.RoundedCorner topCorner = getWindowManager().getDefaultDisplay()
                    .getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_RIGHT);
            if (topCorner != null) cornerRadius = topCorner.getRadius();
        }
        int finalXY = Math.max(cornerRadius, safeMarginPx);

        LinearLayout mainLayout = new LinearLayout(this);
        mainLayout.setOrientation(LinearLayout.VERTICAL);
        mainLayout.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        topPanel = new RelativeLayout(this);
        topPanel.setBackgroundColor(android.graphics.Color.parseColor("#1A1A1A"));
        topPanel.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, finalXY));

        TextView menuButton = new TextView(this);
        menuButton.setText("⋮");
        menuButton.setTextColor(android.graphics.Color.WHITE);
        menuButton.setTextSize(22);
        menuButton.setIncludeFontPadding(false);
        menuButton.setGravity(Gravity.CENTER);

        RelativeLayout.LayoutParams btnParams = new RelativeLayout.LayoutParams(finalXY, finalXY);
        btnParams.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);
        btnParams.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        menuButton.setLayoutParams(btnParams);

        menuButton.setOnClickListener(v -> {
            PopupMenu popup = new PopupMenu(this, v);
            popup.getMenu().add(0, 1, 0, "Settings");
            popup.getMenu().add(0, 2, 0, "Copy link");
            popup.getMenu().add(0, 3, 0, "Back");
            popup.getMenu().add(0, 4, 0, "Restart");

            popup.setOnMenuItemClickListener(item -> {
                int id = item.getItemId();
                if (id == 1) {
                    Intent i = new Intent(this, ZeroActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
                    startActivity(i);
                    moveTaskToBack(true);
                } else {
                    send(YTService.MSG_CMD, id, 0, id == 2);
                }
                return true;
            });
            popup.show();
        });
        topPanel.addView(menuButton);

        sv = new SurfaceView(this);
        sv.setFocusable(true);
        sv.setFocusableInTouchMode(true);
        sv.setOnTouchListener((v, e) -> {
            trackLongPress(e);
            forwardTouch(e);
            return true;
        });

        sv.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder h) {
                surfaceReady = true;
            }

            @Override
            public void surfaceChanged(SurfaceHolder h, int format, int w, int hh) {
                surfW = w;
                surfH = hh;
                sendAttach();
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder h) {
                surfaceReady = false;
                attached = false;
                send(YTService.MSG_DETACH, 0, 0, false);
            }
        });

        input = new EditText(this) {
            @Override
            protected void onSelectionChanged(int selStart, int selEnd) {
                super.onSelectionChanged(selStart, selEnd);
                if (suppressWatcher || !inputActive) return;
                if (SystemClock.uptimeMillis() - lastApplyTime < 400) return;
                if (getText().toString().equals(lastSentText)) {
                    lastLocalEdit = SystemClock.uptimeMillis();
                    send(YTService.MSG_SEL, selStart, selEnd, false);
                    syncMenu();
                }
            }
        };
        input.setBackground(null);
        input.setAlpha(0f);
        input.setCursorVisible(false);
        input.setMaxLines(1);
        input.setCustomSelectionActionModeCallback(new ActionMode.Callback() {
            @Override public boolean onCreateActionMode(ActionMode m, Menu menu) { return false; }
            @Override public boolean onPrepareActionMode(ActionMode m, Menu menu) { return false; }
            @Override public boolean onActionItemClicked(ActionMode m, MenuItem i) { return false; }
            @Override public void onDestroyActionMode(ActionMode m) { }
        });
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                if (!suppressWatcher && inputActive) {
                    sendText(s.toString(), input.getSelectionStart());
                }
            }
        });
        input.setOnEditorActionListener((tv, actionId, ev) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE
                    || actionId == EditorInfo.IME_ACTION_GO) {
                send(YTService.MSG_ENTER, 0, 0, false);
                hideInput();
                return true;
            }
            return false;
        });

        FrameLayout webHolder = new FrameLayout(this);
        webHolder.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));
        webHolder.addView(sv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        int one = Math.max(1, (int) density);
        webHolder.addView(input, new FrameLayout.LayoutParams(one, one, Gravity.TOP | Gravity.START));

        hStart = new HandleView(this, true);
        hEnd = new HandleView(this, false);
        hStart.setVisibility(View.GONE);
        hEnd.setVisibility(View.GONE);
        setupHandle(hStart, 0);
        setupHandle(hEnd, 1);
        webHolder.addView(hStart, new FrameLayout.LayoutParams(handleSize, handleSize, Gravity.TOP | Gravity.START));
        webHolder.addView(hEnd, new FrameLayout.LayoutParams(handleSize, handleSize, Gravity.TOP | Gravity.START));

        mainLayout.addView(topPanel);
        mainLayout.addView(webHolder);
        setContentView(mainLayout);
    }

    @Override
    protected void onCreate(Bundle b) {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onCreate(b);
        buildUi();
    }

    @Override
    protected void onStart() {
        super.onStart();
        attached = false;
        bindToService();
    }

    @Override
    protected void onResume() {
        super.onResume();
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );
    
        if (!isBatteryOptimizationsIgnored()) {
            Intent batteryIntent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            batteryIntent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(batteryIntent);
        }
    }

    private boolean isBatteryOptimizationsIgnored() {
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    @Override
    protected void onStop() {
        hideInput();
        send(YTService.MSG_DETACH, 0, 0, false);
        attached = false;
        if (bound) {
            try { unbindService(conn); } catch (Exception ignored) {}
            bound = false;
        }
        service = null;
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
    }

    @Override
    protected void onUserLeaveHint() {
    }
}
