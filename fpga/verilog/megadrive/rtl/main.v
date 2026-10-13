//============================================================================
//  Game Bub HAL top-level for the MiSTer Nuked-MD MegaDrive/Genesis core.
//
//  This module replaces the MiSTer `emu` top (MegaDrive.sv) and its HPS
//  framework (hps_io / video_mixer / video_freak / cartridge / sdram). It
//  instantiates only the vendored logic core (md_board) together with the
//  video conditioner (video_cond) and the pad shifter (md_io), and exposes a
//  Game-Bub-friendly port list that the Chisel wrapper `megadrive.MegadriveCore`
//  (desiredName "main") binds to.
//
//  Unlike the MiSTer top, the cartridge / SDRAM bridge is NOT here: the
//  framework owns the physical SDRAM and SRAM, so the 68k cartridge bus
//  (CART_*) is exposed as HAL ports and the Chisel side (HandheldMegadrive)
//  implements the bridge (ROM from SDRAM, backup/EEPROM RAM to SRAM, DTACK
//  generation). This mirrors how the SNES port exposes ROM_*/BSRAM_*/WRAM_*.
//
//  The 68k / Z80 work RAM is small (32 KB / 8 KB) and kept as internal block
//  RAM here.
//
//  NOTE: initial port. The cartridge<->SDRAM bridge, DTACK timing and clock
//  domains follow the MiSTer design but are unverified on hardware; see the
//  porting notes.
//============================================================================

module main (
	// ---- clocks ---------------------------------------------------------
	input  RESET_N,        // active-low core reset
	input  MCLK2,          //  53.693 MHz (NTSC): md_board master clock / CLK_SYS
	input  CLK_SYS,        //  53.693 MHz: video_cond.clk (and pad sampling)

	// ---- region ---------------------------------------------------------
	input  PAL,            // 1 = PAL, 0 = NTSC
	input  JAP,            // 1 = Japanese, 0 = export

	// ---- video config (subset of MiSTer status bits) --------------------
	input  VIDEO_BORDER,   // status[29]
	input  VIDEO_H40CORR,  // status[30]
	input  VIDEO_BLEND,    // status[46]
	input  VIDEO_CRAMDOT,  // status[10] (0 = dots enabled)

	// ---- misc config ----------------------------------------------------
	input  MD_MODE,        // status[5]  : 6-button mode
	input  CART_MODE,      // 0 = Mega Drive, 1 = Master System / Mark III

	// ---- cartridge bus (bridged by the Chisel side) --------------------
	// md_board outputs (core -> bridge):
	output        CART_CS,
	output        CART_OE,
	output        CART_LWR,
	output        CART_UWR,
	output        CART_TIME,
	output        CART_DMA,
	output [22:0] CART_ADDR,
	output [15:0] CART_DATA_WR,
	// bridge outputs (bridge -> core):
	input  [15:0] CART_DATA,
	input         CART_DATA_EN,
	input         CART_DTACK,
	input         CART_M3_PAUSE,

	// ---- joypad (MiSTer joy[11:0] layout, 2 players) -------------------
	// bits: 0=r 1=l 2=d 3=u 4=A 5=B 6=C 7=Start 8=Mode 9=X 10=Y 11=Z
	input  [11:0] PAD1,
	input  [11:0] PAD2,

	// ---- video out ------------------------------------------------------
	output  [7:0] R,
	output  [7:0] G,
	output  [7:0] B,
	output        HSYNC,
	output        VSYNC,
	output        HBLANK,
	output        VBLANK,
	output        DOTCLK,

	// ---- audio out (FM + PSG already mixed inside md_board) ------------
	output [15:0] AUDIO_L,
	output [15:0] AUDIO_R
);

// ---------------------------------------------------------------------------
//  Work RAM (68k: 32 KB, 16-bit; Z80: 8 KB, 8-bit) as internal block RAM.
//  Port A is the console's access port (from md_board); port B clears the
//  RAM during reset (mirrors MiSTer's ram_rst_a path).
// ---------------------------------------------------------------------------
reg         rst_active;
reg  [14:0] rst_addr;
always @(posedge MCLK2) begin
	if (!RESET_N) begin
		rst_active <= 1'b1;
		rst_addr   <= 15'd0;
	end else if (rst_active) begin
		rst_addr <= rst_addr + 1'd1;
		if (&rst_addr) rst_active <= 1'b0;
	end
end

wire [15:0] ram_68k_o;
md_ram_68k ram_68k (
	.clk    (MCLK2),
	.addr_a (md_board.ram_68k_address),
	.data_a (md_board.ram_68k_data),
	.be_a   (md_board.ram_68k_byteena),
	.we_a   (md_board.ram_68k_wren),
	.q_a    (ram_68k_o),
	.addr_b (rst_addr),
	.data_b (16'd0),
	.we_b   (rst_active)
);

wire  [7:0] ram_z80_o;
md_ram_z80 ram_z80 (
	.clk    (MCLK2),
	.addr_a (md_board.ram_z80_address),
	.data_a (md_board.ram_z80_data),
	.we_a   (md_board.ram_z80_wren),
	.q_a    (ram_z80_o),
	.addr_b (rst_addr[12:0]),
	.data_b (8'hC7),          // reset instruction, fixes Titan 2 bug
	.we_b   (rst_active)
);

// ---------------------------------------------------------------------------
//  MD logic core
// ---------------------------------------------------------------------------
wire        ym2612_quirk;   // unused here; kept for symmetry

md_board md_board
(
	.MCLK2        (MCLK2),
	.ext_reset    (~RESET_N),
	.reset_button (1'b0),
	.ext_vres     (1'b0),
	.ext_zres     (1'b0),

	.ram_68k_address (md_board.ram_68k_address),
	.ram_68k_byteena (md_board.ram_68k_byteena),
	.ram_68k_data    (md_board.ram_68k_data),
	.ram_68k_wren    (md_board.ram_68k_wren),
	.ram_68k_o       (ram_68k_o),
	.ram_z80_address (md_board.ram_z80_address),
	.ram_z80_data    (md_board.ram_z80_data),
	.ram_z80_wren    (md_board.ram_z80_wren),
	.ram_z80_o       (ram_z80_o),

	.M3            (~CART_MODE),   // MD=0 -> M3=1
	.cart_data     (CART_DATA),
	.cart_data_en  (CART_DATA_EN),
	.cart_address  (CART_ADDR),
	.cart_cs       (CART_CS),
	.cart_oe       (CART_OE),
	.cart_lwr      (CART_LWR),
	.cart_uwr      (CART_UWR),
	.cart_time     (CART_TIME),
	.cart_cas2     (),
	.cart_data_wr  (CART_DATA_WR),
	.cart_dma      (CART_DMA),
	.cart_m3_pause (CART_M3_PAUSE),
	.ext_dtack     (CART_DTACK),
	.pal           (PAL),
	.jap           (JAP),

	.tmss_enable   (1'b0),
	.tmss_data     (16'd0),
	.tmss_address  (10'd0),

	.V_R       (md_board.V_R),
	.V_G       (md_board.V_G),
	.V_B       (md_board.V_B),
	.V_HS      (md_board.V_HS),
	.V_VS      (md_board.V_VS),

	.A_L       (AUDIO_L),
	.A_R       (AUDIO_R),
	.MOL       (),
	.MOR       (),
	.A_L_2612  (),
	.A_R_2612  (),
	.PSG       (),
	.DAC_ch_index (),
	.fm_sel23  (),

	.PA_i (pad_port1_out),
	.PA_o (md_board.PA_o),
	.PA_d (md_board.PA_d),
	.PB_i (pad_port2_out),
	.PB_o (md_board.PB_o),
	.PB_d (md_board.PB_d),
	.PC_i (md_board.PC_d | md_board.PC_o),
	.PC_o (md_board.PC_o),
	.PC_d (md_board.PC_d),

	.vdp_hclk1     (md_board.vdp_hclk1),
	.vdp_intfield  (md_board.vdp_intfield),
	.vdp_de_h      (md_board.vdp_de_h),
	.vdp_de_v      (md_board.vdp_de_v),
	.vdp_m5        (md_board.vdp_m5),
	.vdp_rs1       (md_board.vdp_rs1),
	.vdp_m2        (md_board.vdp_m2),
	.vdp_lcb       (),
	.vdp_psg_clk1  (),
	.vdp_cramdot_dis (~VIDEO_CRAMDOT),
	.fm_clk1       (),
	.vdp_hsync2    (),
	.vdp_vsync2    (),
	.ym2612_status_enable (ym2612_quirk),
	.dma_68k_req   (1'b0),
	.dma_z80_req   (1'b0),
	.dma_z80_ack   (),
	.res_z80       (),
	.vdp_dma_oe_early (),
	.vdp_dma       ()
);

// ---------------------------------------------------------------------------
//  Joypad shifter (MegaDrive 3/6-button pad protocol)
// ---------------------------------------------------------------------------
wire [6:0] pad_port1_out, pad_port2_out;
wire [6:0] pad_port1_in,  pad_port2_in;
wire [6:0] pad_port1_dir, pad_port2_dir;

md_io md_io
(
	.clk    (CLK_SYS),
	.reset  (~RESET_N),

	.MODE      (MD_MODE),
	.SMS       (CART_MODE),
	.MULTITAP  (2'b00),

	.P1_UP(PAD1[3]), .P1_DOWN(PAD1[2]), .P1_LEFT(PAD1[1]), .P1_RIGHT(PAD1[0]),
	.P1_A(PAD1[4]),  .P1_B(PAD1[5]),   .P1_C(PAD1[6]),
	.P1_START(PAD1[7]), .P1_MODE(PAD1[8]),
	.P1_X(PAD1[9]),  .P1_Y(PAD1[10]),  .P1_Z(PAD1[11]),

	.P2_UP(PAD2[3]), .P2_DOWN(PAD2[2]), .P2_LEFT(PAD2[1]), .P2_RIGHT(PAD2[0]),
	.P2_A(PAD2[4]),  .P2_B(PAD2[5]),   .P2_C(PAD2[6]),
	.P2_START(PAD2[7]), .P2_MODE(PAD2[8]),
	.P2_X(PAD2[9]),  .P2_Y(PAD2[10]),  .P2_Z(PAD2[11]),

	.P3_UP(1'b0), .P3_DOWN(1'b0), .P3_LEFT(1'b0), .P3_RIGHT(1'b0),
	.P3_A(1'b0),  .P3_B(1'b0),   .P3_C(1'b0),
	.P3_START(1'b0), .P3_MODE(1'b0), .P3_X(1'b0), .P3_Y(1'b0), .P3_Z(1'b0),

	.P4_UP(1'b0), .P4_DOWN(1'b0), .P4_LEFT(1'b0), .P4_RIGHT(1'b0),
	.P4_A(1'b0),  .P4_B(1'b0),   .P4_C(1'b0),
	.P4_START(1'b0), .P4_MODE(1'b0), .P4_X(1'b0), .P4_Y(1'b0), .P4_Z(1'b0),

	.P5_UP(1'b0), .P5_DOWN(1'b0), .P5_LEFT(1'b0), .P5_RIGHT(1'b0),
	.P5_A(1'b0),  .P5_B(1'b0),   .P5_C(1'b0),
	.P5_START(1'b0), .P5_MODE(1'b0), .P5_X(1'b0), .P5_Y(1'b0), .P5_Z(1'b0),

	.GUN_OPT(1'b0), .GUN_TYPE(1'b0),
	.GUN_SENSOR(1'b0), .GUN_A(1'b0), .GUN_B(1'b0), .GUN_C(1'b0), .GUN_START(1'b0),
	.MOUSE(27'b0), .MOUSE_OPT(3'b0),

	.port1_out(pad_port1_out), .port1_in(pad_port1_in), .port1_dir(pad_port1_dir),
	.port2_out(pad_port2_out), .port2_in(pad_port2_in), .port2_dir(pad_port2_dir),

	.PS2_KEY(11'b0), .PS2_LED(3'b0), .KEYBOARD_OPT(2'b0)
);

assign pad_port1_in = md_board.PA_o;
assign pad_port1_dir = md_board.PA_d;
assign pad_port2_in = md_board.PB_o;
assign pad_port2_dir = md_board.PB_d;

// ---------------------------------------------------------------------------
//  Video: capture VDP signals at MCLK2, then condition at CLK_SYS.
//  Mirrors MiSTer's vh_* sampling so video_cond sees stable pixel values.
// ---------------------------------------------------------------------------
reg  [7:0] vh_r, vh_g, vh_b;
reg        vh_hs, vh_vs, vh_hclk1, vh_de_h, vh_de_v, vh_intfield, vh_m2, vh_m5, vh_rs1;
reg        vh_ph;
reg        vh_h1d;
always @(posedge MCLK2) begin
	vh_h1d <= md_board.vdp_hclk1;
	vh_ph  <= ~vh_ph & (vh_h1d == md_board.vdp_hclk1);
	if (vh_ph)
		{vh_r, vh_g, vh_b, vh_hs, vh_vs, vh_hclk1, vh_de_h, vh_de_v, vh_intfield, vh_m2, vh_m5, vh_rs1}
			<= {md_board.V_R, md_board.V_G, md_board.V_B, md_board.V_HS, md_board.V_VS,
			    md_board.vdp_hclk1, md_board.vdp_de_h, md_board.vdp_de_v, md_board.vdp_intfield,
			    md_board.vdp_m2, md_board.vdp_m5, md_board.vdp_rs1};
end

wire [7:0] vc_r, vc_g, vc_b;
wire       vc_hs, vc_vs, vc_hbl, vc_vbl, vc_ce;
video_cond video_cond
(
	.clk        (CLK_SYS),
	.vdp_hclk1  (vh_hclk1),
	.vdp_de_h   (vh_de_h),
	.vdp_de_v   (vh_de_v),
	.vdp_intfield (vh_intfield),
	.vdp_m2     (vh_m2),
	.vdp_m5     (vh_m5),
	.vdp_rs1    (vh_rs1),
	.r_in       (vh_r),
	.g_in       (vh_g),
	.b_in       (vh_b),
	.hs_in      (vh_hs),
	.vs_in      (vh_vs),
	.pal        (PAL),
	.border_en  (VIDEO_BORDER),
	.h40corr    (VIDEO_H40CORR),
	.blender    (VIDEO_BLEND),
	.arx        (),
	.ary        (),
	.ce_pix     (vc_ce),
	.interlace  (),
	.f1         (),
	.r_out      (vc_r),
	.g_out      (vc_g),
	.b_out      (vc_b),
	.hs_out     (vc_hs),
	.vs_out     (vc_vs),
	.hbl_out    (vc_hbl),
	.vbl_out    (vc_vbl)
);

assign R = vc_r;
assign G = vc_g;
assign B = vc_b;
assign HSYNC  = vc_hs;
assign VSYNC  = vc_vs;
assign HBLANK = vc_hbl;
assign VBLANK = vc_vbl;
assign DOTCLK = vc_ce;

endmodule


// ---------------------------------------------------------------------------
//  68k work RAM: true dual-port block RAM.
// ---------------------------------------------------------------------------
module md_ram_68k
(
	input             clk,
	// port A: console
	input      [14:0] addr_a,
	input      [15:0] data_a,
	input       [1:0] be_a,
	input             we_a,
	output reg [15:0] q_a,
	// port B: reset clear
	input      [14:0] addr_b,
	input      [15:0] data_b,
	input             we_b
);
	reg [15:0] mem [0:32767];
	always @(posedge clk) begin
		q_a <= mem[addr_a];
		if (we_a) begin
			if (be_a[1]) mem[addr_a][15:8] <= data_a[15:8];
			if (be_a[0]) mem[addr_a][ 7:0] <= data_a[ 7:0];
		end
	end
	always @(posedge clk) begin
		if (we_b) mem[addr_b] <= data_b;
	end
endmodule

// ---------------------------------------------------------------------------
//  Z80 work RAM: true dual-port block RAM.
// ---------------------------------------------------------------------------
module md_ram_z80
(
	input             clk,
	input      [12:0] addr_a,
	input       [7:0] data_a,
	input             we_a,
	output reg  [7:0] q_a,
	input      [12:0] addr_b,
	input       [7:0] data_b,
	input             we_b
);
	reg [7:0] mem [0:8191];
	always @(posedge clk) begin
		q_a <= mem[addr_a];
		if (we_a) mem[addr_a] <= data_a;
	end
	always @(posedge clk) begin
		if (we_b) mem[addr_b] <= data_b;
	end
endmodule
