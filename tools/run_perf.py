#!/usr/bin/env python3
"""Run NPC performance benchmarks and collect JSON reports.

Usage:
    npc/tools/run_perf.py [output_dir] [-b BENCH [BENCH ...]] [--no-build]

The script assumes the Nix/direnv shell has been activated (AM_HOME and
NPC_HOME pointing at this workspace).  It builds the npc simulator once
(via ``make -C $NPC_HOME release``) and then runs the selected benchmarks,
writing one ``<name>.json`` per run into the output directory.

Available benchmark keys (default runs all three):

    microbench   — MicroBench at ``test`` scale (fast RTL turnaround)
    dhrystone    — Dhrystone (NUMBER_OF_RUNS reduced to 2000)
    coremark     — CoreMark (ITERATIONS reduced to 20)
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path


WORKSPACE = Path(__file__).resolve().parents[2]
DEFAULT_AM_HOME = WORKSPACE / "abstract-machine"
DEFAULT_NPC_HOME = WORKSPACE / "npc"
AM_KERNELS = WORKSPACE / "am-kernels"
DEFAULT_OUT_DIR = WORKSPACE / "perf-reports"


@dataclass(frozen=True)
class Bench:
    key: str          # CLI identifier
    path: Path        # Benchmark Makefile directory
    perf_name: str    # Name embedded in JSON / filename
    mainargs: str = ""


BENCHMARKS: dict[str, Bench] = {
    b.key: b
    for b in (
        Bench("microbench",
              AM_KERNELS / "benchmarks" / "microbench",
              "microbench-test",
              "test"),
        Bench("dhrystone",
              AM_KERNELS / "benchmarks" / "dhrystone",
              "dhrystone"),
        Bench("coremark",
              AM_KERNELS / "benchmarks" / "coremark",
              "coremark"),
    )
}


def run(cmd: list[str], *, cwd: Path | None = None, env: dict | None = None,
        quiet: bool = False) -> None:
    """Run ``cmd`` and forward its output, exiting on failure."""
    printable = " ".join(str(c) for c in cmd)
    if not quiet:
        print(f"$ {printable}", flush=True)
    try:
        subprocess.run(cmd, cwd=cwd, env=env, check=True)
    except subprocess.CalledProcessError as exc:
        sys.exit(f"command failed ({exc.returncode}): {printable}")


def build_simulator(npc_home: Path) -> None:
    print("== Building NPC simulator ==", flush=True)
    run(["make", "-C", str(npc_home), "release"], quiet=True)


def run_bench(bench: Bench, *, am_home: Path, npc_home: Path,
              out_dir: Path) -> Path:
    print(f"== Running {bench.perf_name} ==", flush=True)

    subprocess.run(["make", "-C", str(bench.path), "clean"],
                   check=False,
                   stdout=subprocess.DEVNULL,
                   stderr=subprocess.DEVNULL)

    cmd = [
        "make", "-C", str(bench.path),
        f"AM_HOME={am_home}",
        f"NPC_HOME={npc_home}",
        "ARCH=riscv64-npc",
        f"PERF_DIR={out_dir}",
        f"PERF_NAME={bench.perf_name}",
    ]
    if bench.mainargs:
        cmd.append(f"mainargs={bench.mainargs}")
    cmd.append("perf")
    run(cmd)

    return out_dir / f"{bench.perf_name}.json"


def summarize(reports: list[Path]) -> None:
    print("\n== Summary ==", flush=True)
    for path in reports:
        if not path.is_file():
            print(f"  [missing] {path.name}")
            continue
        try:
            data = json.loads(path.read_text())
            ipc = data.get("ipc", float("nan"))
            cycles = data.get("cycles", 0)
            print(f"  {path.name:32s}  cycles={cycles:>12}  IPC={ipc:.4f}")
        except json.JSONDecodeError:
            print(f"  [unreadable] {path.name}")


def parse_args(argv: list[str]) -> argparse.Namespace:
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0],
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("out_dir", nargs="?", default=str(DEFAULT_OUT_DIR),
                   help=f"output directory (default: {DEFAULT_OUT_DIR})")
    p.add_argument("-b", "--bench", action="append", choices=sorted(BENCHMARKS),
                   help="benchmark(s) to run; repeat to select multiple. "
                        "Default: run all.")
    p.add_argument("--no-build", action="store_true",
                   help="skip rebuilding the NPC simulator")
    return p.parse_args(argv)


def main(argv: list[str]) -> int:
    args = parse_args(argv)

    am_home = Path(os.environ.get("AM_HOME", DEFAULT_AM_HOME)).resolve()
    npc_home = Path(os.environ.get("NPC_HOME", DEFAULT_NPC_HOME)).resolve()
    out_dir = Path(args.out_dir).resolve()
    out_dir.mkdir(parents=True, exist_ok=True)

    selected_keys = args.bench or list(BENCHMARKS)
    selected = [BENCHMARKS[k] for k in selected_keys]

    if not args.no_build:
        build_simulator(npc_home)

    reports = [run_bench(b, am_home=am_home, npc_home=npc_home,
                         out_dir=out_dir)
               for b in selected]
    summarize(reports)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
