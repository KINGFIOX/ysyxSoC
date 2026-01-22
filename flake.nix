{
  description = "YSYX (一生一芯) 开发环境";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs =
    {
      self,
      nixpkgs,
      flake-utils,
    }:
    flake-utils.lib.eachDefaultSystem (
      system:
      let
        pkgs = import nixpkgs {
          inherit system;
          config.allowUnfree = true;
          overlays = [ (import ./overlay.nix) ];
        };

        # 创建 ccache 包装目录，通过 PATH prepend 方式使用 ccache
        ccacheWrapper = pkgs.runCommand "ccache-wrapper" { } ''
          mkdir -p $out/bin
          for prog in gcc g++ cc c++; do
            ln -s ${pkgs.ccache}/bin/ccache $out/bin/$prog
          done
        '';
      in
      {
        devShells.default = pkgs.mkShell {
          name = "ysyx-dev";

          packages = with pkgs; [
            # ========================
            # 基础构建工具
            # ========================
            gnumake
            cmake
            ninja
            pkg-config
            autoconf
            automake

            # ========================
            # C/C++ 工具链
            # ========================
            gcc
            gdb
            lldb
            clang-tools # clangd, clang-format 等
            bear

            # ========================
            # NPC (Chisel/Scala) 依赖
            # ========================
            jdk21
            scala_2_13
            circt
            metals # mill 不会自动下载
            mill_0_12_4

            # ========================
            # Verilog/仿真工具
            # ========================
            verilator
            gtkwave # 波形查看器 (可选)

            # ========================
            # 实用工具
            # ========================
            git
            python3
            ruff # lsp of python
            bear # 生成 compile_commands.json
            ccache
          ];

          shellHook = ''
            # 使用 ccache: 通过 PATH prepend 方式，让 gcc/g++ 调用自动走 ccache
            export PATH="${ccacheWrapper}/bin:$PATH"

            # Chisel/CIRCT: 使用系统的 firtool
            export CHISEL_FIRTOOL_PATH="${pkgs.circt}/bin"

            # 主机编译器 (确保 CC/CXX 是主机工具链)
            export CC=gcc
            export CXX=g++

            # Java 设置 (for Mill/Scala)
            export JAVA_HOME="${pkgs.jdk21}"
          '';

          # 确保 C/C++ 编译器能找到头文件和库
          hardeningDisable = [ "all" ];

          # NIX_CFLAGS_COMPILE 和 NIX_LDFLAGS 会自动设置
        };
      }
    );
}
