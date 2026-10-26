// Copyright 2026 The Chromium Embedded Framework Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package com.cefrium;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.SpannableString;
import android.text.style.ImageSpan;

import org.chromium.content.browser.picker.InputDialogContainer;

import java.util.ArrayList;
import java.util.List;

/**
 * The default pickers of an off-screen browser, shown on the activity given to
 * {@link CefriumBrowser#setDialogActivity}. Date and time use Chromium's own
 * dialogs, as a surface browser does.
 */
final class CefriumPickerDialogs {
    // Offered when the input has no <datalist>.
    private static final int[] DEFAULT_COLORS = {
        Color.RED, Color.CYAN, Color.BLUE, Color.GREEN, Color.MAGENTA, Color.YELLOW,
        Color.BLACK, Color.WHITE,
    };

    private CefriumPickerDialogs() {}

    static void showSelect(Activity activity, CefriumPickerHandler.SelectItem[] items,
            int selected, boolean multiple, CefriumPickerHandler.SelectCallback callback) {
        // Group labels and separators are shown by the page's markup, not
        // chosen: list only the options and map back to the page's indices.
        List<Integer> map = new ArrayList<>();
        List<CharSequence> labels = new ArrayList<>();
        for (int i = 0; i < items.length; i++) {
            int type = items[i].type;
            if (type == CefriumPickerHandler.SelectItem.GROUP
                    || type == CefriumPickerHandler.SelectItem.SEPARATOR
                    || !items[i].enabled) {
                continue;
            }
            map.add(i);
            labels.add(items[i].label);
        }
        CharSequence[] names = labels.toArray(new CharSequence[0]);
        AlertDialog.Builder b = new AlertDialog.Builder(activity);
        b.setOnCancelListener(d -> callback.cancel());
        if (multiple) {
            boolean[] checked = new boolean[names.length];
            for (int i = 0; i < checked.length; i++) checked[i] = items[map.get(i)].checked;
            b.setMultiChoiceItems(names, checked, (d, which, isChecked) -> checked[which] = isChecked);
            b.setPositiveButton(android.R.string.ok, (d, w) -> {
                List<Integer> chosen = new ArrayList<>();
                for (int i = 0; i < checked.length; i++) {
                    if (checked[i]) chosen.add(map.get(i));
                }
                int[] out = new int[chosen.size()];
                for (int i = 0; i < out.length; i++) out[i] = chosen.get(i);
                callback.select(out);
            });
            b.setNegativeButton(android.R.string.cancel, (d, w) -> callback.cancel());
        } else {
            b.setSingleChoiceItems(names, map.indexOf(selected), (d, which) -> {
                callback.select(new int[] {map.get(which)});
                d.dismiss();
            });
        }
        b.show();
    }

    static void showDateTime(Activity activity, int type, double value, double minimum,
            double maximum, double step, CefriumPickerHandler.DateTimeCallback callback) {
        new InputDialogContainer(activity, new InputDialogContainer.InputActionDelegate() {
            @Override
            public void cancelDateTimeDialog() {
                callback.cancel();
            }

            @Override
            public void replaceDateTime(double v) {
                callback.set(v);
            }
        }).showDialog(type, value, minimum, maximum, step, null);
    }

    static void showColor(Activity activity, int color, int[] suggestions,
            CefriumPickerHandler.ColorCallback callback) {
        int[] colors = suggestions != null && suggestions.length > 0 ? suggestions : DEFAULT_COLORS;
        CharSequence[] names = new CharSequence[colors.length];
        int size = Math.round(24 * activity.getResources().getDisplayMetrics().density);
        int current = -1;
        for (int i = 0; i < colors.length; i++) {
            ColorDrawable swatch = new ColorDrawable(colors[i] | 0xFF000000);
            swatch.setBounds(0, 0, size, size);
            SpannableString name = new SpannableString(
                    "  " + String.format("#%06X", colors[i] & 0xFFFFFF));
            name.setSpan(new ImageSpan(swatch), 0, 1, 0);
            names[i] = name;
            if ((colors[i] & 0xFFFFFF) == (color & 0xFFFFFF)) current = i;
        }
        new AlertDialog.Builder(activity)
                .setOnCancelListener(d -> callback.cancel())
                .setSingleChoiceItems(names, current, (d, which) -> {
                    callback.set(colors[which] | 0xFF000000);
                    d.dismiss();
                })
                .show();
    }
}
