// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium;

import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * FEATURE-087: a browser's page for Android's print framework. Each write
 * prints the page to PDF with the engine (print CSS applies) at the paper size
 * and orientation the print dialog chose, then copies it to the destination.
 */
final class CefriumPrintAdapter extends PrintDocumentAdapter {
    private final CefriumBrowser mBrowser;
    private final String mName;
    private PrintAttributes mAttributes;

    CefriumPrintAdapter(CefriumBrowser browser, String name) {
        mBrowser = browser;
        mName = name;
    }

    @Override
    public void onLayout(PrintAttributes oldAttributes, PrintAttributes newAttributes,
            CancellationSignal cancel, LayoutResultCallback callback, Bundle extras) {
        if (cancel.isCanceled()) {
            callback.onLayoutCancelled();
            return;
        }
        mAttributes = newAttributes;
        PrintDocumentInfo info = new PrintDocumentInfo.Builder(fileName())
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                .build();
        callback.onLayoutFinished(info, !newAttributes.equals(oldAttributes));
    }

    @Override
    public void onWrite(PageRange[] pages, ParcelFileDescriptor destination,
            CancellationSignal cancel, WriteResultCallback callback) {
        PrintAttributes.MediaSize size = mAttributes == null ? null : mAttributes.getMediaSize();
        // The engine takes the portrait size and a landscape flag, in inches.
        double width = 0;
        double height = 0;
        boolean landscape = false;
        if (size != null) {
            int shortSide = Math.min(size.getWidthMils(), size.getHeightMils());
            int longSide = Math.max(size.getWidthMils(), size.getHeightMils());
            width = shortSide / 1000.0;
            height = longSide / 1000.0;
            landscape = !size.isPortrait();
        }
        File pdf;
        try {
            pdf = File.createTempFile("cefrium-print", ".pdf", mBrowser.cacheDirForPrinting());
        } catch (IOException e) {
            callback.onWriteFailed(e.getMessage());
            return;
        }
        mBrowser.printToPdf(pdf.getPath(), width, height, landscape, (path, ok) -> {
            try {
                if (cancel.isCanceled()) {
                    callback.onWriteCancelled();
                } else if (!ok) {
                    callback.onWriteFailed("the page could not be printed");
                } else {
                    try (InputStream in = new FileInputStream(pdf);
                         OutputStream out = new FileOutputStream(destination.getFileDescriptor())) {
                        byte[] buffer = new byte[64 * 1024];
                        for (int n; (n = in.read(buffer)) > 0; ) out.write(buffer, 0, n);
                    }
                    callback.onWriteFinished(new PageRange[] {PageRange.ALL_PAGES});
                }
            } catch (IOException e) {
                callback.onWriteFailed(e.getMessage());
            } finally {
                pdf.delete();
            }
        });
    }

    private String fileName() {
        String name = mName == null || mName.isEmpty() ? "page" : mName;
        return name.endsWith(".pdf") ? name : name + ".pdf";
    }
}
