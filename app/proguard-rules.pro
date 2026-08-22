# TAKPilot2 DJI MSDKv5 — project keep rules.
#
# R44: `minifyEnabled true` was set with NO `proguardFiles` line at all, so this file did not
# exist and there was nowhere to put a keep rule. R8 still ran — the DJI AAR ships its own
# consumer rules and AGP contributes its defaults — which is precisely what made the gap easy to
# miss: the build worked, so nothing pointed at the missing configuration until something got
# stripped in the field.
#
# Keep this file even when it is nearly empty. Its job is to be the place a keep rule goes.

# --- Reflection surfaces --------------------------------------------------------------------
# Room generates implementations by name and Gson-style reflection is used by some DJI paths;
# both ship their own consumer rules, so nothing is repeated here.

# --- Crash readability ----------------------------------------------------------------------
# Line numbers survive obfuscation so a fielded stack trace can be mapped back with
# signedReleases/TAKPilot2-DJIv5-v<version>-mapping.txt. Without these two, a trace deobfuscates
# to method names with no line — enough to find the function, not enough to find the statement.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
