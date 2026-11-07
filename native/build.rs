//! The build script: bindings for the engine, and the link to it.
//!
//! `libghostty-vt.so` is somebody else's build, fetched and verified by
//! `tools/fetch-ghostty-vt.sh`. What this script needs from it is the header,
//! which describes the ABI exactly, and the library, which is linked as a
//! dynamic dependency so the APK carries one copy of the engine.
//!
//! The bindings are generated rather than written: a `#[repr(C)]` struct that
//! is right until the first upstream field moves is a struct nobody can trust,
//! and the headers are the only description that cannot drift.

use std::path::PathBuf;

fn main() {
    let manifest = PathBuf::from(std::env::var("CARGO_MANIFEST_DIR").unwrap());
    let out = PathBuf::from(std::env::var("OUT_DIR").unwrap());

    // Which ABI's copy of the engine to link. The fetched release keeps one
    // directory per ABI.
    let abi = std::env::var("DROSH_ABI").unwrap_or_else(|_| "arm64-v8a".to_string());

    let include = manifest.join("ghostty").join("include");
    let lib = manifest.join("ghostty").join("lib").join(&abi);

    let header = include.join("ghostty").join("vt.h");
    if !header.exists() {
        panic!(
            "missing {}. Run tools/fetch-ghostty-vt.sh first.",
            header.display()
        );
    }

    let mut builder = bindgen::Builder::default()
        .header(header.to_string_lossy())
        .clang_arg(format!("-I{}", include.display()));

    // A target triple, because libclang defaults to none and without one glibc's
    // `gnu/stubs.h` takes its 32-bit branch — it wants `stubs-32.h`, which is
    // not installed, and every include after it fails. The pipeline is the
    // host's; the target the library is *built* for is Cargo's, not bindgen's.
    let host_triple = match std::env::consts::ARCH {
        "aarch64" => "aarch64-unknown-linux-gnu",
        "arm" => "arm-unknown-linux-gnueabihf",
        _ => "x86_64-unknown-linux-gnu",
    };
    builder = builder.clang_arg(format!("--target={host_triple}"));

    // libclang does not know Debian's multiarch layout, so glibc's own headers
    // (`bits/libc-header-start.h`) are not found without the architecture's
    // include directory. The engine's headers include <stdint.h> and friends,
    // which land there.
    for candidate in [
        "/usr/include/x86_64-linux-gnu",
        "/usr/include/aarch64-linux-gnu",
        "/usr/include/arm-linux-gnueabihf",
    ] {
        if std::path::Path::new(candidate).is_dir() {
            builder = builder.clang_arg(format!("-I{candidate}"));
            break;
        }
    }

    builder
        // Only the engine's own surface: the rest of the C world it includes is
        // the platform's, and the platform's declarations are the libc crate's.
        .allowlist_function("ghostty_.*")
        .allowlist_type("Ghostty.*")
        .allowlist_var("GHOSTTY_.*")
        // C enums as constants, not as Rust enums.
        //
        // bindgen's default is to invent a Rust enum per C enum, which renames
        // every variant and types every parameter that way. The engine's enums
        // are plain integers in C — a value can be one the compiler never saw —
        // and the constants keep the names the headers use, so the code reads
        // like the API it calls.
        .default_enum_style(bindgen::EnumVariation::Consts)
        // Layout assertions turn a moved field into a compile error instead of
        // a struct that reads the wrong bytes.
        .layout_tests(true)
        .derive_default(true)
        .generate()
        .expect("the engine's headers could not be translated")
        .write_to_file(out.join("ghostty.rs"))
        .expect("the bindings could not be written");

    println!("cargo:rerun-if-changed={}", header.display());
    println!("cargo:rerun-if-changed=build.rs");

    // A dynamic dependency, not a static one: the APK ships `libghostty-vt.so`
    // beside this library and the loader resolves it by soname.
    println!("cargo:rustc-link-search=native={}", lib.display());
    println!("cargo:rustc-link-lib=dylib=ghostty-vt");

    // Android's log is called from the JNI layer; a build for the host has none.
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("android") {
        println!("cargo:rustc-link-lib=dylib=log");
    }
}