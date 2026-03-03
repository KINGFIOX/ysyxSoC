use std::path::PathBuf;

fn env_path(var: &str) -> PathBuf {
    PathBuf::from(std::env::var(var).unwrap_or_else(|_| panic!("{var} is not set")))
}

fn main() -> miette::Result<()> {
    let npc_home = env_path("NPC_HOME");
    let ffi_dir = npc_home.join("src/rust/ffi");

    let verilator_mdir = npc_home.join("build/obj-verilator");
    let verilator_root = env_path("VERILATOR_ROOT");

    let spike_src = env_path("SPIKE_HOME");
    let spike_build = spike_src.join("build");

    // autocxx only sees the tiny bridge headers
    let autocxx_inc: Vec<&PathBuf> = vec![&ffi_dir];
    let mut b = autocxx_build::Builder::new("src/rust/ffi/mod.rs", autocxx_inc.as_slice())
        .extra_clang_args(&["-std=c++17"])
        .build()?;
    b.flag("-std=c++17").compile("npc-autocxx");

    // Verilator bridge
    cc::Build::new()
        .cpp(true)
        .std("c++17")
        .file(ffi_dir.join("verilator_bridge.cc"))
        .include(&ffi_dir)
        .include(&verilator_mdir)
        .include(verilator_root.join("include"))
        .include(verilator_root.join("include/vltstd"))
        .compile("npc-verilator-bridge");

    // Spike bridge
    cc::Build::new()
        .cpp(true)
        .std("c++17")
        .file(ffi_dir.join("spike_bridge.cc"))
        .include(&ffi_dir)
        .include(&spike_src)
        .include(spike_src.join("riscv"))
        .include(spike_src.join("softfloat"))
        .include(&spike_build)
        .compile("npc-spike-bridge");

    // Verilator libraries
    println!(
        "cargo:rustc-link-arg={}",
        verilator_mdir.join("VNPCSoC__ALL.a").display()
    );
    println!(
        "cargo:rustc-link-search=native={}",
        verilator_mdir.display()
    );
    println!("cargo:rustc-link-lib=static=verilated");

    // Spike libraries
    println!("cargo:rustc-link-search=native={}", spike_build.display());
    println!("cargo:rustc-link-lib=dylib=riscv");
    println!("cargo:rustc-link-arg=-Wl,-rpath,{}", spike_build.display());

    // NVBoard bridge (optional)
    let nvboard_home = env_path("NVBOARD_HOME");
    cc::Build::new()
        .cpp(true)
        .std("c++17")
        .file(ffi_dir.join("nvboard_bridge.cc"))
        .include(&ffi_dir)
        .include(&verilator_mdir)
        .include(verilator_root.join("include"))
        .include(verilator_root.join("include/vltstd"))
        .include(nvboard_home.join("usr/include"))
        .compile("npc-nvboard-bridge");

    println!(
        "cargo:rustc-link-search=native={}",
        nvboard_home.join("build").display()
    );
    println!("cargo:rustc-link-lib=static=nvboard");
    println!("cargo:rustc-link-lib=SDL2");
    println!("cargo:rustc-link-lib=SDL2_image");
    println!("cargo:rustc-link-lib=SDL2_ttf");

    println!("cargo:rerun-if-changed=src/rust/ffi/nvboard_bridge.h");
    println!("cargo:rerun-if-changed=src/rust/ffi/nvboard_bridge.cc");

    println!("cargo:rerun-if-changed=build/obj-verilator/VNPCSoC__ALL.a");

    // System libraries
    println!("cargo:rustc-link-lib=stdc++");
    println!("cargo:rustc-link-lib=dl");
    println!("cargo:rustc-link-lib=pthread");

    println!("cargo:rerun-if-changed=src/rust/ffi/mod.rs");
    println!("cargo:rerun-if-changed=src/rust/ffi/verilator_bridge.h");
    println!("cargo:rerun-if-changed=src/rust/ffi/verilator_bridge.cc");
    println!("cargo:rerun-if-changed=src/rust/ffi/spike_bridge.h");
    println!("cargo:rerun-if-changed=src/rust/ffi/spike_bridge.cc");

    Ok(())
}
