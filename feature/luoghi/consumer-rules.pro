# OsmAnd loads AIDL Bundle Parcelables by their cross-process class names.
# Keep the official wire contract unchanged in final minified APKs.
-keep class net.osmand.aidlapi.** { *; }
