#-------------- MCS Generation ----------------------
set_property BITSTREAM.CONFIG.EXTMASTERCCLK_EN div-1 [current_design]
set_property BITSTREAM.CONFIG.SPI_FALL_EDGE YES [current_design]
set_property BITSTREAM.CONFIG.SPI_BUSWIDTH 4          [current_design]
set_property BITSTREAM.CONFIG.UNUSEDPIN Pullnone [current_design]
set_property CFGBVS GND [current_design]
#set_property CONFIG_VOLTAGE 3.3 [current_design]
set_property CONFIG_MODE SPIx4                        [current_design]

# new modify here

# Reset button
set_property -dict { PACKAGE_PIN R19 IOSTANDARD LVCMOS33 } [get_ports reset]

# Fan control
# set_property -dict { PACKAGE_PIN W19 IOSTANDARD LVCMOS33 } [get_ports fan_en];