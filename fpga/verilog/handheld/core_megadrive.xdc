########################################
# SDRAM interface timing (MegaDrive core)
########################################
# For the MD core the SDRAM runs at the cartridge/68k master clock: 107.386 MHz
# (9.304 ns), the same rate as MCLK2. The controller launches commands, address
# and write data from plain flops on the rising edge of the SDRAM clock and
# captures read data on its falling edge (readCaptureFalling), in a register
# placed in the IOB (see the IOB TRUE constraint below) so the pin path does not
# move with placement.
#
# Chip budget (PC133-class SDR SDRAM): input setup 2.0 ns, input hold 1.0 ns,
# clock-to-data 6.0 ns, data hold 2.5 ns. These delays are per the SDRAM
# datasheet and are independent of the clock rate.
#
# NOTE: this is a first-pass constraint. At 107 MHz the margins are much tighter
# than the SNES's 42.955 MHz SDRAM clock; verify the sdram_clk_pin rows of the
# inter-clock table in the timing summary after every build and adjust the MMCM
# SDRAM output phase (HandheldMegadrive: MMCM.ClockOut(divide = 11, phaseDeg))
# if any path fails.
create_clock -name sdram_clk_pin -period 9.304 [get_ports sdram_clk]
set sdram_outputs [get_ports {sdram_a[*] sdram_bs[*] sdram_dq[*] sdram_cke[*] sdram_cs_n[*] sdram_cas_n sdram_ras_n sdram_we_n sdram_ldqm sdram_udqm}]
set_output_delay -clock sdram_clk_pin -max  2.0 $sdram_outputs
set_output_delay -clock sdram_clk_pin -min -1.0 $sdram_outputs
set_input_delay  -clock sdram_clk_pin -max  6.0 [get_ports {sdram_dq[*]}]
set_input_delay  -clock sdram_clk_pin -min  2.5 [get_ports {sdram_dq[*]}]
# The falling-edge capture register in the IOB: a fixed pad-to-flop path.
set_property IOB TRUE [get_cells -hier -filter {NAME =~ *sdram/dataIn_reg*}]

########################################
# Asynchronous SRAM (backup / EEPROM RAM, IS61WV25616BLL-10).
# The AsyncSramController gives every access a full cycle; the read data is
# captured in the ILOGIC. Timing is referenced to the SDRAM clock here as a
# placeholder -- confirm the controller's actual clock domain (the core's
# system clock) and retarget if needed.
########################################
set sram_clock [get_clocks -of_objects [get_ports sdram_clk]]
set_property IOB TRUE [get_ports {sram_a[*] sram_io[*] sram_ub_n sram_lb_n sram_oe_n}]
set_output_delay -clock $sram_clock -max 35.0 [get_ports {sram_a[*] sram_ub_n sram_lb_n sram_oe_n}]
set_output_delay -clock $sram_clock -max 33.0 [get_ports {sram_io[*]}]
set_output_delay -clock $sram_clock -min -1.0 [get_ports {sram_a[*] sram_io[*] sram_ub_n sram_lb_n sram_oe_n}]
set_input_delay  -clock $sram_clock -max 22.0 [get_ports {sram_io[*]}]
set_input_delay  -clock $sram_clock -min 10.0 [get_ports {sram_io[*]}]
