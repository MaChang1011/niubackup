# M2 build notes (2026-10-30, built on machang-laptop)

## Native libs (real build, not stubs)
Recipe (vendor build.sh equivalent, run manually because the tree came from a tarball, not git):
- Rust via rsproxy.cn mirror (static.rust-lang.org bulk downloads crawl on this broadband):
  RUSTUP_DIST_SERVER=https://rsproxy.cn, RUSTUP_UPDATE_ROOT=https://rsproxy.cn/rustup,
  toolchain 1.95 (repo pin), cargo-ndk 4.1.2, ~/.cargo/config.toml replaces crates-io with rsproxy-sparse.
- GitHub is unreachable from this laptop, but EasyTier has 6 git deps in Cargo.lock plus a
  transitive one (WinDivert, pulled by windivert-rust). They were cloned via another machine
  into ~/src/git-mirrors/ and cargo (git-fetch-with-cli=true) is redirected with:
  git config --global url."file:///home/machang/src/git-mirrors/".insteadOf "https://github.com/EasyTier/"
- protoc: the build needs a host protoc WITH well-known-type includes. Maven Central
  protoc-3.20.1-linux-x86_64.exe + protobuf-java-3.20.1.jar (extract google/protobuf/*.proto)
  assembled as ~/tools/bin/protoc + ~/tools/include/. Export PROTOC=$HOME/tools/bin/protoc.
- ANDROID_NDK_ROOT=$HOME/android-sdk/ndk/28.2.13676358
- cd easytier-contrib/easytier-ffi && cargo ndk -t arm64-v8a build --release
- cd easytier-contrib/easytier-android-jni && cargo ndk -t arm64-v8a build --release
- Artifacts: target/aarch64-linux-android/release/libeasytier_ffi.so (16,787,368 B),
  libeasytier_android_jni.so (17,015,272 B) -> app/src/main/jniLibs/arm64-v8a/.

## APK
- Gradle 8.10.2 distribution came from mirrors.cloud.tencent.com (services.gradle.org is
  slow here); the wrapper dist in ~/.gradle was seeded from that zip, and
  gradle-wrapper.properties now points at the Tencent mirror too.
- JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64; ./gradlew assembleDebug
  -> app/build/outputs/apk/debug/app-debug.apk (16,774,835 B), BUILD SUCCESSFUL.
- AGP 8.7.3, Kotlin 2.0.21, compileSdk/targetSdk 35, buildTools 36.0.0, minSdk 26.

## Open points for the first phone test
- The VpnService starts the EasyTier instance first, then establishes the TUN and calls
  setTunFd. If the core expects a different order, the UI log + getLastError will show it.
- DCIM walking needs the media permissions; on Android 11+ scoped storage the walk may
  see 0 files until "all files access" is granted (the app logs a hint in that case).

## M4c addendum (2026-10-31)

- New dependencies (Maven Central / Google via the existing mirrors):
  androidx.media3 1.4.1 (exoplayer + ui) for tunnel-streamed playback and
  androidx.work:work-runtime-ktx 2.9.1 for constrained auto-backup.
  `gradle.properties`: android.useAndroidX=true (was false in the pure-
  framework M2/M4a app).
- Manifest: usesCleartextTraffic=true (dev account-service endpoint is
  plain HTTP over the mesh; production gets HTTPS), label 牛备， and the
  Library/Records/Player activities.
- Verified: `./gradlew assembleDebug` BUILD SUCCESSFUL;
  app-debug.apk 21,650,801 bytes. Not installed on a device this round.
- Feature map and dev limitations: docs/m4c-notes.md (P3).
