// Copyright 2026 The Chromium Embedded Framework Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package com.cefrium;

/**
 * Shows the pickers of {@code <select>}, the date and time inputs and
 * {@code <input type=color>} for an off-screen browser. A browser on a surface
 * shows Chromium's own dialogs; an off-screen one has no window for them, so
 * it uses this handler, or the default dialogs on the activity given to
 * {@link CefriumBrowser#setDialogActivity}. With neither, the picker is
 * cancelled and the page keeps its value.
 *
 * <p>Called on the main thread. Each method returns true if it will answer
 * through the callback, exactly once; false falls back to the default.</p>
 */
public interface CefriumPickerHandler {
    /** {@code <input type=date>}. */
    int DATE = 8;
    /** {@code <input type=datetime-local>}. */
    int DATE_TIME_LOCAL = 10;
    /** {@code <input type=month>}. */
    int MONTH = 11;
    /** {@code <input type=time>}. */
    int TIME = 12;
    /** {@code <input type=week>}. */
    int WEEK = 13;

    /** One entry of a {@code <select>}. */
    final class SelectItem {
        /** An {@code <option>}. */
        public static final int OPTION = 0;
        /** An {@code <option>} of a multiple select. */
        public static final int CHECKABLE_OPTION = 1;
        /** An {@code <optgroup>} label: not selectable. */
        public static final int GROUP = 2;
        /** A separator ({@code <hr>}): not selectable. */
        public static final int SEPARATOR = 3;

        public final String label;
        public final int type;
        public final boolean enabled;
        public final boolean checked;

        public SelectItem(String label, int type, boolean enabled, boolean checked) {
            this.label = label;
            this.type = type;
            this.enabled = enabled;
            this.checked = checked;
        }
    }

    /** Answers a {@link #onShowSelect} call. */
    interface SelectCallback {
        /** The chosen items, as indices into the items given. */
        void select(int[] indices);

        void cancel();
    }

    /** Answers a {@link #onShowDateTime} call. */
    interface DateTimeCallback {
        /** The chosen value, in the units of {@link #onShowDateTime}. */
        void set(double value);

        void cancel();
    }

    /** Answers a {@link #onShowColor} call. */
    interface ColorCallback {
        /** The chosen colour, ARGB. */
        void set(int color);

        void cancel();
    }

    /**
     * @param items the entries, including group labels and separators.
     * @param selected the index of the selected item, or -1.
     * @param multiple true for {@code <select multiple>}: answer with every
     *        checked item.
     */
    default boolean onShowSelect(SelectItem[] items, int selected, boolean multiple,
            SelectCallback callback) {
        return false;
    }

    /**
     * @param type {@link #DATE}, {@link #TIME}, {@link #DATE_TIME_LOCAL},
     *        {@link #MONTH} or {@link #WEEK}.
     * @param value as Chromium represents it: for {@link #TIME} milliseconds
     *        since midnight, for {@link #MONTH} months since January 1970,
     *        otherwise milliseconds since 1970 UTC. NaN when the input is
     *        empty. {@code minimum}, {@code maximum} and {@code step} use the
     *        same units.
     */
    default boolean onShowDateTime(int type, double value, double minimum, double maximum,
            double step, DateTimeCallback callback) {
        return false;
    }

    /**
     * @param color the current colour, ARGB.
     * @param suggestions the colours of the input's {@code <datalist>}.
     */
    default boolean onShowColor(int color, int[] suggestions, ColorCallback callback) {
        return false;
    }
}
