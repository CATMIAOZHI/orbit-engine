// Copyright 2026 The Chromium Embedded Framework Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be found
// in the LICENSE file.

package com.cefrium;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.DialogInterface;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.ContactsContract;
import android.util.Log;

import org.chromium.content_public.browser.ContactsFetcher;
import org.chromium.content_public.browser.ContactsPickerDelegate;
import org.chromium.content_public.browser.ContactsPickerListener;
import org.chromium.content_public.browser.WebContents;
import org.chromium.ui.base.WindowAndroid;

import java.util.ArrayList;
import java.util.List;

/**
 * Contact Picker delegate for the embedding (navigator.contacts.select).
 *
 * <p>Rather than reuse Chrome's shared {@code ContactsPickerDialog} from
 * components/browser_ui - which pulls in the full Chromium UI resource graph
 * (custom views, ColorStateLists, themed attributes) that does not resolve
 * cleanly inside an embedding AAR - this draws its own dialog with the platform
 * {@link AlertDialog} and reads contacts through {@link ContactsContract}. Same
 * architecture as Cefrium's device chooser: app-drawn UI, no Chromium widgets,
 * no resource coupling that breaks on Chromium updates. Registered via
 * {@code ContactsPicker.setContactsPickerDelegate(...)} at SDK init. The
 * READ_CONTACTS runtime permission is already granted by the time this is called
 * (the engine gates on it through ContactsDialogHost before reaching here).
 */
public class CefContactsPickerDelegate implements ContactsPickerDelegate {
    private static final String TAG = "Cefrium";

    // Mirror components' ContactsPickerProperties bitmask (avoids a dep on the
    // contacts_picker component just for these constants).
    private static final int PROPERTIES_TELS = 1 << 0;
    private static final int PROPERTIES_EMAILS = 1 << 1;
    private static final int PROPERTIES_NAMES = 1 << 2;

    private static final int ACTION_CANCEL = ContactsPickerListener.ContactsPickerAction.CANCEL;
    private static final int ACTION_SELECTED =
            ContactsPickerListener.ContactsPickerAction.CONTACTS_SELECTED;

    /** One contact read from the address book. */
    private static class Entry {
        final String id;
        final String displayName;

        Entry(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }
    }

    /** Handle returned to the engine so it can cancel an in-flight picker. */
    private static class Session {
        volatile AlertDialog dialog;
        volatile boolean finished;
    }

    @Override
    public Object showContactsPicker(
            WebContents webContents,
            ContactsPickerListener listener,
            boolean allowMultiple,
            boolean includeNames,
            boolean includeEmails,
            boolean includeTel,
            boolean includeAddresses,
            boolean includeIcons,
            String formattedOrigin,
            ContactsFetcher contactsFetcher) {
        WindowAndroid windowAndroid = webContents.getTopLevelNativeWindow();
        Activity activity = windowAndroid == null ? null : windowAndroid.getActivity().get();
        if (activity == null) {
            listener.onContactsPickerUserAction(ACTION_CANCEL, null, 0, 0, 0);
            return null;
        }

        final int propertiesRequested =
                (includeNames ? PROPERTIES_NAMES : 0)
                        | (includeEmails ? PROPERTIES_EMAILS : 0)
                        | (includeTel ? PROPERTIES_TELS : 0);

        final Session session = new Session();
        final ContentResolver resolver = activity.getContentResolver();
        final Handler ui = new Handler(Looper.getMainLooper());

        // Reading the address book can be slow; do it off the UI thread, then
        // build and show the dialog back on the UI thread.
        new Thread(() -> {
            final List<Entry> entries = queryContacts(resolver);
            ui.post(() -> {
                if (session.finished || activity.isFinishing()) {
                    return;
                }
                showDialog(
                        activity,
                        resolver,
                        session,
                        listener,
                        entries,
                        allowMultiple,
                        includeNames,
                        includeEmails,
                        includeTel,
                        propertiesRequested);
            });
        }, "CefContactsQuery").start();

        return session;
    }

    @Override
    public void cancelContactsPicker(Object picker) {
        if (!(picker instanceof Session)) {
            return;
        }
        Session session = (Session) picker;
        session.finished = true;
        AlertDialog dialog = session.dialog;
        if (dialog != null) {
            dialog.dismiss();
        }
    }

    private void showDialog(
            Activity activity,
            ContentResolver resolver,
            Session session,
            ContactsPickerListener listener,
            List<Entry> entries,
            boolean allowMultiple,
            boolean includeNames,
            boolean includeEmails,
            boolean includeTel,
            int propertiesRequested) {
        final int total = entries.size();
        final CharSequence[] labels = new CharSequence[total];
        for (int i = 0; i < total; i++) {
            labels[i] = entries.get(i).displayName;
        }
        final boolean[] checked = new boolean[total];

        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle("Select contacts to share");

        if (total == 0) {
            builder.setMessage("No contacts available.");
        } else if (allowMultiple) {
            builder.setMultiChoiceItems(
                    labels,
                    checked,
                    (DialogInterface d, int which, boolean isChecked) ->
                            checked[which] = isChecked);
        } else {
            builder.setSingleChoiceItems(
                    labels,
                    -1,
                    (DialogInterface d, int which) -> {
                        for (int i = 0; i < total; i++) {
                            checked[i] = false;
                        }
                        checked[which] = true;
                    });
        }

        builder.setPositiveButton(
                "Share",
                (DialogInterface d, int which) -> {
                    session.finished = true;
                    deliverSelection(
                            resolver,
                            listener,
                            entries,
                            checked,
                            total,
                            includeNames,
                            includeEmails,
                            includeTel,
                            propertiesRequested);
                });
        builder.setNegativeButton(
                "Cancel",
                (DialogInterface d, int which) -> {
                    session.finished = true;
                    listener.onContactsPickerUserAction(
                            ACTION_CANCEL, null, 0, propertiesRequested, 0);
                });
        builder.setOnCancelListener(
                (DialogInterface d) -> {
                    if (session.finished) {
                        return;
                    }
                    session.finished = true;
                    listener.onContactsPickerUserAction(
                            ACTION_CANCEL, null, 0, propertiesRequested, 0);
                });

        AlertDialog dialog = builder.create();
        session.dialog = dialog;
        dialog.show();
    }

    /** Build the selected contacts on a worker thread, then notify the engine. */
    private void deliverSelection(
            ContentResolver resolver,
            ContactsPickerListener listener,
            List<Entry> entries,
            boolean[] checked,
            int total,
            boolean includeNames,
            boolean includeEmails,
            boolean includeTel,
            int propertiesRequested) {
        final Handler ui = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            List<ContactsPickerListener.Contact> selected = new ArrayList<>();
            int selectedCount = 0;
            for (int i = 0; i < total; i++) {
                if (!checked[i]) {
                    continue;
                }
                selectedCount++;
                Entry e = entries.get(i);
                List<String> names =
                        includeNames ? singleton(e.displayName) : null;
                List<String> emails =
                        includeEmails ? queryEmails(resolver, e.id) : null;
                List<String> tel = includeTel ? queryPhones(resolver, e.id) : null;
                selected.add(
                        new ContactsPickerListener.Contact(names, emails, tel, null, null));
            }
            final int percentage = total == 0 ? 0 : (selectedCount * 100) / total;
            ui.post(() ->
                    listener.onContactsPickerUserAction(
                            ACTION_SELECTED,
                            selected,
                            percentage,
                            propertiesRequested,
                            /* propertiesUserRejected= */ 0));
        }, "CefContactsDeliver").start();
    }

    private static List<String> singleton(String value) {
        List<String> list = new ArrayList<>(1);
        list.add(value);
        return list;
    }

    private List<Entry> queryContacts(ContentResolver resolver) {
        List<Entry> result = new ArrayList<>();
        Cursor c = null;
        try {
            c =
                    resolver.query(
                            ContactsContract.Contacts.CONTENT_URI,
                            new String[] {
                                ContactsContract.Contacts._ID,
                                ContactsContract.Contacts.DISPLAY_NAME
                            },
                            null,
                            null,
                            ContactsContract.Contacts.DISPLAY_NAME + " COLLATE NOCASE ASC");
            if (c != null) {
                int idCol = c.getColumnIndex(ContactsContract.Contacts._ID);
                int nameCol = c.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME);
                while (c.moveToNext()) {
                    String id = c.getString(idCol);
                    String name = c.getString(nameCol);
                    if (name == null || name.trim().isEmpty()) {
                        name = "(no name)";
                    }
                    result.add(new Entry(id, name));
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Contacts query failed: " + t);
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return result;
    }

    private List<String> queryEmails(ContentResolver resolver, String contactId) {
        return queryData(
                resolver,
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                ContactsContract.CommonDataKinds.Email.CONTACT_ID,
                ContactsContract.CommonDataKinds.Email.ADDRESS,
                contactId);
    }

    private List<String> queryPhones(ContentResolver resolver, String contactId) {
        return queryData(
                resolver,
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                contactId);
    }

    private List<String> queryData(
            ContentResolver resolver,
            Uri uri,
            String idColumn,
            String valueColumn,
            String contactId) {
        List<String> values = new ArrayList<>();
        Cursor c = null;
        try {
            c =
                    resolver.query(
                            uri,
                            new String[] {valueColumn},
                            idColumn + " = ?",
                            new String[] {contactId},
                            null);
            if (c != null) {
                int col = c.getColumnIndex(valueColumn);
                while (c.moveToNext()) {
                    String v = c.getString(col);
                    if (v != null && !v.trim().isEmpty()) {
                        values.add(v);
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "Contact data query failed: " + t);
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return values;
    }
}
