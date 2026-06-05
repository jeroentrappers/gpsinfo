# Standard Android Compose project — keep ProGuard rules minimal.
-dontwarn java.lang.invoke.StringConcatFactory

# BRouter (offline routing) loads classes reflectively: routing
# profiles name their kinematic model in a `---model:` line
# (Class.forName on e.g. btools.router.KinematicModel), and the
# expression engine resolves operators the same way. Keep the whole
# btools tree — it's small and R8 can't see those entry points.
-keep class btools.** { *; }
-dontwarn btools.**
