Engine bundle placeholder.

At package time this directory is filled with the receiver engine layout
(see docs/tauri-shell.md):

  engine/
    runtime/bin/java            (bundled JRE)
    app/classes/                (compiled BackupReceiver classes)
    app/lib/sqlite-jdbc-*.jar
    app/lib/slf4j-api-*.jar
    app/easytier-core           (mesh sidecar binary for the target OS)

The Tauri shell resolves the engine in this order:
  1. $NB_DESKTOP_ENGINE_DIR
  2. <exe dir>/engine            (this directory, once packaged)
  3. <resource dir>/engine       (Tauri bundle resources)
