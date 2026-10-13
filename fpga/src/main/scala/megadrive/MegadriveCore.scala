// See LICENSE for license details.

package megadrive

import chisel3._

// Binding for the Game Bub HAL top-level `main` in
// `fpga/verilog/megadrive/rtl/main.v`. The port list must stay in sync with the
// Verilog module. This is the vendored MiSTer Nuked-MD logic core (`md_board`)
// plus video conditioning and pad shifting, with the cartridge bus exposed as
// HAL ports so the Chisel glue (`HandheldMegadrive`) can bridge it to the
// framework-owned SDRAM / SRAM.
//
// The ports are wrapped in a `FlatIO` bundle (mirroring `snes.SnesCore`) so
// that `core.io.<name>` is valid and the emitted Verilog port names match
// `main.v` exactly (no `io_` prefix).
class MegadriveCore extends ExtModule {
  override def desiredName: String = "main"

  val io = FlatIO(new Bundle {
    // ---- clocks ---------------------------------------------------------
    val RESET_N = Input(Bool())        // active-low core reset
    val MCLK2   = Input(Clock())       //  53.693 MHz (NTSC): md_board master clock / CLK_SYS
    val CLK_SYS = Input(Clock())       //  53.693 MHz: video_cond.clk (and pad sampling)

    // ---- region ---------------------------------------------------------
    val PAL = Input(Bool())            // 1 = PAL, 0 = NTSC
    val JAP = Input(Bool())            // 1 = Japanese, 0 = export

    // ---- video config (subset of MiSTer status bits) --------------------
    val VIDEO_BORDER  = Input(Bool())  // status[29]
    val VIDEO_H40CORR = Input(Bool())  // status[30]
    val VIDEO_BLEND   = Input(Bool())  // status[46]
    val VIDEO_CRAMDOT = Input(Bool())  // status[10] (0 = dots enabled)

    // ---- misc config ----------------------------------------------------
    val MD_MODE   = Input(Bool())      // status[5]  : 6-button mode
    val CART_MODE = Input(Bool())      // 0 = Mega Drive, 1 = Master System / Mark III

    // ---- cartridge bus (bridged by the Chisel side) --------------------
    // md_board outputs (core -> bridge):
    val CART_CS      = Output(Bool())
    val CART_OE      = Output(Bool())
    val CART_LWR     = Output(Bool())
    val CART_UWR     = Output(Bool())
    val CART_TIME    = Output(Bool())
    val CART_DMA     = Output(Bool())
    val CART_ADDR    = Output(UInt(23.W))
    val CART_DATA_WR = Output(UInt(16.W))
    // Bridge -> core
    val CART_DATA     = Input(UInt(16.W))
    val CART_DATA_EN  = Input(Bool())
    val CART_DTACK    = Input(Bool())
    val CART_M3_PAUSE = Input(Bool())

    // ---- joypad (MiSTer joy[11:0] layout, 2 players) -------------------
    // bits: 0=r 1=l 2=d 3=u 4=A 5=B 6=C 7=Start 8=Mode 9=X 10=Y 11=Z
    val PAD1 = Input(UInt(12.W))
    val PAD2 = Input(UInt(12.W))

    // ---- video out ------------------------------------------------------
    val R     = Output(UInt(8.W))
    val G     = Output(UInt(8.W))
    val B     = Output(UInt(8.W))
    val HSYNC = Output(Bool())
    val VSYNC = Output(Bool())
    val HBLANK = Output(Bool())
    val VBLANK = Output(Bool())
    val DOTCLK = Output(Bool())

    // ---- audio out (FM + PSG already mixed) ----------------------------
    val AUDIO_L = Output(UInt(16.W))
    val AUDIO_R = Output(UInt(16.W))
  })
}

object MegadriveCore {
  def apply(): MegadriveCore = {
    val core = Module(new MegadriveCore)
    core.suggestName("main")
    core
  }
}
