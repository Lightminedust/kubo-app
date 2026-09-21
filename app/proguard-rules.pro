# Kubo is a desktop application, not a library. Nothing outside it calls in, so every class, field and method
# that is not reached from the entry points below may be renamed, and anything nothing reaches at all may be
# dropped. What ships is a jar whose class and method names say nothing about what the code does.
#
# Only Kubo's own jar goes through this. sshj, Bouncy Castle, Jackson, SLF4J and JavaFX are passed in as
# library jars and are shipped exactly as their authors published them: renaming somebody else's crypto
# provider breaks the service lookups it does on itself, and buys nothing — that code is public anyway.

-verbose

# Shrinking and renaming, but no optimisation. Optimisation inlines, merges classes and rewrites control flow,
# which is where a subtle difference between what was tested and what ships comes from. The goal here is that
# the names say nothing, and renaming alone achieves it.
-dontoptimize

# Kubo is built for one JDK and packaged with its own runtime, so there is no older bytecode to stay
# compatible with.
-dontusemixedcaseclassnames
-keepattributes Exceptions,InnerClasses,Signature,EnclosingMethod
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault
# Records carry their component names in a class-file attribute, and Jackson reads them from there.
-keepattributes Record,PermittedSubclasses

# Line numbers are kept so a stack trace in a user's report still points at a line, but the file names are
# flattened to "SourceFile" so they name nothing.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile


# --- The entry points -------------------------------------------------------------------------------------

# jpackage writes this class name into the launcher it builds, so it is the one name that cannot move.
-keep public final class com.kubo.Launcher {
    public static void main(java.lang.String[]);
}

# JavaFX instantiates the Application subclass itself, through its no-argument constructor. Shrinking would
# otherwise drop a constructor nothing in the code calls.
-keep public final class com.kubo.KuboApp {
    public KuboApp();
}


# --- What Jackson binds by name ---------------------------------------------------------------------------

# These names are not an implementation detail: they are the file format of sessions.json, already written on
# users' machines. Rename a component and every saved session on every existing install becomes unreadable,
# and the next save writes keys nothing will ever read back.
-keep class com.kubo.connection.SavedSession { *; }
-keep class com.kubo.connection.SavedSession$Auth { *; }
-keep class com.kubo.connection.SessionStore$Document { *; }

# Everything else Jackson touches goes through JsonNode and string keys written out in the code, so those
# names are already literals and survive renaming untouched.


# --- Enumerations -----------------------------------------------------------------------------------------

# Constant names are values here, not identifiers: the session log writes Origin.name() into its JSON and
# reads it back with valueOf. Keeping the members of an enum costs almost nothing — an enum holds no logic —
# and the classes and methods around them are still renamed.
-keepclassmembers enum com.kubo.** {
    <fields>;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}


# --- Library jars -----------------------------------------------------------------------------------------

# Referenced by SLF4J's and Jackson's own code for features Kubo does not use. They are not in the
# distribution and never will be; the warning is about a path nothing takes.
-dontwarn org.slf4j.**
-dontwarn com.fasterxml.jackson.**
-dontwarn net.schmizz.**
-dontwarn org.bouncycastle.**
-dontwarn com.hierynomus.**
-dontwarn javafx.**
-dontwarn java.**
-dontwarn javax.**

# Notes about reflection inside libraries Kubo does not process are noise: those jars ship unchanged.
-dontnote org.slf4j.**
-dontnote com.fasterxml.jackson.**
-dontnote net.schmizz.**
-dontnote org.bouncycastle.**
