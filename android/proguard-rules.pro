# Consumer rules for capacitor-pipe.
#
# These are declared as `consumerProguardFiles` in android/build.gradle, so they
# are merged into the CONSUMING app's R8 configuration automatically. R8 never
# runs on this library — it runs once, over the whole app — which makes this
# file the only lever the plugin has over shrinking.
#
# Keep it as narrow as it can be while still working: everything kept here is
# something the app cannot obfuscate.


## --- Capacitor bridge surface -------------------------------------------
#
# Capacitor dispatches from JavaScript BY NAME: the bridge looks up the plugin
# by its @CapacitorPlugin name and then reflects over the class for a method
# whose getName() matches the JS call. Obfuscate either and every call fails at
# runtime with "not implemented", long after the build went green.
#
# @Capacitor/android ships no consumer rules of its own, so this has to live
# here or in each app.
-keep @com.getcapacitor.annotation.CapacitorPlugin class * { *; }
-keepclassmembers class * extends com.getcapacitor.Plugin {
    @com.getcapacitor.PluginMethod <methods>;
}


## --- Rhino (NewPipeExtractor's signature deciphering) --------------------
#
# NewPipe deciphers the signature and n-parameter by executing YouTube's own
# JavaScript in Rhino, so the interpreter is reached reflectively and cannot be
# shrunk. PipePipe does this server-side instead (see CLAUDE.md, Gotcha 5) —
# meaning these rules matter precisely when the primary engine has failed.
# Narrowed from a blanket `org.mozilla.javascript.**`. Three sub-packages are
# provably unreachable for how NewPipe drives Rhino, and they were the bulk of
# both the readable symbols and the size:
#
#   optimizer.**  compiled mode only. JavaScript.java calls
#                 context.setInterpretedMode(true) before every evaluation, so
#                 Codegen/Bootstrapper/OptRuntime never run.
#   tools.**      the debugger and shell REPL.
#   engine.**     the JSR-223 ScriptEngine binding.
#
# Everything else stays kept-and-unobfuscated, and that is NOT laziness.
# Rhino wires its standard objects through LazilyLoadedCtor, whose constructor
# takes the implementation class name as a STRING and resolves it reflectively.
# Scanning rhino-1.8.1 for such literals gives the list below — R8 cannot see
# any of them, and renaming one produces a ClassNotFoundException at runtime in
# the FALLBACK engine, i.e. only once the primary has already failed:
#
#   Context ContextFactory Function ImporterTopLevel Interpreter JavaAdapter
#   NativeContinuation NativeFunction NativeJavaTopPackage ScriptRuntime
#   ScriptableObject VMBridge  jdk18.VMBridge  regexp.{NativeRegExp,RegExpImpl}
#   typedarrays.*  xmlimpl.XMLLibImpl  resources.Messages
#
# Keeping the remaining classes wholesale is cheaper than maintaining that list
# against every Rhino bump, and the sub-packages above are where the size was.
-keep class !org.mozilla.javascript.optimizer.**,!org.mozilla.javascript.tools.**,!org.mozilla.javascript.engine.**,org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.tools.**

# Rhino 1.8.1 ships a JSR-223 ScriptEngine binding, an invokedynamic optimizer
# and a java.beans-based JSON converter. All three reference JDK-only packages
# that do not exist on Android, and R8 treats missing classes as a HARD ERROR
# ("Compilation failed to complete"), not a warning.
#
# Android never takes those code paths — the extractor drives Rhino through
# Context/Scriptable directly — so the references are unreachable in practice.
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**


## --- protobuf-javalite, relocated ----------------------------------------
#
# Note the shaded prefix: NewPipeExtractor's protobuf is bundled and relocated
# into the plugin's private namespace (CLAUDE.md, Gotcha 2), so the keep rule
# from protobuf's own documentation would match nothing here.
#
# Lite generated messages resolve their schema reflectively via dynamicMethod(),
# so shrinking their members yields a runtime parse failure rather than a link
# error.
#
# allowobfuscation is the point: protobuf-lite reflects on FIELDS, never on the
# message class NAME (it reaches messages through `.class` references that R8
# rewrites consistently). So the class may be renamed — and must be, or its
# fully-qualified name leaks `...services.youtube.protos.video.Xtags` into the
# dex in plain text, which no amount of string encryption elsewhere can hide.
# The members keep below still protects the fields the schema needs.
-keep,allowobfuscation class * extends ink.harsh.pipe.shaded.com.google.protobuf.GeneratedMessageLite
-keepclassmembers class * extends ink.harsh.pipe.shaded.com.google.protobuf.GeneratedMessageLite {
    <fields>;
}
-dontwarn ink.harsh.pipe.shaded.com.google.protobuf.**


## --- Optional dependencies the plugin only uses when present -------------
#
# Media3, Compose, core-pip and androidx.window are compileOnly: an app that
# only extracts ships none of them, and every use is behind a runtime class
# check. Their absence is therefore expected, not a missing-class error.
-dontwarn androidx.media3.**
-dontwarn androidx.core.pip.**


## --- Build-time-only annotations ------------------------------------------
#
# spotbugs-annotations is compileOnly (correctly — annotations have no runtime
# role), but both extractors reference them in method signatures, so R8 still
# sees the dangling reference and errors out.
-dontwarn edu.umd.cs.findbugs.annotations.**
-dontwarn javax.annotation.**


## --- Transitive extractor dependencies -----------------------------------
-dontwarn org.bouncycastle.**
-dontwarn org.brotli.**
-dontwarn org.java_websocket.**
-dontwarn com.squareup.wire.**
-dontwarn okhttp3.**
-dontwarn org.jsoup.**
