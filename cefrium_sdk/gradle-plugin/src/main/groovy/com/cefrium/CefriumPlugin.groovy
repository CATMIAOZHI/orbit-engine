// SPDX-FileCopyrightText: 2026 Juan Manuel Méndez Rey <cefrium@proton.me>
// SPDX-License-Identifier: LGPL-3.0-or-later OR LicenseRef-Cefrium-Commercial

package com.cefrium

import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Cefrium Gradle Plugin.
 *
 * Generates R.java for all Chromium packages from the consumer's R.txt.
 * Chromium Java code references org.chromium.*.R across 130+ packages.
 * Standard AARs only generate R for the AAR's own package.
 * This plugin bridges the gap by generating R for every org.chromium.*
 * and gen.* package that Chromium code uses.
 *
 * Uses the AGP 8+/9 variant API (androidComponents.onVariants +
 * sources.java.addGeneratedSourceDirectory); the legacy applicationVariants /
 * registerJavaGeneratingTask API was removed in AGP 9. The androidComponents
 * extension is accessed dynamically so the plugin itself needs no AGP API
 * compile dependency.
 */
class CefriumPlugin implements Plugin<Project> {

    // Packages whose R classes are referenced at runtime by Chromium code.
    // Add packages here as ClassNotFoundException for R$type surfaces.
    // Do NOT add all 131 packages — that causes D8 OOM (260K+ fields).
    static final CHROMIUM_R_PACKAGES = [
        'gen.base_module',
        'org.chromium.base',
        'org.chromium.chrome',
        // Web Notifications: ChromeChannelDefinitions / SiteChannelsManager read
        // notifications R.string (channel group/name) when a site notification
        // channel is created on grant/display (FEATURE-037).
        'org.chromium.chrome.browser.notifications',
        'org.chromium.components.browser_ui.styles',
        'org.chromium.components.browser_ui.widget',
        // Contact Picker (navigator.contacts.select): PickerCategoryView /
        // ContactsPickerDialog read contacts_picker R.color/R.layout/R.string.
        'org.chromium.components.browser_ui.contacts_picker',
        'org.chromium.components.embedder_support.delegate',
        // FEATURE-059: external URL scheme hand-off. When an unknown/app scheme
        // (baiduboxapp://, weixin://, ...) is handed off but no app handles it,
        // InterceptNavigationDelegateImpl.logBlockedNavigationToDevToolsConsole()
        // reads external_intents R.string (blocked_navigation_warning /
        // unreachable_navigation_warning). Missing -> NoClassDefFoundError
        // external_intents/R$string crashes the browser process.
        'org.chromium.components.external_intents',
        // FedCM / federated sign-in (navigator.credentials): AccountSelectionBridge
        // .getBrandIconIdealSize() reads webid R.dimen when a page invokes the
        // account-chooser UI (common on sites with "Sign in with ..." widgets,
        // incl. many ad/identity-heavy pages). Missing -> NoClassDefFoundError
        // webid/R$dimen crashes the browser process on such pages.
        'org.chromium.chrome.browser.ui.android.webid',
        'org.chromium.components.infobars',
        'org.chromium.components.input',
        'org.chromium.components.permissions',
        'org.chromium.components.webapps',
        'org.chromium.content',
        'org.chromium.media',
        'org.chromium.ui',
    ]

    void apply(Project project) {
        // Ensure Chromium's mmap'd assets stay uncompressed.
        project.afterEvaluate {
            project.android.androidResources {
                noCompress 'dat', 'pak', 'bin'
            }
        }

        // Configure-time lint for a silent AGP 9 pitfall: registering a shared
        // source root via sourceSets.main.java.srcDir(...) does NOT feed those
        // files to AGP 9's built-in Kotlin compiler -- kotlin.srcDir(...) must be
        // added too, or the .kt files are dropped and the consumer sees a cascade
        // of "unresolved reference" errors in unrelated files (20-minute hunt for
        // the first external adopter). Warn so the failure is legible.
        project.afterEvaluate {
            try {
                def mainSet = project.android.sourceSets.findByName('main')
                def kotlinSet = (mainSet != null && mainSet.hasProperty('kotlin')) ? mainSet.kotlin : null
                if (kotlinSet == null) return  // java-only project: nothing to check
                def kotlinDirs = kotlinSet.srcDirs
                mainSet.java.srcDirs.each { dir ->
                    def d = (dir instanceof File) ? dir : new File(dir.toString())
                    if (kotlinDirs.contains(d) || !d.isDirectory()) return
                    boolean hasKt = false
                    d.traverse(type: groovy.io.FileType.FILES) { f ->
                        if (f.name.endsWith('.kt')) { hasKt = true; return groovy.io.FileVisitResult.TERMINATE }
                    }
                    if (hasKt) {
                        project.logger.warn(
                            "Cefrium: java.srcDir '${d}' contains Kotlin sources but is not a " +
                            "kotlin.srcDir. Under AGP 9 built-in Kotlin those .kt files are NOT " +
                            "compiled (symptom: 'unresolved reference' errors in unrelated files). " +
                            "Add to this sourceSet: kotlin.srcDir('${d}')")
                    }
                }
            } catch (Throwable ignore) {
                // Best-effort lint only; never fail the consumer build over it.
            }
        }

        // The SDK does not vendor the Maven libraries Chromium uses (Guava,
        // Material, GMS, Firebase, ...); its POM declares them. An app that adds
        // the AAR as a file never reads that POM, so add the list the AAR carries.
        project.afterEvaluate {
            def coords = [] as LinkedHashSet
            ['implementation', 'api'].each { name ->
                def conf = project.configurations.findByName(name)
                if (conf == null) return
                conf.dependencies.withType(org.gradle.api.artifacts.FileCollectionDependency).each { dep ->
                    dep.files.files.findAll { it.isFile() && it.name ==~ /cefrium-sdk.*\.aar/ }.each { aar ->
                        new java.util.zip.ZipFile(aar).withCloseable { z ->
                            def e = z.getEntry('cefrium-maven-deps.txt')
                            if (e != null) {
                                z.getInputStream(e).getText('UTF-8').readLines()*.trim()
                                    .findAll { it && !it.startsWith('#') }.each { coords << it }
                            }
                        }
                    }
                }
            }
            coords.each { project.dependencies.add('runtimeOnly', it) }
        }

        // AGP 8+/9 variant API. Accessed dynamically (Groovy) so no AGP-API
        // compile dependency is needed for the plugin.
        def androidComponents = project.extensions.getByName('androidComponents')

        // Android 17+ runs renderers as native services (Chromium's javaless
        // renderers). Their declarations use android:nativeService and
        // android:zygotePreloadNativeLib, which aapt2 rejects below compileSdk
        // 37, so the SDK manifest cannot carry them; add them here when the app
        // compiles against 37 or later. Cefrium reads JAVALESS_MARKER at startup
        // and keeps renderers on the Java services without it.
        Integer compileSdkLevel = null
        androidComponents.finalizeDsl { android ->
            compileSdkLevel = compileSdkLevelOf(android)
        }

        androidComponents.onVariants(androidComponents.selector().all()) { variant ->
            def variantName = variant.name
            def cap = variantName.capitalize()

            def genTask = project.tasks.register(
                    "generateCefriumR${cap}", GenerateCefriumRTask) { t ->
                // R.txt is produced by process<Variant>Resources; declare it as an
                // input so the R.java regenerates whenever resources change.
                t.rtxt.set(project.layout.buildDirectory.file(
                    "intermediates/runtime_symbol_list/${variantName}/process${cap}Resources/R.txt"))
                t.packages.set(CHROMIUM_R_PACKAGES)
                t.dependsOn("process${cap}Resources")
            }

            // Register the generated dir as a Java source root for this variant.
            variant.sources.java.addGeneratedSourceDirectory(
                genTask, { it.getOutputDir() })

            if (compileSdkLevel != null && compileSdkLevel >= JAVALESS_MIN_COMPILE_SDK) {
                def manifestTask = project.tasks.register(
                        "generateCefriumJavalessManifest${cap}",
                        GenerateJavalessManifestTask) { t ->
                    t.outputFile.set(project.layout.buildDirectory.file(
                        "generated/cefrium/javaless/${variantName}/AndroidManifest.xml"))
                }
                variant.sources.manifests.addGeneratedManifestFile(
                    manifestTask, { it.getOutputFile() })
            }
        }
    }

    static final int JAVALESS_MIN_COMPILE_SDK = 37

    // compileSdk as an API level, or null when it cannot be read (a preview
    // SDK, or a DSL this plugin does not know): the app then gets no javaless
    // declarations, which is safe.
    static Integer compileSdkLevelOf(android) {
        try {
            def level = android.compileSdk
            if (level instanceof Integer) return level
        } catch (Throwable ignore) {
        }
        try {
            def m = (android.compileSdkVersion ?: '') =~ /^android-(\d+)/
            if (m.find()) return m.group(1) as Integer
        } catch (Throwable ignore) {
        }
        return null
    }

    /** Writes the manifest overlay that declares Cefrium's native renderer services. */
    static abstract class GenerateJavalessManifestTask extends DefaultTask {

        @OutputFile
        abstract RegularFileProperty getOutputFile()

        @TaskAction
        void generate() {
            def services = (0..1).collect { i ->
                """        <service android:name="com.cefrium.ChildServices\$NativeSandboxed${i}"
            android:nativeService="true"
            android:process=":sandboxed_process${i}"
            android:isolatedProcess="true"
${i == 0 ? '            android:useAppZygote="true"\n' : ''}            android:exported="false">
            <property android:name="android.app.PROPERTY_NATIVE_SERVICE_LIBRARY_NAME"
                android:value="libcef.so" />
            <property android:name="android.app.PROPERTY_NATIVE_SERVICE_FUNCTION_NAME"
                android:value="NativeChildProcessService_onCreate" />
        </service>
"""
            }.join('')
            def file = outputFile.get().asFile
            file.parentFile.mkdirs()
            file.text = """<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by the com.cefrium Gradle plugin: Android 17+ native renderers. -->
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:zygotePreloadNativeLib="libcef.so">
        <meta-data android:name="${JAVALESS_MARKER}" android:value="true" />
${services}    </application>
</manifest>
"""
        }
    }

    static final String JAVALESS_MARKER = 'com.cefrium.JAVALESS_RENDERERS'

    /**
     * Reads the merged R.txt and writes a R.java per Chromium package so that
     * Chromium code's org.chromium.*.R references resolve against the consumer
     * APK's (non-final) resource IDs.
     */
    static abstract class GenerateCefriumRTask extends DefaultTask {

        @InputFile
        @PathSensitive(PathSensitivity.RELATIVE)
        abstract RegularFileProperty getRtxt()

        @Input
        abstract ListProperty<String> getPackages()

        @OutputDirectory
        abstract DirectoryProperty getOutputDir()

        @TaskAction
        void generate() {
            def rTxtFile = rtxt.get().asFile
            def genDir = outputDir.get().asFile
            if (!rTxtFile.exists()) {
                logger.warn("Cefrium: R.txt not found at ${rTxtFile}")
                return
            }

            // Parse R.txt: "<int|int[]> <type> <name> <value>".
            def entries = new TreeMap()
            rTxtFile.eachLine { line ->
                def parts = line.trim().split(/\s+/, 4)
                if (parts.length >= 4) {
                    def type = parts[1]
                    def name = parts[2]
                    def value = parts[3]
                    if (!entries.containsKey(type)) {
                        entries[type] = []
                    }
                    entries[type].add([name, value])
                }
            }

            def pkgs = packages.get()
            pkgs.each { pkg ->
                def pkgDir = new File(genDir, pkg.replace('.', '/'))
                pkgDir.mkdirs()
                def sb = new StringBuilder()
                sb.append("package ${pkg};\n")
                sb.append("public final class R {\n")
                entries.each { type, names ->
                    sb.append("  public static final class ${type} {\n")
                    names.each { entry ->
                        def n = entry[0]
                        def v = entry[1]
                        if (v.startsWith('{')) {
                            sb.append("    public static final int[] ${n} = ${v};\n")
                        } else {
                            sb.append("    public static final int ${n} = ${v};\n")
                        }
                    }
                    sb.append("  }\n")
                }
                sb.append("}\n")
                new File(pkgDir, 'R.java').text = sb.toString()
            }
            logger.lifecycle("Cefrium: Generated R for ${pkgs.size()} packages")
        }
    }
}
