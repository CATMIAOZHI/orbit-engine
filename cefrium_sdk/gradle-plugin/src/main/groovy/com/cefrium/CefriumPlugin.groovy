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
        // FirebaseOptions.fromResource() reads com.google.android.gms.common
        // R.string (google_app_id, gcm_defaultSenderId, ...) when the app uses
        // native FCM (FirebaseApp.initializeApp / FirebaseMessaging). Without the
        // generated R class -> NoClassDefFoundError gms/common/R$string.
        'com.google.android.gms.common',
        'org.chromium.components.browser_ui.styles',
        'org.chromium.components.browser_ui.widget',
        // Contact Picker (navigator.contacts.select): PickerCategoryView /
        // ContactsPickerDialog read contacts_picker R.color/R.layout/R.string.
        'org.chromium.components.browser_ui.contacts_picker',
        'org.chromium.components.embedder_support.delegate',
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

        // AGP 8+/9 variant API. Accessed dynamically (Groovy) so no AGP-API
        // compile dependency is needed for the plugin.
        def androidComponents = project.extensions.getByName('androidComponents')
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
        }
    }

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
