// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium.itest;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The test turns the screen off, so it needs a device without a secure lock
 * screen. run-instrumentation-suite.sh leaves it out unless
 * CEFRIUM_UNLOCKED_DEVICE=1, which suite-on-emulator.sh sets.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface NeedsUnlockedDevice {}
