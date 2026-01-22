final: prev: {
  mill_0_12_4 = prev.mill.overrideAttrs (oldAttrs: rec {
    version = "0.12.4";
    src = prev.fetchurl {
      url = "https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/${version}/mill-dist-${version}-assembly.jar";
      hash = "sha256-+wSyPh2me1ud5xfkaAPhpMY8m+u+xlecYphikLKmBiE=";
    };
  });
}
