/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.settings;

import android.app.Activity;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.Fragment;
import android.app.FragmentManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.window.BackEvent;
import android.window.OnBackAnimationCallback;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import app.hushgram.extension.instagram.direct.MessagesLock;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Logger;

/**
 * The HushGram screen: a full screen, black dialog with a title bar and the preference list. It is
 * a framework dialog fragment, so it needs no activity of its own and Back closes it.
 */
@SuppressWarnings("deprecation") // Framework fragments are what the shared preference code builds on.
public final class SettingsDialog extends DialogFragment {
    /**
     * The preference list's container. Fixed, because the child manager saves the page with this
     * id and puts it back into a view with the same id after rotation or process recreation.
     * Outside both the generated-id range and aapt's 0x7f ids, as is the restart bar's below.
     */
    static final int CONTAINER_ID = 0x48474301;
    /** The bar under the page that restarts Instagram while a change waits on it. */
    static final int RESTART_ID = 0x48474302;
    private static final String PAGE_FAILED = "hushgram.page_failed";
    /** The argument naming the inline section to expand after Restart now. */
    static final String START_PAGE = "hushgram_start_page";
    private boolean failed;
    private boolean loading;
    /** Put back over a screen that can't draw text yet, so closing without building anything. */
    private boolean closing;
    private Button retry;
    private TextView heading;
    private TextView restart;
    ModernSettingsView modernView;
    /** The lock's checks on this dialog's window while it's started. */
    private Window lockWindow;
    private ViewTreeObserver.OnPreDrawListener lockDraw;
    private ViewTreeObserver.OnWindowFocusChangeListener lockFocus;

    /**
     * The screen, expanding the section whose row has the key [page]. Null or a key no row
     * has opens the first page, as the screen always does.
     */
    static SettingsDialog onPage(@androidx.annotation.Nullable String page) {
        SettingsDialog dialog = new SettingsDialog();
        if (page != null && !page.isEmpty()) {
            Bundle arguments = new Bundle();
            arguments.putString(START_PAGE, page);
            dialog.setArguments(arguments);
        }
        return dialog;
    }

    /** The page this screen was asked to open on, or null. Only a fresh page reads it, not one put back. */
    @androidx.annotation.Nullable
    String startPage() {
        Bundle arguments = getArguments();
        return arguments == null ? null : arguments.getString(START_PAGE);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        failed = savedInstanceState != null && savedInstanceState.getBoolean(PAGE_FAILED);
        try {
            super.onCreate(savedInstanceState);
        } catch (Exception failure) {
            // Fragment.onCreate restores child constructors before our view exists.
            // Finish the dialog's own initialization without retrying the failed saved child.
            super.onCreate(null);
            failed = true;
            Logger.printException(() -> "Could not restore the preference list", failure);
        }
        setStyle(STYLE_NO_TITLE, android.R.style.Theme_Material_NoActionBar);
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        Dialog dialog = new Dialog(getActivity(), getTheme()) {
            @Override
            @SuppressWarnings("deprecation")
            public void onBackPressed() {
                if (closeCategory()) return;
                super.onBackPressed();
            }
        };
        Window window = dialog.getWindow();
        if (window != null) {
            window.setWindowAnimations(android.R.style.Animation_Activity);
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(ScreenColors.current().background));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.setNavigationBarContrastEnforced(false);
            window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                if (closeCategory()) return true;
            }
            return false;
        });
        return dialog;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup parent, Bundle savedInstanceState) {
        // Put back over a screen that still has Instagram's launcher theme (a relaunch), the
        // first TextView would throw. Build nothing, close in onActivityCreated, and open again
        // once a screen can draw it.
        if (!SettingsEntry.drawsText.test(getContext())) {
            Logger.printInfo(() -> "Settings closed over a screen whose theme can't draw text yet");
            closing = true;
            return new FrameLayout(getContext());
        }
        ScreenColors palette = ScreenColors.DEFAULT;
        LinearLayout root = new LinearLayout(getContext()) {
            /**
             * Focus that arrives from nowhere (the first key press, a field giving its focus up) goes
             * to the page's rows before the Back arrow above them.
             */
            @Override
            protected boolean onRequestFocusInDescendants(int direction, android.graphics.Rect previous) {
                View page = findViewById(CONTAINER_ID);
                if (previous == null && page != null && page.requestFocus(direction)) return true;
                return super.onRequestFocusInDescendants(direction, previous);
            }
        };
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(palette.background);

        LinearLayout bar = new LinearLayout(getContext());
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        // Start and end, not left and right: in a right-to-left language the bar is mirrored.
        bar.setPaddingRelative(dp(4), dp(2), dp(16), dp(2));

        ImageButton back = new ImageButton(getContext());
        android.graphics.drawable.Drawable arrow = SettingsIcons.icon(getContext(), SettingsIcons.BACK, palette.title);
        arrow.setBounds(0, 0, dp(24), dp(24));
        arrow.setLayoutDirection(rightToLeft() ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        back.setImageDrawable(arrow);
        back.setPadding(dp(10), dp(10), dp(10), dp(10));
        back.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x22FFFFFF), null,
                new android.graphics.drawable.ColorDrawable(Color.WHITE)));
        back.setMinimumWidth(dp(44));
        back.setMinimumHeight(dp(44));
        back.setContentDescription(L10n.t(getContext(), "Back"));
        // A screen reader calls it a button, as it would the back arrow of any other screen.
        back.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(Button.class.getName());
            }
        });
        back.setOnClickListener(v -> {
            if (closeCategory()) return;
            SettingsEntry.onClosedByUser();
            dismissAllowingStateLoss();
        });
        bar.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));

        TextView title = new TextView(getContext());
        heading = title;
        title.setText(L10n.t(getContext(), "Instagram Plus"));
        title.setTextColor(palette.title);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setPaddingRelative(dp(8), 0, 0, 0);
        // Next to the back arrow in a right-to-left layout too, not at the bar's far end.
        title.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        title.setAccessibilityHeading(true);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));

        View divider = new View(getContext());
        divider.setBackgroundColor(0xFF1F1F1F);
        root.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        FrameLayout container = new FrameLayout(getContext());
        container.setId(CONTAINER_ID);
        root.addView(container, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(restartBar(palette), restartBarSpace());

        keepOffTheBars(root);
        // A newly opened screen must not steal focus and raise the keyboard.
        FocusRest.on(root);
        root.requestFocus();
        return root;
    }

    /**
     * Instagram targets a recent API, so its windows are edge to edge: keeps the bars off [root]'s
     * content. The keyboard too, since nothing resizes an edge-to-edge window for it: the search
     * field, the matches under it and Restart now sat behind the keys (S25, 2026-10-13).
     */
    static void keepOffTheBars(View root) {
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                // Android 9 and 10 report the bars through the calls Android 11 replaced.
                view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
    }

    /**
     * Restart now, under the page and off until a change waits on a restart. As the page's first
     * row it was two screens above a switch far down a long page, and putting it in moved every
     * row under the finger (S25, 2026-10-13). Down here it's in view on every page, and the page
     * only gets shorter from the bottom.
     */
    private TextView restartBar(ScreenColors palette) {
        TextView bar = new TextView(getContext());
        restart = bar;
        bar.setId(RESTART_ID);
        bar.setText(L10n.t(getContext(), "Some changes apply when Instagram restarts. Tap to restart it now."));
        bar.setTextColor(palette.title);
        bar.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setMinHeight(dp(56));
        bar.setPaddingRelative(dp(16), dp(12), dp(20), dp(12));
        // A restart arrow and the heading blue's edge mark it as something to tap, not a notice.
        android.graphics.drawable.Drawable arrow = SettingsIcons.icon(getContext(), SettingsIcons.TOOLS, palette.heading);
        arrow.setBounds(0, 0, dp(24), dp(24));
        bar.setCompoundDrawablesRelative(arrow, null, null, null);
        bar.setCompoundDrawablePadding(dp(14));
        android.graphics.drawable.GradientDrawable surface = new android.graphics.drawable.GradientDrawable();
        surface.setColor(palette.card);
        surface.setCornerRadius(dp(12));
        surface.setStroke(dp(1), palette.heading);
        bar.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(ScreenColors.half(palette.heading)), surface, surface));
        bar.setFocusable(true);
        bar.setClickable(true);
        bar.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(Button.class.getName());
            }
        });
        bar.setOnClickListener(v -> {
            Activity act = getActivity();
            if (act != null && !act.isFinishing()) {
                switch (RestartNow.restart(act)) {
                    case BUSY:
                        app.hushgram.extension.shared.Utils.showToastLong(
                                L10n.t(act, "Still saving or exporting. Restart once it's done."));
                        break;
                    case FAILED:
                        app.hushgram.extension.shared.Utils.showToastLong(
                                L10n.t(act, "Couldn't restart Instagram. Close it and open it again."));
                        break;
                    default:
                        break;
                }
            }
        });
        bar.setVisibility(View.GONE);
        return bar;
    }

    private LinearLayout.LayoutParams restartBarSpace() {
        LinearLayout.LayoutParams space = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        // The cards' and the search field's 18dp side inset, so the bar lines up with them.
        space.setMargins(dp(18), dp(8), dp(18), dp(8));
        return space;
    }

    /** Shows the bar that restarts Instagram while [owed], and takes it off once nothing waits. */
    void showRestart(boolean owed) {
        TextView bar = restart;
        if (bar != null) bar.setVisibility(owed ? View.VISIBLE : View.GONE);
    }

    /**
     * Shows the search title, and HushGram again for null. While [pageHeader], the page's own header
     * has HushGram's name and icon on the screen, so the bar leaves the name off rather than show it
     * twice. Once the header scrolls away the bar names the screen again. Runs on every scroll.
     */
    void showTitle(@androidx.annotation.Nullable CharSequence title, boolean pageHeader) {
        TextView bar = heading;
        if (bar == null) return;
        CharSequence text = title == null ? L10n.t(getContext(), "Instagram Plus") : title;
        // A new text asks for a layout, which a scroll in the middle of one shouldn't.
        if (!TextUtils.equals(text, bar.getText())) {
            bar.animate().cancel();
            bar.setAlpha(0.3f);
            bar.setText(text);
            bar.animate().alpha(1f).setDuration(160).start();
        }
        bar.setVisibility(View.VISIBLE);
    }

    private BackSupport backSupport;

    void setupBackSupport() {
        Dialog dialog = getDialog();
        if (dialog == null) return;
        backSupport = new BackSupport(dialog, this);
        backSupport.update();
    }

    void updateBackHandler() {
        if (backSupport != null) {
            backSupport.update();
        } else {
            setupBackSupport();
        }
    }

    static final class BackSupport {
        private final Dialog dialog;
        private final SettingsDialog settingsDialog;
        private Object registeredCallback;

        BackSupport(Dialog dialog, SettingsDialog settingsDialog) {
            this.dialog = dialog;
            this.settingsDialog = settingsDialog;
        }

        void update() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
            boolean canGoBack = settingsDialog != null && settingsDialog.canGoBack();
            if (canGoBack) {
                if (registeredCallback == null) register();
            } else {
                if (registeredCallback != null) unregister();
            }
        }

        private void register() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                registeredCallback = Api34BackHelper.register(dialog, settingsDialog, this::unregister);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registeredCallback = Api33BackHelper.register(dialog, settingsDialog, this::unregister);
            }
        }

        void unregister() {
            if (registeredCallback != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                try {
                    OnBackInvokedDispatcher dispatcher = dialog.getOnBackInvokedDispatcher();
                    if (dispatcher != null && registeredCallback instanceof OnBackInvokedCallback) {
                        dispatcher.unregisterOnBackInvokedCallback((OnBackInvokedCallback) registeredCallback);
                    }
                } catch (Throwable ignored) {
                } finally {
                    registeredCallback = null;
                }
            }
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    static final class Api34BackHelper {
        static Object register(Dialog dialog, SettingsDialog settingsDialog, Runnable onComplete) {
            try {
                OnBackInvokedDispatcher dispatcher = dialog.getOnBackInvokedDispatcher();
                if (dispatcher == null) return null;
                OnBackAnimationCallback callback = new OnBackAnimationCallback() {
                    @Override
                    public void onBackStarted(BackEvent backEvent) {
                        if (settingsDialog.modernView != null) {
                            settingsDialog.modernView.onBackStarted(backEvent);
                        }
                    }

                    @Override
                    public void onBackProgressed(BackEvent backEvent) {
                        if (settingsDialog.modernView != null) {
                            settingsDialog.modernView.onBackProgressed(backEvent);
                        }
                    }

                    @Override
                    public void onBackInvoked() {
                        if (settingsDialog.modernView != null) {
                            settingsDialog.modernView.onBackInvoked();
                        } else {
                            settingsDialog.closeCategory();
                        }
                        if (onComplete != null) onComplete.run();
                    }

                    @Override
                    public void onBackCancelled() {
                        if (settingsDialog.modernView != null) {
                            settingsDialog.modernView.onBackCancelled();
                        }
                    }
                };
                dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback);
                return callback;
            } catch (Throwable t) {
                Logger.printException(() -> "Could not register API 34 back animation callback", t);
                return null;
            }
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.TIRAMISU)
    static final class Api33BackHelper {
        static Object register(Dialog dialog, SettingsDialog settingsDialog, Runnable onComplete) {
            try {
                OnBackInvokedDispatcher dispatcher = dialog.getOnBackInvokedDispatcher();
                if (dispatcher == null) return null;
                OnBackInvokedCallback callback = () -> {
                    settingsDialog.closeCategory();
                    if (onComplete != null) onComplete.run();
                };
                dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback);
                return callback;
            } catch (Throwable t) {
                Logger.printException(() -> "Could not register API 33 back callback", t);
                return null;
            }
        }
    }

    /** Clears a search before Back closes the dialog. Expanding a section adds no Back level. */
    boolean closeCategory() {
        if (modernView != null && modernView.handleBack()) return true;
        return false;
    }

    boolean canGoBack() {
        return modernView != null && modernView.canGoBack();
    }

    @androidx.annotation.Nullable
    View getActiveBackView() {
        return modernView != null ? modernView.getActiveBackView() : null;
    }

    @Override
    public void onCancel(android.content.DialogInterface dialog) {
        // Back key or a tap outside: the person closed it.
        SettingsEntry.onClosedByUser();
        super.onCancel(dialog);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (failed && !closing) showFailure(view);
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        // Closing drops the dialog, and DialogFragment hands it the view only here, so closing
        // any sooner left it a null dialog to set up.
        if (closing) {
            dismissAllowingStateLoss();
            SettingsEntry.reopenOnceThemed(getActivity());
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        watchLock();
        ShortcutCover.offOnceDrawn(getDialog());
        setupBackSupport();
    }

    @Override
    public void onStop() {
        if (backSupport != null) backSupport.unregister();
        unwatchLock();
        super.onStop();
    }

    /**
     * Kept open across a leave, or put back after a turn or a restart, these settings can outlast
     * the unlock that let them open, and the lock's cover over Instagram is under this window. So
     * this window is checked before each frame and when it gets the focus back, and covered while
     * the lock is locked (Lock your messages and Lock all of Instagram).
     */
    private void watchLock() {
        Dialog dialog = getDialog();
        Activity activity = getActivity();
        Window window = dialog == null ? null : dialog.getWindow();
        if (window == null || activity == null || lockDraw != null) return;
        try {
            ViewTreeObserver.OnPreDrawListener draw = () -> {
                guard(activity, window);
                return true;
            };
            ViewTreeObserver.OnWindowFocusChangeListener focus = hasFocus -> {
                if (hasFocus) guard(activity, window);
            };
            ViewTreeObserver observer = window.getDecorView().getViewTreeObserver();
            observer.addOnPreDrawListener(draw);
            observer.addOnWindowFocusChangeListener(focus);
            lockDraw = draw;
            lockFocus = focus;
            lockWindow = window;
            guard(activity, window);
        } catch (Exception failure) {
            Logger.printException(() -> "Settings: could not watch the lock", failure);
        }
    }

    /**
     * The lock's check on this window. While covered, whatever the page had open over itself is
     * closed: a list or a row's choices left up across a leave would sit over the cover, and the
     * hidden chats list would still name them and still show one again. It also holds the focus, so
     * the lock's ask waits for it to go.
     */
    private void guard(Activity activity, Window window) {
        if (!MessagesLock.guardSettings(activity, window)) return;
    }

    private void unwatchLock() {
        Window window = lockWindow;
        ViewTreeObserver.OnPreDrawListener draw = lockDraw;
        ViewTreeObserver.OnWindowFocusChangeListener focus = lockFocus;
        lockWindow = null;
        lockDraw = null;
        lockFocus = null;
        if (window == null) return;
        try {
            ViewTreeObserver observer = window.getDecorView().getViewTreeObserver();
            if (!observer.isAlive()) return;
            if (draw != null) observer.removeOnPreDrawListener(draw);
            if (focus != null) observer.removeOnWindowFocusChangeListener(focus);
        } catch (Exception failure) {
            Logger.printException(() -> "Settings: could not stop watching the lock", failure);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        // At onViewCreated the child manager is only CREATED. Its view construction would
        // happen later, outside our catch. At resume commitNow also constructs the child view.
        if (failed) {
            if (retry != null) retry.setEnabled(canOpenPage(getView()));
        } else {
            openPage(getView());
        }
    }

    @Override
    public void onPause() {
        if (retry != null) retry.setEnabled(false);
        super.onPause();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        outState.putBoolean(PAGE_FAILED, failed);
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onDestroyView() {
        retry = null;
        heading = null;
        restart = null;
        modernView = null;
        super.onDestroyView();
    }

    private boolean canOpenPage(View owner) {
        return owner != null && owner == getView() && !closing && isAdded() && isResumed()
                && !isRemoving() && !isDetached() && getActivity() != null
                && !getActivity().isFinishing() && !getActivity().isDestroyed()
                && getDialog() != null && getDialog().isShowing();
    }

    private void openPage(View owner) {
        if (loading || !canOpenPage(owner)) return;
        loading = true;
        FrameLayout container = owner.findViewById(CONTAINER_ID);
        try {
            if (modernView == null) {
                container.removeAllViews();
                modernView = new ModernSettingsView(getActivity(), this);
                container.addView(modernView, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                modernView.openStartPage(startPage());
            }
            failed = false;
            retry = null;
        } catch (Exception ex) {
            failed = true;
            Logger.printException(() -> "Could not show the settings view", ex);
            container.removeAllViews();
            showFailure(owner);
        } finally {
            loading = false;
        }
    }

    private void showFailure(View owner) {
        // No page, so no header: the bar names the screen again.
        showTitle(null, false);
        ScreenColors palette = ScreenColors.DEFAULT;
        ScrollView scroll = new ScrollView(getContext());
        LinearLayout content = new LinearLayout(getContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPaddingRelative(dp(32), dp(64), dp(32), dp(24));
        android.widget.ImageView icon = new android.widget.ImageView(getContext());
        icon.setImageDrawable(SettingsIcons.icon(getContext(), SettingsIcons.PRIVACY, palette.heading));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams iconSize = new LinearLayout.LayoutParams(dp(64), dp(64));
        iconSize.bottomMargin = dp(24);
        content.addView(icon, iconSize);
        TextView title = new TextView(getContext());
        title.setText(L10n.t(getContext(), "Settings couldn't open"));
        title.setTextColor(palette.title);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setGravity(Gravity.CENTER);
        title.setAccessibilityHeading(true);
        content.addView(title);
        TextView explanation = new TextView(getContext());
        explanation.setText(L10n.t(getContext(), "Try again, or go back to Instagram."));
        explanation.setTextColor(palette.summary);
        explanation.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        explanation.setGravity(Gravity.CENTER);
        explanation.setPaddingRelative(0, dp(16), 0, dp(16));
        content.addView(explanation);
        Button button = new Button(getContext());
        button.setText(L10n.t(getContext(), "Retry"));
        ScreenColors.styleAction(button, true);
        button.setEnabled(canOpenPage(owner));
        button.setOnClickListener(v -> {
            if (retry == button && failed) openPage(owner);
        });
        retry = button;
        content.addView(button, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        Button back = new Button(getContext());
        back.setText(L10n.t(getContext(), "Back"));
        ScreenColors.styleAction(back, false);
        back.setOnClickListener(v -> {
            SettingsEntry.onClosedByUser();
            dismissAllowingStateLoss();
        });
        LinearLayout.LayoutParams backSize = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        backSize.topMargin = dp(8);
        content.addView(back, backSize);
        scroll.addView(content);
        scroll.setAccessibilityPaneTitle(title.getText());
        ((FrameLayout) owner.findViewById(CONTAINER_ID)).addView(scroll,
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
        button.requestFocus();
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }

    /** Whether the phone's language reads right to left, so the way back points right. */
    private boolean rightToLeft() {
        return getResources().getConfiguration().getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
    }
}
