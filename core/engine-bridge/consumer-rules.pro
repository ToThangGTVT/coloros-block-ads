# JNI name resolution must survive shrinking and obfuscation.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
-keep interface com.utc.color.blockads.core.enginebridge.SocketProtector { *; }
-keepclassmembers class * implements com.utc.color.blockads.core.enginebridge.SocketProtector {
    public boolean protect(int);
}

-keep interface com.utc.color.blockads.core.enginebridge.DnsEventSink { *; }
-keepclassmembers class * implements com.utc.color.blockads.core.enginebridge.DnsEventSink {
    public void onDnsEvents(java.lang.String);
}
