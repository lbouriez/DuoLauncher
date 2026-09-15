# DiscoverBounds resolves the public Window Extensions API by class and member name so it can
# retain the split-host fallback on devices whose extension implementation differs. R8 cannot
# infer these references from the strings used by Class.forName/getMethod/Proxy.
-keep class androidx.window.extensions.** { *; }

# Google's launcher overlay is a private Binder protocol whose callback types and members are
# reached by a remote process. Keep this narrow bridge stable in optimized release builds.
-keep class com.jake.duolauncher.DiscoverClient { *; }
-keep class com.jake.duolauncher.DiscoverClient$* { *; }
-keep class com.jake.duolauncher.LiveDiscoverActivity { *; }
-keep class com.jake.duolauncher.LiveDiscover { *; }
-keep class com.jake.duolauncher.DiscoverFrame { *; }

# Android manifest components and directly constructed widget-host classes are traced by AGP/R8.
# Layout and backup persistence use org.json with explicit keys, so there are no model classes
# that require broad reflection or serialization keep rules.
