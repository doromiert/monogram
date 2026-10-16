{
  description = "Monogram Android/XMPP development and local integration server";
  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    rust-overlay = {
      url = "github:oxalica/rust-overlay";
      inputs.nixpkgs.follows = "nixpkgs";
    };
    libomemo-c = {
      url = "github:dino/libomemo-c/1f0b37fee3ebefc46c32f882623481899a1ba568";
      flake = false;
    };
    android-nixpkgs = {
      url = "github:tadfisher/android-nixpkgs";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };
  outputs = { nixpkgs, android-nixpkgs, rust-overlay, libomemo-c, ... }:
    let
      system = "x86_64-linux";
      pkgs = import nixpkgs { inherit system; overlays = [ rust-overlay.overlays.default ]; config.allowUnfree = true; };
      rust = pkgs.rust-bin.stable."1.96.1".default.override {
        targets = [ "aarch64-linux-android" "armv7-linux-androideabi" "i686-linux-android" "x86_64-linux-android" ];
      };
      sdk = android-nixpkgs.sdk.${system} (p: with p; [
        cmdline-tools-19-0 platform-tools platforms-android-36 build-tools-36-0-0
        platforms-android-35 build-tools-35-0-0
        ndk-27-2-12479018 cmake-3-22-1
      ]);
    in {
      nixosModules.monogramHelper = import ./server/nixos-module.nix;
      devShells.${system} = {
      test = pkgs.mkShell {
        packages = [ pkgs.jdk11 pkgs.maven pkgs.prosody pkgs.openssl pkgs.python3 pkgs.cmake pkgs.ninja pkgs.pkg-config pkgs.check pkgs.gcc pkgs.protobufc pkgs.meson ];
        LIBOMEMO_C_SOURCE = libomemo-c.outPath;
        PROTOBUF_C_SOURCE = pkgs.protobufc.src;
        JAVA_HOME = "${pkgs.jdk11}/lib/openjdk";
        shellHook = ''
          export MAVEN_OPTS="-Dmaven.repo.local=$PWD/.m2/repository ''${MAVEN_OPTS:-}"
        '';
      };
      default = pkgs.mkShell {
        packages = [ pkgs.jdk21 pkgs.maven pkgs.prosody pkgs.openssl pkgs.python3 pkgs.git pkgs.go pkgs.ninja pkgs.yasm pkgs.nasm pkgs.perl pkgs.pkg-config pkgs.cmake pkgs.meson pkgs.gperf pkgs.zlib pkgs.autoconf pkgs.automake pkgs.libtool pkgs.check pkgs.protobufc rust sdk ];
        LIBOMEMO_C_SOURCE = libomemo-c.outPath;
        PROTOBUF_C_SOURCE = pkgs.protobufc.src;
        JAVA_HOME = "${pkgs.jdk21}/lib/openjdk";
        ANDROID_HOME = "${sdk}/share/android-sdk";
        ANDROID_SDK_ROOT = "${sdk}/share/android-sdk";
        shellHook = ''
          export GRADLE_USER_HOME="$PWD/.gradle"
          export MAVEN_OPTS="-Dmaven.repo.local=$PWD/.m2/repository ''${MAVEN_OPTS:-}"
          export GRADLE_OPTS="-Dorg.gradle.project.android.aapt2FromMavenOverride=$ANDROID_HOME/build-tools/36.0.0/aapt2 ''${GRADLE_OPTS:-}"
        '';
      };
      };
    };
}
