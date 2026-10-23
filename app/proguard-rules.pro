# Minification is off (see app/build.gradle.kts). If it is ever enabled:
# - keep the LiteRT-LM adapter, which AppGraph instantiates by name:
#   -keep class dev.dejalango.app.platform.llm.litertlm.** { *; }
# - NanoHTTPD and ONNX Runtime ship their own consumer rules.
