# Game Bub — MegaDrive / Genesis Core

A port of the MiSTer [Nuked-MD](https://github.com/MiSTer-devel/Genesis_MiSTer) MegaDrive/Genesis
core to the [Game Bub](https://github.com/) handheld, built with the Game Bub FPGA framework
(Chisel + CIRCT/firtool, with the MiSTer logic core (`md_board`) kept as an external
Verilog netlist in `fpga/verilog/megadrive/rtl/`).

## Repository layout

```
fpga/
  src/main/scala/.../megadrive/HandheldMegadrive.scala   # Chisel glue layer
  src/main/scala/megadrive/MegadriveCore.scala           # blackbox binding to main.v
  verilog/megadrive/rtl/                                # main.v + nuked-md + MiSTer helpers
  cores/megadrive/                                      # core.json / files.json / settings.json
  scripts/build_core.py                                 # edalize + Vivado driver
  build.mill / mill                                     # Chisel build (mill 1.1.7)
nix/                                                    # Vivado packaging for Nix (optional)
flake.nix                                               # devShell (JDK, mill, verilator, ...)
```

`HandheldTop` elaborates the core to SystemVerilog; `build_core.py` then drives Vivado to
produce the `.bit` / `.bit.hs` bitstream.

## Build: Chisel -> SystemVerilog (elaboration)

This step is pure open-source tooling (JDK + mill + firtool) and runs in CI:

```sh
cd fpga
./mill -i root.runMain platform.handheld.HandheldTop \
  net.gamebub.core.megadrive.HandheldMegadrive 4 --target-dir=build/mega
```

Output: `fpga/build/mega/*.sv` (incl. `HandheldMegadrive.sv`, `HandheldTop.sv`) and
`filelist.f`. The `main` module is emitted as an external blackbox and is provided by
`fpga/verilog/megadrive/rtl/main.v` at synthesis time.

## Build: full bitstream (Vivado)

Vivado is proprietary and **Linux/x86_64 only**. Use the Nix flake on a Linux machine
(see `docs/building.md` in the upstream repo), or install Vivado 2025.1 ML Standard
(Artix-7 support) yourself and point `XILINX_VIVADO` at it:

```sh
cd fpga
python3 scripts/build_core.py \
  --target gamebub_rev4 \
  --name megadrive \
  --core-class net.gamebub.core.megadrive.HandheldMegadrive \
  --build-root build/mega
```

This produces `fpga/build/mega/megadrive.bit` and the compressed `megadrive.bit.hs`.

## CI

`.github/workflows/build.yml` elaborates the core on every push to `main` and uploads the
generated SystemVerilog as the `megadrive-sv` artifact. The final Vivado synthesis is
defined as a `self-hosted` job (disabled by default) — enable it on a runner that has
Vivado 2025.1 installed.

## Licensing

- The Game Bub framework is under `LICENSE-CERN-OHL-S`.
- The MiSTer Nuked-MD logic core and helpers are under `LICENSE-GPL-V3`; see
  `fpga/verilog/megadrive/rtl/`.
