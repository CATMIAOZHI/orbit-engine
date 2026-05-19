package com.cefrium

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * Cefrium Gradle Plugin.
 *
 * Generates R.java for Chromium packages from the consumer's R.txt.
 * Chromium Java code references org.chromium.ui.R, org.chromium.base.R, etc.
 * Standard AARs only generate R for the AAR's own package.
 * This plugin bridges the gap by generating R for each Chromium package.
 */
class CefriumPlugin implements Plugin<Project> {

    static final CHROMIUM_R_PACKAGES = [
        'org.chromium.base',
        'org.chromium.ui',
        'org.chromium.content',
        'org.chromium.media',
        'org.chromium.components.input',
        'gen.base_module',
    ]

    void apply(Project project) {
        project.afterEvaluate {
            // Ensure assets are uncompressed (Chromium reads via mmap)
            project.android.androidResources {
                noCompress 'dat', 'pak', 'bin'
            }

            project.android.applicationVariants.all { variant ->
                def variantName = variant.name.capitalize()
                def genDir = new File(project.buildDir,
                    "generated/cefrium_r/${variant.name}")

                def generateTask = project.tasks.create(
                    "generateCefriumR${variantName}") {
                    outputs.dir(genDir)
                    doLast {
                        def rTxt = new File(project.buildDir,
                            "intermediates/runtime_symbol_list/${variant.name}/process${variantName}Resources/R.txt")
                        if (!rTxt.exists()) {
                            project.logger.warn("Cefrium: R.txt not found")
                            return
                        }

                        // Parse R.txt
                        def entries = new TreeMap()
                        rTxt.eachLine { line ->
                            def parts = line.trim().split(/\s+/, 4)
                            if (parts.length >= 4) {
                                def type = parts[1]
                                def name = parts[2]
                                def value = parts[3]
                                if (!entries.containsKey(type))
                                    entries[type] = []
                                entries[type].add([name, value])
                            }
                        }

                        // Generate R.java per Chromium package
                        CHROMIUM_R_PACKAGES.each { pkg ->
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
                                    if (v.startsWith('{'))
                                        sb.append("    public static final int[] ${n} = ${v};\n")
                                    else
                                        sb.append("    public static final int ${n} = ${v};\n")
                                }
                                sb.append("  }\n")
                            }
                            sb.append("}\n")
                            new File(pkgDir, 'R.java').text = sb.toString()
                        }
                        project.logger.lifecycle(
                            "Cefrium: Generated R for ${CHROMIUM_R_PACKAGES.size()} packages")
                    }
                }

                // Wire into compilation
                generateTask.dependsOn "process${variantName}Resources"
                variant.registerJavaGeneratingTask(generateTask, genDir)
            }
        }
    }
}
