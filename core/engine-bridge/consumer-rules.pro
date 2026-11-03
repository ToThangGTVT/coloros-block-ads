# JNI name resolution must survive shrinking and obfuscation.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
