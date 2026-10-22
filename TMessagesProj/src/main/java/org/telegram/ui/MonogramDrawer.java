// SPDX-License-Identifier: GPL-3.0-or-later
// Drawer geometry and gesture behavior adapted from exteraGram's DrawerContainer/Header/AccountPicker.
package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.PorterDuff;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Comparator;
import org.telegram.messenger.*;
import org.telegram.messenger.xmpp.MonogramController;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.*;
import org.telegram.ui.Components.*;

/** Activity-root drawer: receives edge drags before the home tab pager can claim them. */
public final class MonogramDrawer extends FrameLayout {
    private final View dim;
    private final ScrollView panel;
    private final LinearLayout contents;
    private final DrawerLayoutContainer root;
    private DialogsActivity host;
    private ValueAnimator animator, accountsAnimator;
    private VelocityTracker velocity;
    private float progress, downX, downY, startProgress;
    private int width;
    private boolean candidate, tracking, wasOpen;

    public MonogramDrawer(DrawerLayoutContainer root) {
        super(root.getContext());
        this.root = root;
        setVisibility(GONE);
        setTag("monogramDrawer"); // Insets are owned by the drawer, not dispatched into its children.
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        dim = new View(getContext());
        dim.setBackgroundColor(0x66000000);
        dim.setOnClickListener(v -> close(true));
        addView(dim, LayoutHelper.createFrame(-1, -1));
        panel = new ScrollView(getContext()) {
            private final Path clip = new Path();
            private final RectF bounds = new RectF();
            @Override public void draw(Canvas canvas) {
                float radius = dp(24);
                boolean rtl = LocaleController.isRTL;
                bounds.set(0, 0, getWidth(), getHeight());
                clip.reset();
                clip.addRoundRect(bounds, rtl ? new float[]{radius,radius,0,0,0,0,radius,radius}
                        : new float[]{0,0,radius,radius,radius,radius,0,0}, Path.Direction.CW);
                int save = canvas.save();
                canvas.clipPath(clip);
                super.draw(canvas);
                canvas.restoreToCount(save);
            }
        };
        panel.setFillViewport(true);
        panel.setVerticalScrollBarEnabled(false);
        panel.setOverScrollMode(OVER_SCROLL_NEVER);
        contents = new LinearLayout(getContext()) {
            private SnowflakesEffect snow;
            @Override protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                if (org.telegram.utils.settings.MonogramAppearance.snow.get()) {
                    if (snow == null) { snow = new SnowflakesEffect(1); snow.setForcedColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText)); }
                    snow.onDraw(this, canvas);
                }
            }
        };
        contents.setWillNotDraw(false);
        contents.setOrientation(LinearLayout.VERTICAL);
        panel.addView(contents, new ScrollView.LayoutParams(-1, -2));
        addView(panel, LayoutHelper.createFrame(300, -1, LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT));
    }

    public boolean belongsTo(BaseFragment fragment) { return host == fragment && getVisibility() == VISIBLE; }
    public boolean observingEdge() { return candidate || tracking; }

    public void open(DialogsActivity host) {
        if (!host.canOpenNavigationDrawer()) return;
        if (this.host != host || getVisibility() != VISIBLE) refresh(host);
        settle(1);
    }

    public void close(boolean animated) {
        candidate = tracking = false;
        recycleVelocity();
        if (animated) settle(0);
        else { cancelAnimation(); setProgress(0); setVisibility(GONE); }
    }

    private void refresh(DialogsActivity host) {
        this.host = host;
        if (accountsAnimator != null) accountsAnimator.cancel();
        width = Math.min(dp(300), Math.max(dp(200), root.getWidth() - dp(56)));
        panel.getLayoutParams().width = width;
        panel.requestLayout();
        panel.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        contents.removeAllViews();
        contents.setPadding(0, AndroidUtilities.statusBarHeight, 0, AndroidUtilities.navigationBarHeight + dp(16));
        int account = host.getCurrentAccount();
        TLRPC.User user = UserConfig.getInstance(account).getCurrentUser();
        FrameLayout header = new FrameLayout(getContext());
        BackupImageView avatar = new BackupImageView(getContext());
        AvatarDrawable placeholder = new AvatarDrawable();
        placeholder.setInfo(account, user);
        avatar.setRoundRadius(dp(36));
        avatar.getImageReceiver().setCurrentAccount(account);
        avatar.setForUserOrChat(user, placeholder);
        header.addView(avatar, LayoutHelper.createFrame(72, 72, Gravity.TOP | Gravity.LEFT, 16, 16, 0, 0));
        avatar.setContentDescription("My profile");
        avatar.setOnClickListener(v -> activate(() -> MonogramNavigation.open(host, "profile")));
        ImageView sun = new ImageView(getContext());
        RLottieDrawable sunDrawable = new RLottieDrawable(R.raw.sun, dp(24), dp(24));
        sunDrawable.setCurrentFrame(Theme.isCurrentThemeDark() ? 0 : sunDrawable.getFramesCount() - 1);
        sun.setImageDrawable(sunDrawable);
        sun.setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), PorterDuff.Mode.SRC_IN);
        sun.setScaleType(ImageView.ScaleType.CENTER);
        sun.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 1));
        sun.setContentDescription("Day / night mode");
        header.addView(sun, LayoutHelper.createFrame(44, 44, Gravity.TOP | Gravity.RIGHT, 0, 12, 12, 0));
        sun.setOnClickListener(v -> activate(host::monogramToggleNight));
        sun.setOnLongClickListener(v -> { activate(() -> host.presentFragment(new ThemeActivity(ThemeActivity.THEME_TYPE_BASIC))); return true; });

        FrameLayout identity = new FrameLayout(getContext());
        TextView name = text(user == null ? "Monogram" : ContactsController.formatName(user.first_name, user.last_name), 15, false);
        identity.addView(name, LayoutHelper.createFrame(-1, 24, Gravity.TOP | Gravity.LEFT, 16, 0, 64, 0));
        TextView jid = text(MonogramController.get(account).accountJid(), 12, true);
        identity.addView(jid, LayoutHelper.createFrame(-1, 22, Gravity.TOP | Gravity.LEFT, 16, 26, 64, 0));
        ImageView expand = new ImageView(getContext());
        expand.setImageResource(R.drawable.arrow_more);
        expand.setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2), PorterDuff.Mode.SRC_IN);
        expand.setScaleType(ImageView.ScaleType.CENTER);
        identity.addView(expand, LayoutHelper.createFrame(44, 44, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 12, 0));
        header.addView(identity, LayoutHelper.createFrame(-1, 50, Gravity.TOP | Gravity.LEFT, 0, 100, 0, 0));
        contents.addView(header, LayoutHelper.createLinear(-1, 160));

        LinearLayout accounts = new LinearLayout(getContext());
        accounts.setOrientation(LinearLayout.VERTICAL);
        accounts.setPadding(dp(4), dp(4), dp(4), dp(4));
        accounts.setBackground(Theme.createRoundRectDrawable(dp(16), Theme.getColor(Theme.key_windowBackgroundGray)));
        ArrayList<Integer> accountNumbers = new ArrayList<>();
        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) if (UserConfig.getInstance(i).isClientActivated()) accountNumbers.add(i);
        accountNumbers.sort(Comparator.comparingLong(i -> UserConfig.getInstance(i).loginTime));
        for (int i : accountNumbers) {
            FrameLayout row = new FrameLayout(getContext());
            row.setBackground(i == account ? Theme.createRoundRectDrawable(dp(12), Theme.getColor(Theme.key_windowBackgroundWhite)) : Theme.getSelectorDrawable(false));
            TLRPC.User u = UserConfig.getInstance(i).getCurrentUser();
            BackupImageView picture = new BackupImageView(getContext());
            picture.setRoundRadius(dp(16));
            AvatarDrawable fallback = new AvatarDrawable(); fallback.setInfo(i, u);
            picture.getImageReceiver().setCurrentAccount(i); picture.setForUserOrChat(u, fallback);
            row.addView(picture, LayoutHelper.createFrame(32, 32, Gravity.LEFT | Gravity.CENTER_VERTICAL, 10, 0, 0, 0));
            TextView title = text(u == null ? MonogramController.get(i).accountJid() : ContactsController.formatName(u.first_name, u.last_name), 15, false);
            row.addView(title, LayoutHelper.createFrame(-1, -1, Gravity.LEFT, 56, 0, 12, 0));
            row.setSelected(i == account);
            row.setContentDescription(title.getText() + (i == account ? ", current account" : ""));
            row.setOnClickListener(v -> activate(() -> { if (i != account && LaunchActivity.instance != null) LaunchActivity.instance.switchToAccount(i, true); }));
            accounts.addView(row, LayoutHelper.createLinear(-1, 48));
        }
        if (UserConfig.getFreeAccount() >= 0) addRow(accounts, "Add Account", R.drawable.msg_add, () -> {
            int free = UserConfig.getFreeAccount();
            if (free >= 0) activate(() -> host.presentFragment(new MonogramLoginActivity(free)));
        });
        FrameLayout accountsClip = new FrameLayout(getContext());
        accountsClip.setClipChildren(true);
        accountsClip.addView(accounts, LayoutHelper.createFrame(-1, -2, Gravity.TOP, 12, 0, 12, 8));
        boolean shown = MessagesController.getGlobalMainSettings().getBoolean("accountsShown", true);
        int accountsHeight = dp(8 + 8 + 48 * accounts.getChildCount());
        contents.addView(accountsClip, LayoutHelper.createLinear(-1, shown ? accountsHeight / AndroidUtilities.density : 0));
        expand.setRotation(shown ? 180 : 0);
        identity.setContentDescription("Accounts");
        identity.setOnClickListener(v -> {
            boolean show = !MessagesController.getGlobalMainSettings().getBoolean("accountsShown", true);
            MessagesController.getGlobalMainSettings().edit().putBoolean("accountsShown", show).apply();
            if (accountsAnimator != null) accountsAnimator.cancel();
            accountsAnimator = ValueAnimator.ofInt(accountsClip.getHeight(), show ? accountsHeight : 0);
            accountsAnimator.setDuration(220); accountsAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
            accountsAnimator.addUpdateListener(a -> { accountsClip.getLayoutParams().height = (int) a.getAnimatedValue(); accountsClip.requestLayout(); });
            accountsAnimator.start();
            expand.animate().rotation(show ? 180 : 0).setDuration(220).start();
        });
        for (String key : MonogramNavigation.order()) if (!MonogramNavigation.hidden().contains(key)) {
            if (key.startsWith("divider")) {
                View divider = new View(getContext()); divider.setBackgroundColor(Theme.getColor(Theme.key_divider));
                contents.addView(divider, LayoutHelper.createLinear(-1, 1, 16, 8, 16, 8));
            } else if (!key.equals("theme")) {
                addRow(contents, MonogramNavigation.label(key), MonogramNavigation.icon(key), () -> activate(() -> MonogramNavigation.open(host, key)));
            }
        }
        panel.scrollTo(0, 0);
        setVisibility(VISIBLE);
        bringToFront();
        setProgress(progress);
    }

    private TextView text(String value, int size, boolean secondary) {
        TextView text = new TextView(getContext());
        text.setText(value); text.setTextSize(size); text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END); text.setGravity(Gravity.CENTER_VERTICAL);
        text.setTextColor(Theme.getColor(secondary ? Theme.key_windowBackgroundWhiteGrayText2 : Theme.key_windowBackgroundWhiteBlackText));
        return text;
    }

    private void addRow(LinearLayout parent, String title, int icon, Runnable action) {
        FrameLayout row = new FrameLayout(getContext());
        ImageView image = new ImageView(getContext()); image.setImageResource(icon);
        image.setColorFilter(Theme.getColor(Theme.key_chats_menuItemIcon), PorterDuff.Mode.SRC_IN);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        row.addView(image, LayoutHelper.createFrame(24, 24, Gravity.LEFT | Gravity.CENTER_VERTICAL, 20, 0, 0, 0));
        row.addView(text(title, 15, false), LayoutHelper.createFrame(-1, -1, Gravity.LEFT, 68, 0, 16, 0));
        row.setBackground(Theme.getSelectorDrawable(false)); row.setOnClickListener(v -> action.run());
        parent.addView(row, LayoutHelper.createLinear(-1, 48));
    }

    private void activate(Runnable action) { close(true); AndroidUtilities.runOnUIThread(action, 220); }
    private float direction() { return LocaleController.isRTL ? -1 : 1; }

    /** Called by the activity root, including when a nested pager requests exclusive touch. */
    public boolean intercept(MotionEvent event, DialogsActivity availableHost) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            recycleVelocity();
            downX = event.getX(); downY = event.getY(); startProgress = progress;
            wasOpen = progress > .5f; tracking = false;
            boolean visible = getVisibility() == VISIBLE;
            candidate = visible || event.getY() >= AndroidUtilities.statusBarHeight
                    && event.getY() < root.getHeight() - AndroidUtilities.navigationBarHeight
                    && availableHost != null && availableHost.canOpenNavigationDrawerBySwipe(event);
            if (candidate) { velocity = VelocityTracker.obtain(); velocity.addMovement(event); }
            return false;
        }
        if (!candidate) return false;
        if (velocity != null) velocity.addMovement(event);
        if (action == MotionEvent.ACTION_MOVE) {
            float dx = (event.getX() - downX) * direction(), dy = Math.abs(event.getY() - downY);
            if (dy > dp(8) && dy > Math.abs(dx)) { candidate = false; recycleVelocity(); return false; }
            if (Math.abs(dx) > dp(12) && Math.abs(dx) > dy * 3 && (progress > 0 || dx > 0)) {
                if (availableHost == null && getVisibility() != VISIBLE) { candidate = false; recycleVelocity(); return false; }
                cancelAnimation();
                if (getVisibility() != VISIBLE) refresh(availableHost);
                tracking = true;
                return true;
            }
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) { candidate = false; recycleVelocity(); }
        return false;
    }

    public boolean touch(MotionEvent event) {
        if (!tracking) return false;
        if (velocity != null) velocity.addMovement(event);
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_MOVE) setProgress(startProgress + (event.getX() - downX) * direction() / Math.max(1, width));
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            float target;
            if (action == MotionEvent.ACTION_CANCEL) target = wasOpen ? 1 : 0;
            else {
                velocity.computeCurrentVelocity(1000);
                float vx = velocity.getXVelocity() * direction(), vy = Math.abs(velocity.getYVelocity());
                target = Math.abs(vx) > dp(500) && Math.abs(vx) > vy ? (vx > 0 ? 1 : 0) : (progress >= (wasOpen ? .8f : .2f) ? 1 : 0);
            }
            tracking = candidate = false; recycleVelocity(); settle(target);
        }
        return true;
    }

    private void setProgress(float value) {
        progress = Math.max(0, Math.min(1, value));
        panel.setTranslationX(-direction() * width * (1 - progress));
        dim.setAlpha(progress);
        root.setNavigationDrawerOffset(direction() * width * progress
                * (org.telegram.utils.settings.MonogramAppearance.immersiveDrawer.get() ? 1f : .3f));
    }
    private void cancelAnimation() { if (animator != null) { ValueAnimator previous = animator; animator = null; previous.cancel(); } }
    private void settle(float target) {
        cancelAnimation();
        ValueAnimator next = ValueAnimator.ofFloat(progress, target);
        animator = next;
        next.setDuration(Math.max(100, (long) (250 * Math.abs(target - progress))));
        next.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        next.addUpdateListener(a -> setProgress((float) a.getAnimatedValue()));
        next.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) {
                if (animator != next) return;
                animator = null;
                if (target == 0) setVisibility(GONE);
            }
        });
        next.start();
    }
    private void recycleVelocity() { if (velocity != null) { velocity.recycle(); velocity = null; } }
    @Override protected void onDetachedFromWindow() { root.setNavigationDrawerOffset(0); cancelAnimation(); if (accountsAnimator != null) accountsAnimator.cancel(); recycleVelocity(); super.onDetachedFromWindow(); }
}
