{
  description = "Monogram Android/XMPP development and local integration server";
  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    android-nixpkgs = {
      url = "github:tadfisher/android-nixpkgs";
      inputs.nixpkgs.follows = "nixpkgs";
    };
  };
  outputs = { nixpkgs, android-nixpkgs, ... }:
    let
      system = "x86_64-linux";
      pkgs = import nixpkgs { inherit system; config.allowUnfree = true; };
      sdk = android-nixpkgs.sdk.${system} (p: with p; [
        cmdline-tools-19-0 platform-tools platforms-android-33 build-tools-33-0-0
        (ndk-21-4-7075529.overrideAttrs (old: {
          # The unused LLDB debugger needs Python 2; cross-compilers do not.
          autoPatchelfIgnoreMissingDeps = (old.autoPatchelfIgnoreMissingDeps or []) ++ [ "libpython2.7.so.1.0" ];
        })) cmake-3-10-2-4988404
      ]);
    in {
      nixosModules.monogramHelper = import ./server/nixos-module.nix;
      devShells.${system} = {
      test = pkgs.mkShell {
        packages = [ pkgs.jdk11 pkgs.maven pkgs.prosody pkgs.openssl pkgs.python3 ];
        JAVA_HOME = "${pkgs.jdk11}/lib/openjdk";
        shellHook = ''
          export MAVEN_OPTS="-Dmaven.repo.local=$PWD/.m2/repository ''${MAVEN_OPTS:-}"
        '';
      };
      default = pkgs.mkShell {
        packages = [ pkgs.jdk11 pkgs.maven pkgs.prosody pkgs.openssl pkgs.python3 pkgs.git pkgs.go pkgs.ninja pkgs.yasm pkgs.nasm pkgs.perl pkgs.pkg-config sdk ];
        JAVA_HOME = "${pkgs.jdk11}/lib/openjdk";
        ANDROID_HOME = "${sdk}/share/android-sdk";
        ANDROID_SDK_ROOT = "${sdk}/share/android-sdk";
        shellHook = ''
          export GRADLE_USER_HOME="$PWD/.gradle"
          export MAVEN_OPTS="-Dmaven.repo.local=$PWD/.m2/repository ''${MAVEN_OPTS:-}"
          export GRADLE_OPTS="-Dorg.gradle.project.android.aapt2FromMavenOverride=$ANDROID_HOME/build-tools/33.0.0/aapt2 ''${GRADLE_OPTS:-}"
        '';
      };
      };
    };
}
