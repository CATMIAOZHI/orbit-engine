// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A context menu the page asks for (a long press or a right click on a link
 * or an image), as {@link CefriumChromeClient#onContextMenu} sees it. Show
 * the items, then run the one chosen with {@link Item#execute} or close the
 * menu with {@link #cancel}; the first of those counts. Any thread.
 */
public final class CefriumContextMenu {
    /** What kind of media the menu is for. */
    public enum MediaType { NONE, IMAGE, VIDEO, AUDIO, OTHER }

    /** One entry of the menu. */
    public final class Item {
        private final int mId;
        private final String mLabel;
        private final boolean mEnabled;

        Item(int id, String label, boolean enabled) {
            mId = id;
            mLabel = label;
            mEnabled = enabled;
        }

        /** The text to show, e.g. "Copy link". */
        public String getLabel() {
            return mLabel;
        }

        public boolean isEnabled() {
            return mEnabled;
        }

        /** Runs this entry and closes the menu. */
        public void execute() {
            answer(mId);
        }
    }

    /** How the browser takes the answer: an item's id, or -1 to cancel. */
    interface Responder {
        void respond(int id);
    }

    private final List<Item> mItems;
    private final String mPageUrl;
    private final String mLinkUrl;
    private final String mSourceUrl;
    private final String mSelectionText;
    private final boolean mEditable;
    private final MediaType mMediaType;
    private final int mX;
    private final int mY;
    private final Responder mResponder;
    private final AtomicBoolean mAnswered = new AtomicBoolean();

    CefriumContextMenu(int[] ids, String[] labels, boolean[] enabled, String pageUrl,
            String linkUrl, String sourceUrl, String selectionText, boolean editable,
            MediaType mediaType, int x, int y, Responder responder) {
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < ids.length; i++) items.add(new Item(ids[i], labels[i], enabled[i]));
        mItems = Collections.unmodifiableList(items);
        mPageUrl = pageUrl;
        mLinkUrl = linkUrl;
        mSourceUrl = sourceUrl;
        mSelectionText = selectionText;
        mEditable = editable;
        mMediaType = mediaType;
        mX = x;
        mY = y;
        mResponder = responder;
    }

    /** The entries, in order. Unmodifiable. */
    public List<Item> getItems() {
        return mItems;
    }

    /** The page the menu is on. */
    public String getPageUrl() {
        return mPageUrl;
    }

    /** The link under the touch; empty when none. */
    public String getLinkUrl() {
        return mLinkUrl;
    }

    /** The image or media under the touch ({@code src}); empty when none. */
    public String getSourceUrl() {
        return mSourceUrl;
    }

    /** The selected text; empty when none. */
    public String getSelectionText() {
        return mSelectionText;
    }

    /** Whether the menu is for an editable field. */
    public boolean isEditable() {
        return mEditable;
    }

    public MediaType getMediaType() {
        return mMediaType;
    }

    /** Where the menu was asked for, in the units {@link CefriumBrowser#sendMouseClick} takes. */
    public int getX() {
        return mX;
    }

    public int getY() {
        return mY;
    }

    /** Closes the menu without running an entry. */
    public void cancel() {
        answer(-1);
    }

    private void answer(int id) {
        if (mAnswered.compareAndSet(false, true)) mResponder.respond(id);
    }
}
