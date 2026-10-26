// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium;

import org.chromium.content.app.PrivilegedProcessService;
import org.chromium.content.app.SandboxedProcessService;

/**
 * Cefrium's child process services, under Cefrium's own class names.
 *
 * Chromium binds services named {@code <base>0..N}. The system WebView binds
 * org.chromium.content.app.SandboxedProcessService0 under the embedding app's
 * package, so with the same names Android handed WebView's renderer process to
 * Cefrium and the page stayed blank. The manifest declares these classes and
 * points Chromium at them with the SANDBOXED_SERVICES_NAME and
 * PRIVILEGED_SERVICES_NAME meta-data (patch android/child_service_names). Each
 * class only gives a service its own name; the behaviour is Chromium's.
 * The counts match the manifest: 40 sandboxed, 3 privileged.
 */
public final class ChildServices {
    private ChildServices() {}

    public static class Sandboxed0 extends SandboxedProcessService {}
    public static class Sandboxed1 extends SandboxedProcessService {}
    public static class Sandboxed2 extends SandboxedProcessService {}
    public static class Sandboxed3 extends SandboxedProcessService {}
    public static class Sandboxed4 extends SandboxedProcessService {}
    public static class Sandboxed5 extends SandboxedProcessService {}
    public static class Sandboxed6 extends SandboxedProcessService {}
    public static class Sandboxed7 extends SandboxedProcessService {}
    public static class Sandboxed8 extends SandboxedProcessService {}
    public static class Sandboxed9 extends SandboxedProcessService {}
    public static class Sandboxed10 extends SandboxedProcessService {}
    public static class Sandboxed11 extends SandboxedProcessService {}
    public static class Sandboxed12 extends SandboxedProcessService {}
    public static class Sandboxed13 extends SandboxedProcessService {}
    public static class Sandboxed14 extends SandboxedProcessService {}
    public static class Sandboxed15 extends SandboxedProcessService {}
    public static class Sandboxed16 extends SandboxedProcessService {}
    public static class Sandboxed17 extends SandboxedProcessService {}
    public static class Sandboxed18 extends SandboxedProcessService {}
    public static class Sandboxed19 extends SandboxedProcessService {}
    public static class Sandboxed20 extends SandboxedProcessService {}
    public static class Sandboxed21 extends SandboxedProcessService {}
    public static class Sandboxed22 extends SandboxedProcessService {}
    public static class Sandboxed23 extends SandboxedProcessService {}
    public static class Sandboxed24 extends SandboxedProcessService {}
    public static class Sandboxed25 extends SandboxedProcessService {}
    public static class Sandboxed26 extends SandboxedProcessService {}
    public static class Sandboxed27 extends SandboxedProcessService {}
    public static class Sandboxed28 extends SandboxedProcessService {}
    public static class Sandboxed29 extends SandboxedProcessService {}
    public static class Sandboxed30 extends SandboxedProcessService {}
    public static class Sandboxed31 extends SandboxedProcessService {}
    public static class Sandboxed32 extends SandboxedProcessService {}
    public static class Sandboxed33 extends SandboxedProcessService {}
    public static class Sandboxed34 extends SandboxedProcessService {}
    public static class Sandboxed35 extends SandboxedProcessService {}
    public static class Sandboxed36 extends SandboxedProcessService {}
    public static class Sandboxed37 extends SandboxedProcessService {}
    public static class Sandboxed38 extends SandboxedProcessService {}
    public static class Sandboxed39 extends SandboxedProcessService {}
    public static class Privileged0 extends PrivilegedProcessService {}
    public static class Privileged1 extends PrivilegedProcessService {}
    public static class Privileged2 extends PrivilegedProcessService {}
}
