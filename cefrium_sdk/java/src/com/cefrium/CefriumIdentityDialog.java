// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium;

import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.icu.text.ListFormatter;
import android.net.Uri;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.text.style.ClickableSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;

import org.chromium.ui.text.SpanApplier;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The default FedCM account chooser, laid out as Chrome's: a bottom sheet with
 * the identity provider, the accounts, "Continue as" and, for an account new
 * to the site, what will be shared and the site's policies. Chromium's own
 * translated strings; the app theme's colors.
 */
final class CefriumIdentityDialog {
    private final Activity mActivity;
    private final String mRelyingParty;
    private final List<CefriumIdentityHandler.Account> mAccounts;
    private final CefriumIdentityHandler.Request mRequest;
    private final Dialog mDialog;
    private final LinearLayout mBody;
    private final int mText;
    private final int mTextSecondary;
    private final int mAccent;

    private CefriumIdentityDialog(Activity activity, String relyingParty,
            List<CefriumIdentityHandler.Account> accounts,
            CefriumIdentityHandler.Request request) {
        mActivity = activity;
        mRelyingParty = relyingParty;
        mAccounts = accounts;
        mRequest = request;
        TypedArray a = activity.obtainStyledAttributes(new int[] {
                android.R.attr.colorBackground, android.R.attr.textColorPrimary,
                android.R.attr.textColorSecondary, android.R.attr.colorAccent});
        int background = a.getColor(0, Color.WHITE);
        mText = a.getColor(1, Color.BLACK);
        mTextSecondary = a.getColor(2, Color.DKGRAY);
        mAccent = a.getColor(3, 0xFF1A73E8);
        a.recycle();

        mDialog = new Dialog(activity);
        mDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout sheet = new LinearLayout(activity);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(24), dp(8), dp(24), dp(24));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(background);
        float r = dp(28);
        bg.setCornerRadii(new float[] {r, r, r, r, 0, 0, 0, 0});
        sheet.setBackground(bg);
        sheet.setContentDescription(string("account_selection_content_description", null));

        // Drag handle, as on every bottom sheet.
        View handle = new View(activity);
        GradientDrawable h = new GradientDrawable();
        h.setColor(mTextSecondary);
        h.setAlpha(0x66);
        h.setCornerRadius(dp(2));
        handle.setBackground(h);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dp(32), dp(4));
        hp.gravity = Gravity.CENTER_HORIZONTAL;
        hp.bottomMargin = dp(12);
        sheet.addView(handle, hp);
        sheet.addView(header());
        mBody = new LinearLayout(activity);
        mBody.setOrientation(LinearLayout.VERTICAL);
        sheet.addView(mBody, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mDialog.setContentView(sheet);
        mDialog.setOnCancelListener(d -> request.cancel());
        Window w = mDialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
            w.setWindowAnimations(android.R.style.Animation_InputMethod);
        }
        if (accounts.size() == 1) {
            showConfirm(accounts.get(0));
        } else {
            showList();
        }
    }

    static Dialog show(Activity activity, String relyingParty,
            List<CefriumIdentityHandler.Account> accounts,
            CefriumIdentityHandler.Request request) {
        CefriumIdentityDialog d = new CefriumIdentityDialog(activity, relyingParty, accounts,
                request);
        d.mDialog.show();
        return d.mDialog;
    }

    // ---- views ----

    private View header() {
        CefriumIdentityHandler.Account first = mAccounts.isEmpty() ? null : mAccounts.get(0);
        LinearLayout row = new LinearLayout(mActivity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        if (first != null && first.identityProviderIcon != null) {
            ImageView icon = new ImageView(mActivity);
            icon.setImageBitmap(first.identityProviderIcon);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(24), dp(24));
            ip.setMarginEnd(dp(12));
            row.addView(icon, ip);
        }
        TextView title = text(title(first), 16, mText);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        row.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ImageButton close = new ImageButton(mActivity);
        close.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
        close.setBackground(selectable());
        close.setColorFilter(mTextSecondary);
        close.setContentDescription(mActivity.getString(android.R.string.cancel));
        close.setOnClickListener(v -> mDialog.cancel());
        row.addView(close, new LinearLayout.LayoutParams(dp(40), dp(40)));
        return row;
    }

    private void showList() {
        mBody.removeAllViews();
        for (CefriumIdentityHandler.Account account : mAccounts) {
            View row = accountRow(account);
            row.setBackground(selectable());
            row.setOnClickListener(v -> {
                if (needsDisclosure(account)) {
                    showConfirm(account);
                } else {
                    select(account);
                }
            });
            mBody.addView(row);
        }
    }

    private void showConfirm(CefriumIdentityHandler.Account account) {
        mBody.removeAllViews();
        mBody.addView(accountRow(account));

        Button go = new Button(mActivity);
        go.setAllCaps(false);
        go.setText(string("account_selection_continue", "Continue as %1$s",
                TextUtils.isEmpty(account.givenName) ? account.name : account.givenName));
        go.setTextColor(Color.WHITE);
        go.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(mAccent);
        pill.setCornerRadius(dp(24));
        go.setBackground(pill);
        go.setOnClickListener(v -> select(account));
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        gp.topMargin = dp(16);
        mBody.addView(go, gp);

        if (needsDisclosure(account)) {
            TextView consent = text("", 12, mTextSecondary);
            consent.setText(disclosure(account));
            consent.setMovementMethod(LinkMovementMethod.getInstance());
            consent.setLinkTextColor(mAccent);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cp.topMargin = dp(16);
            mBody.addView(consent, cp);
        }
    }

    private View accountRow(CefriumIdentityHandler.Account account) {
        LinearLayout row = new LinearLayout(mActivity);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, dp(12));
        row.addView(avatar(account), new LinearLayout.LayoutParams(dp(40), dp(40)));
        LinearLayout names = new LinearLayout(mActivity);
        names.setOrientation(LinearLayout.VERTICAL);
        names.setPadding(dp(16), 0, 0, 0);
        String name = TextUtils.isEmpty(account.name) ? account.email : account.name;
        names.addView(text(name, 16, mText));
        if (!TextUtils.isEmpty(account.email) && !account.email.equals(name)) {
            names.addView(text(account.email, 14, mTextSecondary));
        }
        row.addView(names, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return row;
    }

    // The picture, round; without one, the first letter on a disc, as Chrome.
    private View avatar(CefriumIdentityHandler.Account account) {
        if (account.picture != null) {
            ImageView image = new ImageView(mActivity);
            RoundedBitmapDrawable round = RoundedBitmapDrawableFactory.create(
                    mActivity.getResources(), account.picture);
            round.setCircular(true);
            image.setImageDrawable(round);
            return image;
        }
        String name = TextUtils.isEmpty(account.name) ? account.email : account.name;
        TextView letter = text(name.isEmpty() ? "" : name.substring(0, 1).toUpperCase(Locale.getDefault()),
                18, Color.WHITE);
        letter.setGravity(Gravity.CENTER);
        GradientDrawable disc = new GradientDrawable();
        disc.setShape(GradientDrawable.OVAL);
        disc.setColor(mAccent);
        letter.setBackground(disc);
        return letter;
    }

    // ---- text ----

    private String title(CefriumIdentityHandler.Account first) {
        String idp = first == null ? "" : first.identityProvider;
        String context = first == null ? "signin" : first.context;
        String key;
        String fallback;
        switch (context) {
            case "signup":
                key = "account_selection_sheet_title_explicit_signup";
                fallback = "Sign up to %1$s with %2$s";
                break;
            case "use":
                key = "account_selection_sheet_title_explicit_use";
                fallback = "Use %1$s with %2$s";
                break;
            case "continue":
                key = "account_selection_sheet_title_explicit_continue";
                fallback = "Continue to %1$s with %2$s";
                break;
            default:
                key = "account_selection_sheet_title_explicit_signin";
                fallback = "Sign in to %1$s with %2$s";
        }
        return string(key, fallback, mRelyingParty, idp);
    }

    private static boolean needsDisclosure(CefriumIdentityHandler.Account account) {
        return !account.returningUser && !account.disclosureFields.isEmpty();
    }

    // "To continue, idp will share your name, email address and profile picture
    // with this site. See this site's privacy policy and terms of service."
    private SpannableString disclosure(CefriumIdentityHandler.Account account) {
        List<String> fields = new ArrayList<>();
        for (String f : account.disclosureFields) {
            String s = string("account_selection_data_sharing_" + f, f);
            if (!s.isEmpty()) fields.add(s);
        }
        String all = ListFormatter.getInstance(Locale.getDefault()).format(fields);
        boolean pp = !TextUtils.isEmpty(account.privacyPolicyUrl);
        boolean tos = !TextUtils.isEmpty(account.termsOfServiceUrl);
        String key = pp && tos ? "account_selection_data_sharing_consent"
                : pp ? "account_selection_data_sharing_consent_no_tos"
                : tos ? "account_selection_data_sharing_consent_no_pp"
                : "account_selection_data_sharing_consent_no_pp_or_tos";
        String consent = string(key, "To continue, %1$s will share your %2$s with this site.",
                account.identityProvider, all);
        List<SpanApplier.SpanInfo> spans = new ArrayList<>();
        if (pp && consent.contains("<link_privacy_policy>")) {
            spans.add(new SpanApplier.SpanInfo("<link_privacy_policy>",
                    "</link_privacy_policy>", link(account.privacyPolicyUrl)));
        }
        if (tos && consent.contains("<link_terms_of_service>")) {
            spans.add(new SpanApplier.SpanInfo("<link_terms_of_service>",
                    "</link_terms_of_service>", link(account.termsOfServiceUrl)));
        }
        return SpanApplier.applySpans(consent, spans.toArray(new SpanApplier.SpanInfo[0]));
    }

    private ClickableSpan link(String url) {
        return new ClickableSpan() {
            @Override
            public void onClick(View v) {
                try {
                    mActivity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (ActivityNotFoundException e) {
                    // Nothing can show it; the sheet stays.
                }
            }
        };
    }

    // Chromium's translated string: the AAR carries Chromium's resources but
    // the SDK is compiled without their R classes, so look it up by name.
    private String string(String name, String fallback, Object... args) {
        int id = mActivity.getResources().getIdentifier(name, "string",
                mActivity.getPackageName());
        if (id != 0) return mActivity.getString(id, args);
        if (fallback == null) return "";
        return args.length == 0 ? fallback : String.format(fallback, args);
    }

    // ---- helpers ----

    private void select(CefriumIdentityHandler.Account account) {
        mDialog.setOnCancelListener(null);
        mDialog.dismiss();
        mRequest.select(account);
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(mActivity);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    private android.graphics.drawable.Drawable selectable() {
        TypedValue v = new TypedValue();
        mActivity.getTheme().resolveAttribute(
                android.R.attr.selectableItemBackground, v, true);
        return mActivity.getDrawable(v.resourceId);
    }

    private int dp(int v) {
        return Math.round(v * mActivity.getResources().getDisplayMetrics().density);
    }
}
