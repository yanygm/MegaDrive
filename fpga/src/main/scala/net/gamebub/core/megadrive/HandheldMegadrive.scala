// See LICENSE for license details.

package net.gamebub.core.megadrive

import chisel3._
import chisel3.util._
import chisel3.experimental._

import net.gamebub.framework._
import net.gamebub.framework.interface._
import lib.mem._
import lib.mem.cache.LineReadCache
import lib.mem.sdram.BurstSdramController
import lib.mem.sram.AsyncSramController
import lib.util.ButtonFilter
import lib.video.ColorCorrection
import net.gamebub.core.snes.SramHostAdapter
import megadrive.MegadriveCore
import xilinx.{BUFG, BUFGCE, MMCM}

// Initial port of the MiSTer Nuked-MD MegaDrive/Genesis core. The MiSTer logic
// core (`md_board`) lives in the Verilog HAL top `main` (fpga/verilog/megadrive/
// rtl/main.v) bound by `MegadriveCore`. This module bridges its cartridge bus to
// the framework-owned SDRAM (ROM) and SRAM (backup / EEPROM RAM), captures the
// conditioned video and routes audio / joypad, in the same way the SNES port
// bridges its ROM_*/BSRAM_*/WRAM_* buses.
//
// This is an initial, unverified port. The cartridge<->SDRAM bridge, DTACK
// timing, the video-frame capture, the clock constraints and the SDRAM read-
// capture phase require iteration on real hardware (see the porting notes).
object HandheldMegadrive {
  val RomMaxBytes     = 0xA00000      // 10 MB addressable cartridge space
  val SaveRamMaxBytes = 0x10000       // 64 KB backup RAM window

  // Clock tree: 50 MHz in -> 1181.25 MHz VCO (x23.625). Outputs:
  //   out0: /22 =  53.693175 MHz  MCLK2 (md_board master clock + CLK_SYS)
  //   out1: /11 = 107.386350 MHz  SDRAM (2x MCLK2)
  //   out2: display
  //   out3: /7  = 168.75 MHz      host SPI (> 160 MHz)
  val MmcmVcoHz      = 50_000_000.0 * 23.625
  val Mclk2Divider   = 22
  val SdramDivider   = 11
  val SpiDivider     = 7
  val ClockSystemHz  = (MmcmVcoHz / Mclk2Divider).round.toInt    // ~53.69 MHz
  val ClockSdramHz   = (MmcmVcoHz / SdramDivider).round.toInt    // ~107.39 MHz
  val ClockSpiHz     = (MmcmVcoHz / SpiDivider).round.toInt      // 168.75 MHz
  val FramePeriod    = 1.0 / 60.0
}

class HandheldMegadrive extends Module with Core {
  import HandheldMegadrive._

  val displayDivider = (MmcmVcoHz / ClocksV0.getClockDisplayHz(FramePeriod)._1).floor.toInt

  val io = IO(new Bundle {
    val clocks = new ClocksV0(
      clockSystemHz  = ClockSystemHz,
      clockDisplayHz = (MmcmVcoHz / displayDivider).toInt,
      clockSpiHz     = ClockSpiHz,
    )
    val video = new VideoV0(
      videoWidth   = 320,
      videoHeight  = 240,
      colorDepthR  = 5,
      colorDepthG  = 5,
      colorDepthB  = 5,
      framePeriod  = FramePeriod,
    )
    val videoFilter = new VideoFilterBasicV0(
      colorInDepthR = 5, colorInDepthG = 5, colorInDepthB = 5, latency = 3,
    )
    val audio = new AudioV0()
    val host = new HostV0()
    val input = new InputV0()
    val sram = new SramV0()
    val sdram = new SdramV0()
  })

  // ----- MMCM / clock tree -------------------------------------------------
  val mmcm = Module(new MMCM(
    clockInHz   = 50_000_000,
    divide      = 1,
    multiply    = 23.625,
    clockOutConfig = Seq(
      MMCM.ClockOut(Mclk2Divider),                   // MCLK2 / CLK_SYS
      MMCM.ClockOut(SdramDivider, phase = 0.0),      // SDRAM (2x MCLK2)
      MMCM.ClockOut(displayDivider),                 // display
      MMCM.ClockOut(SpiDivider),                     // host SPI
    ),
  ))
  mmcm.io.clockIn   := io.clocks.clockIn50M
  mmcm.io.powerDown := false.B
  io.clocks.locked  := mmcm.io.locked

  val mclk2     = BUFG(mmcm.io.clockOuts(0))
  val clockSdram = mmcm.io.clockOuts(1)
  io.clocks.clockOutSystem  := mclk2
  io.clocks.clockOutDisplay := mmcm.io.clockOuts(2)
  io.clocks.clockOutSpi     := mmcm.io.clockOuts(3)

  // The MD core runs (its MCLK2 is gated) only while focused or being reset.
  val coreRun   = Wire(Bool())
  val coreClock = BUFGCE(mclk2, coreRun)
  val tick      = RegNext(coreRun, false.B)

  // ----- core instance + reset -------------------------------------------
  val core = Module(new MegadriveCore)
  core.io.MCLK2        := coreClock
  core.io.CLK_SYS      := mclk2
  core.io.CART_M3_PAUSE := false.B

  val regCoreSetup     = RegInit(false.B)
  val regCoreReset     = RegInit(true.B)
  val regCoreFocus     = RegInit(false.B)
  val regCoreResetOnce = RegInit(false.B)
  regCoreResetOnce := false.B
  val hostReset = reset.asBool || regCoreReset || regCoreResetOnce

  val coreResetN = withClockAndReset(coreClock, hostReset) {
    val resetN = RegInit(false.B)
    val cnt    = RegInit(0.U(8.W))
    when (hostReset) {
      resetN := false.B
      cnt    := 0.U
    } .elsewhen (!resetN) {
      cnt := cnt + 1.U
      when (cnt === 0xff.U) { resetN := true.B }
    }
    resetN
  }
  core.io.RESET_N := coreResetN

  // ----- configuration registers -----------------------------------------
  // Layout (register 0): bit0 regionAuto, bit1 PAL, bit2 JAP, bit3 BLEND,
  // bit4 MD_MODE (6btn), bit5 CART_MODE (0=MD,1=SMS), bit6 BORDER, bit7 CRAMDOT.
  val configReg = RegInit(0.U(32.W))
  val regionAuto = configReg(0)
  val palManual  = configReg(1)
  val japManual  = configReg(2)
  val blend      = configReg(3)
  val mdMode     = configReg(4)
  val cartMode   = configReg(5)
  val borderEn   = configReg(6)
  val cramdotEn  = !configReg(7)
  core.io.PAL           := Mux(regionAuto.asBool, false.B, palManual.asBool)
  core.io.JAP           := japManual.asBool
  core.io.VIDEO_BORDER  := borderEn.asBool
  core.io.VIDEO_H40CORR := false.B
  core.io.VIDEO_BLEND   := blend.asBool
  core.io.VIDEO_CRAMDOT := cramdotEn.asBool
  core.io.MD_MODE       := mdMode.asBool
  core.io.CART_MODE     := cartMode.asBool

  // ----- host memory map (file loads + registers) ------------------------
  val sdramHost = Wire(new MemoryInterface(addressWidth = 25, dataWidth = 32))
  val sramHost  = Wire(new MemoryInterface(addressWidth = 19, dataWidth = 32))
  val regCommandHost = Reg(Vec(2, UInt(32.W)))
  val registerInterface = Wire(new MemoryInterface(addressWidth = 16, dataWidth = 32))
  val commandInterface  = Wire(new MemoryInterface(addressWidth = 16, dataWidth = 32))
  val colorCorrectInterface = Wire(new MemoryInterface(addressWidth = 16, dataWidth = 16))
  registerInterface <> RegisterMap(addressWidth = 16, dataWidth = 32, entries = Seq(
    0x0000 -> RegisterMap.Entry.rw(configReg),
    0x2000 -> RegisterMap.Entry.w(regCoreResetOnce),
  ))
  commandInterface <> RegisterMap(addressWidth = 16, dataWidth = 32, entries = Seq(
    0x0000 -> RegisterMap.Entry.rw(regCommandHost(0)),
    0x0004 -> RegisterMap.Entry.rw(regCommandHost(1)),
  ))
  val memoryMap = MemoryMap(addressWidth = 32, dataWidth = 32, entries = Seq(
    0x0.U(4.W)  -> registerInterface,
    0x3.U(4.W)  -> sdramHost,
    0x4.U(4.W)  -> sramHost,
    0x5.U(4.W)  -> colorCorrectInterface,
    0xF0.U(8.W) -> commandInterface,
  ))
  io.host.mem.unsafe :<>= memoryMap.unsafe
  memoryMap.writeStrobe := "b1111".U

  // ----- host command handling -------------------------------------------
  io.host.commandCore.request := false.B
  io.host.commandHost.busy   := false.B
  io.host.commandHost.error  := false.B
  val commandHandled = RegInit(false.B)
  when (io.host.commandHost.request) {
    val command = regCommandHost(0)(15, 0)
    when (!commandHandled) {
      commandHandled := true.B
      when (command === HostV0.CommandGetStatus.U) {
        regCommandHost(0) := Mux(regCoreSetup,
          Mux(regCoreReset, HostV0.StatusCoreHalt.U, HostV0.StatusCoreRun.U),
          HostV0.StatusSetup.U)
      } .elsewhen (command === HostV0.CommandSetupComplete.U) {
        regCoreSetup := true.B
      } .elsewhen (command === HostV0.CommandCoreRun.U) {
        regCoreReset := false.B
      } .elsewhen (command === HostV0.CommandCoreHalt.U) {
        regCoreReset := true.B
      } .elsewhen (command === HostV0.CommandNotifyFocus.U) {
        regCoreFocus := regCommandHost(1)(0)
      }
    }
  } .otherwise {
    commandHandled := false.B
  }
  io.host.commandHost.done := commandHandled

  coreRun := regCoreFocus || regCoreReset || regCoreResetOnce

  // ========================================================================
  //  SDRAM (ROM) controller + ROM read cache.
  //  The controller runs in the SDRAM clock domain (clockSdram); the cache and
  //  arbiter run in the default 50 MHz host domain, crossed by the CDC.
  // ========================================================================
  val sdramArbiter = Module(new PipelineMemoryArbiter(addressWidth = 25, dataWidth = 32, n = 2))
  val bridge = Module(new PipelineInterfaceBridge(addressWidth = 25, dataWidth = 32))
  bridge.io.source <> sdramHost
  bridge.io.dest   <> sdramArbiter.io.initiator(0)
  val romCache = Module(new LineReadCache(addressWidth = 25, dataWidth = 32,
    numLines = 2048, wordsPerLine = 1, prefetch = false, ways = 1))
  romCache.io.eagerIssue := false.B
  sdramArbiter.io.initiator(1) <> romCache.io.out

  withClock(clockSdram) {
    val config = BurstSdramController.Config(
      clockFrequency = ClockSdramHz,
      accessLength   = 2,
      timeRsc = (2 * 1_000_000_000) / ClockSdramHz,
      timeWr  = (2 * 1_000_000_000) / ClockSdramHz,
      enableBurst = true,
    )
    val controller = Module(new BurstSdramController(config))
    val cdc = Module(new PipelineMemoryBurstCdc(addressWidth = 25, dataWidth = 32,
      addressBurstIncrement = 4, enablePrefetch = true))
    cdc.io.slowClock := clock
    cdc.io.initiator <> sdramArbiter.io.target
    cdc.io.target    <> controller.io.mem

    io.sdram.clock   := clockSdram
    io.sdram.cke     := controller.io.signals.cke
    io.sdram.cs      := controller.io.signals.cs
    io.sdram.ras     := controller.io.signals.ras
    io.sdram.cas     := controller.io.signals.cas
    io.sdram.we      := controller.io.signals.we
    io.sdram.dqm     := controller.io.signals.dqm
    io.sdram.bank    := controller.io.signals.bank
    io.sdram.address := controller.io.signals.address
    controller.io.signals.dataIn := io.sdram.dataIn
    io.sdram.dataOut := controller.io.signals.dataOut
    io.sdram.dataDir := controller.io.signals.dataDir
  }

  // ========================================================================
  //  Cartridge bridge (default 50 MHz domain). md_board's CART_* bus runs in
  //  the MCLK2 domain, so those signals are synchronized across. DTACK / read
  //  data are synchronized back into MCLK2 before reaching md_board.
  // ========================================================================
  def syncBit(sig: Bool): Bool = { val r = RegNext(sig); RegNext(r) }
  def syncU[T <: Bits](sig: T): T = { val r = RegNext(sig); RegNext(r).asTypeOf(sig) }

  val cartCs   = syncBit(core.io.CART_CS)
  val cartOe   = syncBit(core.io.CART_OE)
  val cartLwr  = syncBit(core.io.CART_LWR)
  val cartUwr  = syncBit(core.io.CART_UWR)
  val cartAddr = syncU(core.io.CART_ADDR)
  val cartDwr  = syncU(core.io.CART_DATA_WR)

  val cartAccess = cartCs && (cartOe || cartLwr || cartUwr)
  val accessPending = RegInit(false.B)
  val cartAddrReg  = RegEnable(cartAddr, cartAccess)
  val cartWriteReg = RegEnable(!cartOe,  cartAccess)
  val cartLwrReg   = RegEnable(cartLwr,  cartAccess)
  val cartUwrReg   = RegEnable(cartUwr,  cartAccess)
  val cartDwrReg   = RegEnable(cartDwr,  cartAccess)

  // 0x200000-0x3FFFFF is the backup / EEPROM RAM window (bit22=0, bit21=1).
  val isSram = cartAddrReg(22) === 0.U && cartAddrReg(21) === 1.U

  // --- ROM request (byte address into the cache; it returns a 32-bit word) ---
  romCache.io.in.enable      := accessPending && !isSram
  romCache.io.in.address     := Cat(0.U(2.W), cartAddrReg)
  romCache.io.in.isWrite     := false.B
  romCache.io.in.writeStrobe := 0.U(4.W)
  romCache.io.in.dataWrite   := 0.U(32.W)
  val romWord = Mux(cartAddrReg(1),
    romCache.io.in.dataRead(31, 16), romCache.io.in.dataRead(15, 0))
  val romReady = romCache.io.in.ready && accessPending && !isSram

  // --- SRAM (backup / EEPROM) request --------------------------------------
  val sramHostAdapter = Module(new SramHostAdapter)
  sramHostAdapter.io.host <> sramHost
  val sramArbiter = Module(new MemoryArbiter(addressWidth = 18, dataWidth = 16, n = 2))
  sramArbiter.io.initiator(0) <> sramHostAdapter.io.sram
  val sramReq = Wire(Flipped(new MemoryInterface(addressWidth = 18, dataWidth = 16)))
  // Backup RAM is byte-addressed by the 68k but stored in 16-bit SRAM words,
  // so the cartridge byte address maps to SRAM word address (cartAddr >> 1).
  // (Mirrors the SNES port's bsramReq.address := romAddressReg(16, 1).)
  sramReq.address   := Cat(0.U(3.W), cartAddrReg(15, 1))
  sramReq.dataWrite := cartDwrReg
  sramReq.write     := accessPending && isSram && cartWriteReg
  sramReq.enable    := accessPending && isSram
  sramReq.writeStrobe := MuxCase(0.U(2.W), Seq(
    (cartLwrReg && cartUwrReg) -> 3.U(2.W),
    cartLwrReg                  -> 1.U(2.W),
    cartUwrReg                  -> 2.U(2.W)))
  sramArbiter.io.initiator(1) <> sramReq

  val sramController = Module(new AsyncSramController(addressWidth = 18, dataWidth = 16, registeredOutputs = true))
  sramController.io.mem <> sramArbiter.io.target
  io.sram.ceN         := false.B
  io.sram.weN         := sramController.io.signals.weN
  io.sram.oeN         := sramController.io.signals.oeN
  io.sram.writeMaskN  := sramController.io.signals.writeMaskN
  io.sram.address     := sramController.io.signals.address
  sramController.io.signals.dataIn := io.sram.dataIn
  io.sram.dataOut     := sramController.io.signals.dataOut
  io.sram.dataDir     := sramController.io.signals.dataDir
  val sramData  = sramController.io.mem.dataRead
  val sramReady = sramController.io.mem.done && accessPending && isSram

  val accessDone = Mux(isSram, sramReady, romReady)
  when (cartAccess && !accessPending) { accessPending := true.B }
  when (accessDone) { accessPending := false.B }

  // Latch the result so the MCLK2-side DTACK handshake is robust across the
  // clock boundary (the result level is held long enough for the faster MCLK2
  // to capture it, then cleared).
  val outDataLatch = RegEnable(Mux(isSram, sramData, romWord), accessDone)
  val resultValid  = RegInit(false.B)
  val clearCnt     = RegInit(0.U(3.W))
  when (accessDone) { resultValid := true.B; clearCnt := 0.U }
  when (resultValid) {
    clearCnt := clearCnt + 1.U
    when (clearCnt === 7.U) { resultValid := false.B }
  }

  // --- return DTACK / data to md_board (entirely in the MCLK2 domain) ------
  val dtackLatch = Wire(Bool())
  val dataLatch  = Wire(UInt(16.W))
  withClock(mclk2) {
    val mclkValid = RegNext(RegNext(resultValid))
    val mclkData  = RegNext(RegNext(outDataLatch))
    val dtack = RegInit(false.B)
    val dlat  = Reg(UInt(16.W))
    when (mclkValid) { dtack := true.B; dlat := mclkData }
    // Clear the held DTACK on the rising edge of a new cartridge access.
    val cartActiveMclk = core.io.CART_CS && (core.io.CART_OE || core.io.CART_LWR || core.io.CART_UWR)
    val edge = cartActiveMclk && !RegNext(cartActiveMclk)
    when (edge) { dtack := false.B }
    dtackLatch := dtack
    dataLatch  := dlat
  }
  core.io.CART_DTACK    := dtackLatch
  core.io.CART_DATA     := dataLatch
  core.io.CART_DATA_EN  := dtackLatch

  // ========================================================================
  //  Video capture (DOTCLK = conditioned pixel clock, MCLK2 domain).
  // ========================================================================
  val OutputWidth  = 320
  val OutputHeight = 240
  val pixelR = RegEnable(core.io.R, core.io.DOTCLK)
  val pixelG = RegEnable(core.io.G, core.io.DOTCLK)
  val pixelB = RegEnable(core.io.B, core.io.DOTCLK)
  val pixelOld   = RegNext(core.io.DOTCLK, false.B)
  val pixelEdge  = core.io.DOTCLK && !pixelOld
  val xCnt = RegInit(0.U(10.W))
  val yCnt = RegInit(0.U(10.W))
  when (pixelEdge) {
    when (xCnt === (OutputWidth - 1).U || core.io.HBLANK) { xCnt := 0.U }
    .otherwise { xCnt := xCnt + 1.U }
    when (core.io.VBLANK) { yCnt := 0.U }
    .elsewhen (xCnt === (OutputWidth - 1).U) { yCnt := yCnt + 1.U }
  }
  io.video.dataEnable := pixelEdge && yCnt < OutputHeight.U && xCnt < OutputWidth.U
  io.video.data.r := pixelR(7, 3)
  io.video.data.g := pixelG(7, 3)
  io.video.data.b := pixelB(7, 3)
  io.video.hblank := core.io.HBLANK
  io.video.vblank := core.io.VBLANK

  // ========================================================================
  //  Audio (FM + PSG already mixed by md_board).
  // ========================================================================
  io.audio.left  := RegNext(core.io.AUDIO_L).asSInt
  io.audio.right := RegNext(core.io.AUDIO_R).asSInt

  // ========================================================================
  //  Joypad (framework Buttons -> MiSTer joy[11:0]; bits:
  //  0=r 1=l 2=d 3=u 4=A 5=B 6=C 7=Start 8=Mode 9=X 10=Y 11=Z)
  // ========================================================================
  val buttonFilter = Module(new ButtonFilter(new InputV0.Buttons))
  buttonFilter.io.enable := true.B
  buttonFilter.io.input  := io.input.buttons
  val b = buttonFilter.io.output
  val pad1 = Cat(
    b.r,      // bit11 Z
    b.l,      // bit10 Y
    b.y,      // bit9  X
    b.select, // bit8  Mode
    b.start,  // bit7  Start
    b.x,      // bit6  C
    b.b,      // bit5  B
    b.a,      // bit4  A
    b.up,     // bit3  Up
    b.down,   // bit2  Down
    b.left,   // bit1  Left
    b.right,  // bit0  Right
  )
  core.io.PAD1 := RegNext(pad1)
  core.io.PAD2 := RegNext(pad1)

  // ----- video filter (color correction) ---------------------------------
  // The MD core outputs 15-bit RGB; colors pass through (5-bit channels
  // expanded to 8) until a host loads a correction table (the firmware loads
  // none for an external core), matching the SNES port's behavior.
  ColorCorrection.setup(
    clock = clock,
    reset = reset,
    videoFilter = io.videoFilter,
    memInterface = colorCorrectInterface,
    passThroughUntilLoaded = true,
  )
}
