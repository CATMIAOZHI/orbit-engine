package org.chromium.build;
/** Native library configuration for Cefrium SDK. */
public class NativeLibraries {
    public static final int CPU_FAMILY_UNKNOWN = 0;
    public static final int CPU_FAMILY_ARM = 1;
    public static final int CPU_FAMILY_MIPS = 2;
    public static final int CPU_FAMILY_X86 = 3;
    public static final int CPU_FAMILY_RISCV = 4;
    public static boolean sUseLinker;
    public static String[] LIBRARIES = {"cef"};
    public static int sCpuFamily = CPU_FAMILY_X86;
    public static boolean sSupport32Bit;
    public static boolean sSupport64Bit = true;
}
