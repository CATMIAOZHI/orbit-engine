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
        // WebXR (Chromium 158+): the Cardboard SDK's QrCodeCaptureActivity reads
        // its R.id/R.layout/R.string; without them R8 stops on missing classes.
        'com.google.cardboard.sdk',
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
        // Ensure Chromium's mmap'd assets stay uncompressed. Since 0.18.0 their
        // names end in BuildConfig.APK_ASSETS_SUFFIX ("+com.cefrium+").
        project.afterEvaluate {
            project.android.androidResources {
                noCompress 'dat', 'pak', 'bin', '+com.cefrium+'
            }
        }

        // The SDK is Java 25 bytecode. An older javac stops on it with "class file
        // has wrong version 69.0" buried among "cannot find symbol" errors; say
        // what to change instead. Reads each task's own compiler, so a project
        // that sets a JDK 25 toolchain on an older Gradle JVM is not flagged.
        project.tasks.withType(org.gradle.api.tasks.compile.JavaCompile).configureEach { t ->
            t.doFirst {
                Integer version = null
                try {
                    version = t.javaCompiler.get().metadata.languageVersion.asInt()
                } catch (Throwable ignore) {
                    return  // cannot tell: let javac speak
                }
                if (version < 25) {
                    throw new org.gradle.api.GradleException(
                        "Cefrium needs JDK 25 to compile against the SDK; this task uses " +
                        "JDK ${version}. Run Gradle on JDK 25 or set a Java 25 toolchain. " +
                        "See https://cefrium.com/guides/adopting-cefrium/#toolchain")
                }
            }
        }

        // The engine ships as one native module per ABI. An app that packages
        // arm64-v8a, armeabi-v7a or x86_64 without that ABI's libcef.so starts and then dies
        // with UnsatisfiedLinkError there (0.9.3 shipped like that): a release
        // build stops, a debuggable one warns (a one-ABI development AAR is
        // normal there). Other ABIs come from other libraries: not ours to check.
        // With Play Feature Delivery the engine can come from a dynamic feature
        // module: the app then may package none, and the module is checked
        // where the plugin is applied to it.
        def debuggable = [:]
        boolean app = project.plugins.hasPlugin('com.android.application')
        if (app || project.plugins.hasPlugin('com.android.dynamic-feature')) {
            project.tasks.matching { it.name ==~ /merge\w*NativeLibs/ }.configureEach { t ->
                t.doLast {
                    def abis = [] as Set
                    def engine = [] as Set
                    t.outputs.files.files.each { out ->
                        if (!out.isDirectory()) return  // the task also writes a report file
                        out.eachFileRecurse { f ->
                            if (!f.name.endsWith('.so')) return
                            abis << f.parentFile.name
                            if (f.name == 'libcef.so') engine << f.parentFile.name
                        }
                    }
                    // abiFilters drop the other ABIs when packaging, after this merge.
                    def filters = [] as Set
                    try {
                        filters.addAll(project.android.defaultConfig.ndk.abiFilters ?: [])
                    } catch (Throwable ignore) {
                        // no ndk block: every merged ABI is packaged
                    }
                    if (!filters.isEmpty()) abis.retainAll(filters)
                    def missing = ['arm64-v8a', 'armeabi-v7a', 'x86_64'].findAll { it in abis && !(it in engine) }
                    if (engine.isEmpty()) {
                        def features = []
                        try {
                            features = app ? (project.android.dynamicFeatures ?: []) : []
                        } catch (Throwable ignore) {
                        }
                        if (!app || !features.isEmpty()) {
                            project.logger.info("Cefrium: no libcef.so here; it is expected " +
                                "from a dynamic feature module.")
                            return
                        }
                        throw new org.gradle.api.GradleException(
                            "Cefrium: the app packages no libcef.so. Depend on " +
                            "com.cefrium:cefrium-sdk (or cefrium-sdk-libre), which brings " +
                            "the engine for every ABI. See https://cefrium.com/quickstart/")
                    }
                    if (!missing.isEmpty()) {
                        def message = "Cefrium: the app packages ${missing.join(', ')} but no " +
                            "libcef.so for it, so Cefrium would fail to start there. Keep the " +
                            "matching com.cefrium:cefrium-native-* module, or leave the ABI out " +
                            "with ndk { abiFilters }. See https://cefrium.com/quickstart/"
                        def name = (t.name - 'merge' - 'NativeLibs')
                        name = name ? name[0].toLowerCase() + name.substring(1) : name
                        def dbg = debuggable[name]
                        if (dbg == null) dbg = name.toLowerCase().contains('debug')
                        if (!dbg) throw new org.gradle.api.GradleException(message)
                        project.logger.warn("WARNING: " + message)
                    }
                }
            }
        }

        // The SDK manifest sets android:appComponentFactory with tools:replace
        // (it keeps the app's Application out of the engine's processes). A
        // library that declares a factory of its own and merges after the SDK
        // loses it without a word, and then that library's factory never runs.
        // Fail and say how to keep both. The app's own factory wins the merge
        // and is checked at run time instead.
        if (app) {
            project.tasks.matching { it.name ==~ /process\w*MainManifest/ }.configureEach { t ->
                t.doLast {
                    def factoryOf = { File f ->
                        def m = f.getText('UTF-8') =~ /android:appComponentFactory\s*=\s*"([^"]+)"/
                        m.find() ? m.group(1) : null
                    }
                    def merged = t.outputs.files.files.collectMany { out ->
                        out.isDirectory()
                            ? out.listFiles().findAll { it.name == 'AndroidManifest.xml' }
                            : (out.name == 'AndroidManifest.xml' ? [out] : [])
                    }
                    if (merged.isEmpty() || factoryOf(merged[0]) != CEFRIUM_FACTORY) return
                    def own = project.projectDir.toPath()
                    def foreign = t.inputs.files.files.findAll {
                        it.name == 'AndroidManifest.xml' && it.isFile() && !it.toPath().startsWith(own)
                    }.collect { [it, factoryOf(it)] }.findAll { it[1] && !(it[1] in COMPATIBLE_FACTORIES) }
                    if (foreign.isEmpty()) return
                    def list = foreign.collect { "${it[1]} (${it[0].parentFile})" }.join(', ')
                    throw new org.gradle.api.GradleException(
                        "Cefrium: a library declares android:appComponentFactory ${list}, and " +
                        "the Cefrium SDK's factory replaced it in the manifest merge, so it would " +
                        "never run. Write a factory that extends ${CEFRIUM_FACTORY}, does what " +
                        "that one does, and set it in your manifest with " +
                        "tools:replace=\"android:appComponentFactory\".")
                }
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
        // Material, GMS, Firebase, androidx, ...); its POM declares them. An app
        // that adds the AAR as a file never reads that POM, so add the lists the
        // AAR carries. Without androidx.browser, web notifications fail silently.
        project.afterEvaluate {
            def coords = [] as LinkedHashSet
            ['implementation', 'api'].each { name ->
                def conf = project.configurations.findByName(name)
                if (conf == null) return
                conf.dependencies.withType(org.gradle.api.artifacts.FileCollectionDependency).each { dep ->
                    dep.files.files.findAll { it.isFile() && it.name ==~ /cefrium-sdk.*\.aar/ }.each { aar ->
                        new java.util.zip.ZipFile(aar).withCloseable { z ->
                            ['cefrium-pom-deps.txt', 'cefrium-maven-deps.txt'].each { entry ->
                                def e = z.getEntry(entry)
                                if (e != null) {
                                    z.getInputStream(e).getText('UTF-8').readLines()*.trim()
                                        .findAll { it && !it.startsWith('#') }.each { coords << it }
                                }
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
            try {
                debuggable[variantName] = variant.debuggable
            } catch (Throwable ignore) {
                // not an application variant, or an older API: the name decides
            }
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

    static final String CEFRIUM_FACTORY = 'com.cefrium.CefriumAppComponentFactory'
    // Factories the SDK's one already does the work of (it delegates to androidx).
    static final COMPATIBLE_FACTORIES = [CEFRIUM_FACTORY,
        'androidx.core.app.CoreComponentFactory',
        'android.support.v4.app.CoreComponentFactory']

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
