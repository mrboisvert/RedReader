-dontobfuscate
-keepattributes LineNumberTable,SourceFile,RuntimeVisibleAnnotations,AnnotationDefault,InnerClasses,EnclosingMethod

-keepclassmembers class * extends org.quantumbadger.redreaderdev.io.WritableObject {
	*;
}

-keepclassmembers class * extends org.quantumbadger.redreaderdev.jsonwrap.JsonObject$JsonDeserializable {
	*;
}

-keepclassmembers class org.quantumbadger.redreaderdev.R { *; }
-keepclassmembers class org.quantumbadger.redreaderdev.R$xml {	*; }
-keepclassmembers class org.quantumbadger.redreaderdev.R$string {	*; }

-keepclassmembers class com.github.luben.zstd.* {
	*;
}

-if @kotlinx.serialization.Serializable class **
{
    static **$* *;
}

-keepnames class <1>$$serializer { # -keepnames suffices; class is kept when serializer() is kept.
    static <1>$$serializer INSTANCE;
}

# Needed for instrumented tests for some reason
-keep class com.google.common.util.concurrent.ListenableFuture { *; }
