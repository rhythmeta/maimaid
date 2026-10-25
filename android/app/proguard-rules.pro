# ONNX Runtime's native bridge resolves these classes and members by their
# compiled Java names. The 1.28.0 Android AAR does not ship consumer rules.
-keep class ai.onnxruntime.** { *; }

# AppWidgetProvider/Glance resolve the receiver and widget implementation from
# manifest and persisted Glance state when the launcher adds or restores a widget.
# Keep these classes and their members stable in release builds.
-keep class org.rhythmeta.maimaid.widget.** { *; }
-keep class org.rhythmeta.maimaid.MaimaidApplication { *; }

# WorkManager restores this input merger by its persisted class name. R8 can
# remove its public constructor, which prevents Glance's update worker from
# starting in a minified build.
-keep class androidx.work.OverwritingInputMerger { *; }

# Protobuf lite resolves generated message fields by their source names.
-keep class org.rhythmeta.maimaid.sharing.** { *; }
-keep class org.rhythmeta.backup.proto.** { *; }

# ML Kit loads its component registrars reflectively. ComponentDiscovery reads
# the registrar class names from MlKitComponentDiscoveryService's manifest
# metadata and instantiates each one with
# Class.forName(name).getDeclaredConstructor().newInstance(). The consumer rules
# shipped by firebase-components 16.1.0 only contain
# `-keep class * implements ComponentRegistrar`, which R8 9.x honours for the
# class itself but not for its implicit no-arg constructor. Release builds
# therefore strip `<init>()`, discovery fails with NoSuchMethodException,
# MlKitContext is initialised without the barcode component, and
# BarcodeScanning.getClient() later NPEs on the missing component. Keep the
# whole ML Kit surface: it is reflection-heavy beyond the registrars (native
# bindings, bundled model loading), so narrowing this to constructors alone
# risks the next release-only crash.
-keep class com.google.mlkit.** { *; }
